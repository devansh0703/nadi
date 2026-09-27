package com.nadi.health.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import com.nadi.health.data.local.HeartRateReading
import com.nadi.health.data.local.WorkoutSession
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A heart-rate sample read from Health Connect. */
data class DeviceHeartRate(
    val bpm: Float,
    val timestamp: Long,
    val origin: String
)

/** An exercise session recorded by another app or a watch. */
data class DeviceSession(
    val title: String,
    val startMs: Long,
    val durationSec: Long,
    /** 0.0 when the source did not report a distance. */
    val distanceM: Double,
    val origin: String
)

/**
 * Everything Nadi needs from Health Connect, in one place.
 *
 * Health Connect is the Android hub that watches, chest straps and other health
 * apps sync into — pairing a watch happens in that watch's own companion app,
 * not here. What Nadi does is *read* whatever those devices deposited, and
 * *write* its own measurements back so they appear alongside them.
 *
 * Every call is defensive: when Health Connect is missing, out of date, or a
 * permission was not granted, these functions return empty results and the UI
 * reports the real reason instead of crashing.
 */
class HealthConnectManager(private val context: Context) {

    enum class Availability {
        /** Provider present and usable. */
        AVAILABLE,

        /** Provider present but needs an update before it will serve data. */
        UPDATE_REQUIRED,

        /** Not installed (normal on Android 13 and below). */
        NOT_INSTALLED
    }

