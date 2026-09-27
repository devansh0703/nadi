package com.nadi.health.ai

import com.nadi.health.ml.qwen.Sampling
import com.nadi.health.workout.ExerciseCatalog

/**
 * One text field the UI must collect before a feature can run.
 *
 * Kept deliberately dumb: a key, a label, and whether it wants a multi-line
 * box. Parsing to numbers happens in the feature's own builder so a typo can
 * fall back to a sane default instead of crashing.
 */
data class AiInputField(
    val key: String,
    val label: String,
    val hint: String = "",
    val multiline: Boolean = false,
    val default: String = ""
)

/** How the result should be presented. */
enum class AiResultKind {
    /** Free prose the user reads. */
    TEXT,
    /** A JSON object the UI pulls fields out of (score ring, plan table…). */
    JSON
}

/** The menu groups, in the order `futureAI.md` presents them. */
enum class AiGroup(val title: String, val blurb: String) {
    REPORTS("Reports & insights", "Wellness summaries written from your own measurements"),
    ASSISTANT("Assistant", "Ask about your data, explain terms, plan a goal"),
    WORKOUT("Workout coach", "Plans, progression and recovery-aware training"),
    DIET("Diet & nutrition", "India-first plans, protein gap and dish estimates"),
    VISION("Vision (needs a VL model)", "Image features — switched off with the text-only bundle"),
    CONTEXT("Context & environment", "Air quality, season and sleep coaching"),
    ENGAGE("Motivation", "Streaks, challenges and progress stories"),
    ELDER("Elder & family", "Simple summaries and a share-with-family message")
}

/**
 * A single runnable AI feature.
 *
 * [build] returns null when this feature cannot run on [snapshot] at all (for
 * example a vision feature with no vision model, or a feature whose input was
 * left blank). The UI shows the returned [AiRequest]'s label as the busy text.
 */
data class AiFeature(
    val id: String,
    val group: AiGroup,
    val title: String,
    val blurb: String,
    val resultKind: AiResultKind = AiResultKind.TEXT,
    /** True for features that need an image — always gated on a VL bundle. */
    val requiresVision: Boolean = false,
    val inputs: List<AiInputField> = emptyList(),
    /** When true the outcome is stored in the reports list after it is produced. */
    val cacheAsInsight: Boolean = true,
    val build: (HealthSnapshot, Map<String, String>) -> AiRequest?
)

/**
 * The full catalogue.
 *
 * One entry per row of the `futureAI.md` menu that has a prompt builder. A
 * handful of menu rows are *not* features here on purpose:
 *  - §1.3 personal baseline — the snapshot already compares the user to their
 *    own 30-day baseline; there is nothing to generate.
 *  - §2.3 voice assistant — needs speech in/out and an audio pipeline the app
 *    does not have; the text assistant covers the same questions.
 *  - §2.5 symptom journal and §7.4 family profiles — data entry + local
 *    profiles, handled by the Log tab and the profile editor.
 */
object AiFeatures {

    val all: List<AiFeature> get() = catalogue

    fun byId(id: String): AiFeature? = catalogue.firstOrNull { it.id == id }

    fun grouped(): Map<AiGroup, List<AiFeature>> =
        catalogue.groupBy { it.group }.toSortedMap(compareBy { it.ordinal })

    private fun text(
        key: String,
        label: String,
        hint: String = "",
        multiline: Boolean = false,
        default: String = ""
    ) = AiInputField(key, label, hint, multiline, default)

    private fun intOf(inputs: Map<String, String>, key: String, default: Int): Int =
        inputs[key]?.trim()?.toIntOrNull()?.coerceIn(1, 3650) ?: default

    private fun doubleOf(inputs: Map<String, String>, key: String, default: Double): Double =
        inputs[key]?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: default

    private fun str(inputs: Map<String, String>, key: String, fallback: String = ""): String =
        inputs[key]?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback

    // ── §1 Reports & insights ───────────────────────────────────────────────

