package com.nadi.health.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nadi.health.viewmodel.WorkoutViewModel
import com.nadi.health.ui.charts.FlowStep
import com.nadi.health.ui.charts.MetroBullet
import com.nadi.health.ui.charts.MetroChartCard
import com.nadi.health.ui.charts.MetroGauge
import com.nadi.health.ui.charts.MetroPhaseStrip
import com.nadi.health.ui.charts.MetroStepFlow
import com.nadi.health.workout.Exercise

/**
 * Running screen - duration, distance, route and pace. Running is deliberately
 * NOT a repetition counter and never touches the camera.
 *
 * "AI running" adds cadence-aware behaviour: the accelerometer watches the
 * runner's stride and starts/pauses the run automatically. Location permission
 * is requested only when the runner actually starts a run.
 */
@Composable
fun RunSection(vm: WorkoutViewModel, exercise: Exercise) {
    val context = LocalContext.current
    val distanceM by vm.runDistanceM.collectAsState()
    val paceSecPerKm by vm.runPaceSecPerKm.collectAsState()
    val cadence by vm.runCadence.collectAsState()
    val route by vm.runRoute.collectAsState()
    val runActive by vm.runActive.collectAsState()
    val elapsedSec by vm.elapsedSec.collectAsState()
    val started by vm.runStarted.collectAsState()
    val mode by vm.runMode.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // startRun() re-checks the grant and reports a clear error when the
        // permission is still missing, so both outcomes land in the same place.
        vm.startRun()
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (mode == WorkoutViewModel.RunMode.AI) "AI · CADENCE" else "MANUAL",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                exercise.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (!started) {
                MetroChartCard(
                    title = "Run flow",
                    subtitle = "permission → GPS → run → save"
                ) {
                    MetroStepFlow(
                        steps = listOf(
                            FlowStep("Location access", "Requested only when you start"),
                            FlowStep("GPS lock", "Route appears after a few fixes"),
                            FlowStep("Run", "Manual start, or cadence in AI mode"),
                            FlowStep("Finish & save", "Distance, pace and route stored")
                        ),
                        current = 0
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val fine = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) {
                            vm.startRun()
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start run")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.exitRun() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RunStat("Duration", formatDuration(elapsedSec), Modifier.weight(1f))
                    RunStat("Distance", formatDistance(distanceM), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RunStat("Pace", formatPace(paceSecPerKm), Modifier.weight(1f))
                    RunStat("Cadence", if (cadence > 0) "$cadence spm" else "--",
                        Modifier.weight(1f))
                }

                Spacer(Modifier.height(10.dp))
                MetroPhaseStrip(
                    phases = listOf("Permission", "GPS lock", "Running", "Saved"),
                    current = if (mode == WorkoutViewModel.RunMode.AI && !runActive) 1 else 2
                )

                if (started && cadence > 0) {
                    Spacer(Modifier.height(10.dp))
                    MetroChartCard(
                        title = "Stride cadence",
                        subtitle = "auto start ≥ 150 spm · auto pause ≤ 55 spm"
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            MetroGauge(
                                fraction = cadence / 180f,
                                valueText = "$cadence",
                                label = "spm",
                                modifier = Modifier.size(84.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                MetroBullet(
                                    valueNorm = cadence / 200f,
                                    bands = listOf(0.275f, 0.75f),
                                    targetNorm = null,
                                    caption = if (runActive) {
                                        "Stride detected — the run is recording"
                                    } else {
                                        "Waiting for your stride to cross the start gate…"
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                RouteMap(route)

                if (mode == WorkoutViewModel.RunMode.AI) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (runActive) "Running — stride detected"
                        else "Waiting for your stride to start…",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (runActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { vm.toggleRunActive() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (runActive) "Pause" else "Start now")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.finishRun() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Finish run")
                }
            }
        }
    }
}

/** Draws the accepted GPS fixes as a polyline, aspect-fitted to the card. */
@Composable
private fun RouteMap(route: List<Pair<Double, Double>>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        if (route.size < 2) {
            Text(
                "Route appears after a few GPS fixes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Box
        }

        val minLat = route.minOf { it.first }
        val maxLat = route.maxOf { it.first }
        val minLon = route.minOf { it.second }
        val maxLon = route.maxOf { it.second }
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-5)
        val lonSpan = (maxLon - minLon).coerceAtLeast(1e-5)

        val lineColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize()) {
            val paddingPx = 8.dp.toPx()
            val usableW = (size.width - paddingPx * 2).coerceAtLeast(1f)
            val usableH = (size.height - paddingPx * 2).coerceAtLeast(1f)

            val path = Path()
            route.forEachIndexed { index, point ->
                val x = paddingPx + usableW * ((point.second - minLon) / lonSpan).toFloat()
                // Latitude grows north, the canvas grows down: invert it.
                val y = paddingPx + usableH * (1f - ((point.first - minLat) / latSpan).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))

            val lastX = paddingPx + usableW * ((route.last().second - minLon) / lonSpan).toFloat()
            val lastY = paddingPx + usableH * (1f - ((route.last().first - minLat) / latSpan).toFloat())
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
}

@Composable
private fun RunStat(label: String, value: String, modifier: Modifier = Modifier) {
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
