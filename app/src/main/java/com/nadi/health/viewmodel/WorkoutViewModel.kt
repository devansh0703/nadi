package com.nadi.health.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadi.health.NadiApplication
import com.nadi.health.data.local.NadiRepository
import com.nadi.health.data.local.WorkoutSession
import com.nadi.health.workout.Exercise
import com.nadi.health.workout.ExerciseCatalog
import com.nadi.health.workout.RepCountEngine
import com.nadi.health.workout.RunTracker
import com.nadi.health.workout.WorkoutKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the sensor-only workout dashboard.
 *
 * Rep-based sessions (push-ups, sit-ups, squats, jumping jacks, and the
 * barbell lifts) are driven by [RepCountEngine], which ports the FitTrack and
 * RepCapture accelerometer algorithms. Elliptical is a manual timed session.
 * Running uses [RunTracker] for duration / distance / route / pace, with an
 * accelerometer cadence auto-start for the AI mode.
 *
 * All sensor handling stays off the critical path: the engine calls back with
 * already-thinned events and this class only publishes StateFlow values.
 */
class WorkoutViewModel(application: Application) : AndroidViewModel(application) {

    enum class Phase { SELECT, READY, ACTIVE, DONE }

    enum class RunMode { NORMAL, AI }

    /** A finished session, mirrored from the local database. */
    data class WorkoutRecord(
        /** [Exercise.id], needed to map the session back to Health Connect. */
        val exerciseId: String = "",
        val name: String,
        val emoji: String,
        val reps: Int,
        val seconds: Int,
        val distanceM: Double,
        val timestamp: Long
    ) {
        val isCardio: Boolean get() = distanceM > 0.0
    }

    /** Local store. Sessions are written here so the log survives restarts. */
    private val repository: NadiRepository? =
        (application as? NadiApplication)?.repository

    val exercises: List<Exercise> = ExerciseCatalog.exercises

    private val _phase = MutableStateFlow(Phase.SELECT)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _selected = MutableStateFlow<Exercise?>(null)
    val selected: StateFlow<Exercise?> = _selected.asStateFlow()

    private val _reps = MutableStateFlow(0)
    val reps: StateFlow<Int> = _reps.asStateFlow()

    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec.asStateFlow()

    private val _calibrating = MutableStateFlow(false)
    val calibrating: StateFlow<Boolean> = _calibrating.asStateFlow()

    private val _signalLevel = MutableStateFlow(0f)
    val signalLevel: StateFlow<Float> = _signalLevel.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _history = MutableStateFlow<List<WorkoutRecord>>(emptyList())
    val history: StateFlow<List<WorkoutRecord>> = _history.asStateFlow()

    init {
        // The history shown on the dashboard is the database, not a local list:
        // finishing a set writes a row and the flow pushes it straight back.
        repository?.let { repo ->
            viewModelScope.launch {
                repo.recentWorkouts(MAX_HISTORY).collect { sessions ->
                    _history.value = sessions.map { it.toRecord() }
                }
            }
        }
    }

    // --- Run state ---
    private val _runMode = MutableStateFlow(RunMode.NORMAL)
    val runMode: StateFlow<RunMode> = _runMode.asStateFlow()

    private val _runDistanceM = MutableStateFlow(0.0)
    val runDistanceM: StateFlow<Double> = _runDistanceM.asStateFlow()

    private val _runPaceSecPerKm = MutableStateFlow(0.0)
    val runPaceSecPerKm: StateFlow<Double> = _runPaceSecPerKm.asStateFlow()

    private val _runCadence = MutableStateFlow(0)
    val runCadence: StateFlow<Int> = _runCadence.asStateFlow()

    private val _runRoute = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val runRoute: StateFlow<List<Pair<Double, Double>>> = _runRoute.asStateFlow()

    private val _runActive = MutableStateFlow(false)
    val runActive: StateFlow<Boolean> = _runActive.asStateFlow()

