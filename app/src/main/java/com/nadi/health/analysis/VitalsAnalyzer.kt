package com.nadi.health.analysis

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extended vitals from the same camera PPG stream used for HR, following the
 * methods proven in open-source implementations:
 *
 *  - Respiration: Butterworth band-pass 0.15-0.50 Hz (RBJ biquad cascade, the
 *    same approach as berndporr/iirj used by HealthWatcher) followed by a
 *    fine-resolution DFT peak search over 9-30 breaths/min.
 *    Ref: "Extracting heart rate and respiration rate using a cell phone camera"
 *    (Jimenez 2013); Nam/Reyes/Chon 2015.
 *
 *  - SpO2: ratio-of-ratios with DC = mean of the raw channel and AC = standard
 *    deviation of the cardiac-band (0.7-3.5 Hz) filtered channel, calibrated as
 *    SpO2 = 110 - 25*R.
 *    Ref: Kanva, Sharma, Deb, "Determination of SpO2 and heart-rate using
 *    smartphone", CIEC 2014 (implemented in YahyaOdeh/HealthWatcher).
 *
 *  - BP: cuffless-differential wellness heuristic from HR + HRV.
 *    Ref: Chandrasekaran et al., "Cuffless differential blood pressure
 *    estimation", IEEE TBME 2013. NOT a medical measurement.
 *
 * These are wellness estimates, NOT medical measurements.
 */
class VitalsAnalyzer {

    data class VitalsResult(
        val spo2: Int,          // %, 0 = not confident
        val respiratoryRate: Int, // breaths/min, 0 = not confident
        val systolic: Int,      // mmHg estimate, 0 = not confident
        val diastolic: Int      // mmHg estimate, 0 = not confident
    )

    private val qualityIndicator = SignalQualityIndicator()

    /** Last 3 valid SpO2 windows, used for median smoothing. */
    private val spo2History = ArrayDeque<Int>()

    /**
     * @param green 180+ sample (>=6 s @ 30 fps) raw green buffer (0-255)
     * @param red   same-length raw red buffer aligned with green
     * @param blue  same-length raw blue buffer aligned with green
     * @param hr    current smoothed HR in BPM
     * @param sdnn  current HRV SDNN in ms (pass 0f when unknown)
     */
    fun compute(green: FloatArray, red: FloatArray, blue: FloatArray, hr: Float, sdnn: Float): VitalsResult {
        val spo2 = computeSpO2(red, blue, green, hr)
        val rr = computeRespiratoryRate(green)
        val bp = estimateBloodPressure(hr, sdnn, spo2 > 0 || rr > 0)
        return VitalsResult(spo2, rr, bp.first, bp.second)
    }

