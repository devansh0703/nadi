package com.nadi.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadi.health.health.DeviceHeartRate
import com.nadi.health.health.DeviceSession
import com.nadi.health.health.HealthConnectManager
import com.nadi.health.viewmodel.HealthViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Health tab: Health Connect plus the local store, in one place.
 *
 * Health Connect is Android's on-device health hub — a watch, band or chest
 * strap pairs with its own companion app, that app writes into Health Connect,
 * and Nadi reads the result. So "adding a device" happens in the device's app;
 * this screen gets Nadi permission to see the data and lets Nadi write its own
 * measurements back into the same pool.
 *
 * Everything is read on demand. Nothing here queries Health Connect at launch,
 * and every provider call is wrapped so a missing or outdated app degrades to a
 * clear status line instead of a crash.
 */
@Composable
fun HealthScreen(vm: HealthViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    // The contract is null when the provider is absent; the launcher is only
    // built in that case so `rememberLauncherForActivityResult` never sees null.
    val contract = vm.permissionContract
    val permissionLauncher = if (contract != null) {
        androidx.activity.compose.rememberLauncherForActivityResult(contract) { granted ->
            vm.onPermissionsResult(granted)
        }
    } else {
        null
    }

    var confirmClear by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeaderCard()

        state.message?.let { message ->
            InfoCard(message, onDismiss = { vm.dismissMessage() })
        }

        when (state.availability) {
            HealthConnectManager.Availability.AVAILABLE -> PermissionCard(
                hasAccess = state.hasAnyAccess,
                hasFullAccess = state.hasFullAccess,
                grantedCount = state.grantedCount,
                total = state.totalPermissions,
                onConnect = { permissionLauncher?.launch(vm.requestedPermissions) },
                onOpenSettings = { vm.settingsIntent()?.let { context.startActivity(it) } }
            )

            HealthConnectManager.Availability.UPDATE_REQUIRED -> ActionCard(
                title = "Health Connect needs an update",
                body = "The Health Connect app is installed but out of date, so it " +
                    "will not serve data yet. Update it, then come back.",
                action = "Update Health Connect",
                onClick = { vm.installIntent()?.let { context.startActivity(it) } }
            )

            HealthConnectManager.Availability.NOT_INSTALLED -> ActionCard(
                title = "Health Connect is not installed",
                body = "Health Connect ships with Android 14 and up, and is a free " +
                    "install on Android 13 and below. It is what lets a watch, band " +
                    "or another health app share data with Nadi.",
                action = "Install Health Connect",
                onClick = { vm.installIntent()?.let { context.startActivity(it) } }
            )
        }

        LocalCard(
            state = state,
            onSync = { vm.syncToHealthConnect() },
            onClear = { confirmClear = true }
        )

        DeviceCard(
            hr = state.deviceHeartRate,
            sessions = state.deviceSessions,
            stepsToday = state.stepsToday,
            distanceTodayM = state.distanceTodayM,
            hasAccess = state.hasAnyAccess
        )

        OutlinedButton(
            onClick = { vm.refresh() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading
        ) {
            Text(if (state.loading) "Refreshing…" else "Refresh")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear local history?") },
            text = {
                Text(
                    "This deletes every heart-rate reading and workout Nadi has " +
                        "stored on this phone. Data already in Health Connect is " +
                        "not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearLocalData()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cards
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
                    "Health",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Health Connect · on-device store",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Your readings and workouts are stored on this phone first. Health " +
                    "Connect is how watches, bands and other health apps share data " +
                    "with Nadi — and how Nadi shares back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionCard(
    hasAccess: Boolean,
    hasFullAccess: Boolean,
    grantedCount: Int,
    total: Int,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    SectionCard("Health Connect") {
        Text(
            when {
                hasFullAccess -> "Connected — Nadi can read your device data and write " +
                    "its own measurements."
                hasAccess -> "Partially connected — $grantedCount of $total permissions " +
                    "granted, so some data is still hidden."
                else -> "Not connected yet. Connect to pull in what your watch recorded " +
                    "and to push Nadi's measurements out."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect) {
                Text(if (hasAccess) "Review access" else "Connect")
            }
            OutlinedButton(onClick = onOpenSettings) {
                Text("Manage apps")
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit
) {
    SectionCard(title) {
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun LocalCard(
    state: HealthViewModel.UiState,
    onSync: () -> Unit,
    onClear: () -> Unit
) {
    SectionCard("On this phone") {
        StatRow("Heart-rate readings", state.local.readingCount.toString())
        StatRow("Workouts saved", state.local.sessionCount.toString())
        StatRow(
            "Average BPM today",
            if (state.local.averageBpmToday > 0f) {
                "${state.local.averageBpmToday.toInt()} bpm"
            } else {
                "—"
            }
        )
        StatRow("Reps today", state.local.repsToday.toString())
        StatRow("Distance today", formatMeters(state.local.distanceTodayM))

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSync, enabled = !state.loading) {
                Text("Sync to Health Connect")
            }
            OutlinedButton(onClick = onClear, enabled = !state.loading) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun DeviceCard(
    hr: List<DeviceHeartRate>,
    sessions: List<DeviceSession>,
    stepsToday: Long?,
    distanceTodayM: Double?,
    hasAccess: Boolean
) {
    SectionCard("From your devices") {
        if (!hasAccess) {
            Text(
                "Connect Health Connect above to see data from your watch, band or " +
                    "other health apps here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        StatRow("Steps today", stepsToday?.toString() ?: "—")
        StatRow("Distance today", distanceTodayM?.let(::formatMeters) ?: "—")

        Spacer(Modifier.height(10.dp))
        Text(
            "Latest heart rate",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (hr.isEmpty()) {
            Text(
                "Nothing from other sources in the last day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            hr.take(5).forEach { sample ->
                StatRow(
                    "${formatTime(sample.timestamp)}  ·  ${shortOrigin(sample.origin)}",
                    "${sample.bpm.toInt()} bpm"
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Recent sessions",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (sessions.isEmpty()) {
            Text(
                "No exercise sessions logged by other apps in the last week.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            sessions.take(5).forEach { session ->
                StatRow(
                    "${session.title}  ·  ${formatDay(session.startMs)}",
                    formatDurationShort(session.durationSec)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Small building blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InfoCard(message: String, onDismiss: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatting
// ─────────────────────────────────────────────────────────────────────────────

private fun formatMeters(meters: Double): String = when {
    meters <= 0.0 -> "—"
    meters < 1000.0 -> "${meters.toInt()} m"
    else -> String.format(Locale.US, "%.2f km", meters / 1000.0)
}

private fun formatDurationShort(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins <= 0) "${secs}s" else "${mins}m ${secs}s"
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatDay(timestamp: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))

/** Trims a package id down to something legible in a row. */
private fun shortOrigin(origin: String): String {
    val last = origin.substringAfterLast('.').ifBlank { origin }
    return last.replaceFirstChar { it.uppercase() }
}