    /**
     * The permission set Nadi asks for: read what devices measured, write what
     * Nadi measured. Requesting the whole set once keeps the grant flow to a
     * single screen.
     */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    )

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
        else -> Availability.NOT_INSTALLED
    }

    fun isAvailable(): Boolean = availability() == Availability.AVAILABLE

    private fun clientOrNull(): HealthConnectClient? =
        if (isAvailable()) {
            runCatching { HealthConnectClient.getOrCreate(context) }
                .onFailure { Log.w(TAG, "Health Connect client unavailable", it) }
                .getOrNull()
        } else {
            null
        }

    /** Contract for the permission screen; hand it to `rememberLauncherForActivityResult`. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** Permissions the user has actually granted, intersected with [permissions]. */
    suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }
            .onFailure { Log.w(TAG, "Could not read granted permissions", it) }
            .getOrDefault(emptySet())
            .intersect(permissions)
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    /**
     * Latest heart-rate samples from other sources, newest first.
     * [originFilter] lets the caller drop a package it wrote itself.
     */
    suspend fun readHeartRate(
        days: Int = 1,
        limit: Int = 20,
        originFilter: String = HC_PACKAGE
    ): List<DeviceHeartRate> {
        val client = clientOrNull() ?: return emptyList()
        val start = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
                )
            )
            response.records
                .flatMap { record ->
                    record.samples.map { sample ->
                        DeviceHeartRate(
                            bpm = sample.beatsPerMinute.toFloat(),
                            timestamp = sample.time.toEpochMilli(),
                            origin = record.metadata.dataOrigin.packageName
                        )
                    }
                }
                .sortedByDescending { it.timestamp }
                .take(limit)
        }.onFailure { Log.w(TAG, "readHeartRate failed", it) }
            .getOrDefault(emptyList())
    }

    /** Total steps recorded today, or null when unavailable / not granted. */
    suspend fun readStepsToday(): Long? {
        val client = clientOrNull() ?: return null
        val filter = TimeRangeFilter.between(todayStart(), Instant.now())
        return runCatching {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), filter))[
                StepsRecord.COUNT_TOTAL
            ]
        }.onFailure { Log.w(TAG, "readStepsToday failed", it) }
            .getOrNull()
    }

    /** Total distance in metres recorded today, or null when unavailable. */
    suspend fun readDistanceToday(): Double? {
        val client = clientOrNull() ?: return null
        val filter = TimeRangeFilter.between(todayStart(), Instant.now())
        return runCatching {
            client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), filter))[
                DistanceRecord.DISTANCE_TOTAL
            ]?.inMeters
        }.onFailure { Log.w(TAG, "readDistanceToday failed", it) }
            .getOrNull()
    }

    /** Exercise sessions logged by other apps or watches in the last [days]. */
    suspend fun readExerciseSessions(days: Int = 7, limit: Int = 20): List<DeviceSession> {
        val client = clientOrNull() ?: return emptyList()
        val start = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
                )
            )
            response.records
                .sortedByDescending { it.startTime.toEpochMilli() }
                .take(limit)
                .map { record ->
                    DeviceSession(
                        title = exerciseLabel(record.exerciseType),
                        startMs = record.startTime.toEpochMilli(),
                        durationSec = ChronoUnit.SECONDS.between(
                            record.startTime,
                            record.endTime
                        ),
                        distanceM = 0.0,
                        origin = record.metadata.dataOrigin.packageName
                    )
                }
        }.onFailure { Log.w(TAG, "readExerciseSessions failed", it) }
            .getOrDefault(emptyList())
    }

    // ── Writes ────────────────────────────────────────────────────────────

    /**
     * Push locally stored heart-rate readings into Health Connect.
     * Returns how many samples were written; 0 means nothing was attempted.
     */
    suspend fun writeHeartRate(readings: List<HeartRateReading>): Int {
        val client = clientOrNull() ?: return 0
        val ours = readings.filter { it.source == HeartRateReading.SOURCE_RPPG }
        if (ours.isEmpty()) return 0
        return runCatching {
            val records = ours.map { reading ->
                val at = Instant.ofEpochMilli(reading.timestamp)
                HeartRateRecord(
                    startTime = at,
                    startZoneOffset = null,
                    endTime = at,
                    endZoneOffset = null,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = at,
                            beatsPerMinute = reading.bpm.toLong()
                        )
                    ),
                    metadata = Metadata.manualEntry()
                )
            }
            client.insertRecords(records)
            records.size
        }.onFailure { Log.w(TAG, "writeHeartRate failed", it) }
            .getOrDefault(0)
    }

    /**
     * Push locally stored workout sessions into Health Connect as exercise
     * sessions, attaching a distance record when the session measured one.
     */
    suspend fun writeWorkouts(sessions: List<WorkoutSession>): Int {
        val client = clientOrNull() ?: return 0
        if (sessions.isEmpty()) return 0
        return runCatching {
            var written = 0
            sessions.forEach { session ->
                val start = Instant.ofEpochMilli(session.timestamp)
                val end = start.plusSeconds(session.seconds.coerceAtLeast(1L))
                val records = mutableListOf<Record>(
                    ExerciseSessionRecord(
                        startTime = start,
                        startZoneOffset = null,
                        endTime = end,
                        endZoneOffset = null,
                        metadata = Metadata.manualEntry(),
                        exerciseType = exerciseType(session.exerciseId)
                    )
                )
                if (session.distanceM > 0.0) {
                    records += DistanceRecord(
                        startTime = start,
                        startZoneOffset = null,
                        endTime = end,
                        endZoneOffset = null,
                        distance = Length.meters(session.distanceM),
                        metadata = Metadata.manualEntry()
                    )
                }
                client.insertRecords(records)
                written++
            }
            written
        }.onFailure { Log.w(TAG, "writeWorkouts failed", it) }
            .getOrDefault(0)
    }

    // ── Navigation helpers ────────────────────────────────────────────────

    /**
     * Intent that opens Health Connect's data management screen — the list of
     * connected apps and their permissions, which is where the user checks that
     * a watch or health app is actually feeding data in.
     */
    fun settingsIntent(): Intent = HealthConnectClient.getHealthConnectManageDataIntent(context)

    /** Play Store fallback for devices without Health Connect installed. */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$HC_PACKAGE"))

    // ── Mapping ───────────────────────────────────────────────────────────

    /**
     * Closest Health Connect activity type for a Nadi exercise id.
     * Bodyweight reps are calisthenics; loaded lifts are strength training.
     */
    fun exerciseType(exerciseId: String): Int = when (exerciseId) {
        "running", "running_ai" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "elliptical" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        "deadlift", "overhead_press", "bench_press" ->
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "pushups", "situps", "squats", "jumping_jacks" ->
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    private fun exerciseLabel(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength training"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swimming"
        else -> "Exercise"
    }

    private fun todayStart(): Instant = Instant.now().truncatedTo(ChronoUnit.DAYS)

    companion object {
        private const val TAG = "HealthConnect"

        /** Health Connect's package id on Android 13 and below. */
        const val HC_PACKAGE = "com.google.android.apps.healthdata"
    }
}
