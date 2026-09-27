package com.nadi.health.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * High-rate inertial recorder for physiological sensing.
 *
 * Samples accelerometer, gyroscope and gravity at SENSOR_DELAY_FASTEST
 * (the lsm6dsvx on the iQOO 15 delivers 1000+ Hz here; seismocardiography
 * needs >= 200 Hz to resolve the 20-50 Hz valve-click band per
 * Inan et al., "Novel methods of SCG signal processing", IEEE TBME 2015).
 *
 * Buffers hold the last ~15 s of samples. Gravity is estimated online as
 * an exponential moving average (2 s time constant) so the linear
 * component available to analyzers is body motion + micro-vibration only.
 * This is the standard first stage of the Beiwe respiration pipeline and
 * the Seismo SCG chain.
 */
class ImuRecorder(context: Context, private val secondsToKeep: Float = 15f) : SensorEventListener {

    data class ImuSample(
        val t: Double,      // seconds, monotonic
        val ax: Float, val ay: Float, val az: Float,  // linear accel (gravity removed)
        val gx: Float, val gy: Float, val gz: Float,  // raw angular velocity
        val gMag: Float,    // gravity magnitude (for stillness detection)
        val ugx: Float = 0f, val ugy: Float = 0f, val ugz: Float = 1f
        // unit gravity direction in device frame -> lets analyzers project
        // onto true vertical regardless of phone slant/tilt
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private val lock = Any()
    private val samples = ArrayList<ImuSample>(16384)

    // Gravity EMA state (per axis), seeded on first sample
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f
    private var gravSeeded = false

    private var startTime = 0.0
    private var running = false
    private var lastSampleT = 0.0

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        if (accel == null) return false
        synchronized(lock) {
            samples.clear()
            gravSeeded = false
            startTime = System.nanoTime() / 1e9
            lastSampleT = 0.0
        }
        // FASTEST (>=200 Hz) needs HIGH_SAMPLING_RATE_SENSORS (normal, declared
        // in manifest). Defensive fallback keeps measurement alive at 50 Hz if
        // registration is ever refused (SCG degrades, respiration still works).
        var ok = false
        try {
            ok = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST)
            gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } catch (_: SecurityException) {
            ok = false
        }
        if (!ok) {
            try {
                ok = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
                gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            } catch (_: SecurityException) {
                ok = false
            }
        }
        running = ok
        return ok
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        running = false
    }

    fun clear() {
        synchronized(lock) { samples.clear() }
    }

    /** Snapshot copy of the retained window. */
    fun snapshot(): List<ImuSample> = synchronized(lock) { ArrayList(samples) }

    fun sampleCount(): Int = synchronized(lock) { samples.size }

    /** RMS linear acceleration - motion/stillness indicator. */
    fun motionRms(): Float {
        val snap = synchronized(lock) { ArrayList(samples) }
        if (snap.isEmpty()) return 0f
        var acc = 0.0
        for (s in snap) {
            acc += (s.ax * s.ax + s.ay * s.ay + s.az * s.az).toDouble()
        }
        return sqrt(acc / snap.size).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp / 1e9 // device-monotonic seconds
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                // Gravity EMA: alpha tuned for ~2 s time constant at any rate
                val dt = if (lastSampleT > 0) (t - lastSampleT).coerceIn(0.001, 0.1) else 0.01
                lastSampleT = t
                val alpha = (dt / 2.0).toFloat().coerceIn(0.002f, 0.5f)

                if (!gravSeeded) {
                    gravX = ax; gravY = ay; gravZ = az
                    gravSeeded = true
                } else {
                    gravX += alpha * (ax - gravX)
                    gravY += alpha * (ay - gravY)
                    gravZ += alpha * (az - gravZ)
                }

                val gMag = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
                val invG = if (gMag > 0.1f) 1f / gMag else 0f

                val sample = ImuSample(
                    t, ax - gravX, ay - gravY, az - gravZ,
                    0f, 0f, 0f, gMag,
                    gravX * invG, gravY * invG, gravZ * invG
                )
                synchronized(lock) {
                    samples.add(sample)
                    trim()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                synchronized(lock) {
                    if (samples.isNotEmpty()) {
                        // Attach to the most recent accel sample (fusion-frame)
                        val last = samples[samples.size - 1]
                        samples[samples.size - 1] = last.copy(
                            gx = event.values[0], gy = event.values[1], gz = event.values[2]
                        )
                    }
                }
            }
        }
    }

    private fun trim() {
        val cutoff = (System.nanoTime() / 1e9) - secondsToKeep
        // Convert to event-time domain by trimming relative to newest sample
        if (samples.size > 16) {
            val newest = samples[samples.size - 1].t
            val minT = newest - secondsToKeep
            var idx = 0
            while (idx < samples.size - 1 && samples[idx].t < minT) idx++
            if (idx > 0) {
                samples.subList(0, idx).clear()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun release() {
        stop()
    }
}
