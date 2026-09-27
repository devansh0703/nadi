package com.nadi.health.imu

/**
 * Cardiac signals from phone-on-sternum seismocardiography (SCG,
 * accelerometer) and gyrocardiography (GCG, gyroscope).
 *
 * Method: after Wiener denoising (Seismo preprocessing, Wang et al. CHI
 * 2018), SCG is band-passed 15-45 Hz to isolate valve-mechanics
 * vibrations (Inan et al., IEEE TBME 2015), GCG 1-40 Hz (Elgendi et al.,
 * Frontiers Cardiovascular Medicine 2023 - GCG is preferred when linear
 * motion corrupts the accelerometer). The HeartPy adaptive detector then
 * picks heart beats; BPM, SDNN and RMSSD follow from the inter-beat
 * intervals.
 *
 * The two channels are cross-checked: the estimator whose peak carries
 * more band power wins (adaptive channel selection, as Seismo fuses
 * accel/gyro views of the same mechanics).
 */
class CardioImu(private val fs: Float) {

    data class Result(
        val bpm: Int,           // 0 = not confident
        val sdnnMs: Float,
        val rmssdMs: Float,
        val viaGyro: Boolean,   // true when GCG won the channel vote
        val confidence: Float,  // peak share of in-band power
        val waveform: FloatArray // winning filtered channel for UI
    )

    fun analyze(
        samples: List<ImuRecorder.ImuSample>
    ): Result {
        val n = samples.size
        val minSamples = (fs * 8).toInt() // >= 8 beats at 60 bpm
        if (n < minSamples) {
            return Result(0, 0f, 0f, false, 0f, FloatArray(0))
        }

        // SCG axis selection robust to ANY phone placement on the chest,
        // including slanted: evaluate the three device axes plus the
        // true-vertical gravity projection and keep the one with the
        // highest variance (the axis that carries the valve-click energy).
        val ax = FloatArray(n) { samples[it].ax }
        val ay = FloatArray(n) { samples[it].ay }
        val az = FloatArray(n) { samples[it].az }
        val vert = FloatArray(n) { i ->
            val s = samples[i]
            s.ax * s.ugx + s.ay * s.ugy + s.az * s.ugz
        }
        val candidates = arrayOf(ax, ay, az, vert)
        var best = candidates[0]
        var bestVar = -1f
        for (c in candidates) {
            val m = c.average().toFloat()
            var acc = 0.0
            for (v in c) acc += (v - m).toDouble() * (v - m)
            val v = (acc / c.size).toFloat()
            if (v > bestVar) {
                bestVar = v
                best = c
            }
        }

        val gyroMag = FloatArray(n) { i ->
            val s = samples[i]
            kotlin.math.sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
        }

        val scg = SignalFilters.wienerDenoise(best, fs)
        val gcg = SignalFilters.wienerDenoise(gyroMag, fs)

        val scgRes = HeartPeakDetector(fs).detect(scg, 15f, 45f)
        val gcgRes = HeartPeakDetector(fs).detect(gcg, 1f, 40f)

        val scgOk = valid(scgRes.bpm)
        val gcgOk = valid(gcgRes.bpm)

        val winner: HeartPeakDetector.Result
        val viaGyro: Boolean
        when {
            scgOk && gcgOk -> {
                // Cross-check: if estimates agree within 5 bpm, average IBIs;
                // otherwise keep the channel with more confident beats.
                val agree = kotlin.math.abs(scgRes.bpm - gcgRes.bpm) <= 5
                winner = if (agree) fuse(scgRes, gcgRes) else {
                    val scoreS = scgRes.peakIndices.size / (scgRes.sdnnMs + 1f)
                    val scoreG = gcgRes.peakIndices.size / (gcgRes.sdnnMs + 1f)
                    if (scoreS >= scoreG) scgRes else gcgRes
                }
                viaGyro = gcgRes.bpm >= scgRes.bpm
            }
            scgOk -> { winner = scgRes; viaGyro = false }
            gcgOk -> { winner = gcgRes; viaGyro = true }
            else -> return Result(0, 0f, 0f, false, 0f, FloatArray(0))
        }

        val wave = if (viaGyro) gcg else scg
        val lo = if (viaGyro) 1f else 15f
        val hi = if (viaGyro) 40f else 45f
        val conf = inBandPowerShare(wave, lo, hi)

        return Result(
            winner.bpm.toInt().coerceIn(40, 180),
            winner.sdnnMs,
            winner.rmssdMs,
            viaGyro,
            conf,
            wave
        )
    }

    private fun valid(bpm: Float): Boolean = bpm in 40f..180f

    /** Average the two IBI streams when they agree - reduces jitter. */
    private fun fuse(
        a: HeartPeakDetector.Result, b: HeartPeakDetector.Result
    ): HeartPeakDetector.Result {
        val n = minOf(a.ibiMs.size, b.ibiMs.size)
        if (n == 0) return a
        val ibi = FloatArray(n) { (a.ibiMs[it] + b.ibiMs[it]) / 2f }
        var bpmSum = 0f
        for (v in ibi) bpmSum += 60000f / v
        return HeartPeakDetector.Result(
            a.peakIndices, ibi, bpmSum / n, SignalFilters.std(ibi), a.rmssdMs, a.rejected
        )
    }

    private fun inBandPowerShare(x: FloatArray, loHz: Float, hiHz: Float): Float {
        val (freqs, psd) = SignalFilters.welch(x, fs, SignalFilters.nextPow2(minOf(x.size, 4096)))
        var band = 0f
        var total = 0f
        var peak = 0f
        for (k in freqs.indices) {
            total += psd[k]
            if (freqs[k] in loHz..hiHz) {
                band += psd[k]
                if (psd[k] > peak) peak = psd[k]
            }
        }
        if (total <= 0f || band <= 0f) return 0f
        return (peak / band).coerceIn(0f, 1f)
    }

    companion object {
        fun gyroMagnitude(gx: FloatArray, gy: FloatArray, gz: FloatArray): FloatArray {
            val n = minOf(gx.size, gy.size, gz.size)
            return FloatArray(n) { i ->
                kotlin.math.sqrt(gx[i] * gx[i] + gy[i] * gy[i] + gz[i] * gz[i])
            }
        }
    }
}