    private val _runStarted = MutableStateFlow(false)
    val runStarted: StateFlow<Boolean> = _runStarted.asStateFlow()

    private var timerJob: Job? = null

    // AI-mode auto start/pause counters
    private var highCadenceTicks = 0
    private var lowCadenceTicks = 0

    private val engineListener = object : RepCountEngine.Listener {
        override fun onCalibrated() {
            _calibrating.value = false
        }

        override fun onRep(total: Int) {
            _reps.value = total
        }

        override fun onTelemetry(level: Float) {
            _signalLevel.value = level
        }
    }

    private val runListener = object : RunTracker.Listener {
        override fun onTick(distanceM: Double, paceSecPerKm: Double, lat: Double, lon: Double) {
            _runDistanceM.value = distanceM
            _runPaceSecPerKm.value = paceSecPerKm
            _runRoute.value = runTracker.routePoints
        }

        override fun onCadence(spm: Int) {
            _runCadence.value = spm
            if (_runMode.value != RunMode.AI) return
            if (spm >= AI_START_CADENCE) {
                highCadenceTicks++
                lowCadenceTicks = 0
                if (!_runActive.value && highCadenceTicks >= AI_START_TICKS) {
                    _runActive.value = true
                }
            } else if (spm < AI_PAUSE_CADENCE) {
                lowCadenceTicks++
                highCadenceTicks = 0
                if (_runActive.value && lowCadenceTicks >= AI_PAUSE_TICKS) {
                    _runActive.value = false
                }
            } else {
                highCadenceTicks = 0
                lowCadenceTicks = 0
            }
        }

        override fun onLocationUnavailable(message: String) {
            _error.value = message
            stopRunInternal(save = false)
        }
    }

    // Created after the listeners so their callbacks are already wired up.
    // Explicit types: the run listener reads back from the tracker, which would
    // otherwise close an inference cycle.
    private val engine: RepCountEngine = RepCountEngine(application, engineListener)
    private val runTracker: RunTracker = RunTracker(application, runListener)

    // ─────────────────────────────────────────────────────────────────────
    // Rep / timed sessions
    // ─────────────────────────────────────────────────────────────────────

    fun select(exercise: Exercise) {
        if (_phase.value == Phase.ACTIVE) return
        _selected.value = exercise
        _phase.value = Phase.READY
        _reps.value = 0
        _elapsedSec.value = 0
        _signalLevel.value = 0f
        _calibrating.value = false
        _error.value = null
    }

    fun clearSelection() {
        if (_phase.value == Phase.ACTIVE) return
        _selected.value = null
        _phase.value = Phase.SELECT
        _reps.value = 0
        _elapsedSec.value = 0
        _signalLevel.value = 0f
    }

    /** Start the selected session (position confirmed / timed mode begin). */
    fun begin() {
        val exercise = _selected.value ?: return
        if (_phase.value == Phase.ACTIVE) return
        _reps.value = 0
        _elapsedSec.value = 0
        _signalLevel.value = 0f
        _error.value = null

        when (exercise.kind) {
            WorkoutKind.REP_SENSOR, WorkoutKind.BARBELL -> {
                _calibrating.value = true
                if (!engine.start(exercise)) {
                    _error.value = "Accelerometer unavailable on this device"
                    _calibrating.value = false
                    return
                }
            }
            WorkoutKind.TIMED -> {
                _calibrating.value = false
            }
            WorkoutKind.RUN -> return
        }
        _phase.value = Phase.ACTIVE
        startTimer()
    }

    /** Finish the session and append it to the dashboard log. */
    fun finish() {
        val exercise = _selected.value
        engine.stop()
        stopTimer()
        _calibrating.value = false
        if (exercise != null && (exercise.kind == WorkoutKind.REP_SENSOR ||
                exercise.kind == WorkoutKind.BARBELL || exercise.kind == WorkoutKind.TIMED)
        ) {
            record(exercise, _reps.value, _elapsedSec.value, 0.0)
        }
        _phase.value = if (exercise == null) Phase.SELECT else Phase.DONE
    }