    private val reportFeatures = listOf(
        AiFeature(
            id = "report.session",
            group = AiGroup.REPORTS,
            title = "Wellness report",
            blurb = "A shareable summary of your measurements and what changed.",
        ) { snap, _ -> AiPrompts.sessionReport(snap) },

        AiFeature(
            id = "score.daily",
            group = AiGroup.REPORTS,
            title = "Today's wellness score",
            blurb = "One 0–100 number blended from HR, HRV, stress, activity and sleep.",
            resultKind = AiResultKind.JSON
        ) { snap, _ -> AiPrompts.dailyScore(snap) },

        AiFeature(
            id = "digest.weekly",
            group = AiGroup.REPORTS,
            title = "Weekly digest",
            blurb = "What moved this week against your own 30-day baseline.",
        ) { snap, _ -> AiPrompts.weeklyDigest(snap) },

        AiFeature(
            id = "insight.readiness",
            group = AiGroup.REPORTS,
            title = "Recovery / readiness",
            blurb = "Are you recovered enough to train hard today?",
        ) { snap, _ -> AiPrompts.recoveryReadiness(snap) },

        AiFeature(
            id = "insight.correlations",
            group = AiGroup.REPORTS,
            title = "Lifestyle correlations",
            blurb = "Patterns between your sleep, meals, workouts and vitals.",
        ) { snap, _ -> AiPrompts.correlations(snap) },

        AiFeature(
            id = "card.doctor",
            group = AiGroup.REPORTS,
            title = "Doctor-visit card",
            blurb = "A one-page snapshot a clinician can read in 30 seconds.",
        ) { snap, _ -> AiPrompts.doctorCard(snap) }
    )

    // ── §2 Assistant (chat itself lives in the Ask tab) ─────────────────────

    private val assistantFeatures = listOf(
        AiFeature(
            id = "glossary",
            group = AiGroup.ASSISTANT,
            title = "Explain a term",
            blurb = "Plain-language explanation of HRV, SpO2, AQI… tied to your data.",
            inputs = listOf(text("topic", "Term", "e.g. HRV, resting heart rate", false))
        ) { snap, in_ -> AiPrompts.glossary(snap, str(in_, "topic", "HRV")) },

        AiFeature(
            id = "education",
            group = AiGroup.ASSISTANT,
            title = "Health education",
            blurb = "Short, India-first explainers on BP, diabetes, anaemia, Vit-D.",
            inputs = listOf(
                text("topic", "Topic", "e.g. blood pressure, anaemia, vitamin D", false)
            )
        ) { snap, in_ -> AiPrompts.education(snap, str(in_, "topic", "general wellbeing")) },

        AiFeature(
            id = "symptom.summary",
            group = AiGroup.ASSISTANT,
            title = "Symptom journal summary",
            blurb = "Patterns from your logged symptoms, with an honest escalation.",
        ) { snap, _ -> AiPrompts.symptomSummary(snap) },

        AiFeature(
            id = "card.emergency",
            group = AiGroup.ASSISTANT,
            title = "Emergency card",
            blurb = "Name, blood group, conditions, medications, allergies, contact.",
        ) { snap, _ -> AiPrompts.emergencyCard(snap) },

        AiFeature(
            id = "medication.qa",
            group = AiGroup.ASSISTANT,
            title = "Medication Q&A",
            blurb = "What a medicine is generally for and food timing — never a dose.",
            inputs = listOf(
                text("medication", "Medicine", "e.g. Metformin"),
                text("question", "Your question", "e.g. should I take it with food?", true)
            )
        ) { snap, in_ ->
            val med = str(in_, "medication")
            val q = str(in_, "question", "What is it generally used for?")
            if (med.isEmpty()) null else AiPrompts.medicationQa(snap, med, q)
        },

        AiFeature(
            id = "goal.dialogue",
            group = AiGroup.ASSISTANT,
            title = "Set a goal",
            blurb = "Answer a question or two; Nadi turns it into one measurable goal.",
            resultKind = AiResultKind.JSON,
            inputs = listOf(
                text(
                    "answers",
                    "Your answers so far",
                    "Q: What do you want to change?\nA: …",
                    true
                )
            )
        ) { snap, in_ ->
            // Each non-empty line is one answer; the model already asked the
            // question in the previous turn, so an empty question is honest.
            val answers = str(in_, "answers").lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { "" to it }
            AiPrompts.goalDialogue(snap, answers)
        }
    )

