package com.nadi.health.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * App-scoped ambient/context sensors - every remaining PUBLIC sensor on the
 * iQOO 15 not driven by ImuRecorder, per live `dumpsys sensorservice`:
 *
 *  - tcs3743 ambient light (type 5)      -> lux, dim-light gate for camera rPPG
 *  - tcs3743 proximity (type 8)          -> occlusion gate for camera rPPG
 *  - qmc6309h magnetometer (type 2)      -> heading (gait straightness)
 *  - sns_smd significant motion (17)     -> one-shot wake trigger
 *  - stationary_detect (29)              -> one-shot "user became still"
 *  - motion_detect (30)                  -> one-shot motion flag
 *  - pedometer step_counter (19)         -> cumulative steps (ACTIVITY_RECOGNITION)
 *  - step_detect (18)                    -> per-step events (ACTIVITY_RECOGNITION)
 *  - lsm6dsvx sensor_temperature (66573) -> chip die temp (NOT body temp)
 *
 * Trigger sensors (SMD/stationary/motion) are one-shot: they are re-armed
 * after every firing. Step sensors register only when ACTIVITY_RECOGNITION
 * is granted; otherwise their flows stay 0.
 */
class SensorContext private constructor(context: Context) : SensorEventListener {

    enum class Stillness { MOVING, STILL, UNKNOWN }

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val sigMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val stationary = sensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT)
    private val motionDetect = sensorManager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)

    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetect = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val dieTempSensor = run {
        var found: Sensor? = null
        for (s in sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (s.stringType.endsWith("sensor_temperature")) {
                found = s
                break
            }
        }
        found
    }

    // --- State flows ---
    private val _lux = MutableStateFlow(-1f)
    val lux: StateFlow<Float> = _lux.asStateFlow()

    private val _proximityCm = MutableStateFlow(-1f)
    val proximityCm: StateFlow<Float> = _proximityCm.asStateFlow()

    private val _headingDeg = MutableStateFlow(-1f)
    val headingDeg: StateFlow<Float> = _headingDeg.asStateFlow()

    private val _stillness = MutableStateFlow(Stillness.UNKNOWN)
    val stillness: StateFlow<Stillness> = _stillness.asStateFlow()

    private val _stepsToday = MutableStateFlow(0L)
    val stepsToday: StateFlow<Long> = _stepsToday.asStateFlow()

    private val _stepEvents = MutableStateFlow(0L)
    val stepEvents: StateFlow<Long> = _stepEvents.asStateFlow()

    private val _dieTempC = MutableStateFlow(0f)
    val dieTempC: StateFlow<Float> = _dieTempC.asStateFlow()

    // Magnetometer/accel latest for heading computation
    private val magLatest = FloatArray(3)
    private val accelLatest = FloatArray(3)
    private val rotation = FloatArray(9)
    private val inclination = FloatArray(9)

    private var running = false

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            when (event?.sensor?.type) {
                Sensor.TYPE_SIGNIFICANT_MOTION -> _stillness.value = Stillness.MOVING
                Sensor.TYPE_STATIONARY_DETECT -> _stillness.value = Stillness.STILL
                Sensor.TYPE_MOTION_DETECT -> _stillness.value = Stillness.MOVING
            }
            // Re-arm one-shot triggers
            armTriggers()
        }
    }

    /** Register everything available. [activityRecognitionGranted] gates steps. */
    fun start(activityRecognitionGranted: Boolean) {
        if (running) return
        running = true
        register(light)
        register(proximity)
        register(magnetometer)
        register(accel)
        if (activityRecognitionGranted) {
            register(stepCounter)
            register(stepDetect)
        }
        armTriggers()
        dieTempSensor?.let { register(it) }
    }

    private fun armTriggers() {
        sigMotion?.let {
            try {
                sensorManager.requestTriggerSensor(triggerListener, it)
            } catch (_: SecurityException) {
            }
        }
        stationary?.let {
            try {
                sensorManager.requestTriggerSensor(triggerListener, it)
            } catch (_: SecurityException) {
            }
        }
        motionDetect?.let {
            try {
                sensorManager.requestTriggerSensor(triggerListener, it)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun register(sensor: Sensor?) {
        sensor ?: return
        try {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        } catch (_: SecurityException) {
            // Step sensors without ACTIVITY_RECOGNITION land here
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        sensorManager.cancelTriggerSensor(triggerListener, sigMotion)
        sensorManager.cancelTriggerSensor(triggerListener, stationary)
        sensorManager.cancelTriggerSensor(triggerListener, motionDetect)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> _lux.value = event.values[0]

            Sensor.TYPE_PROXIMITY -> _proximityCm.value = event.values[0]

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magLatest, 0, 3)
                computeHeading()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelLatest, 0, 3)
                computeHeading()
            }

            Sensor.TYPE_STEP_COUNTER -> {
                // Cumulative since boot - first reading becomes the session zero
                val total = event.values[0].toLong()
                if (stepZero < 0) stepZero = total
                _stepsToday.value = (total - stepZero).coerceAtLeast(0)
            }

            Sensor.TYPE_STEP_DETECTOR -> _stepEvents.value += 1

            else -> {
                // lsm6dsvx sensor_temperature: value[0] is degrees Celsius
                if (event.sensor.stringType.endsWith("sensor_temperature")) {
                    _dieTempC.value = event.values[0]
                }
            }
        }
    }

    /** Called after ACTIVITY_RECOGNITION is granted mid-session. */
    fun registerSteps() {
        register(stepCounter)
        register(stepDetect)
    }

    private var stepZero = -1L

    private fun computeHeading() {
        val ok = SensorManager.getRotationMatrix(rotation, inclination, accelLatest, magLatest)
        if (!ok) return
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (deg < 0) deg += 360f
        _headingDeg.value = deg
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        @Volatile
        private var instance: SensorContext? = null

        fun get(context: Context): SensorContext =
            instance ?: synchronized(this) {
                instance ?: SensorContext(context.applicationContext).also { instance = it }
            }
    }
}
