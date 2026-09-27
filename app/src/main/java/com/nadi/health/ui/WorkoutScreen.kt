package com.nadi.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadi.health.viewmodel.WorkoutViewModel
import com.nadi.health.workout.Exercise
import com.nadi.health.workout.WorkoutKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Workout dashboard: phone-sensor exercise tracking, no camera involved.
 *
 * Rep-based exercises (push-ups, sit-ups, squats, jumping jacks, deadlift,
 * overhead press, bench press) are counted from the accelerometer - the phone
 * is placed or carried the same way for every rep. Elliptical is a manual timed
 * session and running opens the separate run screen (see [RunSection]).
 */
@Composable
fun WorkoutScreen(vm: WorkoutViewModel = viewModel()) {
    val phase by vm.phase.collectAsState()
    val selected by vm.selected.collectAsState()
    val reps by vm.reps.collectAsState()
    val elapsedSec by vm.elapsedSec.collectAsState()
    val calibrating by vm.calibrating.collectAsState()
    val signalLevel by vm.signalLevel.collectAsState()
    val error by vm.error.collectAsState()
    val history by vm.history.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeaderCard()

        error?.let { message ->
            ErrorCard(message, onDismiss = { vm.clearError() })
        }

        when (phase) {
            WorkoutViewModel.Phase.SELECT -> ExercisePicker(
                exercises = vm.exercises,
                history = history,
                onPick = { exercise ->
                    if (exercise.kind == WorkoutKind.RUN) vm.prepareRun(exercise)
                    else vm.select(exercise)
                }
            )

            WorkoutViewModel.Phase.READY -> ReadySection(
                exercise = selected,
                onStart = { vm.begin() },
                onBack = { vm.clearSelection() }
            )

            WorkoutViewModel.Phase.ACTIVE -> ActiveSection(
                exercise = selected,
                vm = vm,
                reps = reps,
                elapsedSec = elapsedSec,
                calibrating = calibrating,
                signalLevel = signalLevel
            )

            WorkoutViewModel.Phase.DONE -> SummarySection(
                record = history.firstOrNull(),
                onNewWorkout = { vm.discard() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header / shared cards
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeaderCard() {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Workout",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "sensor-only · no camera",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Reps are detected from phone acceleration on the x, y or z axis. " +
                    "Place or carry the phone the same way for every rep or the count drifts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Exercise picker
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExercisePicker(
    exercises: List<Exercise>,
    history: List<WorkoutViewModel.WorkoutRecord>,
    onPick: (Exercise) -> Unit
) {
    val sensor = exercises.filter { it.kind == WorkoutKind.REP_SENSOR }
    val barbell = exercises.filter { it.kind == WorkoutKind.BARBELL }
    val manual = exercises.filter { it.kind == WorkoutKind.TIMED || it.kind == WorkoutKind.RUN }

    SectionHeader("Automatic rep counting", "accelerometer")
    ExerciseGrid(sensor, onPick)

    SectionHeader("Barbell lifts", "accelerometer · gravity tracking")
    ExerciseGrid(barbell, onPick)

    SectionHeader("Manual & running", "timed · location")
    ExerciseGrid(manual, onPick)

    if (history.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        SectionHeader("History", "this session")
        history.forEach { record -> HistoryRow(record) }
    }
}

@Composable
private fun ExerciseGrid(items: List<Exercise>, onPick: (Exercise) -> Unit) {
    items.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            row.forEach { exercise ->
                ExerciseTile(
                    exercise = exercise,
                    caption = exerciseCaption(exercise),
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(exercise) }
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExerciseTile(
    exercise: Exercise,
    caption: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(exercise.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                exercise.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun exerciseCaption(exercise: Exercise): String = when (exercise.kind) {
    WorkoutKind.REP_SENSOR ->
        "${exercise.axis.name.lowercase(Locale.US)}-axis · auto count"
    WorkoutKind.BARBELL ->
        "${exercise.axis.name.lowercase(Locale.US)}-axis · lift detection"
    WorkoutKind.TIMED -> "manual timed mode"
    WorkoutKind.RUN -> if (exercise.aiMode) "cadence auto start/pause" else "duration · distance · pace"
}

@Composable
private fun HistoryRow(record: WorkoutViewModel.WorkoutRecord) {
    val stamp = remember(record.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(record.emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (record.isCardio) {
                        "${formatDistance(record.distanceM)} · ${formatDuration(record.seconds)}"
                    } else {
                        "${record.reps} reps · ${formatDuration(record.seconds)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ready
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadySection(exercise: Exercise?, onStart: () -> Unit, onBack: () -> Unit) {
    if (exercise == null) return

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(exercise.emoji, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                exercise.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                exercise.hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (exercise.kind == WorkoutKind.TIMED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Manual timed mode: start when you begin and stop when you finish. " +
                        "Elapsed exercise time is tracked, repetitions are not counted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hold still for about half a second after starting - the detector " +
                        "calibrates a baseline before it begins counting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Text(if (exercise.kind == WorkoutKind.TIMED) "Start timer" else "Start set")
                }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Active session
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActiveSection(
    exercise: Exercise?,
    vm: WorkoutViewModel,
    reps: Int,
    elapsedSec: Int,
    calibrating: Boolean,
    signalLevel: Float
) {
    if (exercise == null) return

    when (exercise.kind) {
        WorkoutKind.RUN -> RunSection(vm, exercise)
        WorkoutKind.TIMED -> TimedSection(exercise, elapsedSec, vm)
        WorkoutKind.REP_SENSOR, WorkoutKind.BARBELL ->
            RepSection(exercise, reps, elapsedSec, calibrating, signalLevel, vm)
    }
}

@Composable
private fun RepSection(
    exercise: Exercise,
    reps: Int,
    elapsedSec: Int,
    calibrating: Boolean,
    signalLevel: Float,
    vm: WorkoutViewModel
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                exercise.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$reps",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "reps",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            if (calibrating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    "Calibrating — hold the phone still",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Movement signal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = signalLevel.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatBlock("Elapsed", formatDuration(elapsedSec), Modifier.weight(1f))
                StatBlock("Axis", exercise.axis.name.lowercase(Locale.US), Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            Text(
                exercise.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.finish() }, modifier = Modifier.weight(1f)) {
                    Text("Finish set")
                }
                OutlinedButton(onClick = { vm.discard() }) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun TimedSection(
    exercise: Exercise,
    elapsedSec: Int,
    vm: WorkoutViewModel
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                exercise.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Manual timed mode",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                formatDuration(elapsedSec),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.finish() }, modifier = Modifier.weight(1f)) {
                    Text("Stop & save")
                }
                OutlinedButton(onClick = { vm.discard() }) { Text("Discard") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Summary
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SummarySection(
    record: WorkoutViewModel.WorkoutRecord?,
    onNewWorkout: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Session saved", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            if (record == null) {
                Text("Nothing was recorded.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(record.emoji, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                Text(record.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatBlock("Duration", formatDuration(record.seconds), Modifier.weight(1f))
                    if (record.isCardio) {
                        StatBlock("Distance", formatDistance(record.distanceM), Modifier.weight(1f))
                    } else {
                        StatBlock("Reps", "${record.reps}", Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onNewWorkout) { Text("New workout") }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatting
// ─────────────────────────────────────────────────────────────────────────────

internal fun formatDuration(totalSeconds: Int): String {
    val safe = if (totalSeconds < 0) 0 else totalSeconds
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) String.format(Locale.US, "%.2f km", meters / 1000.0)
    else String.format(Locale.US, "%.0f m", meters)

internal fun formatPace(secPerKm: Double): String {
    if (secPerKm <= 0.0 || secPerKm > 3600.0) return "--"
    val minutes = (secPerKm / 60.0).toInt()
    val seconds = (secPerKm % 60.0).toInt()
    return String.format(Locale.US, "%d:%02d /km", minutes, seconds)
}