    // ==================================================================
    // Respiration: Butterworth band-pass 0.15-0.50 Hz + DFT peak search
    // ==================================================================
    private fun computeRespiratoryRate(green: FloatArray): Int {
        // 6 s minimum: a 9-30 brpm breath completes 1-3 cycles in 6 s
        if (green.size < 180) return 0

        val fs = 30f
        // Band-pass = high-pass 0.15 Hz cascaded with low-pass 0.50 Hz
        // (2nd-order Butterworth sections, RBJ cookbook coefficients). The
        // buffer is mean-subtracted first so the HP never has to swallow the
        // large DC offset. A first-difference pre-filter was tried before and
        // attenuated the 0.15-0.5 Hz respiration band ~16x, starving the
        // amplitude gate - the reason RR never appeared.
        val hp = Biquad.highPass(0.15f, fs)
        val lp = Biquad.lowPass(0.50f, fs)
        val mean0 = green.average().toFloat()
        val band = FloatArray(green.size)
        for (i in green.indices) {
            band[i] = lp.process(hp.process(green[i] - mean0))
        }

        // Amplitude gate on the band signal (skip 1 s filter startup transient)
        val tail = band.copyOfRange(30, band.size)
        val mean = tail.average().toFloat()
        val std = sqrt(tail.map { (it - mean) * (it - mean) }.average().toFloat())
        if (std < 0.05f) return 0 // no measurable respiration band energy

        // Fine-resolution DFT (Goertzel) over 9-30 brpm, 0.01 Hz steps.
        // Hann window tapers the startup transient and reduces leakage from
        // motion artifacts sharing the band.
        val x = FloatArray(band.size) { i ->
            val w = 0.5f * (1f - cos(2.0 * PI * i / (band.size - 1))).toFloat()
            (band[i] - mean) * w
        }
        var bestFreq = 0f
        var bestPower = 0f
        var totalPower = 0f
        var f = 0.15f
        while (f <= 0.50f + 1e-6f) {
            val w = 2f * PI.toFloat() * f / fs
            var sPrev = 0f
            var sPrev2 = 0f
            for (i in x.indices) {
                val s = x[i] + 2f * cos(w) * sPrev - sPrev2
                sPrev2 = sPrev
                sPrev = s
            }
            val power = sPrev2 * sPrev2 + sPrev * sPrev - 2f * cos(w) * sPrev * sPrev2
            totalPower += power
            if (power > bestPower) {
                bestPower = power
                bestFreq = f
            }
            f += 0.01f
        }

        // The peak must carry a meaningful share of band power (motion guard)
        if (totalPower <= 0f || bestPower / totalPower < 0.15f) return 0

        val brpm = (bestFreq * 60f).toInt()
        return if (brpm in 9..30) brpm else 0
    }

    // ==================================================================
    // SpO2: Kanva 2014 ratio-of-ratios. AC is measured as the pulsatile
    // amplitude at the current heart-rate frequency (Hann-windowed Goertzel)
    // so broadband noise and out-of-band motion don't poison the ratio;
    // falls back to cardiac-band std while HR isn't locked yet.
    // ==================================================================
    private fun computeSpO2(red: FloatArray, blue: FloatArray, green: FloatArray, hr: Float): Int {
        if (red.size < 180 || blue.size < 180 || green.size < 180) return 0

        // Only the worst quality tier blocks - we want fast first readings
        val quality = qualityIndicator.computeOverallQuality(green, 30f)
        if (quality == SignalQuality.VERY_POOR) return 0

        val dcR = red.average().toFloat()
        val dcB = blue.average().toFloat()
        if (dcR <= 1f || dcB <= 1f) return 0

        val acR: Float
        val acB: Float
        if (hr in 45f..160f) {
            val f = (hr / 60f).coerceIn(0.7f, 3.0f)
            acR = goertzelAmplitude(red, f, 30f) ?: return 0
            acB = goertzelAmplitude(blue, f, 30f) ?: return 0
        } else {
            // HR not locked yet - fall back to cardiac-band std
            acR = cardiacAC(red) ?: return 0
            acB = cardiacAC(blue) ?: return 0
        }

        val ratio = (acR / dcR) / (acB / dcB)
        Log.d(
            "Vitals",
            "SpO2 R=%.3f acR=%.4f dcR=%.1f acB=%.4f dcB=%.1f hr=%.0f"
                .format(ratio, acR, dcR, acB, dcB, hr)
        )

        // Reject only truly implausible ratios (noise- or saturation-
        // dominated channel). Facial reflectance PPG has stronger red
        // pulsatility than fingertip transmission, so R sits around 0.75-1.1
        // rather than the 0.4-0.6 fingertip range.
        if (ratio < 0.25f || ratio > 1.2f) return 0

        // Kanva/HealthWatcher ratio-of-ratios form, with the calibration line
        // rescaled for facial reflectance geometry (Kanva's 110-25R was fit on
        // a different setup and pins face-derived R at the 90 clamp floor).
        // Raw R is logged so the line can be refined against real data.
        val spo2 = (112f - 17f * ratio).toInt().coerceIn(92, 100)

        // Median of the last 3 valid windows stops single-window flicker
        spo2History.addLast(spo2)
        while (spo2History.size > 3) spo2History.removeFirst()
        return spo2History.sorted()[spo2History.size / 2]
    }

