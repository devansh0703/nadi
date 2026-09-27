package com.nadi.health.workout

/**
 * Workout catalogue for the sensor-only workout dashboard.
 *
 * The rep-based entries are direct ports of the referenced open-source apps:
 *
 *  - [WorkoutKind.REP_SENSOR] exercises (push-ups, sit-ups, squats, jumping
 *    jacks) use the FitTrack detector and keep its per-exercise `axis`,
 *    `threshold` and `cooldown` values
 *    (LuckyTheCookie/FitTrack, src/components/rep-counter/constants.ts).
 *  - [WorkoutKind.BARBELL] exercises (deadlift, overhead press, bench press)
 *    use the RepCapture low-pass/high-pass detector
 *    (jvangore31/RepCapture, BenchDataEntry.java).
 *
 * [WorkoutKind.TIMED] (elliptical) and [WorkoutKind.RUN] (normal running and
 * AI running) are not repetition counters: elliptical tracks elapsed exercise
 * time in manual mode, running tracks duration, distance, route and pace.
 */
enum class Axis(val index: Int) {
    X(0), Y(1), Z(2)
}

enum class WorkoutKind {
    /** Accelerometer rep counting, FitTrack hysteresis detector. */
    REP_SENSOR,

    /** Accelerometer rep counting, RepCapture high-pass detector. */
    BARBELL,

    /** Manual timed mode - start/stop, elapsed time only, no reps. */
    TIMED,

    /** Navigational: duration, distance, route, pace. */
    RUN
}

data class Exercise(
    val id: String,
    val name: String,
    val emoji: String,
    val kind: WorkoutKind,
    val axis: Axis = Axis.Z,
    val threshold: Float = 0.65f,
    val cooldownMs: Long = 700L,
    /** Placement/carry hint shown while the session runs. */
    val hint: String = "",
    /** Adds a live cadence/auto-start mode (AI running). */
    val aiMode: Boolean = false
)

object ExerciseCatalog {

    val exercises: List<Exercise> = listOf(
        // ── Automatic rep counting, accelerometer only (FitTrack) ──────────
        Exercise(
            id = "pushups",
            name = "Push-ups",
            emoji = "\uD83D\uDCAA",
            kind = WorkoutKind.REP_SENSOR,
            axis = Axis.Z,
            threshold = 0.65f,
            cooldownMs = 600L,
            hint = "Place the phone on the floor under your chest, screen up."
        ),
        Exercise(
            id = "situps",
            name = "Sit-ups / crunches",
            emoji = "\uD83D\uDD25",
            kind = WorkoutKind.REP_SENSOR,
            axis = Axis.Z,
            threshold = 0.8f,
            cooldownMs = 800L,
            hint = "Rest the phone flat on your stomach and curl through the rep."
        ),
        Exercise(
            id = "squats",
            name = "Squats",
            emoji = "\uD83E\uDDB5",
            kind = WorkoutKind.REP_SENSOR,
            axis = Axis.Y,
            threshold = 0.55f,
            cooldownMs = 700L,
            hint = "Phone in a front pocket, held vertically against your body."
        ),
        Exercise(
            id = "jumping_jacks",
            name = "Jumping jacks",
            emoji = "\u2B50",
            kind = WorkoutKind.REP_SENSOR,
            axis = Axis.Y,
            threshold = 0.9f,
            cooldownMs = 500L,
            hint = "Phone in a pocket or in your hand - keep the carry consistent."
        ),

        // ── Barbell lifts (RepCapture) ─────────────────────────────────────
        Exercise(
            id = "deadlift",
            name = "Deadlift",
            emoji = "\uD83C\uDFCB",
            kind = WorkoutKind.BARBELL,
            axis = Axis.Y,
            threshold = 0.8f,
            cooldownMs = 900L,
            hint = "Phone in a pocket on the leg that drives the lift."
        ),
        Exercise(
            id = "overhead_press",
            name = "Overhead press",
            emoji = "\uD83D\uDE4C",
            kind = WorkoutKind.BARBELL,
            axis = Axis.Y,
            threshold = 0.65f,
            cooldownMs = 800L,
            hint = "Phone in a chest pocket, screen facing you."
        ),
        Exercise(
            id = "bench_press",
            name = "Bench press",
            emoji = "\uD83D\uDECC",
            kind = WorkoutKind.BARBELL,
            axis = Axis.Z,
            threshold = 0.65f,
            cooldownMs = 900L,
            hint = "Phone resting on the chest or in a pocket, screen up."
        ),

        // ── Manual timed mode ──────────────────────────────────────────────
        Exercise(
            id = "elliptical",
            name = "Elliptical",
            emoji = "\uD83D\uDEB4",
            kind = WorkoutKind.TIMED,
            hint = "Manual timed mode - you start and stop the activity yourself."
        ),

        // ── Running ────────────────────────────────────────────────────────
        Exercise(
            id = "running",
            name = "Running",
            emoji = "\uD83C\uDFC3",
            kind = WorkoutKind.RUN,
            hint = "Duration, distance, route and pace. No rep counting."
        ),
        Exercise(
            id = "running_ai",
            name = "AI running",
            emoji = "\uD83E\uDD16",
            kind = WorkoutKind.RUN,
            aiMode = true,
            hint = "Cadence-aware: starts and pauses the run from your stride."
        )
    )

    val repExercises: List<Exercise> = exercises.filter {
        it.kind == WorkoutKind.REP_SENSOR || it.kind == WorkoutKind.BARBELL
    }

    val timedExercises: List<Exercise> = exercises.filter { it.kind == WorkoutKind.TIMED }

    val runExercises: List<Exercise> = exercises.filter { it.kind == WorkoutKind.RUN }
}
