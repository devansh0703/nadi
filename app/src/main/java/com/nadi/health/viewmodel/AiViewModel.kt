package com.nadi.health.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadi.health.NadiApplication
import com.nadi.health.ai.AiFeature
import com.nadi.health.ai.AiFeatures
import com.nadi.health.ai.AiPrompts
import com.nadi.health.ai.AiResultKind
import com.nadi.health.ai.HealthSnapshotBuilder
import com.nadi.health.ai.VisionSupport
import com.nadi.health.data.local.AiChatMessage
import com.nadi.health.data.local.AiInsight
import com.nadi.health.data.local.HydrationLog
import com.nadi.health.data.local.LabValue
import com.nadi.health.data.local.MealLog
import com.nadi.health.data.local.MedicationReminder
import com.nadi.health.data.local.NadiRepository
import com.nadi.health.data.local.SleepLog
import com.nadi.health.data.local.SymptomLog
import com.nadi.health.data.local.UserProfile
import com.nadi.health.data.local.WellnessGoal
import com.geniex.sdk.ModelManagerWrapper
import com.nadi.health.ml.qwen.QwenEngine
import com.nadi.health.ml.qwen.QwenModelManager
import com.nadi.health.ml.qwen.QwenNpuUnavailableException
import com.nadi.health.ml.qwen.QwenPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Drives the AI tab.
 *
 * Everything the model needs is assembled here: the [NadiRepository] holds the
 * user's records, [HealthSnapshotBuilder] turns them into one factual block,
 * the selected [AiFeature] turns that into a prompt, and [QwenEngine] runs it on
 * the Hexagon NPU. Nothing else in the app talks to the engine directly, which
 * is what keeps the "one model context" rule intact.
 */
class AiViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NadiApplication
    private val repo: NadiRepository get() = app.repository
    private val engine: QwenEngine get() = app.qwen

    /** A finished generation, ready to render. */
    data class Result(
        val featureId: String,
        val title: String,
        val body: String,
        /** Parsed object when the feature produced JSON, else null. */
        val json: JSONObject?,
        val elapsedMs: Long,
        val truncated: Boolean
    )

    /** Everything the AI screen renders. */
    data class UiState(
        val model: QwenModelManager.Status? = null,
        val importing: Boolean = false,
        val importProgress: Float = 0f,
        val note: String = "",
        val busyFeatureId: String? = null,
        val busyLabel: String = "",
        val streaming: String = "",
        val result: Result? = null,
        val error: String? = null,
        val sending: Boolean = false,
        val reply: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val features: List<AiFeature> = AiFeatures.all
    val groups: Map<com.nadi.health.ai.AiGroup, List<AiFeature>> = AiFeatures.grouped()

    /** Chat history for the Ask tab. */
    val chat: StateFlow<List<AiChatMessage>> = repo.chat(SESSION)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cached reports/insights, newest first. */
    val insights: StateFlow<List<AiInsight>> = repo.recentInsights(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The single-row profile, for the editor and for grounding prompts. */
    val profile: StateFlow<UserProfile> = repo.profile()
        .map { it ?: UserProfile() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    val meals: StateFlow<List<MealLog>> = repo.meals(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sleep: StateFlow<List<SleepLog>> = repo.sleep(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val symptoms: StateFlow<List<SymptomLog>> = repo.symptoms(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val labs: StateFlow<List<LabValue>> = repo.labs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val goals: StateFlow<List<WellnessGoal>> = repo.goals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medications: StateFlow<List<MedicationReminder>> = repo.medications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { engine.initialize() }
            refreshModel()
        }
    }

    // ── Model status & import ───────────────────────────────────────────────

    fun refreshModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = engine.status()
            _state.value = _state.value.copy(model = status)
        }
    }

    /** Where the bundle may have been pushed, best first (all app-private). */
    private fun importSources(): List<File> = buildList {
        add(engine.manager.pushedDir)
        add(engine.manager.legacyPushedDir)
    }

    /**
     * One-time copy of the ~3.1 GB bundle into `filesDir`.
     *
     * The bundle is pushed, not downloaded: the user runs a documented `adb
     * push` (GUIDE.md §6) and then taps Import. Nothing here touches the network.
     */
    fun importModel() {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, importProgress = 0f, note = "Looking for the bundle…", error = null)
            try {
                val source = importSources().firstOrNull { s ->
                    s.isDirectory && File(s, QwenModelManager.METADATA_FILE).exists()
                } ?: throw IllegalStateException(
                    "No bundle found. Push it first:\n" + QwenModelManager.IMPORT_HINT
                )
                engine.manager.importFrom(source) { copied, total, name ->
                    _state.value = _state.value.copy(
                        importProgress = if (total > 0) copied.toFloat() / total else 0f,
                        note = "Importing $name"
                    )
                }
                _state.value = _state.value.copy(
                    importing = false,
                    importProgress = 1f,
                    note = "Imported into the GenieX cache"
                )
                refreshModel()
            } catch (t: Throwable) {
                Log.e(TAG, "Model import failed", t)
                _state.value = _state.value.copy(
                    importing = false,
                    error = t.message ?: "Import failed"
                )
            }
        }
    }

    // ── Feature runs ────────────────────────────────────────────────────────

    fun runFeature(feature: AiFeature, inputs: Map<String, String>) {
        if (_state.value.busyFeatureId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busyFeatureId = feature.id,
                busyLabel = feature.title,
                streaming = "",
                result = null,
                error = null
            )
            try {
                if (feature.requiresVision && !VisionSupport.hasVisionModel) {
                    throw QwenNpuUnavailableException(VisionSupport.UNAVAILABLE_MESSAGE)
                }
                val snapshot = withContext(Dispatchers.IO) { HealthSnapshotBuilder.build(repo) }
                val request = feature.build(snapshot, inputs)
                    ?: throw IllegalArgumentException("Fill in the highlighted fields first.")

                val out = engine.generate(
                    system = request.system,
                    turns = listOf("user" to request.user),
                    sampler = request.sampler,
                    assistantPrefill = request.prefill,
                    maxOutputChars = request.maxOutputChars,
                    onChunk = { delta ->
                        _state.value = _state.value.copy(
                            streaming = _state.value.streaming + delta
                        )
                    }
                )

                val json = if (feature.resultKind == AiResultKind.JSON)
                    QwenPrompt.extractJson(out.text)?.let { runCatching { JSONObject(it) }.getOrNull() }
                else null

                _state.value = _state.value.copy(
                    busyFeatureId = null,
                    streaming = "",
                    result = Result(feature.id, feature.title, out.text, json, out.elapsedMs, out.truncated)
                )

                if (feature.cacheAsInsight && feature.resultKind == AiResultKind.TEXT && out.text.isNotBlank()) {
                    withContext(Dispatchers.IO) { repo.saveInsight(feature.id, feature.title, out.text) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Feature ${feature.id} failed", t)
                _state.value = _state.value.copy(
                    busyFeatureId = null,
                    streaming = "",
                    error = t.message ?: "Generation failed"
                )
            }
        }
    }

    fun cancel() {
        engine.cancel()
        _state.value = _state.value.copy(busyFeatureId = null, streaming = "")
    }

    fun dismissResult() {
        _state.value = _state.value.copy(result = null, error = null, streaming = "")
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.sending || _state.value.busyFeatureId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true, reply = "", error = null)
            try {
                withContext(Dispatchers.IO) { repo.addChatMessage(AiChatMessage.ROLE_USER, q, SESSION) }
                val snapshot = withContext(Dispatchers.IO) { HealthSnapshotBuilder.build(repo) }
                val history = withContext(Dispatchers.IO) { repo.recentChat(8) }
                    .filter { it.sessionId == SESSION }
                    .sortedBy { it.timestamp }
                    .dropLast(1) // drop the question we just inserted
                    .map { it.role to it.content }
                val request = AiPrompts.qa(snapshot, q, history)

                val turns = history + listOf("user" to q)
                val out = engine.generate(
                    system = request.system,
                    turns = turns,
                    sampler = request.sampler,
                    maxOutputChars = request.maxOutputChars,
                    onChunk = { delta ->
                        _state.value = _state.value.copy(reply = _state.value.reply + delta)
                    }
                )
                withContext(Dispatchers.IO) {
                    repo.addChatMessage(AiChatMessage.ROLE_ASSISTANT, out.text, SESSION)
                }
                _state.value = _state.value.copy(sending = false, reply = "")
            } catch (t: Throwable) {
                Log.e(TAG, "Chat failed", t)
                _state.value = _state.value.copy(
                    sending = false,
                    reply = "",
                    error = t.message ?: "Generation failed"
                )
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch(Dispatchers.IO) { repo.clearChat(SESSION) }
    }

    // ── Applying a structured result back to the store ──────────────────────

    /**
     * Persists what the model extracted when that makes sense: a dish estimate
     * becomes a meal, an extracted report becomes lab values, a completed goal
     * dialogue becomes a goal. The user always sees the result first and taps to
     * save, so nothing is written silently.
     */
    fun applyResultToStore() {
        val result = _state.value.result ?: return
        val json = result.json ?: return
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    when (result.featureId) {
                        "diet.dish_estimate" -> {
                            val calLow = json.optInt("calories_low", 0)
                            val calHigh = json.optInt("calories_high", 0)
                            val proLow = json.optInt("protein_low", 0)
                            val proHigh = json.optInt("protein_high", 0)
                            repo.addMeal(
                                dish = json.optString("dish", "Meal"),
                                calories = mid(calLow, calHigh),
                                proteinG = mid(proLow, proHigh),
                                carbsG = mid(json.optInt("carbs_low", 0), json.optInt("carbs_high", 0)),
                                fatG = mid(json.optInt("fat_low", 0), json.optInt("fat_high", 0)),
                                slot = slotNow(),
                                edited = true,
                                source = "ai"
                            )
                            "Meal added to your log"
                        }

                        "lab.extract" -> {
                            val values = json.optJSONArray("values")
                            var count = 0
                            if (values != null) {
                                for (i in 0 until values.length()) {
                                    val v = values.optJSONObject(i) ?: continue
                                    val name = v.optString("name").trim()
                                    if (name.isEmpty()) continue
                                    repo.addLab(
                                        name = name,
                                        value = v.optDouble("value", 0.0).toFloat(),
                                        unit = v.optString("unit", ""),
                                        refLow = v.optDouble("ref_low", 0.0).toFloat(),
                                        refHigh = v.optDouble("ref_high", 0.0).toFloat()
                                    )
                                    count++
                                }
                            }
                            if (count == 0) "No lab values found to save" else "Saved $count lab value(s)"
                        }

                        "goal.dialogue" -> {
                            if (json.optBoolean("complete", false)) {
                                val goal = json.optJSONObject("goal")
                                if (goal != null) {
                                    repo.addGoal(
                                        kind = goal.optString("kind", "workout"),
                                        title = goal.optString("title", "My goal"),
                                        targetValue = goal.optDouble("target", 0.0).toFloat(),
                                        unit = goal.optString("unit", ""),
                                        deadline = System.currentTimeMillis() +
                                            goal.optInt("weeks", 0).coerceIn(0, 104) * 7 * 86_400_000L,
                                        notes = "Set with Nadi"
                                    )
                                    "Goal saved"
                                } else "Goal incomplete — answer the next question"
                            } else {
                                "Goal incomplete — answer the next question"
                            }
                        }

                        "score.daily" -> {
                            repo.saveInsight("score.daily", "Wellness score", result.body)
                            "Score saved to reports"
                        }

                        "plan.workout", "diet.meal_plan" -> {
                            repo.saveInsight(result.featureId, result.title, result.body)
                            "Plan saved to reports"
                        }

                        else -> "Nothing to save for this feature"
                    }
                }
                _state.value = _state.value.copy(note = saved, result = null)
            } catch (t: Throwable) {
                Log.e(TAG, "applyResult failed", t)
                _state.value = _state.value.copy(error = t.message ?: "Could not save")
            }
        }
    }

    // ── Logging helpers (data entry that grounds the prompts) ───────────────

    fun saveProfile(profile: UserProfile) = io("Profile saved") { repo.saveProfile(profile) }

    fun logMeal(
        dish: String, calories: Int, proteinG: Int, carbsG: Int, fatG: Int,
        slot: String, source: String = "manual"
    ) = io("Meal logged") {
        repo.addMeal(dish, calories, proteinG, carbsG, fatG, slot, source = source)
    }

    fun logSleep(hours: Float, quality: Int, note: String) =
        io("Sleep logged") { repo.addSleep(hours, quality, note) }

    fun logSymptom(text: String, severity: Int) =
        io("Symptom logged") { repo.addSymptom(text, severity) }

    fun logLab(name: String, value: Float, unit: String, refLow: Float, refHigh: Float) =
        io("Lab value saved") { repo.addLab(name, value, unit, refLow, refHigh) }

    fun logGoal(kind: String, title: String, target: Float, unit: String, weeks: Int) =
        io("Goal saved") {
            repo.addGoal(
                kind, title, target, unit,
                deadline = System.currentTimeMillis() + weeks.coerceIn(0, 104) * 7 * 86_400_000L
            )
        }

    fun logMedication(name: String, dose: String, schedule: String, note: String) =
        io("Reminder saved") { repo.addMedication(name, dose, schedule, note) }

    fun addGlass() = io("Hydration updated") { repo.addGlass(1) }

    fun setHydrationGoal(glasses: Int) = io("Hydration goal updated") {
        // Keep today's count, change only the goal.
        val current = repo.hydrationToday()
        repo.saveHydration(
            HydrationLog(
                dayKey = dayKey(),
                glasses = current?.glasses ?: 0,
                goalGlasses = glasses.coerceIn(1, 30),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun deleteMeal(id: Long) = io("Meal removed") { repo.deleteMeal(id) }
    fun deleteSleep(id: Long) = io("Entry removed") { repo.deleteSleep(id) }
    fun deleteSymptom(id: Long) = io("Entry removed") { repo.deleteSymptom(id) }
    fun deleteLab(id: Long) = io("Value removed") { repo.deleteLab(id) }
    fun deleteGoal(id: Long) = io("Goal removed") { repo.deleteGoal(id) }
    fun deleteMedication(id: Long) = io("Reminder removed") { repo.deleteMedication(id) }

    /** Runs a repository write, then posts an honest one-line status. */
    private fun io(note: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                _state.value = _state.value.copy(note = note)
            } catch (t: Throwable) {
                Log.e(TAG, "Write failed", t)
                _state.value = _state.value.copy(error = t.message ?: "Could not save")
            }
        }
    }

    fun clearNote() {
        _state.value = _state.value.copy(note = "")
    }

    private fun mid(low: Int, high: Int): Int = when {
        low <= 0 && high <= 0 -> 0
        low <= 0 -> high
        high <= 0 -> low
        else -> (low + high) / 2
    }

    private fun slotNow(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 11 -> "breakfast"
            hour < 16 -> "lunch"
            hour < 19 -> "snack"
            else -> "dinner"
        }
    }

    private fun dayKey(): String {
        val now = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    companion object {
        private const val TAG = "AiViewModel"

        /** One chat thread, so history is stable across app restarts. */
        const val SESSION = "nadi-main"
    }
}
