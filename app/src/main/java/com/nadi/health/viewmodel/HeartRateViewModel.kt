package com.nadi.health.viewmodel

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadi.health.analysis.HRVAnalyzer
import com.nadi.health.analysis.SignalQuality
import com.nadi.health.analysis.SignalQualityIndicator
import com.nadi.health.analysis.VitalsAnalyzer
import com.nadi.health.core.NativeSignalProcessor
import com.nadi.health.NadiApplication
import com.nadi.health.data.local.HeartRateReading
import com.nadi.health.data.local.NadiRepository
import com.nadi.health.ml.Iqoo15NpuUnavailableException
import com.nadi.health.ml.PulseML
import com.nadi.health.vision.FaceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class HeartRateViewModel(application: Application) : AndroidViewModel(application) {

    // --- Core Components ---
    // Make these nullable to prevent Main Thread freeze on init
    var faceTracker: FaceTracker? = null
    private var pulseML: PulseML? = null

    // Native processor (C++)
    private val signalProcessor = NativeSignalProcessor(bufferSize = 300, samplingRate = 30f)

    // --- Ghost Features (Now Active!) ---
    private val hrvAnalyzer = HRVAnalyzer()
    private val qualityIndicator = SignalQualityIndicator()

    /**
     * On-device store. Readings are written here so history survives restarts;
     * previously this was an in-memory list that died with the process.
     */
    private val repository: NadiRepository? =
        (application as? NadiApplication)?.repository

    /** Epoch millis of the last persisted reading, for throttling. */
    private var lastSavedAt = 0L

    // Extended vitals (SpO2 / respiration / estimated BP) from the same PPG stream
    private val vitalsAnalyzer = VitalsAnalyzer()
    private val redBuffer = ArrayList<Float>(300)
    private val blueBuffer = ArrayList<Float>(300)
    private var lastSdnn = 0f

    // --- UI State ---
    private val _heartRate = MutableStateFlow(0f)
    val heartRate: StateFlow<Float> = _heartRate.asStateFlow()

    private val _signalBuffer = MutableStateFlow<List<Float>>(emptyList())
    val signalBuffer: StateFlow<List<Float>> = _signalBuffer.asStateFlow()

    private val _status = MutableStateFlow(MeasurementStatus.INITIALIZING)
    val status: StateFlow<MeasurementStatus> = _status.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    // NEW: Stress & Insights
    private val _stressLevel = MutableStateFlow("Analyzing...")
    val stressLevel: StateFlow<String> = _stressLevel.asStateFlow()

    private val _signalQualityMsg = MutableStateFlow("")
    val signalQualityMsg: StateFlow<String> = _signalQualityMsg.asStateFlow()

    // Extended vitals (0 = "not confident yet")
    private val _spo2 = MutableStateFlow(0)
    val spo2: StateFlow<Int> = _spo2.asStateFlow()

    private val _respiratoryRate = MutableStateFlow(0)
    val respiratoryRate: StateFlow<Int> = _respiratoryRate.asStateFlow()

    private val _bloodPressure = MutableStateFlow(0 to 0)
    val bloodPressure: StateFlow<Pair<Int, Int>> = _bloodPressure.asStateFlow()

    // iQOO 15-exclusive: hard error when the Hexagon NPU stack is unavailable.
    // There is no fallback - when this is non-null, no refined HR is produced.
    private val _npuError = MutableStateFlow<String?>(null)
    val npuError: StateFlow<String?> = _npuError.asStateFlow()

    // Internal logic variables
    private var processingJob: Job? = null
    private var hrComputationJob: Job? = null
    private var currentHrEstimate = 0f
    private val alpha = 0.15f // Smoothing factor

    private val _faceDetected = MutableStateFlow(false)
    val faceDetected: StateFlow<Boolean> = _faceDetected.asStateFlow()

    private val _landmarks = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val landmarks: StateFlow<List<Pair<Float, Float>>> = _landmarks.asStateFlow()


    init {
        // Initialize heavy AI models in background to prevent UI freeze
        viewModelScope.launch(Dispatchers.Default) {
            try {
                faceTracker = FaceTracker(application)
                pulseML = PulseML(application)
                startProcessing()
            } catch (e: Iqoo15NpuUnavailableException) {
                Log.e(TAG, "iQOO 15 NPU stack unavailable - refusing to degrade", e)
                _npuError.value = e.message
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init AI models", e)
                _npuError.value = "Init failed: ${e.message}"
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun startProcessing() {
        val tracker = faceTracker ?: return

        viewModelScope.launch {
            tracker.faceDetected.collect { _faceDetected.value = it }
        }
        viewModelScope.launch {
            tracker.landmarks.collect { _landmarks.value = it }
        }

        // 1. Collect Green Signal from Face
        processingJob = viewModelScope.launch {
            tracker.faceDetected.combine(tracker.greenSignal) { detected, green ->
                Pair(detected, green)
            }.collect { (detected, green) ->
                if (detected) {
                    signalProcessor.addSample(green)
                    // Keep red/blue ring buffers aligned with the green buffer
                    val rgb = tracker.rgbSignal.value
                    if (rgb.r > 0f || rgb.b > 0f) {
                        redBuffer.add(rgb.r)
                        blueBuffer.add(rgb.b)
                        if (redBuffer.size > 300) redBuffer.removeAt(0)
                        if (blueBuffer.size > 300) blueBuffer.removeAt(0)
                    }
                    val sampleCount = signalProcessor.getCurrentSampleCount()
                    updateStatus(sampleCount)
                } else {
                    _status.value = MeasurementStatus.NO_FACE
                    _signalQualityMsg.value = "" // Clear warnings
                }
            }
        }

        // 2. Periodic Analysis Loop (1Hz)
        hrComputationJob = viewModelScope.launch {
            while (true) {
                delay(1000) // Run every second
                if (signalProcessor.getCurrentSampleCount() >= 150) {
                    analyzeSignal()
                }
            }
        }
    }

    private fun updateStatus(sampleCount: Int) {
        if (_status.value != MeasurementStatus.COMPLETED) {
            _status.value = when {
                sampleCount < 90 -> MeasurementStatus.ACQUIRING
                sampleCount < 300 -> MeasurementStatus.TRACKING
                else -> MeasurementStatus.MEASURING
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM) // For History saving
    private fun analyzeSignal() {
        viewModelScope.launch {
            try {
                val bufferFloatArray = signalProcessor.getSignalBuffer()

                // --- A. Quality Check (Ghost Feature #1) ---
                val quality = qualityIndicator.computeOverallQuality(bufferFloatArray, 30f)

                // Update UI warning if quality is bad
                if (quality == SignalQuality.POOR || quality == SignalQuality.VERY_POOR) {
                    _signalQualityMsg.value = "⚠️ Poor Lighting: Move to brighter area"
                    _confidence.value = 0.2f
                } else {
                    _signalQualityMsg.value = "" // Clear warning
                    _confidence.value = 1.0f
                }

                // If quality is terrible, don't show random numbers
                if (quality == SignalQuality.VERY_POOR) return@launch

                // --- C. Extended vitals: computed independently of HR/HRV so
                // they appear within the first ~15 s. Values are sticky - a
                // single bad window doesn't blank them (quality gates inside
                // VitalsAnalyzer already reject unusable windows).
                // 6 s windows (180 samples @ 30 fps) -> first vitals in ~7 s
                if (bufferFloatArray.size >= 180 && redBuffer.size >= 180 && blueBuffer.size >= 180) {
                    val vitals = vitalsAnalyzer.compute(
                        bufferFloatArray,
                        redBuffer.toFloatArray(),
                        blueBuffer.toFloatArray(),
                        currentHrEstimate,
                        lastSdnn
                    )
                    if (vitals.spo2 > 0) _spo2.value = vitals.spo2
                    if (vitals.respiratoryRate > 0) _respiratoryRate.value = vitals.respiratoryRate
                    if (vitals.systolic > 0 && vitals.diastolic > 0) {
                        _bloodPressure.value = vitals.systolic to vitals.diastolic
                    }
                }

                // --- B. Heart Rate Calculation ---
                val rawHR = signalProcessor.computeHeartRate()

                // Filter valid range (45-200 BPM)
                if (rawHR > 45 && rawHR < 200) {
                    // Refine with AI
                    var finalHR = pulseML?.refineHeartRate(bufferFloatArray, rawHR) ?: rawHR

                    // Fallback if AI returns 45 (clamped) but raw was good
                    if (finalHR == 45f && rawHR > 50) finalHR = rawHR

                    // Exponential Smoothing (prevents jumping)
                    if (currentHrEstimate == 0f) {
                        currentHrEstimate = finalHR
                    } else {
                        currentHrEstimate = (alpha * finalHR) + ((1 - alpha) * currentHrEstimate)
                    }

                    _heartRate.value = currentHrEstimate
                    _signalBuffer.value = bufferFloatArray.takeLast(150).toList()

                    // --- C. Stress/HRV Analysis (Ghost Feature #2) ---
                    // Only analyze stress if we have a full 10s buffer (300 samples)
                    if (bufferFloatArray.size >= 300) {
                        val peaks = hrvAnalyzer.extractPeakIntervals(bufferFloatArray, 30f)
                        val hrvMetrics = hrvAnalyzer.computeHRV(peaks)

                        if (hrvMetrics != null) {
                            _stressLevel.value = "Stress: ${hrvMetrics.stressIndex.toInt()}/100\n(${hrvMetrics.interpretation})"
                            lastSdnn = hrvMetrics.sdnn

                            // --- D. Persist to the local database ---
                            // The analysis loop runs at 1 Hz; storing every tick
                            // would bloat the table with near-duplicate rows, so
                            // readings are throttled to one every SAVE_INTERVAL_MS.
                            val now = System.currentTimeMillis()
                            if (now - lastSavedAt >= SAVE_INTERVAL_MS) {
                                lastSavedAt = now
                                persistReading(
                                    bpm = currentHrEstimate,
                                    qualityName = quality.name,
                                    sdnn = hrvMetrics.sdnn,
                                    rmssd = hrvMetrics.rmssd,
                                    stress = hrvMetrics.stressIndex
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis error", e)
            }
        }
    }

    /**
     * Writes one measurement to the local database off the main thread.
     *
     * Two rows: the single-number [HeartRateReading] the existing UI and Health
     * Connect sync use, and a full [com.nadi.health.data.local.VitalsSample]
     * carrying the same window's HRV / stress / SpO2 / estimated BP so the AI
     * features can trend them. Failures are logged, never surfaced as a crash —
     * a missing history row must not take down a live measurement.
     */
    private fun persistReading(
        bpm: Float,
        qualityName: String,
        sdnn: Float = 0f,
        rmssd: Float = 0f,
        stress: Float = 0f
    ) {
        val repo = repository ?: return
        val confidenceNow = _confidence.value
        val spo2Now = _spo2.value
        val rrNow = _respiratoryRate.value
        val bpNow = _bloodPressure.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.addHeartRate(
                    bpm = bpm,
                    confidence = confidenceNow,
                    quality = qualityName,
                    source = HeartRateReading.SOURCE_RPPG
                )
                repo.addVitalsSample(
                    bpm = bpm,
                    sdnnMs = sdnn,
                    rmssdMs = rmssd,
                    stressIndex = stress,
                    spo2 = spo2Now,
                    respiratoryRate = rrNow,
                    systolic = bpNow.first,
                    diastolic = bpNow.second,
                    quality = qualityName,
                    source = HeartRateReading.SOURCE_RPPG
                )
                // A measurement day is a streak day (idempotent per calendar day).
                repo.touchStreak("measurement")
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist vital signs", e)
            }
        }
    }

    fun reset() {
        signalProcessor.reset()
        currentHrEstimate = 0f
        lastSdnn = 0f
        _heartRate.value = 0f
        _stressLevel.value = "Analyzing..."
        _signalBuffer.value = emptyList()
        _status.value = MeasurementStatus.INITIALIZING
        redBuffer.clear()
        blueBuffer.clear()
        _spo2.value = 0
        _respiratoryRate.value = 0
        _bloodPressure.value = 0 to 0
    }

    override fun onCleared() {
        super.onCleared()
        faceTracker?.release()
        signalProcessor.release()
        pulseML?.release()
    }

    companion object {
        private const val TAG = "HeartRateViewModel"

        /** Minimum gap between persisted readings (the analysis loop is 1 Hz). */
        private const val SAVE_INTERVAL_MS = 10_000L
    }
}

enum class MeasurementStatus {
    INITIALIZING, NO_FACE, ACQUIRING, TRACKING, MEASURING, COMPLETED
}