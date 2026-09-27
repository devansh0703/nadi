package com.nadi.health.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadi.health.imu.CardioImu
import com.nadi.health.imu.ImuRecorder
import com.nadi.health.imu.RespirationImu
import com.nadi.health.imu.SensorContext
import com.nadi.health.imu.TremorAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Orchestrates the phone-body physiological measurement modes:
 *
 *  CHEST  - respiration rate, phone resting on the chest (accelerometer,
 *           gravity-projected vertical, slant-tolerant)
 *  CARDIO - heart rate + HRV, phone pressed on the sternum (SCG + GCG,
 *           best-axis selection, slant-tolerant)
 *  TREMOR - 10 s hand-tremor capture, phone held in outstretched hand
 *
 * Steps are NOT derived here - they come exclusively from the vendor
 * pedometer sensor (public TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR API)
 * via [SensorContext], as required.
 *
 * All DSP (Wiener/FFT/Welch/zero-phase filtering) runs on Dispatchers.Default;
 * sensor callbacks and StateFlow updates stay off the critical path.
 */
class ImuViewModel(application: Application) : AndroidViewModel(application) {

    enum class Mode { OFF, CHEST, CARDIO, TREMOR }

    private val recorder = ImuRecorder(application)

    // App-scoped context sensors (light, proximity, heading, steps, die temp).
    // Used for telemetry readouts only - quality tips for the camera pipeline
    // come from the camera's own signal metrics, not these.
    private val sensorContext = SensorContext.get(application)

    init {
        val perm = application.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
        sensorContext.start(perm == android.content.pm.PackageManager.PERMISSION_GRANTED)
        startLoop()
    }

    /** Call after the user grants ACTIVITY_RECOGNITION mid-session. */
    fun onActivityRecognitionGranted() {
        sensorContext.registerSteps()
    }

    // --- UI state ---
    private val _mode = MutableStateFlow(Mode.OFF)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _imuError = MutableStateFlow<String?>(null)
    val imuError: StateFlow<String?> = _imuError.asStateFlow()

    // Respiration
    private val _respiration = MutableStateFlow(0)
    val respiration: StateFlow<Int> = _respiration.asStateFlow()

    private val _respirationWave = MutableStateFlow<List<Float>>(emptyList())
    val respirationWave: StateFlow<List<Float>> = _respirationWave.asStateFlow()

    // Cardiac (SCG/GCG)
    private val _imuHeartRate = MutableStateFlow(0)
    val imuHeartRate: StateFlow<Int> = _imuHeartRate.asStateFlow()

    private val _imuSdnn = MutableStateFlow(0f)
    val imuSdnn: StateFlow<Float> = _imuSdnn.asStateFlow()

    private val _imuRmssd = MutableStateFlow(0f)
    val imuRmssd: StateFlow<Float> = _imuRmssd.asStateFlow()

    private val _imuViaGyro = MutableStateFlow(false)
    val imuViaGyro: StateFlow<Boolean> = _imuViaGyro.asStateFlow()

    private val _cardioWave = MutableStateFlow<List<Float>>(emptyList())
    val cardioWave: StateFlow<List<Float>> = _cardioWave.asStateFlow()

    // Tremor
    private val _tremorHz = MutableStateFlow(0f)
    val tremorHz: StateFlow<Float> = _tremorHz.asStateFlow()

    private val _tremorMg = MutableStateFlow(0f)
    val tremorMg: StateFlow<Float> = _tremorMg.asStateFlow()

    private val _tremorClass = MutableStateFlow("")
    val tremorClass: StateFlow<String> = _tremorClass.asStateFlow()

    private val _tremorWave = MutableStateFlow<List<Float>>(emptyList())
    val tremorWave: StateFlow<List<Float>> = _tremorWave.asStateFlow()

    private val _tremorProgress = MutableStateFlow(0f)
    val tremorProgress: StateFlow<Float> = _tremorProgress.asStateFlow()

    // Live context-sensor readouts (public APIs only)
    val steps: StateFlow<Long> get() = sensorContext.stepsToday
    val stepEvents: StateFlow<Long> get() = sensorContext.stepEvents
    val dieTempC: StateFlow<Float> get() = sensorContext.dieTempC

    private var loopJob: Job? = null
    private var tremorJob: Job? = null