    fun discard() {
        engine.stop()
        stopTimer()
        _selected.value = null
        _phase.value = Phase.SELECT
        _reps.value = 0
        _elapsedSec.value = 0
        _signalLevel.value = 0f
        _calibrating.value = false
    }

    private fun startTimer() {
        stopTimer()
        timerJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                // A paused run (AI auto-pause or a manual pause) does not
                // accumulate exercise time.
                val exercise = _selected.value
                if (exercise != null && exercise.kind == WorkoutKind.RUN &&
                    !_runActive.value
                ) {
                    continue
                }
                _elapsedSec.value += 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun record(exercise: Exercise, reps: Int, seconds: Int, distanceM: Double) {
        val repo = repository
        val timestamp = System.currentTimeMillis()

        // Only reachable if the Application class was not applied to the
        // manifest. Keep the in-memory entry so the finished set is still shown
        // rather than silently dropped.
        if (repo == null) {
            val entry = WorkoutRecord(
                exerciseId = exercise.id,
                name = exercise.name,
                emoji = exercise.emoji,
                reps = reps,
                seconds = seconds,
                distanceM = distanceM,
                timestamp = timestamp
            )
            _history.value = (listOf(entry) + _history.value).take(MAX_HISTORY)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.addWorkout(
                    exerciseId = exercise.id,
                    name = exercise.name,
                    emoji = exercise.emoji,
                    reps = reps,
                    seconds = seconds.toLong(),
                    distanceM = distanceM,
                    timestamp = timestamp
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist workout session", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Running
    // ─────────────────────────────────────────────────────────────────────

    fun prepareRun(exercise: Exercise) {
        _selected.value = exercise
        _runMode.value = if (exercise.aiMode) RunMode.AI else RunMode.NORMAL
        _runDistanceM.value = 0.0
        _runPaceSecPerKm.value = 0.0
        _runCadence.value = 0
        _runRoute.value = emptyList()
        _runActive.value = false
        _runStarted.value = false
        _elapsedSec.value = 0
        _error.value = null
        _phase.value = Phase.ACTIVE
    }

    fun startRun() {
        runTracker.reset()
        if (!runTracker.start()) return
        _runStarted.value = true
        _elapsedSec.value = 0
        // Normal running is manual: active from the tap. AI running waits for
        // the accelerometer to confirm the stride.
        _runActive.value = _runMode.value == RunMode.NORMAL
        startTimer()
    }

    fun toggleRunActive() {
        _runActive.value = !_runActive.value
    }

    fun finishRun() {
        stopRunInternal(save = true)
    }

    private fun stopRunInternal(save: Boolean) {
        val exercise = _selected.value
        runTracker.stop()
        stopTimer()
        if (save && exercise != null) {
            record(exercise, 0, _elapsedSec.value, _runDistanceM.value)
        }
        _runActive.value = false
        _runStarted.value = false
        _phase.value = if (save) Phase.DONE else Phase.SELECT
    }

    fun exitRun() {
        runTracker.stop()
        stopTimer()
        _selected.value = null
        _runActive.value = false
        _runStarted.value = false
        _phase.value = Phase.SELECT
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
        runTracker.stop()
        stopTimer()
        Log.d(TAG, "WorkoutViewModel cleared")
    }

    private fun WorkoutSession.toRecord() = WorkoutRecord(
        exerciseId = exerciseId,
        name = name,
        emoji = emoji,
        reps = reps,
        seconds = seconds.toInt(),
        distanceM = distanceM,
        timestamp = timestamp
    )

    companion object {
        private const val TAG = "WorkoutViewModel"
        private const val MAX_HISTORY = 20
        private const val AI_START_CADENCE = 140
        private const val AI_PAUSE_CADENCE = 60
        private const val AI_START_TICKS = 3
        private const val AI_PAUSE_TICKS = 5
    }
}
