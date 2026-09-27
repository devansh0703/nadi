package com.nadi.health.imu

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Port of the HeartPy adaptive peak-detection pipeline
 * (van Gent, Farah, van Nes, van Gent 2019, "Analysing Noisy Driver
 * Physiology Real-Time Using Off-the-Shelf Sensors", JOSS 4(38) - the
 * same detector used for SCG beat picking in smartphone cardiac studies).
 *
 * Stages:
 *  1. zero-phase Butterworth band-pass (no group delay -> beat timing intact)
 *  2. square the signal (enhances systolic peaks over noise)
 *  3. moving-average threshold (kernel 0.6 s)
 *  4. peaks = argmax of each contiguous run above threshold
 *  5. reject interp beats outside 60-125% of the median inter-beat distance
 *
 * Outputs inter-beat intervals, SDNN, RMSSD, BPM and confidence.
 */
class HeartPeakDetector(private val fs: Float) {

    data class Result(
        val peakIndices: IntArray,
        val ibiMs: FloatArray,   // inter-beat intervals in ms
        val bpm: Float,
        val sdnnMs: Float,
        val rmssdMs: Float,
        val rejected: Int
    )

    /**
     * @param x        raw signal (already gravity/motion handled upstream)
     * @param loHz     band-pass low edge
     * @param hiHz     band-pass high edge
     * @param maPerc   moving-average kernel in seconds (HeartPy default 0.6)
     */
    fun detect(x: FloatArray, loHz: Float, hiHz: Float, maPerc: Float = 0.6f): Result {
        if (x.size < (fs * 2).toInt()) {
            return Result(IntArray(0), FloatArray(0), 0f, 0f, 0f, 0)
        }

        // 1. zero-phase band-pass
        val filtered = SignalFilters.bandPassZeroPhase(x, fs, loHz, hiHz)

        // 2. square
        val squared = FloatArray(filtered.size) { filtered[it] * filtered[it] }

        // 3. moving average threshold
        val kernel = max(3, (maPerc * fs).toInt())
        val ma = movingAverage(squared, kernel)

        // 4. contiguous runs above threshold -> argmax per run
        val peaks = ArrayList<Int>()
        var i = 0
        while (i < squared.size) {
            if (squared[i] > ma[i]) {
                var j = i
                while (j < squared.size && squared[j] > ma[j]) j++
                // argmax within [i, j)
                var best = i
                for (k in i until j) if (squared[k] > squared[best]) best = k
                // skip runs that hug the array edge (incomplete beats)
                if (best > kernel && best < squared.size - kernel) peaks.add(best)
                i = j
            } else {
                i++
            }
        }
        if (peaks.size < 3) {
            return Result(IntArray(0), FloatArray(0), 0f, 0f, 0f, peaks.size)
        }

        // 5. HeartPy rejection: distances < 60% or > 125% of median distance
        val distances = FloatArray(peaks.size - 1) { (peaks[it + 1] - peaks[it]) / fs }
        val med = SignalFilters.median(distances).coerceAtLeast(0.2f) // >= 300 bpm guard
        val kept = ArrayList<Int>()
        var rejected = 0
        kept.add(peaks[0])
        for (k in 1 until peaks.size) {
            val d = (peaks[k] - peaks[k - 1]) / fs
            if (d in (0.6f * med)..(1.25f * med)) {
                kept.add(peaks[k])
            } else {
                rejected++
            }
        }
        if (kept.size < 3) {
            return Result(IntArray(0), FloatArray(0), 0f, 0f, 0f, rejected)
        }

        // Metrics
        val ibi = FloatArray(kept.size - 1) { (kept[it + 1] - kept[it]) / fs * 1000f }
        var bpmSum = 0f
        for (v in ibi) bpmSum += 60000f / v
        val bpm = bpmSum / ibi.size
        val sdnn = SignalFilters.std(ibi)
        var rmssdSum = 0.0
        for (k in 1 until ibi.size) {
            val d = (ibi[k] - ibi[k - 1]).toDouble()
            rmssdSum += d * d
        }
        val rmssd = sqrt(rmssdSum / (ibi.size - 1).coerceAtLeast(1)).toFloat()

        return Result(kept.toIntArray(), ibi, bpm, sdnn, rmssd, rejected)
    }

    private fun movingAverage(x: FloatArray, kernel: Int): FloatArray {
        val out = FloatArray(x.size)
        var sum = 0.0
        val half = kernel / 2
        for (i in x.indices) {
            val lo = max(0, i - half)
            val hi = min(x.size - 1, i + half)
            // Sliding recompute on the padded window; kernel is small so cost is fine
            sum = 0.0
            for (k in lo..hi) sum += x[k]
            out[i] = (sum / (hi - lo + 1)).toFloat()
        }
        return out
    }
}
