package com.nadi.health.imu

import kotlin.math.sqrt

/**
 * Hand tremor quantification from a 10 s held-outstretched-hand test
 * (phone in hand, arm extended forward).
 *
 * Method (TremorSoft / Apkinson approach): Welch PSD of the linear
 * acceleration magnitude; the dominant frequency classifies the tremor:
 *
 *  - 1-3 Hz    physiological tremor (normal, everyone has it) or slow sway
 *  - 4-7 Hz    parkinsonian rest tremor band
 *  - 8-12 Hz   essential tremor band
 *
 * Amplitude is reported in milli-g. Clinical cut-offs vary; we follow the
 * published bands only and label results as screening information, not
 * diagnosis.
 */
class TremorAnalyzer(private val fs: Float) {

    enum class Class(val label: String) {
        NONE("No measurable tremor"),
        PHYSIOLOGICAL("Physiological (normal)"),
        PARKINSONIAN("Parkinsonian-range (4-7 Hz)"),
        ESSENTIAL("Essential-range (8-12 Hz)")
    }

    data class Result(
        val dominantHz: Float,
        val amplitudeMg: Float,   // peak sinusoid amplitude in milli-g
        val classification: Class,
        val confidence: Float,    // peak share of 1-15 Hz band power
        val wave: FloatArray      // 1-15 Hz band-passed tremor signal for UI
    )

    fun analyze(magnitude: FloatArray): Result {
        val minSamples = (fs * 6).toInt()
        if (magnitude.size < minSamples) {
            return Result(0f, 0f, Class.NONE, 0f, FloatArray(0))
        }

        // Detrend: remove gravity-mean and linear drift of the held pose
        val centered = FloatArray(magnitude.size)
        val mean = magnitude.average().toFloat()
        val n = magnitude.size
        // linear drift fit (least squares on endpoints)
        val drift = magnitude[n - 1] - magnitude[0]
        for (i in 0 until n) {
            centered[i] = magnitude[i] - mean - drift * (i.toFloat() / (n - 1).coerceAtLeast(1) - 0.5f)
        }

        val (freqs, psd) = SignalFilters.welch(centered, fs, SignalFilters.nextPow2(minOf(centered.size, 4096)))

        var bandPower = 0f
        var peakPower = 0f
        var peakFreq = 0f
        for (k in freqs.indices) {
            val f = freqs[k]
            if (f < 1f || f > 15f) continue
            bandPower += psd[k]
            if (psd[k] > peakPower) {
                peakPower = psd[k]
                peakFreq = f
            }
        }
        if (bandPower <= 0f || peakFreq <= 0f) {
            return Result(0f, 0f, Class.NONE, 0f, SignalFilters.bandPassZeroPhase(centered, fs, 1f, 15f))
        }

        // Peak sinusoid amplitude (mg) from PSD: A = sqrt(2 * integral around peak)
        // Simplified: A_mg = 1000 * sqrt(2 * peakPower * binWidth)
        val binWidth = freqs[1] - freqs[0]
        val amplitudeMg = 1000f * sqrt(2f * peakPower * binWidth)

        val confidence = (peakPower / bandPower).coerceIn(0f, 1f)

        val classification = when {
            amplitudeMg < 15f -> Class.NONE              // below hand-jitter floor
            peakFreq < 3.5f -> Class.PHYSIOLOGICAL
            peakFreq <= 7.5f -> Class.PARKINSONIAN
            peakFreq <= 12.5f -> Class.ESSENTIAL
            else -> Class.PHYSIOLOGICAL
        }

        return Result(
            peakFreq, amplitudeMg, classification, confidence,
            SignalFilters.bandPassZeroPhase(centered, fs, 1f, 15f)
        )
    }
}
