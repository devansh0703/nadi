package com.nadi.health.ml.qwen

import android.app.Application
import android.util.Log
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the on-device Qwen3-4B (GenieX / QAIRT w4a16) bundle through the
 * official GenieX Android SDK (`com.geniex.sdk`), which runs the model
 * in-process on the Hexagon NPU.
 *
 * Responsibilities:
 *  - verify the GenieX runtime is packaged (plugin libs in `nativeLibraryDir`);
 *  - one-time import of the ~3.1 GB bundle from the adb-pushed folder via
 *    [ModelManagerWrapper.pullFlow] with [HubSource.LOCALFS] — 100% offline;
 *  - report import progress so the AI tab can show honest progress.
 *
 * The SDK owns every NPU concern the old child-process approach hand-rolled
 * (matched QNN/HTP libs, v81 skeletons, ADSP_LIBRARY_PATH) — see GUIDE.md §0.
 */
class QwenModelManager(private val app: Application) {

    /**
     * The pushed bundle directory, app-private (no Android 16 storage
     * permissions needed): `filesDir/qwen3-4b-2507`. GenieX imports FROM here
     * into its own cache (`filesDir/geniex/`); the folder stays as-is.
     */
    val pushedDir: File get() = File(app.filesDir, MODEL_DIR_NAME)

    /** Fallback source: the app's scoped external dir (also permission-free). */
    val legacyPushedDir: File get() = File(
        app.getExternalFilesDir(null) ?: app.filesDir,
        "models/$MODEL_DIR_NAME"
    )

    // ---------------------------------------------------------------------
    //  Status
    // ---------------------------------------------------------------------

    /** Everything the AI tab needs to know before it offers a feature. */
    enum class State { READY, RUNTIME_MISSING, MODEL_MISSING, MODEL_INCOMPLETE, IMPORTING }

    data class Status(
        val state: State,
        val detail: String,
        val modelDir: String,
        val bytesPresent: Long = 0L,
        /** Files that are missing, for a precise error message. */
        val missing: List<String> = emptyList()
    ) {
        val isReady: Boolean get() = state == State.READY
    }

    private fun nativeLibDir(): String = app.applicationInfo.nativeLibraryDir

    private fun runtimeMissing(): List<String> =
        RUNTIME_LIBS.filterNot { File(nativeLibDir(), it).exists() }

    fun status(): Status {
        val runtimeMissing = runtimeMissing()
        if (runtimeMissing.isNotEmpty()) {
            return Status(
                state = State.RUNTIME_MISSING,
                detail = "GenieX native runtime not packaged (missing " +
                    "${runtimeMissing.joinToString(", ")}). Rebuild with the " +
                    "GenieX AAR wired in — see GUIDE.md §6.",
                modelDir = "",
                missing = runtimeMissing
            )
        }

        val imported = File(app.filesDir, "geniex")
        val hasImportedModel = imported.isDirectory &&
            imported.walkTopDown().any { it.name == METADATA_FILE }
        return if (hasImportedModel) {
            Status(
                state = State.READY,
                detail = "Qwen3-4B ready on the Hexagon NPU (GenieX)",
                modelDir = imported.absolutePath,
                bytesPresent = 0L
            )
        } else {
            Status(
                state = State.MODEL_MISSING,
                detail = "Model not imported yet. Push the bundle and tap Import.",
                modelDir = pushedDir.absolutePath
            )
        }
    }

    fun totalBytes(): Long =
        pushedDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** True when a push source exists to import from. */
    suspend fun hasPushedBundle(): Boolean = withContext(Dispatchers.IO) {
        importSource() != null
    }

    private suspend fun importSource(): File? = withContext(Dispatchers.IO) {
        listOf(pushedDir, legacyPushedDir).firstOrNull { src ->
            src.isDirectory && File(src, METADATA_FILE).exists()
        }
    }

    // ---------------------------------------------------------------------
    //  Import (LOCALFS, fully offline)
    // ---------------------------------------------------------------------

    /**
     * Imports the pushed bundle into the GenieX model cache. No network is
     * touched: [HubSource.LOCALFS] reads [source] (an extracted AI Hub dir
     * holding `metadata.json` + the `.bin` shards) straight from disk.
     *
     * [onProgress] receives (copiedBytes, totalBytes, currentFileName).
     */
    suspend fun importFrom(
        source: File,
        onProgress: (copied: Long, total: Long, name: String) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        require(source.isDirectory) { "${source.absolutePath} is not a directory" }
        require(File(source, METADATA_FILE).exists()) {
            "${source.absolutePath} has no $METADATA_FILE — push the whole bundle folder"
        }

        val input = ModelPullInput(
            model_name = MODEL_NAME,
            hub = HubSource.LOCALFS,
            local_path = source.absolutePath
        )

        ModelManagerWrapper.pullFlow(input).collect { event ->
            when (event) {
                is ModelManagerWrapper.PullEvent.Progress -> {
                    var copied = 0L
                    var total = 0L
                    var name = ""
                    for (f in event.files) {
                        copied += f.downloaded_bytes
                        total += f.total_bytes
                        name = f.file_name
                    }
                    onProgress(copied, total, name)
                }
                is ModelManagerWrapper.PullEvent.Completed -> {
                    Log.i(TAG, "GenieX import completed from ${source.absolutePath}")
                }
                is ModelManagerWrapper.PullEvent.Error -> {
                    throw IllegalStateException(
                        "GenieX import failed (rc=${event.code}): ${event.message}"
                    )
                }
            }
        }
    }

    /**
     * Convenience wrapper used by the AI tab: finds the pushed bundle itself.
     * Returns the source that was used, or null when nothing was pushed.
     */
    suspend fun importPushed(
        onProgress: (copied: Long, total: Long, name: String) -> Unit = { _, _, _ -> }
    ): File? {
        val source = importSource() ?: return null
        importFrom(source, onProgress)
        return source
    }

    companion object {
        private const val TAG = "QwenModelManager"

        /** Cache key inside the GenieX model store (any org/repo-style string). */
        const val MODEL_NAME = "local/qwen3-4b-2507"

        /** Bundle folder name for the adb push. */
        const val MODEL_DIR_NAME = "qwen3-4b-2507"

        /** Marks a directory as a complete qairt bundle. */
        const val METADATA_FILE = "metadata.json"

        /** Shown in the "model not imported" message. */
        const val IMPORT_HINT =
            "adb push <bundle>/. /data/local/tmp/nadi-model && " +
                "adb shell run-as com.nadi.health cp -r /data/local/tmp/nadi-model/. files/$MODEL_DIR_NAME/"

        /** Native pieces GenieX needs in nativeLibraryDir (from the AAR). */
        val RUNTIME_LIBS = listOf(
            "libnpu_jni.so",
            "libgeniex.so",
            "libgeniex_core.so",
            "libgeniex-proc.so",
            "libgeniex_plugin_qairt.so",
            "libQnnHtp.so",
            "libQnnHtpV81Skel.so",
            "libQnnHtpV81Stub.so",
            "libQnnSystem.so"
        )
    }
}
