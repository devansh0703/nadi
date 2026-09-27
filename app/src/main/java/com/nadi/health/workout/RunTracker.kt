package com.nadi.health.workout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Running tracker for the workout dashboard.
 *
 * Running is deliberately NOT a repetition counter: it reports duration,
 * distance, route and pace (the FitTrack `run` / `run_ai` screens). Distance
 * and route come from the platform location provider; cadence comes from the
 * accelerometer so "AI running" can auto start/pause from the runner's stride
 * without touching the camera.
 */
class RunTracker(
    context: Context,
    private val listener: Listener
) : LocationListener, SensorEventListener {

    interface Listener {
        /** New accepted fix. [paceSecPerKm] <= 0 means "no pace yet". */
        fun onTick(distanceM: Double, paceSecPerKm: Double, lat: Double, lon: Double)

        /** Steps per minute estimated from the accelerometer. */
        fun onCadence(spm: Int)

        /** Location is unavailable / permission missing. */
        fun onLocationUnavailable(message: String)
    }

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var tracking = false

    // --- Route state ---
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var distanceM = 0.0
    private var lastSpeedMps = 0f
    private val route = ArrayList<Pair<Double, Double>>(512)

    // --- Cadence state ---
    private val stepTimes = ArrayDeque<Long>()
    private var lastStepMs = 0L
    private var cadence = 0

    val isTracking: Boolean get() = tracking

    val routePoints: List<Pair<Double, Double>> get() = ArrayList(route)

    val distanceMeters: Double get() = distanceM

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** Reset accumulated metrics. Call before each run. */
    fun reset() {
        lastLat = Double.NaN
        lastLon = Double.NaN
        distanceM = 0.0
        lastSpeedMps = 0f
        route.clear()
        stepTimes.clear()
        lastStepMs = 0L
        cadence = 0
    }

    /** Begin tracking. Returns false when location is unavailable. */
    fun start(): Boolean {
        if (tracking) return true
        if (!hasLocationPermission()) {
            listener.onLocationUnavailable("Location permission is required for running metrics")
            return false
        }
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            listener.onLocationUnavailable("Turn on location services to track a run")
            return false
        }
        var ok = false
        try {
            locationManager.requestLocationUpdates(
                provider, UPDATE_INTERVAL_MS, MIN_UPDATE_DISTANCE_M, this, Looper.getMainLooper()
            )
            ok = true
        } catch (_: SecurityException) {
            ok = false
        } catch (_: IllegalArgumentException) {
            ok = false
        }
        if (ok) {
            registerCadence()
        } else {
            listener.onLocationUnavailable("Location provider refused the request")
        }
        tracking = ok
        return ok
    }

    fun stop() {
        if (!tracking) return
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
            // removeUpdates is best-effort
        }
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
        tracking = false
    }

    private fun registerCadence() {
        val accel = accelerometer ?: return
        try {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        } catch (_: SecurityException) {
            // cadence is optional - AI mode degrades to manual start/stop
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!tracking) return
        if (!location.hasAccuracy() || location.accuracy > ACCURACY_REJECT_M) return

        val lat = location.latitude
        val lon = location.longitude
        if (!lastLat.isNaN()) {
            val d = haversineMeters(lastLat, lastLon, lat, lon)
            if (d > MIN_UPDATE_DISTANCE_M) distanceM += d
        }
        lastLat = lat
        lastLon = lon
        if (location.hasSpeed()) lastSpeedMps = location.speed

        route.add(lat to lon)
        if (route.size > MAX_ROUTE_POINTS) route.removeAt(0)

        val pace = if (lastSpeedMps > 0.5f) (1000.0 / lastSpeedMps) else 0.0
        listener.onTick(distanceM, pace, lat, lon)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!tracking) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        val now = SystemClock.elapsedRealtime()
        // A stride shows up as the vertical acceleration pushing above 1 g.
        if (magnitude > STEP_ACCEL_THRESHOLD && now - lastStepMs > STEP_MIN_INTERVAL_MS) {
            lastStepMs = now
            stepTimes.addLast(now)
            while (stepTimes.isNotEmpty() && now - stepTimes.first() > CADENCE_WINDOW_MS) {
                stepTimes.removeFirst()
            }
            val windowSec = CADENCE_WINDOW_MS / 1000.0
            val spm = (stepTimes.size / windowSec * 60.0).toInt()
            if (spm != cadence) {
                cadence = spm
                listener.onCadence(spm)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        if (tracking) listener.onLocationUnavailable("Location provider disabled")
    }

    @Deprecated("Required for API < 30 devices")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    companion object {
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MIN_UPDATE_DISTANCE_M = 1f
        private const val MAX_ROUTE_POINTS = 5000
        private const val ACCURACY_REJECT_M = 35f
        // Raised so pocket jitter and minor shakes don't register as steps.
        private const val STEP_ACCEL_THRESHOLD = 13f
        private const val STEP_MIN_INTERVAL_MS = 300L
        private const val CADENCE_WINDOW_MS = 10_000L

        /** Great-circle distance in metres. */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(min(1.0, sqrt(a)))
        }
    }
}
