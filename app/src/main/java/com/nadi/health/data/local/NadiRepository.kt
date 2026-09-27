package com.nadi.health.data.local

import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/** Aggregate view of what is stored locally, shown on the Health screen. */
data class LocalStats(
    val readingCount: Int,
    val sessionCount: Int,
    val averageBpmToday: Float,
    val repsToday: Int,
    val distanceTodayM: Double
)

/**
 * Single entry point to the on-device store.
 *
 * The UI never touches the DAO directly: it observes flows from here, so a
 * write from the camera pipeline or the workout VM lands on every screen that
 * cares without manual refresh.
 */
class NadiRepository(private val dao: NadiDao) {

    // ── Reads ─────────────────────────────────────────────────────────────

    fun recentHeartRate(limit: Int = 50): Flow<List<HeartRateReading>> =
        dao.observeRecentHeartRate(limit)

    fun recentWorkouts(limit: Int = 50): Flow<List<WorkoutSession>> =
        dao.observeRecentWorkouts(limit)

    fun heartRateCount(): Flow<Int> = dao.observeHeartRateCount()

    fun workoutCount(): Flow<Int> = dao.observeWorkoutCount()

    // ── Writes ────────────────────────────────────────────────────────────

    suspend fun addHeartRate(
        bpm: Float,
        confidence: Float,
        quality: String,
        source: String = HeartRateReading.SOURCE_RPPG,
        timestamp: Long = System.currentTimeMillis()
    ) {
        dao.insertHeartRate(
            HeartRateReading(
                bpm = bpm,
                confidence = confidence,
                quality = quality,
                source = source,
                timestamp = timestamp
            )
        )
    }

    suspend fun addWorkout(
        exerciseId: String,
        name: String,
        emoji: String,
        reps: Int,
        seconds: Long,
        distanceM: Double,
        timestamp: Long = System.currentTimeMillis()
    ) {
        dao.insertWorkout(
            WorkoutSession(
                exerciseId = exerciseId,
                name = name,
                emoji = emoji,
                reps = reps,
                seconds = seconds,
                distanceM = distanceM,
                timestamp = timestamp
            )
        )
    }

    // ── Aggregates ────────────────────────────────────────────────────────

    /** Readings from the last [hours] hours, oldest first — for syncing out. */
    suspend fun heartRateSince(hours: Int): List<HeartRateReading> =
        dao.heartRateSince(System.currentTimeMillis() - hours * 60L * 60L * 1000L)

    /** Sessions from the last [hours] hours, newest first — for syncing out. */
    suspend fun workoutsSince(hours: Int): List<WorkoutSession> =
        dao.workoutsSince(System.currentTimeMillis() - hours * 60L * 60L * 1000L)

    suspend fun allWorkouts(): List<WorkoutSession> = dao.allWorkouts()

    suspend fun stats(): LocalStats {
        val since = startOfToday()
        val recent = dao.heartRateSince(since)
        val sessions = dao.workoutsSince(since)
        return LocalStats(
            readingCount = dao.countHeartRateBySource(HeartRateReading.SOURCE_RPPG) +
                dao.countHeartRateBySource(HeartRateReading.SOURCE_HEALTH_CONNECT),
            sessionCount = allWorkouts().size,
            averageBpmToday = if (recent.isEmpty()) 0f else recent.map { it.bpm }.average().toFloat(),
            repsToday = sessions.sumOf { it.reps },
            distanceTodayM = sessions.sumOf { it.distanceM }
        )
    }

    suspend fun clearAll() {
        dao.clearHeartRate()
        dao.clearWorkouts()
        dao.clearVitalsSamples()
    }

    // ── Vitals samples (analytics rows) ───────────────────────────────────

    suspend fun addVitalsSample(
        bpm: Float,
        sdnnMs: Float = 0f,
        rmssdMs: Float = 0f,
        stressIndex: Float = 0f,
        spo2: Int = 0,
        respiratoryRate: Int = 0,
        systolic: Int = 0,
        diastolic: Int = 0,
        quality: String = "",
        source: String = HeartRateReading.SOURCE_RPPG,
        timestamp: Long = System.currentTimeMillis()
    ) = dao.insertVitalsSample(
        VitalsSample(
            bpm = bpm,
            sdnnMs = sdnnMs,
            rmssdMs = rmssdMs,
            stressIndex = stressIndex,
            spo2 = spo2,
            respiratoryRate = respiratoryRate,
            systolic = systolic,
            diastolic = diastolic,
            quality = quality,
            source = source,
            timestamp = timestamp
        )
    )

    fun vitalsSamples(limit: Int = 500): Flow<List<VitalsSample>> =
        dao.observeVitalsSamples(limit)

