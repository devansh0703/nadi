package com.nadi.health.ml.qwen

import android.app.Application
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Thrown when the Hexagon NPU text-generation stack is unavailable.
 *
 * Same philosophy as [com.nadi.health.ml.Iqoo15NpuUnavailableException]: there
 * is deliberately **no CPU / cloud fallback** for the AI path. If the runtime
 * or the model is missing, the app says so instead of silently degrading.
 */
class QwenNpuUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Outcome of one generation. */
data class QwenResult(
    val text: String,
    val elapsedMs: Long,
    val truncated: Boolean = false
)

/**
 * Runs Qwen3-4B on the Hexagon NPU through the official GenieX SDK
 * (`com.geniex.sdk`) — in-process, no child process, no env vars.
 *
 * Flow per app session: [initialize] once (registers the qairt plugin), then
 * [generate] as often as wanted. The engine holds ONE LlmWrapper; requests are
 * serialized by a mutex because the HTP holds a single model context.
 */
class QwenEngine(private val app: Application) {

    /** Exposed so the AI tab can import the bundle and read its status. */
    val manager = QwenModelManager(app)
    private val mutex = Mutex()
    private val initMutex = Mutex()

    @Volatile
    private var llm: LlmWrapper? = null

    @Volatile
    private var sdkReady = false

    @Volatile
    private var cancelled = false

    fun status(): QwenModelManager.Status = manager.status()

    fun modelDir(): java.io.File = manager.pushedDir

    /** True when the runtime + imported model are present. */
    fun isReady(): Boolean = manager.status().isReady

    /**
     * Idempotent one-time SDK init: registers the GenieX plugins and the
     * model-manager store under `filesDir/geniex`.
     */
    suspend fun initialize() {
        if (sdkReady) return
        initMutex.withLock {
            if (sdkReady) return
            withContext(Dispatchers.IO) {
                GenieXSdk.getInstance().init(app, object : GenieXSdk.InitCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "GenieX SDK initialized")
                    }

                    override fun onFailure(reason: String) {
                        Log.e(TAG, "GenieX SDK init failed: $reason")
                    }
                })
                ModelManagerWrapper.init(
                    app.filesDir.resolve("geniex").apply { mkdirs() }.absolutePath
                ).getOrThrow()
            }
            sdkReady = true
        }
    }

    /**
     * Generates text for [system] + [turns] on the NPU.
     *
     * @param onChunk called per streamed token.
     * @param maxOutputChars hard stop; the caller is told via
     *   [QwenResult.truncated] when the answer hit it.
     * @throws QwenNpuUnavailableException when the NPU stack is not usable.
     */
    suspend fun generate(
        system: String,
        turns: List<Pair<String, String>>,
        sampler: Sampling = Sampling.GROUNDED,
        assistantPrefill: String = "",
        maxOutputChars: Int = DEFAULT_MAX_CHARS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onChunk: (String) -> Unit = {}
    ): QwenResult = mutex.withLock {
        initialize()

        val status = manager.status()
        if (!status.isReady) {
            throw QwenNpuUnavailableException(status.detail)
        }

        val paths = ModelManagerWrapper.getPaths(QwenModelManager.MODEL_NAME)
            ?: throw QwenNpuUnavailableException(
                "GenieX cannot resolve the imported model (${QwenModelManager.MODEL_NAME})"
            )

        cancelled = false
        val started = System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            // One LlmWrapper per process keeps the NPU context warm; reset
            // between requests instead of re-creating the 3.4 GB model.
            val wrapper = llm ?: LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_path = paths.model_path,
                        // QAIRT fixes n_ctx / n_gpu_layers at bundle compile
                        // time and rejects non-zero values — and the Kotlin
                        // ModelConfig defaults are non-zero (nCtx=2048,
                        // nGpuLayers=-1), so zero them explicitly. Zero means
                        // "unset": JNI skips the flag and qairt falls back to
                        // genie_config.json inside the bundle.
                        config = ModelConfig(nCtx = 0, nGpuLayers = 0),
                        runtime_id = RUNTIME_QAIRT,
                        compute_unit = null // qairt is NPU-only
                    )
                )
                .build()
                .getOrElse { t ->
                    throw QwenNpuUnavailableException(
                        "NPU model load failed: ${t.message}", t
                    )
                }
                .also { llm = it }
            runCatching { wrapper.reset() }

            val chat = ArrayList<ChatMessage>(turns.size + 1)
            chat.add(ChatMessage(role = "system", content = system.trim()))
            for ((role, content) in turns) {
                chat.add(ChatMessage(role = role, content = content.trim()))
            }

            val templated = wrapper.applyChatTemplate(
                chat.toTypedArray(),
                null,
                enableThinking = false,
                addGenerationPrompt = true
            ).getOrElse { t ->
                throw QwenNpuUnavailableException("Chat template failed: ${t.message}", t)
            }

            var text = StringBuilder()
            var hardStopped = false
            try {
                withTimeoutOrNull(timeoutMs) {
                    wrapper.generateStreamFlow(
                        templated.formattedText,
                        GenerationConfig(
                            maxTokens = maxOutputChars / APPROX_CHARS_PER_TOKEN,
                            samplerConfig = SamplerConfig(
                                temperature = sampler.temperature,
                                topP = sampler.topP,
                                topK = sampler.topK,
                                seed = sampler.seed.toInt()
                            )
                        )
                    ).collect { result ->
                        coroutineContext.ensureActive()
                        when (result) {
                            is LlmStreamResult.Token -> {
                                text.append(result.text)
                                if (text.length > maxOutputChars) {
                                    hardStopped = true
                                    throw StopGeneration()
                                }
                                onChunk(result.text)
                            }
                            is LlmStreamResult.Error -> throw result.throwable
                            is LlmStreamResult.Completed -> Unit
                        }
                    }
                } ?: run { hardStopped = true } // timeout
            } catch (s: StopGeneration) {
                // Expected on the char cap or cancel path.
            } finally {
                if (cancelled || hardStopped) {
                    runCatching { wrapper.stopStream() }
                }
            }

            val out = text.toString().trim()
            QwenResult(
                text = out,
                elapsedMs = System.currentTimeMillis() - started,
                truncated = hardStopped
            )
        }
    }

    private class StopGeneration : Exception()

    /** Aborts the in-flight generation, if any. */
    fun cancel() {
        cancelled = true
        kotlinx.coroutines.runBlocking {
            runCatching { llm?.stopStream() }
        }
    }

    private companion object {
        const val TAG = "QwenEngine"
        const val RUNTIME_QAIRT = "qairt"

        /** ~600 tokens of headroom on top of a typical answer. */
        const val DEFAULT_MAX_CHARS = 6000
        const val DEFAULT_TIMEOUT_MS = 180_000L
        const val APPROX_CHARS_PER_TOKEN = 4
    }
}
