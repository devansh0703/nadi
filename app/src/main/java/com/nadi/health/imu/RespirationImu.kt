package com.nadi.health.imu

import kotlin.math.sqrt

/**
 * Respiration rate - faithful port of the **BioWatch** respiratory-rate
 * estimator (github.com/apoorvam/smart-sensor, from the BioWatch
 * Ballistocardiography paper), which benchmarked best among the three
 * reference algorithms (BioWatch / SeismoTracker / Sleep Monitor) on the
 * UCI Mhealth wrist datasets.
 *
 * BioWatch pipeline (as documented in the repo):
 *  1. z-score normalize each acceleration component
 *  2. averaging filter (moving-average smoothing) per component
 *  3. FFT per component
 *  4. periodicity level = maximum amplitude within 0.13-0.66 Hz
 *     (8-40 breaths/min) - choose the component with the highest level
 *  5. respiratory rate = frequency of that maximum amplitude * 60
 *
 * The candidate components here are the device X/Y/Z axes plus the
 * true-vertical gravity projection (better "Z" for a chest phone); the
 * per-component max-amplitude selection is BioWatch's own rule.
 *
 * The band deliberately extends to 0.66 Hz: fast breathing (30-40 brpm)
 * must stay INSIDE the search band or the estimator latches onto
 * harmonics of slower components (observed as values stuck at 13-16).
 */
class RespirationImu(private val fs: Float) {

    data class Result(
        val brpm: Int,           // breaths per minute, 0 = not confident
        val confidence: Float,   // peak share of band power (stillness proxy)
        val waveform: FloatArray // smoothed winning component for UI
    )

    /** Minimum window: 12 s gives >= 8 cycles at 40 brpm, 1.6 at 8 brpm. */
    fun analyze(samples: List<ImuRecorder.ImuSample>): Result {
        val n = samples.size
        if (n < (fs * 12).toInt()) return Result(0, 0f, FloatArray(0))

        // Candidate components: device axes + true-vertical projection
        val ax = FloatArray(n) { samples[it].ax }
        val ay = FloatArray(n) { samples[it].ay }
        val az = FloatArray(n) { samples[it].az }
        val vert = FloatArray(n) { i ->
            val s = samples[i]
            s.ax * s.ugx + s.ay * s.ugy + s.az * s.ugz
        }
        val candidates = arrayOf(ax, ay, az, vert)

        // BioWatch step 1+2+3+4 per component, keep the most periodic one
        var bestBrpm = 0f
        var bestAmp = 0f
        var bestRatio = 0f
        var bestWave = FloatArray(0)

        for (c in candidates) {
            val res = estimateComponent(c) ?: continue
            if (res.maxAmp > bestAmp) {
                bestAmp = res.maxAmp
                bestBrpm = res.brpm
                bestRatio = res.ratio
                bestWave = res.smoothed
            }
        }

        if (bestBrpm <= 0f || bestAmp <= 0f) return Result(0, 0f, bestWave)
        if (bestRatio < 0.10f) return Result(0, bestRatio, bestWave) // motion-dominated

        return Result(bestBrpm.toInt().coerceIn(8, 40), bestRatio, bestWave)
    }

    private data class ComponentResult(
        val brpm: Float,
        val maxAmp: Float,
        val ratio: Float,
        val smoothed: FloatArray
    )

    /**
     * BioWatch per-component estimation:
     *  z-score -> averaging filter -> FFT -> max amplitude in 0.13-0.66 Hz.
     * Zero-padding to 16384 sharpens bin resolution (~0.03 Hz @ 500 Hz fs),
     * and a Hann window limits leakage (standard FFT preprocessing).
     */
    private fun estimateComponent(x: FloatArray): ComponentResult? {
        // 1. z-score normalize
        val mean = x.average().toFloat()
        val std = SignalFilters.std(x)
        if (std < 1e-4f) return null
        val norm = FloatArray(x.size) { (x[it] - mean) / std }

        // 2. averaging filter (BioWatch smoothing, 5-sample window)
        val smooth = movingAverage(norm, 5)

        // 3. FFT with Hann window + zero padding
        val nfft = maxOf(SignalFilters.nextPow2(smooth.size), 16384)
        val window = SignalFilters.hann(smooth.size)
        var winSum = 0.0
        for (w in window) winSum += w
        val re = FloatArray(nfft)
        val im = FloatArray(nfft)
        for (i in smooth.indices) re[i] = (smooth[i] * window[i]).toFloat()

        SignalFilters.fft(re, im)

        // 4. max amplitude within 0.13-0.66 Hz + band power share
        val binHz = fs / nfft
        val loBin = (0.13f / binHz).toInt().coerceAtLeast(1)
        val hiBin = (0.66f / binHz).toInt().coerceAtMost(nfft / 2 - 1)

        var maxAmp = 0f
        var maxFreq = 0f
        var bandPower = 0.0
        for (k in loBin..hiBin) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val p = (mag * mag).toDouble()
            bandPower += p
            if (mag > maxAmp) {
                maxAmp = mag
                maxFreq = k * binHz
            }
        }
        if (maxFreq <= 0f) return null

        // Amplitude normalization by window energy (keeps axes comparable
        // across different fs/lengths - same role as BioWatch's raw FFT
        // magnitudes on fixed-length datasets)
        val ampScale = 2.0 / winSum
        val maxAmpNorm = (maxAmp * ampScale).toFloat()
        val ratio = if (bandPower > 0.0) {
            ((maxAmp * maxAmp).toDouble() / bandPower).toFloat()
        } else 0f

        val brpm = maxFreq * 60f
        if (brpm < 8f || brpm > 40f) return null

        return ComponentResult(brpm, maxAmpNorm, ratio, smooth)
    }

    private fun movingAverage(x: FloatArray, kernel: Int): FloatArray {
        val half = kernel / 2
        val out = FloatArray(x.size)
        var sum = 0.0
        var count = 0
        // O(n) sliding window
        for (i in x.indices) {
            sum += x[i].toDouble()
            count++
            if (i >= kernel) {
                sum -= x[i - kernel].toDouble()
                count--
            }
            if (i >= half) {
                out[i - half] = (sum / count).toFloat()
            }
        }
        for (i in x.size - half until x.size) {
            if (i >= 0) out[i] = x[x.size - 1]
        }
        return out
    }
}