    fun vitalsSampleCount(): Flow<Int> = dao.observeVitalsSampleCount()

    suspend fun vitalsSamplesSince(hours: Int): List<VitalsSample> =
        dao.vitalsSamplesSince(System.currentTimeMillis() - hours * 3_600_000L)

    private fun startOfToday(): Long = Calendar.getInstance().run {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AI feature store
    // ══════════════════════════════════════════════════════════════════════

    // ── Profile ───────────────────────────────────────────────────────────

    fun profile(): Flow<UserProfile?> = dao.observeProfile()

    suspend fun profileOnce(): UserProfile = dao.profile() ?: UserProfile()

    suspend fun saveProfile(profile: UserProfile) =
        dao.upsertProfile(profile.copy(id = 1, updatedAt = System.currentTimeMillis()))

    // ── Chat ──────────────────────────────────────────────────────────────

    fun chat(sessionId: String): Flow<List<AiChatMessage>> = dao.observeChat(sessionId)

    suspend fun addChatMessage(role: String, content: String, sessionId: String) =
        dao.insertChatMessage(
            AiChatMessage(
                role = role,
                content = content,
                sessionId = sessionId,
                timestamp = System.currentTimeMillis()
            )
        )

    suspend fun recentChat(limit: Int = 20): List<AiChatMessage> = dao.recentChat(limit)

    suspend fun clearChat(sessionId: String) = dao.clearChat(sessionId)

    // ── Insights (cached AI output) ────────────────────────────────────────

    suspend fun saveInsight(kind: String, title: String, body: String) =
        dao.insertInsight(
            AiInsight(
                kind = kind,
                title = title,
                body = body,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun latestInsight(kind: String): AiInsight? = dao.latestInsight(kind)

    fun observeInsight(kind: String): Flow<AiInsight?> = dao.observeLatestInsight(kind)

    fun recentInsights(limit: Int = 20): Flow<List<AiInsight>> =
        dao.observeRecentInsights(limit)

    // ── Symptoms ──────────────────────────────────────────────────────────

    fun symptoms(limit: Int = 100): Flow<List<SymptomLog>> = dao.observeSymptoms(limit)

    suspend fun addSymptom(text: String, severity: Int = 0) =
        dao.insertSymptom(
            SymptomLog(
                text = text,
                severity = severity,
                timestamp = System.currentTimeMillis()
            )
        )

    suspend fun deleteSymptom(id: Long) = dao.deleteSymptom(id)

    // ── Goals ─────────────────────────────────────────────────────────────

    fun goals(): Flow<List<WellnessGoal>> = dao.observeGoals()

    suspend fun addGoal(
        kind: String,
        title: String,
        targetValue: Float = 0f,
        unit: String = "",
        deadline: Long = 0L,
        notes: String = ""
    ) = dao.insertGoal(
        WellnessGoal(
            kind = kind,
            title = title,
            targetValue = targetValue,
            unit = unit,
            deadline = deadline,
            notes = notes,
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun setGoalActive(id: Long, active: Boolean) = dao.setGoalActive(id, active)

    suspend fun deleteGoal(id: Long) = dao.deleteGoal(id)

    // ── Meals ─────────────────────────────────────────────────────────────

    fun meals(limit: Int = 200): Flow<List<MealLog>> = dao.observeMeals(limit)

    suspend fun addMeal(
        dish: String,
        calories: Int = 0,
        proteinG: Int = 0,
        carbsG: Int = 0,
        fatG: Int = 0,
        slot: String = "",
        edited: Boolean = false,
        source: String = "manual",
        photoPath: String = ""
    ) = dao.insertMeal(
        MealLog(
            dish = dish,
            calories = calories,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            slot = slot,
            edited = edited,
            source = source,
            photoPath = photoPath,
            timestamp = System.currentTimeMillis()
        )
    )

    suspend fun deleteMeal(id: Long) = dao.deleteMeal(id)

    // ── Labs ──────────────────────────────────────────────────────────────

    fun labs(): Flow<List<LabValue>> = dao.observeLabs()

    suspend fun addLab(
        name: String,
        value: Float,
        unit: String = "",
        refLow: Float = 0f,
        refHigh: Float = 0f,
        refMissing: Boolean = refLow <= 0f && refHigh <= 0f,
        note: String = ""
    ) = dao.insertLab(
        LabValue(
            name = name,
            value = value,
            unit = unit,
            refLow = refLow,
            refHigh = refHigh,
            refMissing = refMissing,
            note = note,
            timestamp = System.currentTimeMillis()
        )
    )

    suspend fun deleteLab(id: Long) = dao.deleteLab(id)

    // ── Sleep / hydration ─────────────────────────────────────────────────

    fun sleep(limit: Int = 60): Flow<List<SleepLog>> = dao.observeSleep(limit)

    suspend fun addSleep(hours: Float, quality: Int = 0, note: String = "") =
        dao.insertSleep(
            SleepLog(
                hours = hours,
                quality = quality,
                note = note,
                timestamp = System.currentTimeMillis()
            )
        )

    suspend fun deleteSleep(id: Long) = dao.deleteSleep(id)

    suspend fun hydrationToday(): HydrationLog? = dao.hydration(dayKey())

    suspend fun saveHydration(log: HydrationLog) = dao.upsertHydration(log)

    suspend fun addGlass(delta: Int = 1): HydrationLog {
        val key = dayKey()
        val current = dao.hydration(key)
        val updated = HydrationLog(
            dayKey = key,
            glasses = ((current?.glasses ?: 0) + delta).coerceAtLeast(0),
            goalGlasses = current?.goalGlasses ?: 8,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertHydration(updated)
        return updated
    }

    // ── Medications ───────────────────────────────────────────────────────

    fun medications(): Flow<List<MedicationReminder>> = dao.observeMedications()

    suspend fun addMedication(name: String, dose: String, schedule: String, note: String) =
        dao.insertMedication(
            MedicationReminder(
                name = name,
                dose = dose,
                schedule = schedule,
                note = note,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun setMedicationEnabled(id: Long, enabled: Boolean) =
        dao.setMedicationEnabled(id, enabled)

    suspend fun deleteMedication(id: Long) = dao.deleteMedication(id)

    // ── Photos ────────────────────────────────────────────────────────────

    fun photos(kind: String, limit: Int = 50): Flow<List<PhotoLog>> =
        dao.observePhotos(kind, limit)

    suspend fun addPhoto(path: String, kind: String, note: String = "") =
        dao.insertPhoto(
            PhotoLog(
                path = path,
                kind = kind,
                note = note,
                timestamp = System.currentTimeMillis()
            )
        )

    suspend fun deletePhoto(id: Long) = dao.deletePhoto(id)

    // ── Streaks ───────────────────────────────────────────────────────────

    fun streaks(): Flow<List<Streak>> = dao.observeStreaks()

    suspend fun streaksOnce(): List<Streak> = dao.streaks()

    /**
     * Registers activity for today in [kind]. Idempotent per calendar day, so
     * calling it on every measurement does not inflate the count.
     */
    suspend fun touchStreak(kind: String): Streak {
        val today = epochDay()
        val existing = dao.streaks().firstOrNull { it.kind == kind }
        val current = when {
            existing == null -> 1
            existing.lastDay == today -> existing.current
            existing.lastDay == today - 1 -> existing.current + 1
            else -> 1
        }
        val updated = Streak(
            kind = kind,
            current = current,
            best = maxOf(existing?.best ?: 0, current),
            lastDay = today,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertStreak(updated)
        return updated
    }

    // ── Environment ───────────────────────────────────────────────────────

    fun environment(): Flow<EnvironmentSnapshot?> = dao.observeEnvironment()

    /** One-shot read for snapshot building (suspend, not a Flow). */
    suspend fun environmentOnce(): EnvironmentSnapshot? = dao.environment()

    suspend fun saveEnvironment(snapshot: EnvironmentSnapshot) =
        dao.upsertEnvironment(snapshot.copy(id = 1))

    // ── Aggregate reads used to ground AI prompts ─────────────────────────

    suspend fun mealsSince(hours: Int): List<MealLog> =
        dao.mealsSince(System.currentTimeMillis() - hours * 3_600_000L)

    suspend fun sleepSince(days: Int): List<SleepLog> =
        dao.sleepSince(System.currentTimeMillis() - days * 86_400_000L)

    suspend fun symptomsSince(days: Int): List<SymptomLog> =
        dao.symptomsSince(System.currentTimeMillis() - days * 86_400_000L)

    suspend fun labsAll(): List<LabValue> = dao.allLabs()

    suspend fun activeGoals(): List<WellnessGoal> = dao.activeGoals()

    suspend fun streak(kind: String): Streak? = dao.streaks().firstOrNull { it.kind == kind }

    suspend fun hydrationRecent(days: Int): List<HydrationLog> =
        dao.hydrationSince(System.currentTimeMillis() - days * 86_400_000L)

    /** Deletes everything the AI features added, leaving vitals untouched. */
    suspend fun clearAiData() {
        dao.clearInsights()
        dao.clearSymptoms()
        dao.clearMeals()
        dao.clearLabs()
        dao.clearSleep()
        dao.clearGoals()
        dao.clearMedications()
        dao.clearPhotos()
        dao.clearEnvironment()
        dao.clearAllChat()
    }

    private fun dayKey(): String {
        val now = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L
}
