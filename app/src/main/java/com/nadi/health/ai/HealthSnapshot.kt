package com.nadi.health.ai

import com.nadi.health.data.local.EnvironmentSnapshot
import com.nadi.health.data.local.LabValue
import com.nadi.health.data.local.MealLog
import com.nadi.health.data.local.NadiRepository
import com.nadi.health.data.local.SleepLog
import com.nadi.health.data.local.Streak
import com.nadi.health.data.local.SymptomLog
import com.nadi.health.data.local.UserProfile
import com.nadi.health.data.local.VitalsSample
import com.nadi.health.data.local.WellnessGoal
import com.nadi.health.data.local.WorkoutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One factual bundle of what the app actually knows about the user.
 *
 * **This is the only thing the model is allowed to reason over.** Every AI
 * feature builds a [HealthSnapshot], renders it into a compact text block, and
 * puts that block in the prompt. If a number is not in here, the model is
 * instructed to say it does not know — which is what keeps a 4B model from
 * inventing a heart rate (RULES.md: never fabricate).
 *
 * Capacity note: the model's context is 4096 tokens, so [render] is deliberately
 * terse — summary statistics, not raw rows.
 */
data class HealthSnapshot(
    val generatedAt: Long,
    val profile: UserProfile,

    /** All-time / 30-day / 7-day / 24-hour windows of camera vitals. */
    val hr30: Stat,
    val hr7: Stat,
    val hr24: Stat,
    val hrv7: Stat,
    val hrv30: Stat,
    val stress7: Stat,
    val spo27: Stat,
    val bp7: List<Pair<Int, Int>>,

    val restingHrToday: Float,
    val restingHrBaseline30: Float,
    val hrvBaseline30: Float,

    val workout: WorkoutSummary,
    val mealsToday: Macro,
    val protein7Avg: Float,
    val sleep7Avg: Float,
    val sleep7Min: Float,
    val hydrationToday: Int,
    val hydrationGoal: Int,

    val symptoms14: List<SymptomLog>,
    val labsLatest: List<LabValue>,
    val goals: List<WellnessGoal>,
    val streaks: List<Streak>,
    val environment: EnvironmentSnapshot?,

    /** Vitals sample count over 30 days — drives the "not enough data" gates. */
    val vitalsCount30: Int
) {
    /** True when there is anything at all worth asking the model about. */
    val hasVitals: Boolean get() = vitalsCount30 > 0
    val hasWorkouts: Boolean get() = workout.sessions30 > 0
    val hasMeals: Boolean get() = mealsToday.calories > 0 || protein7Avg > 0f
    val hasSleep: Boolean get() = sleep7Avg > 0f

    /** Age in years, or 0 when the profile has not been filled in. */
    val age: Int get() = profile.ageYears

    /**
     * A deliberately short, factual rendering for the prompt.
     *
     * Missing data is stated as "no data" rather than omitted, because an
     * omission invites the model to fill the gap.
     */
    fun render(): String = buildString {
        appendLine("DATE: ${java.text.SimpleDateFormat("yyyy-MM-dd EEE", java.util.Locale.US).format(java.util.Date(generatedAt))}")

        // ── Who the user is ─────────────────────────────────────────────
        val who = buildList {
            if (profile.ageYears > 0) add("age ${profile.ageYears}")
            if (profile.sex.isNotBlank()) add(profile.sex)
            if (profile.heightCm > 0) add("${profile.heightCm} cm")
            if (profile.weightKg > 0f) add("%.1f kg".format(profile.weightKg))
            if (profile.experience.isNotBlank()) add("training ${profile.experience}")
            if (profile.foodPreference.isNotBlank()) add("diet ${profile.foodPreference}")
            if (profile.region.isNotBlank()) add("region ${profile.region}")
            if (profile.budgetTier.isNotBlank()) add("budget ${profile.budgetTier}")
        }
        appendLine("PROFILE: ${if (who.isEmpty()) "not filled in" else who.joinToString(", ")}")
        if (profile.conditions.isNotBlank()) appendLine("SELF-REPORTED CONDITIONS: ${profile.conditions}")
        if (profile.medications.isNotBlank()) appendLine("SELF-REPORTED MEDICATIONS: ${profile.medications}")
        if (profile.allergies.isNotBlank()) appendLine("SELF-REPORTED ALLERGIES: ${profile.allergies}")
        appendLine("LANGUAGE: ${profile.language}")

        // ── Camera vitals ───────────────────────────────────────────────
        appendLine()
        if (vitalsCount30 == 0) {
            appendLine("MEASURED VITALS: no measurements recorded in the last 30 days.")
        } else {
            appendLine("MEASURED VITALS (camera rPPG, last 30 days, $vitalsCount30 windows):")
            appendLine("  heart rate 24h  : ${hr24.line()}")
            appendLine("  heart rate 7d   : ${hr7.line()}")
            appendLine("  heart rate 30d  : ${hr30.line()}")
            appendLine("  resting HR today: ${f(restingHrToday)} bpm | your 30d baseline ${f(restingHrBaseline30)} bpm")
            appendLine("  HRV (SDNN) 7d   : ${hrv7.line()} ms | your 30d baseline ${f(hrvBaseline30)} ms")
            appendLine("  HRV (SDNN) 30d  : ${hrv30.line()} ms")
            appendLine("  stress index 7d : ${stress7.line()} (0-100, higher = more stressed)")
            appendLine("  SpO2 7d         : ${spo27.line()} %")
            appendLine(
                "  BP estimate 7d  : " + if (bp7.isEmpty()) "no data"
                else bp7.takeLast(10).joinToString(", ") { "${it.first}/${it.second}" }
            )
        }

        // ── Workouts ────────────────────────────────────────────────────
        appendLine()
        if (workout.sessions30 == 0) {
            appendLine("WORKOUTS: none recorded in the last 30 days.")
        } else {
            appendLine("WORKOUTS (sensor-counted, camera-free):")
            appendLine("  last 7 days : ${workout.sessions7} sessions, ${workout.minutes7} min, " +
                "${workout.reps7} reps, ${"%.2f".format(workout.distanceKm7)} km run")
            appendLine("  last 30 days: ${workout.sessions30} sessions")
            if (workout.byExercise.isNotEmpty()) {
                appendLine("  breakdown 30d: " + workout.byExercise.entries
                    .sortedByDescending { it.value }.take(6)
                    .joinToString(", ") { "${it.key} x${it.value}" })
            }
            workout.lastSession?.let {
                appendLine("  most recent : ${it.name} - ${it.reps} reps / ${it.seconds / 60} min" +
                    if (it.distanceM > 0) " / ${"%.2f".format(it.distanceM / 1000)} km" else "")
            }
        }

        // ── Meals ───────────────────────────────────────────────────────
        appendLine()
        appendLine(
            "NUTRITION: today " + if (mealsToday.calories == 0) "nothing logged" else
                "${mealsToday.calories} kcal, ${mealsToday.protein} g protein, " +
                    "${mealsToday.carbs} g carbs, ${mealsToday.fat} g fat"
        )
        if (protein7Avg > 0f) {
            appendLine("  7-day average protein: ${f(protein7Avg)} g/day")
        } else {
            appendLine("  7-day average protein: no meal logs")
        }

        // ── Sleep / hydration ───────────────────────────────────────────
        appendLine()
        appendLine(
            if (sleep7Avg > 0f)
                "SLEEP (self-reported): ${f(sleep7Avg)} h/night average over 7 days " +
                    "(shortest ${f(sleep7Min)} h)"
            else "SLEEP (self-reported): nothing logged"
        )
        appendLine(
            "HYDRATION: $hydrationToday of $hydrationGoal glasses today" +
                if (hydrationToday == 0) " (not logged)" else ""
        )

        // ── Symptoms ────────────────────────────────────────────────────
        appendLine()
        if (symptoms14.isEmpty()) {
            appendLine("SYMPTOMS (last 14 days): none logged.")
        } else {
            appendLine("SYMPTOMS (last 14 days, self-reported):")
            symptoms14.takeLast(10).forEach {
                val whenText = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
                    .format(java.util.Date(it.timestamp))
                appendLine("  $whenText: ${it.text}${if (it.severity > 0) " (severity ${it.severity}/5)" else ""}")
            }
        }

        // ── Labs ────────────────────────────────────────────────────────
        appendLine()
        if (labsLatest.isEmpty()) {
            appendLine("LAB VALUES: none entered.")
        } else {
            appendLine("LAB VALUES (user-entered / scanned, verbatim - do not diagnose):")
            labsLatest.take(12).forEach {
                val ref = if (it.refMissing) "" else
                    " (their report's range ${f(it.refLow)}-${f(it.refHigh)})"
                appendLine("  ${it.name}: ${f(it.value)} ${it.unit}$ref")
            }
        }

        // ── Goals / streaks ─────────────────────────────────────────────
        appendLine()
        if (goals.isEmpty()) {
            appendLine("GOALS: none set.")
        } else {
            appendLine("ACTIVE GOALS: " + goals.joinToString("; ") {
                "${it.title}${if (it.targetValue > 0f) " target ${f(it.targetValue)} ${it.unit}" else ""}"
            })
        }
        if (streaks.isNotEmpty()) {
            appendLine("STREAKS: " + streaks.joinToString(", ") { "${it.kind} ${it.current}d (best ${it.best})" })
        }

        // ── Environment ─────────────────────────────────────────────────
        environment?.let { env ->
            if (env.aqi >= 0 || env.tempC > -100f) {
                appendLine()
                appendLine(
                    "ENVIRONMENT (${env.source.ifBlank { "unknown" }}${if (env.place.isNotBlank()) ", ${env.place}" else ""}): " +
                        listOfNotNull(
                            if (env.aqi >= 0) "AQI ${env.aqi}" else null,
                            if (env.pm25 >= 0f) "PM2.5 ${f(env.pm25)}" else null,
                            if (env.tempC > -100f) "${f(env.tempC)} C" else null,
                            if (env.humidityPct >= 0) "humidity ${env.humidityPct}%" else null,
                            env.condition.ifBlank { null }
                        ).joinToString(", ")
                )
            }
        }
    }.trim()

    private fun f(v: Float): String =
        if (v == 0f) "0" else "%.1f".format(v)

    /** Summary statistics for one metric over one window. */
    data class Stat(val n: Int, val avg: Float, val min: Float, val max: Float, val last: Float) {
        fun line(): String =
            if (n == 0) "no data"
            else "avg ${f(avg)}, min ${f(min)}, max ${f(max)}, latest ${f(last)} (n=$n)"

        private fun f(v: Float): String = "%.1f".format(v)
    }

    data class Macro(val calories: Int, val protein: Int, val carbs: Int, val fat: Int)

    data class WorkoutSummary(
        val sessions7: Int,
        val sessions30: Int,
        val minutes7: Int,
        val reps7: Int,
        val distanceKm7: Double,
        val byExercise: Map<String, Int>,
        val lastSession: WorkoutSession?
    )
}

