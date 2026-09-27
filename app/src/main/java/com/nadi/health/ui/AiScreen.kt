package com.nadi.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadi.health.ai.AiFeature
import com.nadi.health.ai.AiFeatures
import com.nadi.health.ai.AiPrompts
import com.nadi.health.ai.AiResultKind
import com.nadi.health.ai.VisionSupport
import com.nadi.health.data.local.AiChatMessage
import com.nadi.health.data.local.HydrationLog
import com.nadi.health.data.local.UserProfile
import com.nadi.health.ml.qwen.QwenModelManager
import com.nadi.health.viewmodel.AiViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI tab.
 *
 * Four surfaces in one screen:
 *  - **Coach** — the feature catalogue, grouped exactly like `futureAI.md`.
 *  - **Ask** — grounded chat about the user's own readings.
 *  - **Reports** — everything the model has generated, cached on device.
 *  - **Log** — the data entry that makes the answers grounded rather than generic.
 *
 * The model is text-only (see [VisionSupport]); vision rows are shown disabled
 * rather than hidden, so the capability boundary is explicit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(vm: AiViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Coach", "Ask", "Reports", "Log")

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        ModelStatusCard(
            status = state.model,
            importing = state.importing,
            progress = state.importProgress,
            note = state.note,
            onImport = { vm.importModel() },
            onRefresh = { vm.refreshModel() }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { index, label ->
                FilterChip(
                    selected = tab == index,
                    onClick = { tab = index; vm.clearNote() },
                    label = { Text(label) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> CoachTab(vm)
                1 -> AskTab(vm)
                2 -> ReportsTab(vm)
                else -> LogTab(vm)
            }
        }
    }

    state.result?.let { result ->
        ResultDialog(
            vm = vm,
            result = result
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { vm.dismissError() },
            confirmButton = { TextButton(onClick = { vm.dismissError() }) { Text("OK") } },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Couldn't finish") },
            text = { Text(error) }
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  Model status / import
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun ModelStatusCard(
    status: QwenModelManager.Status?,
    importing: Boolean,
    progress: Float,
    note: String,
    onImport: () -> Unit,
    onRefresh: () -> Unit
) {
    val ready = status?.isReady == true
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ready) Icons.Default.Check else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = when {
                        status == null -> "Checking the NPU runtime…"
                        ready -> "Qwen3-4B ready on the Hexagon NPU"
                        else -> "AI model not ready"
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = status?.detail ?: "Reading the on-device model state.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status != null && status.bytesPresent > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Bundle on disk: ${status.bytesPresent / 1_000_000} MB",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (importing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(note, style = MaterialTheme.typography.labelSmall)
            }
            if (!ready && !importing) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Import from /sdcard/Nadi/models")
                    }
                    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  Coach — the feature catalogue
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun CoachTab(vm: AiViewModel) {
    val state by vm.state.collectAsState()
    var pending by remember { mutableStateOf<AiFeature?>(null) }
    var inputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        vm.groups.forEach { (group, features) ->
            item(key = "h-${group.name}") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        group.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(features, key = { it.id }) { feature ->
                FeatureRow(
                    feature = feature,
                    running = state.busyFeatureId == feature.id,
                    enabled = state.model?.isReady == true &&
                        (!feature.requiresVision || VisionSupport.hasVisionModel) &&
                        state.busyFeatureId == null,
                    onClick = {
                        if (feature.inputs.isEmpty()) {
                            vm.runFeature(feature, emptyMap())
                        } else {
                            inputs = feature.inputs.associate { it.key to it.default }
                            pending = feature
                        }
                    }
                )
            }
        }
    }

    if (state.busyFeatureId != null && state.streaming.isNotBlank()) {
        StreamingOverlay(text = state.streaming, onCancel = { vm.cancel() })
    }

    pending?.let { feature ->
        InputDialog(
            feature = feature,
            values = inputs,
            onValue = { key, value -> inputs = inputs + (key to value) },
            onDismiss = { pending = null },
            onRun = {
                vm.runFeature(feature, inputs)
                pending = null
            }
        )
    }
}

@Composable
private fun FeatureRow(
    feature: AiFeature,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val visionBlocked = feature.requiresVision && !VisionSupport.hasVisionModel
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(feature.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    feature.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (visionBlocked) {
                    Text(
                        "Needs a vision-language model",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = onClick, enabled = enabled) { Text("Run") }
            }
        }
    }
}

@Composable
private fun InputDialog(
    feature: AiFeature,
    values: Map<String, String>,
    onValue: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(feature.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(feature.blurb, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                feature.inputs.forEach { field ->
                    OutlinedTextField(
                        value = values[field.key] ?: "",
                        onValueChange = { onValue(field.key, it) },
                        label = { Text(field.label) },
                        placeholder = if (field.hint.isNotEmpty()) ({ Text(field.hint) }) else null,
                        singleLine = !field.multiline,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = { Button(onClick = onRun, enabled = true) { Text("Run") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StreamingOverlay(text: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Working on the NPU…") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Stop") } }
    )
}

// ══════════════════════════════════════════════════════════════════════════
//  Result
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun ResultDialog(vm: AiViewModel, result: AiViewModel.Result) {
    val canSave = result.json != null && result.featureId in SAVEABLE
    AlertDialog(
        onDismissRequest = { vm.dismissResult() },
        title = { Text(result.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(result.body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Generated on-device in ${result.elapsedMs / 1000}s" +
                        if (result.truncated) " (hit the output cap)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    AiPrompts.DISCLAIMER,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    AiPrompts.ESCALATION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canSave) {
                    Button(onClick = { vm.applyResultToStore() }) {
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Save")
                    }
                }
                TextButton(onClick = { vm.dismissResult() }) { Text("Close") }
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════════
//  Ask — grounded chat
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun AskTab(vm: AiViewModel) {
    val state by vm.state.collectAsState()
    val chat by vm.chat.collectAsState()
    var input by remember { mutableStateOf("") }
    val enabled = state.model?.isReady == true && !state.sending

    Column(modifier = Modifier.fillMaxSize()) {
        if (chat.isEmpty()) {
            Text(
                "Ask about your own readings — \"why is my heart rate higher today?\", " +
                    "\"what is HRV?\", \"am I recovered enough to train?\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chat, key = { it.id }) { message -> ChatBubble(message) }
            if (state.sending && state.reply.isNotBlank()) {
                item { ChatBubblePreview(state.reply) }
            } else if (state.sending) {
                item { Text("Thinking on the NPU…", style = MaterialTheme.typography.labelSmall) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about your data") },
                maxLines = 3
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = { vm.ask(input); input = "" },
                enabled = enabled && input.isNotBlank()
            ) {
                Icon(Icons.Default.Send, null, Modifier.size(18.dp))
            }
        }
        TextButton(onClick = { vm.clearChat() }) { Text("Clear conversation") }
    }
}

@Composable
private fun ChatBubble(message: AiChatMessage) {
    val isUser = message.role == AiChatMessage.ROLE_USER
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(message.content, style = MaterialTheme.typography.bodyMedium)
            Text(
                SimpleDateFormat("HH:mm", Locale.US).format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatBubblePreview(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  Reports — cached AI output
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun ReportsTab(vm: AiViewModel) {
    val insights by vm.insights.collectAsState()
    if (insights.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing saved yet. Run a feature from the Coach tab and it will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(insights, key = { it.id }) { insight ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(insight.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(insight.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(insight.body, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  Log — data entry that grounds the prompts
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun LogTab(vm: AiViewModel) {
    val profile by vm.profile.collectAsState()
    val meals by vm.meals.collectAsState()
    val sleep by vm.sleep.collectAsState()
    val symptoms by vm.symptoms.collectAsState()
    val labs by vm.labs.collectAsState()
    val goals by vm.goals.collectAsState()
    val medications by vm.medications.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProfileEditor(profile = profile, onSave = { vm.saveProfile(it) })

        Section("Log a meal") { MealLogger(onSave = { d, c, p, cb, f, s -> vm.logMeal(d, c, p, cb, f, s) }) }
        meals.take(5).forEach { m ->
            LogRow("${m.dish} — ${m.calories} kcal, ${m.proteinG} g protein") { vm.deleteMeal(m.id) }
        }

        Section("Log sleep") { SleepLogger(onSave = { h, q, n -> vm.logSleep(h, q, n) }) }
        sleep.take(5).forEach { s ->
            LogRow("${"%.1f".format(s.hours)} h (quality ${s.quality}/5)") { vm.deleteSleep(s.id) }
        }

        Section("Symptom journal") { SymptomLogger(onSave = { t, sev -> vm.logSymptom(t, sev) }) }
        symptoms.take(5).forEach { s ->
            LogRow("${s.text}${if (s.severity > 0) " (${s.severity}/5)" else ""}") { vm.deleteSymptom(s.id) }
        }

        Section("Lab value") { LabLogger(onSave = { n, v, u, lo, hi -> vm.logLab(n, v, u, lo, hi) }) }
        labs.take(6).forEach { l ->
            LogRow("${l.name}: ${"%.1f".format(l.value)} ${l.unit}") { vm.deleteLab(l.id) }
        }

        Section("Goal") { GoalLogger(onSave = { k, t, tv, u, w -> vm.logGoal(k, t, tv, u, w) }) }
        goals.take(5).forEach { g ->
            LogRow("${g.title}${if (g.targetValue > 0) " → ${"%.1f".format(g.targetValue)} ${g.unit}" else ""}") { vm.deleteGoal(g.id) }
        }

        Section("Medication reminder") { MedicationLogger(onSave = { n, d, s, no -> vm.logMedication(n, d, s, no) }) }
        medications.take(5).forEach { m ->
            LogRow("${m.name} ${m.dose} — ${m.schedule}") { vm.deleteMedication(m.id) }
        }

        Section("Hydration") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addGlass() }) { Text("+ 1 glass") }
                OutlinedButton(onClick = { vm.setHydrationGoal(8) }) { Text("Goal 8") }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun LogRow(text: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDelete) { Text("Remove") }
    }
}

@Composable
private fun ProfileEditor(profile: UserProfile, onSave: (UserProfile) -> Unit) {
    var p by remember(profile) { mutableStateOf(profile) }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Everything the model reasons over. Free-text fields are treated as self-reported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    p.ageYears.takeIf { it > 0 }?.toString() ?: "",
                    { p = p.copy(ageYears = it.toIntOrNull() ?: 0) },
                    label = { Text("Age") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    p.weightKg.takeIf { it > 0f }?.toString() ?: "",
                    { p = p.copy(weightKg = it.toFloatOrNull() ?: 0f) },
                    label = { Text("Weight kg") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            OutlinedTextField(p.conditions, { p = p.copy(conditions = it) }, label = { Text("Conditions (self-reported)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.medications, { p = p.copy(medications = it) }, label = { Text("Medications") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.allergies, { p = p.copy(allergies = it) }, label = { Text("Allergies") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.bloodGroup, { p = p.copy(bloodGroup = it) }, label = { Text("Blood group") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.emergencyContact, { p = p.copy(emergencyContact = it) }, label = { Text("Emergency contact") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.region, { p = p.copy(region = it) }, label = { Text("Region / state") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(p.foodPreference, { p = p.copy(foodPreference = it) }, label = { Text("Diet") }, modifier = Modifier.weight(1f))
                OutlinedTextField(p.experience, { p = p.copy(experience = it) }, label = { Text("Experience") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(p.language, { p = p.copy(language = it) }, label = { Text("Language (e.g. en-IN, hi-IN)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSave(p) }) { Text("Save profile") }
        }
    }
}

@Composable
private fun MealLogger(onSave: (String, Int, Int, Int, Int, String) -> Unit) {
    var dish by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf("lunch") }
    var cal by remember { mutableStateOf("") }
    var pro by remember { mutableStateOf("") }
    var carb by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(dish, { dish = it }, label = { Text("Dish") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(slot, { slot = it }, label = { Text("Slot") }, modifier = Modifier.weight(1f))
            OutlinedTextField(cal, { cal = it }, label = { Text("kcal") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(pro, { pro = it }, label = { Text("Protein g") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(carb, { carb = it }, label = { Text("Carbs g") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(fat, { fat = it }, label = { Text("Fat g") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(
            onClick = {
                onSave(dish, cal.toIntOrNull() ?: 0, pro.toIntOrNull() ?: 0, carb.toIntOrNull() ?: 0, fat.toIntOrNull() ?: 0, slot)
                dish = ""; cal = ""; pro = ""; carb = ""; fat = ""
            },
            enabled = dish.isNotBlank()
        ) { Text("Log meal") }
    }
}

@Composable
private fun SleepLogger(onSave: (Float, Int, String) -> Unit) {
    var hours by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(hours, { hours = it }, label = { Text("Hours") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(quality, { quality = it }, label = { Text("Quality 1-5") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { onSave(hours.toFloatOrNull() ?: 0f, quality.toIntOrNull() ?: 0, note); hours = ""; quality = ""; note = "" },
            enabled = (hours.toFloatOrNull() ?: 0f) > 0f
        ) { Text("Log sleep") }
    }
}

@Composable
private fun SymptomLogger(onSave: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text("What are you feeling?") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(severity, { severity = it }, label = { Text("Severity 1-5 (optional)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Button(
            onClick = { onSave(text, severity.toIntOrNull() ?: 0); text = ""; severity = "" },
            enabled = text.isNotBlank()
        ) { Text("Log symptom") }
    }
}

@Composable
private fun LabLogger(onSave: (String, Float, String, Float, Float) -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var low by remember { mutableStateOf("") }
    var high by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Hemoglobin)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value, { value = it }, label = { Text("Value") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(low, { low = it }, label = { Text("Ref low") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(high, { high = it }, label = { Text("Ref high") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Button(
            onClick = {
                onSave(name, value.toFloatOrNull() ?: 0f, unit, low.toFloatOrNull() ?: 0f, high.toFloatOrNull() ?: 0f)
                name = ""; value = ""; unit = ""; low = ""; high = ""
            },
            enabled = name.isNotBlank() && (value.toFloatOrNull() ?: 0f) > 0f
        ) { Text("Save lab value") }
    }
}

@Composable
private fun GoalLogger(onSave: (String, String, Float, String, Int) -> Unit) {
    var kind by remember { mutableStateOf("workout") }
    var title by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var weeks by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(kind, { kind = it }, label = { Text("Kind") }, modifier = Modifier.weight(1f))
            OutlinedTextField(weeks, { weeks = it }, label = { Text("Weeks") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(target, { target = it }, label = { Text("Target") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, modifier = Modifier.weight(1f))
        }
        Button(
            onClick = {
                onSave(kind, title, target.toFloatOrNull() ?: 0f, unit, weeks.toIntOrNull() ?: 0)
                title = ""; target = ""; unit = ""; weeks = ""
            },
            enabled = title.isNotBlank()
        ) { Text("Save goal") }
    }
}

@Composable
private fun MedicationLogger(onSave: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Medicine") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(dose, { dose = it }, label = { Text("Dose") }, modifier = Modifier.weight(1f))
            OutlinedTextField(schedule, { schedule = it }, label = { Text("Schedule") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { onSave(name, dose, schedule, note); name = ""; dose = ""; schedule = ""; note = "" },
            enabled = name.isNotBlank()
        ) { Text("Save reminder") }
    }
}

/** Feature ids whose JSON result can be written back to the store. */
private val SAVEABLE = setOf(
    "diet.dish_estimate", "lab.extract", "goal.dialogue", "score.daily",
    "plan.workout", "diet.meal_plan"
)
