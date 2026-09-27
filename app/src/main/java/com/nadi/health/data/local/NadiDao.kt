package com.nadi.health.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the on-device store.
 *
 * Reads that feed the UI return [Flow] so screens update automatically when new
 * readings or sessions land. Everything is `suspend` so it can be driven from
 * [kotlinx.coroutines.Dispatchers.IO] — never the main thread.
 */
@Dao
interface NadiDao {

    // ── Heart rate ────────────────────────────────────────────────────────

    @Insert
    suspend fun insertHeartRate(reading: HeartRateReading): Long

    @Query("SELECT * FROM heart_rate_readings ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentHeartRate(limit: Int): Flow<List<HeartRateReading>>

    @Query(
        "SELECT * FROM heart_rate_readings WHERE timestamp >= :since " +
            "ORDER BY timestamp ASC"
    )
    suspend fun heartRateSince(since: Long): List<HeartRateReading>

    @Query("SELECT COUNT(*) FROM heart_rate_readings")
    fun observeHeartRateCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM heart_rate_readings WHERE source = :source")
    suspend fun countHeartRateBySource(source: String): Int

    @Query("DELETE FROM heart_rate_readings")
    suspend fun clearHeartRate()

    // ── Workouts ──────────────────────────────────────────────────────────

    @Insert
    suspend fun insertWorkout(session: WorkoutSession): Long

    @Query("SELECT * FROM workout_sessions ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentWorkouts(limit: Int): Flow<List<WorkoutSession>>

    @Query("SELECT * FROM workout_sessions ORDER BY timestamp DESC")
    suspend fun allWorkouts(): List<WorkoutSession>

    @Query("SELECT COUNT(*) FROM workout_sessions")
    fun observeWorkoutCount(): Flow<Int>

    @Query("SELECT * FROM workout_sessions WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun workoutsSince(since: Long): List<WorkoutSession>

    @Query("DELETE FROM workout_sessions")
    suspend fun clearWorkouts()

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    // ══ Vitals samples (analytics rows for the AI features) ════════════════

    @Insert
    suspend fun insertVitalsSample(sample: VitalsSample): Long

    @Query("SELECT * FROM vitals_samples ORDER BY timestamp DESC LIMIT :limit")
    fun observeVitalsSamples(limit: Int): Flow<List<VitalsSample>>

    @Query("SELECT * FROM vitals_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun vitalsSamplesSince(since: Long): List<VitalsSample>

    @Query("SELECT COUNT(*) FROM vitals_samples")
    fun observeVitalsSampleCount(): Flow<Int>

    @Query("DELETE FROM vitals_samples")
    suspend fun clearVitalsSamples()

    // ══ Profile ═══════════════════════════════════════════════════════════

    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observeProfile(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun profile(): UserProfile?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfile)

    // ══ Chat ══════════════════════════════════════════════════════════════

    @Insert
    suspend fun insertChatMessage(message: AiChatMessage): Long

    @Query("SELECT * FROM ai_chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeChat(sessionId: String): Flow<List<AiChatMessage>>

    @Query("SELECT * FROM ai_chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentChat(limit: Int): List<AiChatMessage>

    @Query("DELETE FROM ai_chat_messages WHERE sessionId = :sessionId")
    suspend fun clearChat(sessionId: String)

    @Query("DELETE FROM ai_chat_messages")
    suspend fun clearAllChat()

    // ══ AI insights (reports, digests, plans) ═════════════════════════════

    @Insert
    suspend fun insertInsight(insight: AiInsight): Long

    @Query("SELECT * FROM ai_insights WHERE kind = :kind ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestInsight(kind: String): AiInsight?

    @Query("SELECT * FROM ai_insights WHERE kind = :kind ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestInsight(kind: String): Flow<AiInsight?>

    @Query("SELECT * FROM ai_insights ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentInsights(limit: Int): Flow<List<AiInsight>>

    @Query("DELETE FROM ai_insights WHERE kind = :kind")
    suspend fun deleteInsightsOfKind(kind: String)

    // ══ Symptoms ══════════════════════════════════════════════════════════

    @Insert
    suspend fun insertSymptom(log: SymptomLog): Long

    @Query("SELECT * FROM symptom_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeSymptoms(limit: Int): Flow<List<SymptomLog>>

    @Query("SELECT * FROM symptom_logs WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun symptomsSince(since: Long): List<SymptomLog>

    @Query("DELETE FROM symptom_logs WHERE id = :id")
    suspend fun deleteSymptom(id: Long)

    // ══ Goals ═════════════════════════════════════════════════════════════

    @Insert
    suspend fun insertGoal(goal: WellnessGoal): Long

    @Query("SELECT * FROM wellness_goals ORDER BY createdAt DESC")
    fun observeGoals(): Flow<List<WellnessGoal>>

    @Query("SELECT * FROM wellness_goals WHERE active = 1 ORDER BY createdAt DESC")
    suspend fun activeGoals(): List<WellnessGoal>

    @Query("UPDATE wellness_goals SET active = :active WHERE id = :id")
    suspend fun setGoalActive(id: Long, active: Boolean)

    @Query("DELETE FROM wellness_goals WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    // ══ Meals ═════════════════════════════════════════════════════════════

    @Insert
    suspend fun insertMeal(meal: MealLog): Long

    @Query("SELECT * FROM meal_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeMeals(limit: Int): Flow<List<MealLog>>

    @Query("SELECT * FROM meal_logs WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun mealsSince(since: Long): List<MealLog>

    @Query("DELETE FROM meal_logs WHERE id = :id")
    suspend fun deleteMeal(id: Long)

    // ══ Labs ══════════════════════════════════════════════════════════════

    @Insert
    suspend fun insertLab(value: LabValue): Long

    @Query("SELECT * FROM lab_values ORDER BY timestamp DESC")
    fun observeLabs(): Flow<List<LabValue>>

    @Query("SELECT * FROM lab_values ORDER BY timestamp ASC")
    suspend fun allLabs(): List<LabValue>

    @Query("DELETE FROM lab_values WHERE id = :id")
    suspend fun deleteLab(id: Long)

    // ══ Sleep / hydration ═════════════════════════════════════════════════

    @Insert
    suspend fun insertSleep(log: SleepLog): Long

    @Query("SELECT * FROM sleep_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeSleep(limit: Int): Flow<List<SleepLog>>

    @Query("SELECT * FROM sleep_logs WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun sleepSince(since: Long): List<SleepLog>

    @Query("DELETE FROM sleep_logs WHERE id = :id")
    suspend fun deleteSleep(id: Long)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertHydration(log: HydrationLog)

    @Query("SELECT * FROM hydration_logs WHERE dayKey = :dayKey")
    suspend fun hydration(dayKey: String): HydrationLog?

    @Query("SELECT * FROM hydration_logs WHERE updatedAt >= :since ORDER BY updatedAt DESC")
    suspend fun hydrationSince(since: Long): List<HydrationLog>

    // ══ Medications ═══════════════════════════════════════════════════════

    @Insert
    suspend fun insertMedication(reminder: MedicationReminder): Long

    @Query("SELECT * FROM medication_reminders ORDER BY createdAt DESC")
    fun observeMedications(): Flow<List<MedicationReminder>>

    @Query("UPDATE medication_reminders SET enabled = :enabled WHERE id = :id")
    suspend fun setMedicationEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM medication_reminders WHERE id = :id")
    suspend fun deleteMedication(id: Long)

    // ══ Photos ════════════════════════════════════════════════════════════

    @Insert
    suspend fun insertPhoto(photo: PhotoLog): Long

    @Query("SELECT * FROM photo_logs WHERE kind = :kind ORDER BY timestamp DESC LIMIT :limit")
    fun observePhotos(kind: String, limit: Int): Flow<List<PhotoLog>>

    @Query("DELETE FROM photo_logs WHERE id = :id")
    suspend fun deletePhoto(id: Long)

    // ══ Streaks ═══════════════════════════════════════════════════════════

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertStreak(streak: Streak)

    @Query("SELECT * FROM streaks")
    fun observeStreaks(): Flow<List<Streak>>

    @Query("SELECT * FROM streaks")
    suspend fun streaks(): List<Streak>

    // ══ Environment ═══════════════════════════════════════════════════════

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertEnvironment(snapshot: EnvironmentSnapshot)

    @Query("SELECT * FROM environment_snapshots WHERE id = 1")
    fun observeEnvironment(): Flow<EnvironmentSnapshot?>

    @Query("SELECT * FROM environment_snapshots WHERE id = 1")
    suspend fun environment(): EnvironmentSnapshot?

    // ══ Aggregate reads for grounded AI context ═══════════════════════════

    @Query("SELECT * FROM heart_rate_readings WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun heartRateSinceRows(since: Long): List<HeartRateReading>

    @Query("SELECT COUNT(*) FROM workout_sessions WHERE timestamp >= :since")
    suspend fun workoutCountSince(since: Long): Int

    @Query("DELETE FROM ai_insights")
    suspend fun clearInsights()

    @Query("DELETE FROM symptom_logs")
    suspend fun clearSymptoms()

    @Query("DELETE FROM meal_logs")
    suspend fun clearMeals()

    @Query("DELETE FROM lab_values")
    suspend fun clearLabs()

    @Query("DELETE FROM sleep_logs")
    suspend fun clearSleep()

    @Query("DELETE FROM wellness_goals")
    suspend fun clearGoals()

    @Query("DELETE FROM medication_reminders")
    suspend fun clearMedications()

    @Query("DELETE FROM photo_logs")
    suspend fun clearPhotos()

    @Query("DELETE FROM environment_snapshots")
    suspend fun clearEnvironment()
}
