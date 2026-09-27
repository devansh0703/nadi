package com.nadi.health.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single heart-rate reading, persisted to the local database.
 *
 * Readings come from two places and are distinguished by [source]:
 *  - [SOURCE_RPPG] — measured by this app's camera pipeline.
 *  - [SOURCE_HEALTH_CONNECT] — imported from Health Connect (a watch, a chest
 *    strap, or any other app that pushes heart rate into the platform).
 *
 * Storing both in one table is deliberate: the UI shows one timeline, and the
 * `source` column makes it possible to compare our own measurement against a
 * device's without conflating them.
 */
@Entity(
    tableName = "heart_rate_readings",
    indices = [Index(value = ["timestamp"])]
)
data class HeartRateReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bpm: Float,
    val confidence: Float,
    /** Signal-quality label from the analyzer, e.g. "GOOD" / "POOR". */
    val quality: String,
    /** [SOURCE_RPPG] or [SOURCE_HEALTH_CONNECT]. */
    val source: String,
    /** Epoch millis. */
    val timestamp: Long
) {
    companion object {
        const val SOURCE_RPPG = "rppg"
        const val SOURCE_HEALTH_CONNECT = "health_connect"
    }
}

/**
 * A completed workout session, persisted to the local database.
 *
 * Rep-based sessions carry [reps]; cardio sessions carry [distanceM]. Both stay
 * zero when the exercise legitimately has neither (elliptical is timed only),
 * so nothing is invented.
 */
@Entity(
    tableName = "workout_sessions",
    indices = [Index(value = ["timestamp"])]
)
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** [com.nadi.health.workout.Exercise.id], e.g. "pushups". */
    val exerciseId: String,
    val name: String,
    val emoji: String,
    val reps: Int,
    val seconds: Long,
    val distanceM: Double,
    /** Epoch millis. */
    val timestamp: Long
)

/**
 * A full vitals sample captured by one camera measurement window.
 *
 * [HeartRateReading] is a single-number log (kept for the existing UI and for
 * Health Connect sync). This table is the analytics row: one measurement window
 * with everything that was derived from the same PPG buffer, so the AI features
 * can trend HRV / stress / SpO2 / estimated BP over time instead of only bpm.
 *
 * Zero means "not confident in this window", never "measured as zero".
 */
@Entity(
    tableName = "vitals_samples",
    indices = [Index(value = ["timestamp"])]
)
data class VitalsSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bpm: Float,
    /** SDNN in ms. */
    val sdnnMs: Float = 0f,
    /** RMSSD in ms. */
    val rmssdMs: Float = 0f,
    /** 0-100, higher = more stress. */
    val stressIndex: Float = 0f,
    /** %; 0 = not confident. */
    val spo2: Int = 0,
    /** Breaths/min; 0 = not confident. */
    val respiratoryRate: Int = 0,
    /** Estimated (wellness heuristic), 0 = not confident. */
    val systolic: Int = 0,
    val diastolic: Int = 0,
    val quality: String = "",
    /** [HeartRateReading.SOURCE_RPPG] or `_HEALTH_CONNECT`. */
    val source: String = HeartRateReading.SOURCE_RPPG,
    val timestamp: Long
)

// ══════════════════════════════════════════════════════════════════════════
//  AI feature store
//
//  Everything the on-device Qwen model is allowed to reason over lives here.
//  The model never sees anything that is not in one of these tables (plus the
//  heart-rate / workout tables above), which is what keeps answers grounded
//  in the user's real data instead of invented numbers.
// ══════════════════════════════════════════════════════════════════════════

/**
 * The single-row user profile (always `id = 1`).
 *
 * Free-text fields ([conditions], [medications], [allergies]) are stored as
 * the user typed them — no parsing, no inference, no clinical coding. The
 * model is told to treat them as self-reported and never to interpret them.
 */
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val name: String = "",
    val ageYears: Int = 0,
    /** "female" | "male" | "other" | "" */
    val sex: String = "",
    val heightCm: Int = 0,
    val weightKg: Float = 0f,
    val bloodGroup: String = "",
    val conditions: String = "",
    val medications: String = "",
    val allergies: String = "",
    val emergencyContact: String = "",
    /** BCP-47-ish tag, e.g. "en-IN", "hi-IN". */
    val language: String = "en-IN",
    /** "veg" | "nonveg" | "egg" | "jain" | "sattvic" | "" */
    val foodPreference: String = "",
    /** Free-text Indian region/state, used by the meal planner. */
    val region: String = "",
    /** "low" | "mid" | "high" — budget tier for meal plans. */
    val budgetTier: String = "mid",
    /** "beginner" | "intermediate" | "advanced" — workout experience. */
    val experience: String = "beginner",
    /** Self-reported weekly training days target. */
    val daysPerWeek: Int = 3,
    /** Opt-in for proactive tips (RULES.md: no nagging unless asked). */
    val proactiveTipsEnabled: Boolean = false,
    val updatedAt: Long = 0L
)

/** A chat turn in the health-vitals Q&A assistant. */
@Entity(
    tableName = "ai_chat_messages",
    indices = [Index(value = ["sessionId"])]
)
data class AiChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** "user" | "assistant" */
    val role: String,
    val content: String,
    val sessionId: String,
    val timestamp: Long
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * A cached, already-generated AI artifact (report, digest, plan, card…).
 *
 * Caching matters on a 4B phone NPU: a weekly digest takes tens of seconds to
 * generate, so it is written once here and shown instantly afterwards. [kind]
 * is a stable key (`report.session`, `digest.weekly`, `plan.workout`, …).
 */
