package com.nadi.health.core

import android.os.Build
import android.util.Log

/**
 * iQOO 15 device identification & runtime capability probe.
 *
 * iQOO 15 hardware:
 *   - SoC:  Qualcomm Snapdragon 8 Elite Gen 5 (model "SM8850")
 *   - CPU:  2x Oryon Prime + 6x Oryon Performance
 *   - GPU:  Adreno 840
 *   - NPU:  Hexagon (HTP) - reachable from the app via Qualcomm SNPE (DLC models)
 *   - OS:   Android 16 / OriginOS 6
 *
 * Build.SOC_MODEL is API 31+; older builds simply report "unknown".
 */
object DeviceCapability {

    private const val TAG = "DeviceCapability"

    /** SoC model reported by the platform (e.g. "SM8850"). */
    val socModel: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { "unknown" }
        } else "unknown"

    /** True when running on the iQOO 15 / Snapdragon 8 Elite Gen 5 class of device. */
    val isIqoo15: Boolean
        get() = isSnapdragon8850 && isVivoFamily

    /** True on any Snapdragon 8 Elite Gen 5 (SM8850) device, not just iQOO-branded. */
    val isSnapdragon8850: Boolean
        get() {
            if (socModel.contains("8850", ignoreCase = true)) return true
            // Fallback: some OEM builds expose the SoC in HARDWARE / DEVICE
            return Build.HARDWARE.contains("sm8850", ignoreCase = true) ||
                    Build.DEVICE.contains("sm8850", ignoreCase = true)
        }

    private val isVivoFamily: Boolean
        get() = Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
                Build.MANUFACTURER.equals("iqoo", ignoreCase = true) ||
                Build.BRAND.contains("iqoo", ignoreCase = true)

    /** Number of usable CPU cores (8 on the iQOO 15's Oryon cluster). */
    val cpuCoreCount: Int
        get() = Runtime.getRuntime().availableProcessors()

    /**
     * Whether the Qualcomm SNPE runtime (needed for Hexagon NPU / DLC execution)
     * is present in this build. Drop snpe-release.aar into app/libs/ to enable it.
     */
    val hasSnpeRuntime: Boolean
        get() = try {
            Class.forName("com.qualcomm.qti.snpe.NeuralNetwork")
            true
        } catch (e: Throwable) {
            false
        }

    /** True when the app bundle ships the converted DLC model asset. */
    fun hasDlcModel(context: android.content.Context): Boolean = try {
        context.assets.open("rppg_model.dlc").use { true }
    } catch (e: Throwable) {
        false
    }

    /** One-line summary for logs / on-screen diagnostics. */
    fun describe(context: android.content.Context): String {
        val npuReady = hasSnpeRuntime && hasDlcModel(context)
        return "SoC=$socModel, cores=$cpuCoreCount, iQOO15=$isIqoo15, " +
                "NPU-ready=$npuReady (SNPE=${hasSnpeRuntime}, DLC=${hasDlcModel(context)})"
    }

    fun logDeviceProfile(context: android.content.Context) {
        Log.i(TAG, describe(context))
    }
}
