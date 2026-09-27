package com.nadi.health.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The on-device Nadi database.
 *
 * Everything the app measures — and everything the on-device AI reasons over —
 * is written here, so history survives process death and app restarts.
 *
 * v2 adds the AI feature store (profile, chat, insights, symptoms, goals,
 * meals, labs, sleep, hydration, medications, photos, streaks, environment).
 * The migration is additive only: no existing vitals row is touched.
 */
@Database(
    entities = [
        // v1 — vitals
        HeartRateReading::class,
        WorkoutSession::class,
        VitalsSample::class,
        // v2 — AI feature store
        UserProfile::class,
        AiChatMessage::class,
        AiInsight::class,
        SymptomLog::class,
        WellnessGoal::class,
        MealLog::class,
        LabValue::class,
        SleepLog::class,
        HydrationLog::class,
        MedicationReminder::class,
        PhotoLog::class,
        Streak::class,
        EnvironmentSnapshot::class
    ],
    version = 2,
    exportSchema = true
)
abstract class NadiDatabase : RoomDatabase() {

    abstract fun dao(): NadiDao

    companion object {
        private const val NAME = "nadi.db"

        /** v1 → v2: create the AI feature tables. Additive, no data loss. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vitals_samples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bpm` REAL NOT NULL,
                        `sdnnMs` REAL NOT NULL,
                        `rmssdMs` REAL NOT NULL,
                        `stressIndex` REAL NOT NULL,
                        `spo2` INTEGER NOT NULL,
                        `respiratoryRate` INTEGER NOT NULL,
                        `systolic` INTEGER NOT NULL,
                        `diastolic` INTEGER NOT NULL,
                        `quality` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vitals_samples_timestamp` " +
                        "ON `vitals_samples` (`timestamp`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_profile` (
                        `id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `ageYears` INTEGER NOT NULL,
                        `sex` TEXT NOT NULL,
                        `heightCm` INTEGER NOT NULL,
                        `weightKg` REAL NOT NULL,
                        `bloodGroup` TEXT NOT NULL,
                        `conditions` TEXT NOT NULL,
                        `medications` TEXT NOT NULL,
                        `allergies` TEXT NOT NULL,
                        `emergencyContact` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `foodPreference` TEXT NOT NULL,
                        `region` TEXT NOT NULL,
                        `budgetTier` TEXT NOT NULL,
                        `experience` TEXT NOT NULL,
                        `daysPerWeek` INTEGER NOT NULL,
                        `proactiveTipsEnabled` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_chat_messages_sessionId` " +
                        "ON `ai_chat_messages` (`sessionId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_insights` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_insights_kind_createdAt` " +
                        "ON `ai_insights` (`kind`, `createdAt`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `symptom_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `text` TEXT NOT NULL,
                        `severity` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_symptom_logs_timestamp` " +
                        "ON `symptom_logs` (`timestamp`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wellness_goals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `targetValue` REAL NOT NULL,
                        `unit` TEXT NOT NULL,
                        `deadline` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `active` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wellness_goals_active` " +
                        "ON `wellness_goals` (`active`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `meal_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dish` TEXT NOT NULL,
                        `calories` INTEGER NOT NULL,
                        `proteinG` INTEGER NOT NULL,
                        `carbsG` INTEGER NOT NULL,
                        `fatG` INTEGER NOT NULL,
                        `slot` TEXT NOT NULL,
                        `edited` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `photoPath` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_meal_logs_timestamp` " +
                        "ON `meal_logs` (`timestamp`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lab_values` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `value` REAL NOT NULL,
                        `unit` TEXT NOT NULL,
                        `refLow` REAL NOT NULL,
                        `refHigh` REAL NOT NULL,
                        `refMissing` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_lab_values_name_timestamp` " +
                        "ON `lab_values` (`name`, `timestamp`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sleep_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `hours` REAL NOT NULL,
                        `quality` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sleep_logs_timestamp` " +
                        "ON `sleep_logs` (`timestamp`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hydration_logs` (
                        `dayKey` TEXT NOT NULL,
                        `glasses` INTEGER NOT NULL,
                        `goalGlasses` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`dayKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medication_reminders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `dose` TEXT NOT NULL,
                        `schedule` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `path` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_photo_logs_timestamp` " +
                        "ON `photo_logs` (`timestamp`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `streaks` (
                        `kind` TEXT NOT NULL,
                        `current` INTEGER NOT NULL,
                        `best` INTEGER NOT NULL,
                        `lastDay` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`kind`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `environment_snapshots` (
                        `id` INTEGER NOT NULL,
                        `aqi` INTEGER NOT NULL,
                        `pm25` REAL NOT NULL,
                        `tempC` REAL NOT NULL,
                        `humidityPct` INTEGER NOT NULL,
                        `condition` TEXT NOT NULL,
                        `place` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: NadiDatabase? = null

        fun get(context: Context): NadiDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): NadiDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NadiDatabase::class.java,
                NAME
            )
                // Room's default journal mode is fine for this write volume
                // (a reading every few seconds at most), but enabling WAL keeps
                // reads from blocking the writer while a set is in progress.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Indices are declared on the entities; nothing extra to do.
                        super.onOpen(db)
                    }
                })
                .build()
    }
}