@Entity(
    tableName = "ai_insights",
    indices = [Index(value = ["kind", "createdAt"])]
)
data class AiInsight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val kind: String,
    val title: String,
    /** Plain text; may contain the model's own markdown-ish structure. */
    val body: String,
    val createdAt: Long
)

/** A low-friction symptom diary entry (voice or typed, free text). */
@Entity(
    tableName = "symptom_logs",
    indices = [Index(value = ["timestamp"])]
)
data class SymptomLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    /** Self-reported 1..5; 0 = not stated. */
    val severity: Int = 0,
    val timestamp: Long
)

/** A concrete, locally-stored wellness goal produced by the goal-setting dialogue. */
@Entity(
    tableName = "wellness_goals",
    indices = [Index(value = ["active"])]
)
data class WellnessGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** "weight" | "workout" | "meal" | "sleep" | "hydration" | "steps" */
    val kind: String,
    val title: String,
    val targetValue: Float = 0f,
    val unit: String = "",
    val deadline: Long = 0L,
    val notes: String = "",
    val active: Boolean = true,
    val createdAt: Long
)

/**
 * A logged meal. Nutrition is deliberately stored as the *range* centre the
 * model produced (or the user's own edit) — see RULES.md: ranges, never
 * precision the signal cannot support.
 */
@Entity(
    tableName = "meal_logs",
    indices = [Index(value = ["timestamp"])]
)
data class MealLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Indian dish name as recognised/entered, e.g. "2 roti + dal". */
    val dish: String,
    val calories: Int = 0,
    val proteinG: Int = 0,
    val carbsG: Int = 0,
    val fatG: Int = 0,
    /** "breakfast" | "lunch" | "snack" | "dinner" | "" */
    val slot: String = "",
    /** True when the user corrected the estimate. */
    val edited: Boolean = false,
    /** "photo" | "manual" | "plan" */
    val source: String = "manual",
    /** Optional local path of the photo this came from (never uploaded). */
    val photoPath: String = "",
    val timestamp: Long
)

/**
 * A lab value the user scanned or typed in.
 *
 * Stored verbatim with its unit and reference range. Nadi never re-interprets
 * these as a diagnosis — they are trend inputs and report content only.
 */
@Entity(
    tableName = "lab_values",
    indices = [Index(value = ["name", "timestamp"])]
)
data class LabValue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** "Hemoglobin", "Fasting glucose", "HbA1c", "TSH", "Vit-D", … */
    val name: String,
    val value: Float,
    val unit: String = "",
    val refLow: Float = 0f,
    val refHigh: Float = 0f,
    /** True when [refLow] / [refHigh] were absent on the report. */
    val refMissing: Boolean = false,
    val note: String = "",
    val timestamp: Long
)

/** Self-reported sleep (Nadi does not measure sleep — RULES.md §6). */
@Entity(
    tableName = "sleep_logs",
    indices = [Index(value = ["timestamp"])]
)
data class SleepLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hours: Float,
    /** Self-reported 1..5; 0 = not stated. */
    val quality: Int = 0,
    val note: String = "",
    val timestamp: Long
)

/** A hydration tally for a single day (one row per calendar day, `id` = day key). */
@Entity(tableName = "hydration_logs")
data class HydrationLog(
    @PrimaryKey val dayKey: String,
    val glasses: Int,
    val goalGlasses: Int = 8,
    val updatedAt: Long
)

/** A medication reminder the user created, plus informational Q&A only. */
@Entity(tableName = "medication_reminders")
data class MedicationReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val dose: String = "",
    /** Human-readable schedule, e.g. "08:00, 20:00". */
    val schedule: String = "",
    val note: String = "",
    val enabled: Boolean = true,
    val createdAt: Long
)

/**
 * A progress photo. Path is app-private (filesDir) and never leaves the phone.
 * Used for framing/consistency only — explicitly not body-composition analysis.
 */
@Entity(
    tableName = "photo_logs",
    indices = [Index(value = ["timestamp"])]
)
data class PhotoLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val path: String,
    /** "progress" | "food" | "lab" */
    val kind: String,
    val note: String = "",
    val timestamp: Long
)

/** A habit streak counter (measurement / workout / hydration). */
@Entity(tableName = "streaks")
data class Streak(
    @PrimaryKey val kind: String,
    val current: Int,
    val best: Int,
    /** Epoch-day index of the last contributing day. */
    val lastDay: Long,
    val updatedAt: Long
)

/**
 * A user-entered or cached environment reading (AQI / weather).
 *
 * The app is offline-first, so this is either fetched once (opt-in network) and
 * cached with a timestamp, or hand-entered. Nothing here is invented.
 */
@Entity(tableName = "environment_snapshots")
data class EnvironmentSnapshot(
    @PrimaryKey val id: Int = 1,
    val aqi: Int = -1,
    val pm25: Float = -1f,
    val tempC: Float = -100f,
    val humidityPct: Int = -1,
    val condition: String = "",
    val place: String = "",
    /** "network" | "manual" */
    val source: String = "" ,
    val fetchedAt: Long = 0L
)