    // ── §3 Workout & fitness coach ──────────────────────────────────────────

    private val workoutFeatures = listOf(
        AiFeature(
            id = "plan.workout",
            group = AiGroup.WORKOUT,
            title = "Weekly workout plan",
            blurb = "A plan built only from the exercises this app can count.",
            resultKind = AiResultKind.JSON,
            inputs = listOf(
                text("goal", "Goal", "e.g. get stronger, lose fat", false, "general fitness"),
                text("equipment", "Equipment", "e.g. none, dumbbells, gym", false, "none"),
                text("days", "Days per week", "1–7", false, "3"),
                text("minutes", "Minutes per session", "e.g. 30", false, "30")
            )
        ) { snap, in_ ->
            AiPrompts.workoutPlan(
                snapshot = snap,
                goal = str(in_, "goal", "general fitness"),
                equipment = str(in_, "equipment", "none"),
                daysPerWeek = intOf(in_, "days", 3).coerceIn(1, 7),
                minutesPerSession = intOf(in_, "minutes", 30).coerceIn(5, 180),
                allowedExercises = ExerciseCatalog.exercises.map { it.name }
            )
        },

        AiFeature(
            id = "coach.progression",
            group = AiGroup.WORKOUT,
            title = "Progression advice",
            blurb = "Progress, hold or deload — with a plain-language why.",
            inputs = listOf(
                text("exercise", "Exercise", "e.g. Push-ups"),
                text("reps", "Recent reps", "e.g. 12, 14, 15", false)
            )
        ) { snap, in_ ->
            val ex = str(in_, "exercise")
            if (ex.isEmpty()) null
            else AiPrompts.progression(
                snap,
                ex,
                str(in_, "reps").split(',', ' ', ';')
                    .mapNotNull { it.trim().toIntOrNull() }
            )
        },

        AiFeature(
            id = "coach.recovery_adapt",
            group = AiGroup.WORKOUT,
            title = "Adjust a plan to recovery",
            blurb = "Softens or hardens today's session from your recovery signal.",
            inputs = listOf(text("plan", "Current plan", "paste a plan to adjust", true))
        ) { snap, in_ ->
            val plan = str(in_, "plan")
            if (plan.isEmpty()) null else AiPrompts.adaptPlanToRecovery(snap, plan)
        },

        AiFeature(
            id = "coach.run_pacing",
            group = AiGroup.WORKOUT,
            title = "Run pacing coach",
            blurb = "Opening, mid-run and finish pace strategy from your last run.",
            inputs = listOf(
                text("distance", "Distance (km)", "e.g. 5.0", false, "5.0"),
                text("duration", "Duration (min)", "e.g. 31", false, "30"),
                text("pace", "Avg pace (min/km)", "e.g. 6.2", false, "6.0"),
                text("hr", "Avg HR (bpm)", "e.g. 152", false, "150")
            )
        ) { snap, in_ ->
            AiPrompts.runPacing(
                snap,
                distanceKm = doubleOf(in_, "distance", 5.0),
                durationMin = doubleOf(in_, "duration", 30.0),
                avgPaceMinPerKm = doubleOf(in_, "pace", 6.0),
                avgHr = doubleOf(in_, "hr", 150.0)
            )
        },

        AiFeature(
            id = "coach.yoga",
            group = AiGroup.WORKOUT,
            title = "Yoga / mobility session",
            blurb = "A guided session whose timed holds the app can time.",
            inputs = listOf(
                text("minutes", "Minutes", "e.g. 20", false, "20"),
                text("focus", "Focus", "e.g. hips, stress, back", false, "stress relief")
            )
        ) { snap, in_ ->
            AiPrompts.yogaSession(
                snap,
                minutes = intOf(in_, "minutes", 20).coerceIn(5, 120),
                focus = str(in_, "focus", "stress relief")
            )
        },

        AiFeature(
            id = "coach.breathing",
            group = AiGroup.WORKOUT,
            title = "Breathing session",
            blurb = "A paced breathwork protocol tracked by the chest IMU signal.",
            inputs = listOf(
                text("minutes", "Minutes", "e.g. 8", false, "8"),
                text("bpm", "Target breaths / min", "e.g. 6", false, "6")
            )
        ) { snap, in_ ->
            AiPrompts.breathingSession(
                snap,
                minutes = intOf(in_, "minutes", 8).coerceIn(2, 60),
                targetBreathsPerMin = intOf(in_, "bpm", 6).coerceIn(2, 30)
            )
        },

        AiFeature(
            id = "insight.fitness_age",
            group = AiGroup.WORKOUT,
            title = "Fitness age",
            blurb = "A motivational cardio estimate from resting HR and running.",
        ) { snap, _ -> AiPrompts.fitnessAge(snap) }
    )