/** Builds a [HealthSnapshot] from the local store. */
object HealthSnapshotBuilder {

    suspend fun build(repository: NadiRepository): HealthSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val profile = repository.profileOnce()

        val vitals30 = repository.vitalsSamplesSince(30 * 24)
        val vitals7 = vitals30.filter { it.timestamp >= now - 7 * 86_400_000L }
        val vitals24 = vitals30.filter { it.timestamp >= now - 86_400_000L }

        val workouts30 = repository.workoutsSince(30 * 24)
        val workouts7 = workouts30.filter { it.timestamp >= now - 7 * 86_400_000L }
        val meals7 = repository.mealsSince(7 * 24)
        val mealsToday = repository.mealsSince(24)
        val sleep7 = repository.sleepSince(7)
        val hydration = repository.hydrationToday()

        HealthSnapshot(
            generatedAt = now,
            profile = profile,
            hr30 = stat(vitals30) { it.bpm },
            hr7 = stat(vitals7) { it.bpm },
            hr24 = stat(vitals24) { it.bpm },
            hrv7 = stat(vitals7.filter { it.sdnnMs > 0f }) { it.sdnnMs },
            hrv30 = stat(vitals30.filter { it.sdnnMs > 0f }) { it.sdnnMs },
            stress7 = stat(vitals7.filter { it.stressIndex > 0f }) { it.stressIndex },
            spo27 = stat(vitals7.filter { it.spo2 > 0 }) { it.spo2.toFloat() },
            bp7 = vitals7.filter { it.systolic > 0 }.map { it.systolic to it.diastolic },
            restingHrToday = restingHr(vitals24),
            restingHrBaseline30 = restingHr(vitals30),
            hrvBaseline30 = if (vitals30.any { it.sdnnMs > 0f })
                vitals30.filter { it.sdnnMs > 0f }.map { it.sdnnMs }.average().toFloat() else 0f,
            workout = HealthSnapshot.WorkoutSummary(
                sessions7 = workouts7.size,
                sessions30 = workouts30.size,
                minutes7 = (workouts7.sumOf { it.seconds } / 60).toInt(),
                reps7 = workouts7.sumOf { it.reps },
                distanceKm7 = workouts7.sumOf { it.distanceM } / 1000.0,
                byExercise = workouts30.groupingBy { it.name }.eachCount(),
                lastSession = workouts30.maxByOrNull { it.timestamp }
            ),
            mealsToday = HealthSnapshot.Macro(
                calories = mealsToday.sumOf { it.calories },
                protein = mealsToday.sumOf { it.proteinG },
                carbs = mealsToday.sumOf { it.carbsG },
                fat = mealsToday.sumOf { it.fatG }
            ),
            protein7Avg = if (meals7.isEmpty()) 0f
            else meals7.groupBy { it.timestamp / 86_400_000L }
                .map { (_, rows) -> rows.sumOf { it.proteinG } }
                .average().toFloat(),
            sleep7Avg = if (sleep7.isEmpty()) 0f
            else sleep7.map { it.hours }.average().toFloat(),
            sleep7Min = sleep7.minOfOrNull { it.hours } ?: 0f,
            hydrationToday = hydration?.glasses ?: 0,
            hydrationGoal = hydration?.goalGlasses ?: 8,
            symptoms14 = repository.symptomsSince(14),
            labsLatest = repository.labsAll().groupBy { it.name }.mapNotNull { (_, rows) ->
                rows.maxByOrNull { it.timestamp }
            }.sortedBy { it.name },
            goals = repository.activeGoals(),
            streaks = repository.streaksOnce(),
            environment = repository.environmentOnce()?.let { env ->
                // Ignore a stale cache: advice built on yesterday's AQI is worse
                // than no advice (RULES.md: no lying proxies).
                if (env.fetchedAt >= now - 6 * 3_600_000L) env else null
            },
            vitalsCount30 = vitals30.size
        )
    }

    private fun stat(rows: List<VitalsSample>, pick: (VitalsSample) -> Float): HealthSnapshot.Stat {
        val values = rows.map(pick).filter { it > 0f }
        return if (values.isEmpty()) HealthSnapshot.Stat(0, 0f, 0f, 0f, 0f)
        else HealthSnapshot.Stat(
            n = values.size,
            avg = values.average().toFloat(),
            min = values.min(),
            max = values.max(),
            last = values.last()
        )
    }

    /** Interbeat-interval-free resting estimate: the calm end of the window. */
    private fun restingHr(rows: List<VitalsSample>): Float {
        val values = rows.map { it.bpm }.filter { it > 0f }.sorted()
        if (values.isEmpty()) return 0f
        val take = (values.size * 0.2f).toInt().coerceAtLeast(1)
        return values.take(take).average().toFloat()
    }
}
