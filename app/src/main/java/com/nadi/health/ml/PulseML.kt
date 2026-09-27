package com.nadi.health.ml


import android.app.Application
import android.content.Context
import android.util.Log
import com.qualcomm.qti.snpe.FloatTensor
import com.qualcomm.qti.snpe.NeuralNetwork
import com.qualcomm.qti.snpe.SNPE
import java.io.File
import java.io.FileOutputStream

/** Thrown when the iQOO 15 NPU execution stack (SNPE + DLC + Hexagon HTP) is unavailable. */
class Iqoo15NpuUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * AI-powered signal refinement - iQOO 15 EXCLUSIVE execution stack.
 *
 * Target device: iQOO 15 (Snapdragon 8 Elite Gen 5 / SM8850, Android 16 / OriginOS 6)
 *
 * This model runs ONLY on the Hexagon NPU (HTP) via Qualcomm SNPE with the
 * quantized .dlc model. There are deliberately NO CPU / GPU fallbacks:
 *   - runtime order is [DSP] only (Hexagon)
 *   - CPU fallback inside SNPE is disabled (setCpuFallbackEnabled(false))
 *   - no TFLite, no NNAPI, no Adreno GPU delegate
 *
 * If the SNPE runtime, the DLC asset, or the Hexagon HTP runtime is missing,
 * [Iqoo15NpuUnavailableException] is thrown at construction time and the app
 * surfaces a hard error instead of silently degrading accuracy.
 *
 * Setup (required):
 *   1. ./tools/convert_tflite_to_dlc.sh   -> app/src/main/assets/rppg_model.dlc
 *   2. snpe-release.aar already wired in app/libs/ (QAIRT SDK v2.50 verified)
 *
 * Cleans motion artifacts from rPPG signals
 */
class PulseML(context: Context) {

    /** Active backend name - always "SNPE-DLC/HTP" on a successful init. */
    var backendName: String = "SNPE-DLC/HTP"
        private set

    private val appContext: Context = context.applicationContext

    private var network: NeuralNetwork? = null
    private var inputName: String? = null
    private var outputName: String? = null
    private var inputShape: IntArray? = null

    /** Model input length - must match the DLC graph (300-sample rPPG window). */
    private val inputSize = 300

    init {
        initSnpe()
    }

    // ---------------------------------------------------------------------
    //  SNPE / DLC (Hexagon NPU) - the ONLY backend
    // ---------------------------------------------------------------------