    /** 1 Hz analysis loop on the Default dispatcher (DSP-heavy). */
    private fun startLoop() {
        loopJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                try {
                    analysisTick()
                } catch (e: Exception) {
                    Log.e(TAG, "IMU analysis error", e)
                }
            }
        }
    }

    fun setMode(newMode: Mode) {
        if (newMode == _mode.value) return
        _mode.value = newMode
        tremorJob?.cancel()
        _tremorProgress.value = 0f

        when (newMode) {
            Mode.OFF -> {
                recorder.stop()
                _message.value = ""
            }
            Mode.CHEST -> {
                recorder.clear()
                ensureStarted()
                _message.value = "Lie down and rest the phone on your chest - flat or slanted, both fine"
            }
            Mode.CARDIO -> {
                recorder.clear()
                ensureStarted()
                _message.value = "Press the phone firmly against your sternum - any angle works"
            }
            Mode.TREMOR -> {
                recorder.clear()
                ensureStarted()
                _message.value = "Hold the phone in your outstretched hand, arm forward"
                startTremorCapture()
            }
        }
    }

    private fun ensureStarted() {
        if (!recorder.isRunning && !recorder.start()) {
            _imuError.value = "Accelerometer unavailable on this device"
        }
    }

    /** 10 s tremor capture: progress updates, then one-shot analysis. */
    private fun startTremorCapture() {
        tremorJob = viewModelScope.launch(Dispatchers.Default) {
            val startMs = System.currentTimeMillis()
            val durationMs = 10_000L
            while (System.currentTimeMillis() - startMs < durationMs) {
                _tremorProgress.value =
                    (System.currentTimeMillis() - startMs).toFloat() / durationMs
                delay(100)
            }
            _tremorProgress.value = 1f
            runTremorAnalysis()
            _mode.value = Mode.OFF
            recorder.stop()
            _message.value = ""
        }
    }

    private fun runTremorAnalysis() {
        val snap = recorder.snapshot()
        if (snap.size < 200) return
        val fs = measuredFs(snap) ?: return
        val mag = FloatArray(snap.size) {
            val s = snap[it]
            kotlin.math.sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        }
        val res = TremorAnalyzer(fs).analyze(mag)
        _tremorWave.value = downsample(res.wave, 240)
        if (res.dominantHz > 0f) {
            _tremorHz.value = res.dominantHz
            _tremorMg.value = res.amplitudeMg
            _tremorClass.value = res.classification.label
        } else {
            _tremorClass.value = "Hands too still to measure - try again"
        }
    }

    private fun analysisTick() {
        if (_mode.value == Mode.OFF) return

        val snap = recorder.snapshot()
        if (snap.size < 150) return
        val fs = measuredFs(snap) ?: return

        when (_mode.value) {
            Mode.CHEST -> {
                // BioWatch estimator: z-score -> averaging filter -> FFT per
                // component, most-periodic component wins (0.13-0.66 Hz band
                // = 8-40 brpm so fast breathing is inside the search range).
                val res = RespirationImu(fs).analyze(snap)
                if (res.brpm > 0) {
                    _respiration.value = res.brpm
                    _respirationWave.value = downsample(res.waveform, 240)
                    _message.value = ""
                }
            }
            Mode.CARDIO -> {
                val res = CardioImu(fs).analyze(snap)
                if (res.bpm > 0) {
                    _imuHeartRate.value = res.bpm
                    _imuSdnn.value = res.sdnnMs
                    _imuRmssd.value = res.rmssdMs
                    _imuViaGyro.value = res.viaGyro
                    _cardioWave.value = downsample(res.waveform, 240)
                    _message.value = ""
                }
            }
            else -> Unit
        }
    }

    /** Effective sampling rate from timestamp span; null when degenerate. */
    private fun measuredFs(snap: List<ImuRecorder.ImuSample>): Float? {
        if (snap.size < 100) return null
        val span = snap[snap.size - 1].t - snap[0].t
        if (span <= 0.0) return null
        val fs = ((snap.size - 1) / span).toFloat()
        return if (fs in 20f..2500f) fs else null
    }

    /** Downsample a signal to ~[target] points for UI rendering. */
    private fun downsample(x: FloatArray, target: Int): List<Float> {
        if (x.isEmpty()) return emptyList()
        if (x.size <= target) return x.toList()
        val step = x.size.toFloat() / target
        val out = ArrayList<Float>(target)
        var idx = 0f
        while (out.size < target && idx < x.size) {
            out.add(x[min(x.size - 1, idx.toInt())])
            idx += step
        }
        return out
    }

    fun reset() {
        _respiration.value = 0
        _respirationWave.value = emptyList()
        _imuHeartRate.value = 0
        _imuSdnn.value = 0f
        _imuRmssd.value = 0f
        _imuViaGyro.value = false
        _cardioWave.value = emptyList()
        _tremorHz.value = 0f
        _tremorMg.value = 0f
        _tremorClass.value = ""
        _tremorProgress.value = 0f
        _tremorWave.value = emptyList()
        _message.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        loopJob?.cancel()
        tremorJob?.cancel()
        recorder.release()
        sensorContext.stop()
    }

    companion object {
        private const val TAG = "ImuViewModel"
    }
}