    // ── §4 Diet & nutrition (India-first) ───────────────────────────────────

    private val dietFeatures = listOf(
        AiFeature(
            id = "diet.meal_plan",
            group = AiGroup.DIET,
            title = "Indian meal plan",
            blurb = "Region- and budget-aware plan on everyday Indian foods.",
            resultKind = AiResultKind.JSON,
            inputs = listOf(
                text("days", "Days", "1–14", false, "3"),
                text("goal", "Goal", "e.g. protein, weight loss", false, "balanced"),
                text("fasting", "Fasting preset (optional)", "e.g. Ekadashi, Navratri")
            )
        ) { snap, in_ ->
            AiPrompts.mealPlan(
                snap,
                days = intOf(in_, "days", 3).coerceIn(1, 14),
                goal = str(in_, "goal", "balanced"),
                fastingPreset = str(in_, "fasting")
            )
        },

        AiFeature(
            id = "diet.protein_gap",
            group = AiGroup.DIET,
            title = "Protein gap",
            blurb = "Your target vs intake, and Indian foods that close it.",
        ) { snap, _ -> AiPrompts.proteinGap(snap) },

        AiFeature(
            id = "diet.condition",
            group = AiGroup.DIET,
            title = "Condition-aware diet",
            blurb = "Painless eating guidance for BP, diabetes or anaemia.",
            inputs = listOf(
                text("condition", "Condition", "e.g. blood pressure, diabetes", false, "diabetes")
            )
        ) { snap, in_ ->
            AiPrompts.conditionDiet(snap, str(in_, "condition", "diabetes"))
        },

        AiFeature(
            id = "diet.dish_estimate",
            group = AiGroup.DIET,
            title = "Estimate a dish",
            blurb = "Describe a plate; get an editable macro range. Text fallback for photos.",
            resultKind = AiResultKind.JSON,
            inputs = listOf(
                text("dish", "Dish", "e.g. 2 roti, dal, sabzi", false),
                text("portion", "Portion", "e.g. 1 plate, 2 pieces", false, "1 serving")
            )
        ) { snap, in_ ->
            val dish = str(in_, "dish")
            if (dish.isEmpty()) null
            else AiPrompts.dishEstimate(snap, dish, str(in_, "portion", "1 serving"))
        },

        AiFeature(
            id = "lab.extract",
            group = AiGroup.DIET,
            title = "Import lab report",
            blurb = "Paste a blood report; values are extracted verbatim (text fallback for OCR).",
            resultKind = AiResultKind.JSON,
            inputs = listOf(
                text("report", "Report text", "paste the report body here", true)
            )
        ) { snap, in_ ->
            val body = str(in_, "report")
            if (body.isEmpty()) null else AiPrompts.labExtraction(snap, body)
        },

        AiFeature(
            id = "diet.hydration_nudge",
            group = AiGroup.DIET,
            title = "Hydration check",
            blurb = "A reminder only when heat, humidity or your vitals actually warrant it.",
        ) { snap, _ -> AiPrompts.hydrationNudge(snap) }
    )