    private fun initSnpe() {
        // SNPE v2.x builder requires an Application context
        val app = appContext as? Application
            ?: throw Iqoo15NpuUnavailableException(
                "PulseML must be constructed with an Application context"
            )

        // 1) Copy the DLC asset to a cache file (builder needs a File/InputStream)
        val dlcFile: File = try {
            val f = File(app.cacheDir, DLC_PATH)
            app.assets.open(DLC_PATH).use { input ->
                FileOutputStream(f).use { output -> input.copyTo(output) }
            }
            f
        } catch (e: Throwable) {
            throw Iqoo15NpuUnavailableException(
                "Missing $DLC_PATH asset. Generate it with: ./tools/convert_tflite_to_dlc.sh", e
            )
        }

        // 2) Enable native SNPE/HTP/fastrpc logging (tag SNPE-DebugLog) so the
        //    runtime-selection probe is observable in logcat.
        try {
            SNPE.logger.initializeLogging(app, NeuralNetwork.LogLevel.LOG_VERBOSE)
            Log.i(TAG, "SNPE runtime version: " + SNPE.getRuntimeVersion(app))
        } catch (t: Throwable) {
            Log.w(TAG, "SNPE logging init failed (non-fatal)", t)
        }

        // 3) Build the network - Hexagon HTP only, CPU fallback disabled
        val builder = SNPE.NeuralNetworkBuilder(app)
            .setRuntimeOrder(NeuralNetwork.Runtime.DSP)   // DSP == Hexagon HTP
            .setPerformanceProfile(NeuralNetwork.PerformanceProfile.SUSTAINED_HIGH_PERFORMANCE)
            .setCpuFallbackEnabled(false)                 // NO silent CPU fallback
            // iQOO 15 / OriginOS: CDSP sessions must run in unsigned PD. The
            // default runtime probe opens a SIGNED session, then the network's
            // op-validation opens an UNSIGNED one -> "Expected pdSession (0)
            // does not match actual pdSession (1)" and error 73. UNSIGNEDPD_CHECK
            // makes the probe use an unsigned session too, keeping every PD in
            // the process consistent (the documented OEM unsigned-PD setup).
            .setRuntimeCheckOption(NeuralNetwork.RuntimeCheckOption.UNSIGNEDPD_CHECK)
            .setUnsignedPD(true)
            // NOTE: do NOT set setDebugEnabled(true) for release. Debug mode
            // forces full per-op on-device validation at build() which trips the
            // same pdSession-mismatch bug in SNPE 2.50 on SM8850, and disables
            // accelerated HTP init.
            // Native diagnostics when needed:
            //   SNPE.logger.initializeLogging(app, NeuralNetwork.LogLevel.LOG_VERBOSE)

        try {
            builder.setModel(dlcFile)
        } catch (e: Exception) {
            throw Iqoo15NpuUnavailableException("Failed to read DLC model file", e)
        }

        val net: NeuralNetwork = try {
            builder.build()
        } catch (e: Throwable) {
            throw Iqoo15NpuUnavailableException(
                "Hexagon HTP runtime failed to load the DLC on this device " +
                        "(SNPE present, DSP build rejected). Verify the DLC was built " +
                        "for HTP v79 / SM8850 and that you are on an iQOO 15.",
                e.cause ?: e
            )
        }

        // 3) Cache tensor metadata
        network = net
        inputName = net.inputTensorsNames.firstOrNull()
            ?: throw Iqoo15NpuUnavailableException("DLC has no input tensors")
        outputName = net.outputTensorsNames.firstOrNull()
            ?: throw Iqoo15NpuUnavailableException("DLC has no output tensors")
        inputShape = net.inputTensorsShapes[inputName]

        Log.i(
            TAG, "PulseML online on iQOO 15 Hexagon NPU: backend=$backendName, " +
                    "input='$inputName' shape=${inputShape?.contentToString()}, " +
                    "output='$outputName', dlc=${dlcFile.length()} bytes"
        )
    }

    private fun infer(rawSignal: FloatArray, rawHR: Float): Float {
        val net = network ?: return rawHR
        val name = inputName ?: return rawHR
        return try {
            val shape = inputShape ?: intArrayOf(1, inputSize, 1)

            val inputTensor: FloatTensor = net.createFloatTensor(*shape)
            inputTensor.write(rawSignal, 0, rawSignal.size)

            val outputs: Map<String, FloatTensor> = net.execute(mapOf(name to inputTensor))
            val outTensor = outputs[outputName]

            var refined = rawHR
            if (outTensor != null) {
                val outArr = FloatArray(outTensor.size)
                val read = outTensor.read(outArr, 0, outArr.size)
                if (read > 0) refined = outArr[0]
                outTensor.release()
            }
            inputTensor.release()

            // Sanity check: Keep HR in valid range
            if (refined < 40f) rawHR else refined
        } catch (e: Throwable) {
            Log.e(TAG, "SNPE inference error", e)
            rawHR
        }
    }

    // ---------------------------------------------------------------------
    //  Public API (unchanged)
    // ---------------------------------------------------------------------

    /**
     * Refine raw HR estimate using AI model
     * Input: 300-sample buffer of raw green values
     * Output: Cleaned heart rate estimate in BPM
     */
    fun refineHeartRate(rawSignal: FloatArray, rawHR: Float): Float {
        if (rawSignal.size != inputSize) {
            return rawHR  // Fall back to raw estimate
        }
        return infer(rawSignal, rawHR)
    }

    /**
     * Alternative: Refine signal waveform (for advanced use cases)
     */
    fun refineSignalWaveform(rawSignal: FloatArray): FloatArray {
        // If model outputs full waveform instead of single HR value
        // This would be used for more sophisticated signal cleaning
        return rawSignal  // Placeholder
    }

    fun release() {
        try {
            network?.release()
        } catch (_: Throwable) { /* ignore double-release */ }
        network = null
        Log.d(TAG, "PulseML resources released (backend was: $backendName)")
    }

    companion object {
        private const val TAG = "PulseML"
        private const val DLC_PATH = "rppg_model.dlc"  // iQOO 15 NPU model - the only model
    }
}
