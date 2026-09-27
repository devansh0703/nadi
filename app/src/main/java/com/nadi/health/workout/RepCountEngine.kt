package com.nadi.health.workout

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/**
 * Accelerometer-only repetition counter - no camera involved.
 *
 * Two detectors are ported from the referenced open-source apps and selected
 * per exercise:
 *
 *  1. [Algorithm.HYSTERESIS] - FitTrack (LuckyTheCookie/FitTrack,
 *     `src/components/rep-counter/useSensorDetection.ts`). Calibrate a baseline
 *     from the first [CALIBRATION_SAMPLES] samples (~450 ms at ~33 Hz), smooth
 *     the chosen axis with a [SMOOTH_WINDOW]-sample moving average, then count
 *     a rep when `|value - baseline|` rises above `threshold` and falls back
 *     below `threshold * 0.4`, gated by a per-exercise cooldown and a minimum
 *     peak of `threshold * 1.2`. Push-ups, sit-ups, squats, jumping jacks.
 *
 *  2. [Algorithm.HIGH_PASS] - RepCapture (jvangore31/RepCapture,
 *     `BenchDataEntry.java`). Low-pass the axis with alpha 0.8 to estimate
 *     gravity, subtract it to isolate the linear component, then count a rep
 *     on a full above/below cycle of that signal. Deadlift, overhead press,
 *     bench press.
 *
 * Sensor callbacks stay lean: only running state is mutated here, and the live
 * telemetry meter is throttled to ~10 Hz before reaching the UI thread.
 */
class RepCountEngine(
    context: Context,
    private val listener: Listener
) : SensorEventListener {

    interface Listener {
        /** Baseline captured - real counting starts now. */
        fun onCalibrated()

        /** A rep was completed; [total] is the running count. */
        fun onRep(total: Int)

        /** Normalised signal magnitude (~0..2x threshold) for the live meter. */
        fun onTelemetry(level: Float)
    }

    enum class Algorithm { HYSTERESIS, HIGH_PASS }

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // --- Active configuration ---
    private var axisIndex = Axis.Z.index
    private var threshold = 0.4f
    private var cooldownMs = 700L
    private var algorithm = Algorithm.HYSTERESIS

    private var running = false
    private var reps = 0

    // --- HYSTERESIS state (FitTrack) ---
    private val calibration = ArrayList<Float>(CALIBRATION_SAMPLES)
    private val smoothBuffer = ArrayList<Float>(SMOOTH_WINDOW)
    private var baseline = 0f
    private var calibrated = false
    private var wasAbove = false
    private var peak = 0f
    private var lastRepMs = 0L

    // --- HIGH_PASS state (RepCapture) ---
    private var gravityLowPass = 0f
    private var lowPassSeeded = false
    private var phaseAbove = false

    // --- Telemetry throttle ---
    private var lastTelemetryMs = 0L

    val isRunning: Boolean get() = running

    val repCount: Int get() = reps

    /**
     * Start counting. Returns false when the device exposes no accelerometer
     * or the listener cannot be registered.
     */
    fun start(exercise: Exercise): Boolean {
        val accel = accelerometer ?: return false
        reset(exercise)
        // SENSOR_DELAY_GAME (~50 Hz) resolves a rep cycle comfortably;
        // SENSOR_DELAY_UI is the defensive fallback, mirroring how the rest of
        // the app degrades on refused registrations.
        var ok = false
        try {
            ok = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        } catch (_: SecurityException) {
            ok = false
        }
        if (!ok) {
            try {
                ok = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
            } catch (_: SecurityException) {
                ok = false
            }
        }
        running = ok
        return ok
    }

    fun stop() {
        if (!running) return
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
            // unregister is idempotent; ignore anything the framework throws
        }
        running = false
    }

    private fun reset(exercise: Exercise) {
        axisIndex = exercise.axis.index
        threshold = exercise.threshold
        cooldownMs = exercise.cooldownMs
        algorithm = when (exercise.kind) {
            WorkoutKind.BARBELL -> Algorithm.HIGH_PASS
            else -> Algorithm.HYSTERESIS
        }
        reps = 0
        calibration.clear()
        smoothBuffer.clear()
        baseline = 0f
        calibrated = false
        wasAbove = false
        peak = 0f
        lastRepMs = 0L
        gravityLowPass = 0f
        lowPassSeeded = false
        phaseAbove = false
        lastTelemetryMs = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (event.values.size <= axisIndex) return
        val value = event.values[axisIndex]
        when (algorithm) {
            Algorithm.HYSTERESIS -> hysteresis(value)
            Algorithm.HIGH_PASS -> highPass(value)
        }
    }

    /** FitTrack detector: baseline delta with hysteresis + cooldown. */
    private fun hysteresis(value: Float) {
        if (!calibrated) {
            calibration.add(value)
            if (calibration.size >= CALIBRATION_SAMPLES) {
                var sum = 0f
                for (v in calibration) sum += v
                baseline = sum / calibration.size
                calibrated = true
                listener.onCalibrated()
            }
            return
        }

        // 3-sample moving average, exactly as the reference hook.
        smoothBuffer.add(value)
        if (smoothBuffer.size > SMOOTH_WINDOW) smoothBuffer.removeAt(0)
        var sum = 0f
        for (v in smoothBuffer) sum += v
        val smoothed = sum / smoothBuffer.size

        val delta = abs(smoothed - baseline)
        val now = SystemClock.elapsedRealtime()
        emitTelemetry(delta / threshold, now)

        if (delta > threshold) {
            if (delta > peak) peak = delta
            wasAbove = true
        } else if (wasAbove && delta < threshold * 0.4f) {
            // Peak gate raised from 1.2x to 1.4x: a brief jerk that merely
            // crosses the threshold no longer counts as a rep.
            if (now - lastRepMs > cooldownMs && peak > threshold * 1.4f) {
                lastRepMs = now
                reps++
                listener.onRep(reps)
            }
            wasAbove = false
            peak = 0f
        }
    }

    /** RepCapture detector: gravity low-pass -> linear high-pass -> cycle count. */
    private fun highPass(value: Float) {
        if (!lowPassSeeded) {
            gravityLowPass = value
            lowPassSeeded = true
        }
        // RepCapture lowPass(): gravity * alpha + current * (1 - alpha)
        gravityLowPass = gravityLowPass * ALPHA + value * (1f - ALPHA)
        // RepCapture highPass(): current - gravity
        val linear = value - gravityLowPass

        val now = SystemClock.elapsedRealtime()
        emitTelemetry(abs(linear) / threshold, now)

        // One rep per full up/down cycle, with hysteresis at the far edge so
        // the falling half of the movement is not counted twice.
        if (!phaseAbove && linear > threshold) {
            phaseAbove = true
        } else if (phaseAbove && linear < -threshold * 0.5f) {
            phaseAbove = false
            if (now - lastRepMs > cooldownMs) {
                lastRepMs = now
                reps++
                listener.onRep(reps)
            }
        }
    }

    private fun emitTelemetry(level: Float, now: Long) {
        if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
            lastTelemetryMs = now
            listener.onTelemetry(max(0f, level))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** FitTrack: calibration window in samples. */
        private const val CALIBRATION_SAMPLES = 15

        /** FitTrack: moving-average window. */
        private const val SMOOTH_WINDOW = 3

        /** RepCapture: low-pass coefficient on gravity. */
        private const val ALPHA = 0.8f

        private const val TELEMETRY_INTERVAL_MS = 100L
    }
}