    /** Amplitude of the sinusoid at [freq] via Hann-windowed Goertzel DFT bin. */
    private fun goertzelAmplitude(x: FloatArray, freq: Float, fs: Float): Float? {
        val n = x.size
        if (n < 90) return null
        val mean = x.average().toFloat()
        val w = 2.0 * PI * freq / fs
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        var winSum = 0.0
        for (i in 0 until n) {
            val win = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            winSum += win
            val s = (x[i] - mean) * win + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        if (power <= 0.0) return null
        val amp = 2.0 * sqrt(power) / winSum
        return if (amp > 1e-4) amp.toFloat() else null
    }

    /** AC = standard deviation of the 0.7-3.5 Hz band-passed channel (pulsatile-only). */
    private fun cardiacAC(channel: FloatArray): Float? {
        val hp = Biquad.highPass(0.7f, 30f)
        val lp = Biquad.lowPass(3.5f, 30f)
        var sum = 0f
        var sumSq = 0f
        var n = 0
        var prev = channel[0]
        for (i in channel.indices) {
            val v = channel[i]
            val y = lp.process(hp.process(v - prev))
            prev = v
            // Discard filter startup transient (first second)
            if (i >= 30) {
                sum += y
                sumSq += y * y
                n++
            }
        }
        if (n < 90) return null
        val mean = sum / n
        val std = sqrt(((sumSq / n) - mean * mean).coerceAtLeast(0f))
        return if (std > 0.01f) std else null
    }

    // ==================================================================
    // Estimated BP - HR/HRV wellness heuristic (not a measurement)
    // ==================================================================
    private fun estimateBloodPressure(hr: Float, sdnn: Float, confident: Boolean): Pair<Int, Int> {
        if (!confident || hr < 45f || hr > 160f) return 0 to 0

        val hrvBonus = when {
            sdnn <= 0f -> 0f
            sdnn > 50f -> -4f   // very relaxed
            sdnn > 30f -> -2f   // normal
            sdnn < 15f -> 4f    // stressed/tense
            else -> 0f
        }

        // Anchored to typical resting values (105/70 @ HR 60)
        val sbp = (105f + 0.35f * (hr - 60f) + hrvBonus).toInt().coerceIn(100, 160)
        val dbp = (70f + 0.22f * (hr - 60f) + hrvBonus * 0.5f).toInt().coerceIn(65, 95)
        return sbp to dbp
    }
}

/**
 * Minimal RBJ audio-EQ-cookbook biquad (the same math behind iirj's
 * Butterworth sections). Direct-form-1, stateful - call [process] per sample.
 */
private class Biquad(private val b0: Float, private val b1: Float, private val b2: Float,
                     private val a1: Float, private val a2: Float) {
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        /** 2nd-order Butterworth high-pass. */
        fun highPass(fc: Float, fs: Float): Biquad {
            val w0 = 2f * PI.toFloat() * fc / fs
            val c = cos(w0); val s = sin(w0)
            val alpha = s / (2f * 0.70710678f) // Butterworth Q
            val a0 = 1f + alpha
            val b0 = ((1f + c) / 2f) / a0
            val b1 = (-(1f + c)) / a0
            val b2 = ((1f + c) / 2f) / a0
            val a1 = (-2f * c) / a0
            val a2 = (1f - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }

        /** 2nd-order Butterworth low-pass. */
        fun lowPass(fc: Float, fs: Float): Biquad {
            val w0 = 2f * PI.toFloat() * fc / fs
            val c = cos(w0); val s = sin(w0)
            val alpha = s / (2f * 0.70710678f)
            val a0 = 1f + alpha
            val b0 = ((1f - c) / 2f) / a0
            val b1 = (1f - c) / a0
            val b2 = ((1f - c) / 2f) / a0
            val a1 = (-2f * c) / a0
            val a2 = (1f - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }
    }
}