    // ── §5 Vision (gated) ───────────────────────────────────────────────────

    private val visionFeatures = listOf(
        AiFeature(
            id = "vision.frame_coaching",
            group = AiGroup.VISION,
            title = "Measurement lighting help",
            blurb = "Judges lighting and framing from the camera frame.",
            requiresVision = true,
        ) { snap, _ -> AiPrompts.frameCoaching(snap, "sitting, front camera") },

        AiFeature(
            id = "vision.food_photo",
            group = AiGroup.VISION,
            title = "Food photo → nutrition",
            blurb = "Identify an Indian dish from a photo and estimate macros.",
            resultKind = AiResultKind.JSON,
            requiresVision = true,
        ) { snap, _ -> AiPrompts.foodPhoto(snap) }
    )

    // ── §6 Context & environment ────────────────────────────────────────────

    private val contextFeatures = listOf(
        AiFeature(
            id = "context.aqi",
            group = AiGroup.CONTEXT,
            title = "Train outside or not?",
            blurb = "AQI- and weather-aware verdict on outdoor running and breathwork.",
        ) { snap, _ -> AiPrompts.airQualityAdvice(snap) },

        AiFeature(
            id = "context.seasonal",
            group = AiGroup.CONTEXT,
            title = "Seasonal tips",
            blurb = "Three season- and region-specific things to watch.",
        ) { snap, _ -> AiPrompts.seasonalTips(snap) },

        AiFeature(
            id = "context.sleep",
            group = AiGroup.CONTEXT,
            title = "Sleep hygiene",
            blurb = "Three ranked sleep changes tied to your self-reported data.",
        ) { snap, _ -> AiPrompts.sleepHygiene(snap) }
    )

    // ── §7 Engagement ───────────────────────────────────────────────────────

    private val engageFeatures = listOf(
        AiFeature(
            id = "engage.streak",
            group = AiGroup.ENGAGE,
            title = "Streak encouragement",
            blurb = "One grounded line about your streaks. No hype.",
        ) { snap, _ -> AiPrompts.streakEncouragement(snap) },

        AiFeature(
            id = "engage.challenge",
            group = AiGroup.ENGAGE,
            title = "Weekly challenge",
            blurb = "One measurable 7-day challenge aimed at a real gap.",
        ) { snap, _ -> AiPrompts.weeklyChallenge(snap) },

        AiFeature(
            id = "engage.progress",
            group = AiGroup.ENGAGE,
            title = "Progress story",
            blurb = "First measurement versus now, in under 120 words.",
        ) { snap, _ -> AiPrompts.progressStory(snap) }
    )

    // ── §8 Elder & family ───────────────────────────────────────────────────

    private val elderFeatures = listOf(
        AiFeature(
            id = "elder.summary",
            group = AiGroup.ELDER,
            title = "Simple summary",
            blurb = "Large-type, one-idea-per-line summary for an older reader.",
        ) { snap, _ -> AiPrompts.elderCareSummary(snap) },

        AiFeature(
            id = "family.share",
            group = AiGroup.ELDER,
            title = "Message to family",
            blurb = "A warm 3–4 sentence WhatsApp-ready health update.",
        ) { snap, _ -> AiPrompts.familyShareMessage(snap) }
    )

    private val catalogue: List<AiFeature> =
        reportFeatures + assistantFeatures + workoutFeatures + dietFeatures +
            visionFeatures + contextFeatures + engageFeatures + elderFeatures
}

/** Sampler override used only by the chat tab. */
internal val CHAT_SAMPLER: Sampling = Sampling.varied()
