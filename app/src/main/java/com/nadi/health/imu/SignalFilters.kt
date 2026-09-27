package com.nadi.health.imu

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Signal-processing toolbox for IMU-derived physiological signals.
 *
 * Implementations follow the algorithms used in the referenced open-source
 * projects:
 *  - HeartPy (van Gent et al., 2019, JOSS) - analysis pipeline reference
 *  - berndporr/iirj - RBJ biquad Butterworth sections (used by HealthWatcher)
 *  - JTransforms-style radix-2 iterative FFT (public-domain style algorithm)
 *
 * All filters are stateful per instance; create a fresh one per measurement
 * window or call reset() between windows.
 */
object SignalFilters {

    /**
     * Butterworth band-pass, 4th order (two cascaded RBJ biquad sections
     * per edge), implemented as forward-backward (zero-phase) filtering -
     * the same filtfilt trick HeartPy applies to PPG before peak picking.
     * Zero-phase matters for SCG/GCG beat timing (no group delay).
     */
    fun bandPassZeroPhase(x: FloatArray, fs: Float, loHz: Float, hiHz: Float): FloatArray {
        val forward = bandPass(x, fs, loHz, hiHz)
        val reversed = FloatArray(forward.size) { forward[forward.size - 1 - it] }
        val backward = bandPass(reversed, fs, loHz, hiHz)
        return FloatArray(backward.size) { backward[backward.size - 1 - it] }
    }

    /** Causal Butterworth band-pass (4th order) via two cascaded biquads per edge. */
    fun bandPass(x: FloatArray, fs: Float, loHz: Float, hiHz: Float): FloatArray {
        // Guard against impossible band edges on short windows
        val nyquist = fs / 2f
        val lo = min(loHz, nyquist * 0.9f).coerceAtLeast(0.01f)
        val hi = min(hiHz, nyquist * 0.95f).coerceAtLeast(lo * 1.2f)

        var y = x
        repeat(2) {
            y = cascade(y, Biquad.highPass(lo, fs), Biquad.highPass(lo, fs))
        }
        repeat(2) {
            y = cascade(y, Biquad.lowPass(hi, fs), Biquad.lowPass(hi, fs))
        }
        return y
    }

    /** Causal Butterworth low-pass (4th order). */
    fun lowPass(x: FloatArray, fs: Float, fcHz: Float): FloatArray {
        var y = x
        repeat(2) {
            y = cascade(y, Biquad.lowPass(fcHz, fs), Biquad.lowPass(fcHz, fs))
        }
        return y
    }

    private fun cascade(x: FloatArray, vararg stages: Biquad): FloatArray {
        var y = x
        for (s in stages) {
            val out = FloatArray(y.size)
            for (i in y.indices) out[i] = s.process(y[i])
            y = out
        }
        return y
    }

    /**
     * Wiener-style denoising via STFT magnitude shrinkage - the "cleanIMU"
     * preprocessing used before SCG beat detection in the Seismo pipeline.
     * Signal is assumed quasi-stationary across short frames.
     */
    fun wienerDenoise(x: FloatArray, fs: Float, frameMs: Int = 50): FloatArray {
        val frame = max(64, (fs * frameMs / 1000f).toInt().let { nextPow2(it) })
        val hop = frame / 2
        if (x.size < frame) return x.copyOf()

        val window = hann(frame)
        val out = FloatArray(x.size)
        val norm = FloatArray(x.size)

        var start = 0
        while (start + frame <= x.size) {
            val seg = FloatArray(frame) { x[start + it] * window[it] }
            val re = seg.copyOf()
            val im = FloatArray(frame)
            fft(re, im)

            // Per-bin local variance across 2 bins each side -> Wiener gain
            val mag = FloatArray(frame / 2 + 1) { sqrt(re[it] * re[it] + im[it] * im[it]) }
            val cleaned = FloatArray(mag.size)
            for (k in mag.indices) {
                val k0 = max(0, k - 2)
                val k1 = min(mag.size - 1, k + 2)
                var sum = 0f
                for (j in k0..k1) sum += mag[j] * mag[j]
                val localMean = sum / (k1 - k0 + 1)
                val gain = localMean / (localMean + mag[k] * mag[k] + 1e-9f).let {
                    // Clamp gain so quiet bins are not crushed to zero
                    it.coerceIn(0.15f, 1f)
                }
                cleaned[k] = mag[k] * gain
            }
            // Rebuild with cleaned magnitudes, original phase
            val rr = FloatArray(frame)
            val ii = FloatArray(frame)
            for (k in 1 until frame / 2) {
                val m = cleaned[k]
                val ph = Math.atan2(im[k].toDouble(), re[k].toDouble())
                rr[k] = (m * Math.cos(ph)).toFloat()
                ii[k] = (m * Math.sin(ph)).toFloat()
                rr[frame - k] = rr[k]
                ii[frame - k] = -ii[k]
            }
            ifft(rr, ii)
            for (i in 0 until frame) {
                out[start + i] += rr[i] * window[i]
                norm[start + i] += window[i] * window[i]
            }
            start += hop
        }
        for (i in out.indices) if (norm[i] > 1e-6f) out[i] /= norm[i]
        return out
    }

    /**
     * Welch power spectral density with Hann window and 50% overlap.
     * Returns (frequenciesHz, powerDensity) for bins 0..nyquist.
     */
    fun welch(x: FloatArray, fs: Float, segmentLen: Int = 0): Pair<FloatArray, FloatArray> {
        val seg = if (segmentLen > 0) segmentLen else min(x.size, nextPow2(x.size))
        val hop = seg / 2
        val window = hann(seg)
        val nBins = seg / 2 + 1
        val power = DoubleArray(nBins)
        var nSeg = 0

        var start = 0
        while (start + seg <= x.size) {
            val re = FloatArray(seg) { x[start + it] * window[it] }
            val im = FloatArray(seg)
            fft(re, im)
            var winPow = 0.0
            for (w in window) winPow += (w * w).toDouble()
            for (k in 0 until nBins) {
                val m = (re[k] * re[k] + im[k] * im[k]).toDouble()
                power[k] += m / (fs.toDouble() * winPow)
            }
            nSeg++
            start += hop
        }
        if (nSeg == 0) {
            // Signal shorter than one segment: single window, zero padded
            val re = FloatArray(seg) { if (it < x.size) x[it] * window[it] else 0f }
            val im = FloatArray(seg)
            fft(re, im)
            nSeg = 1
            for (k in 0 until nBins) {
                power[k] += (re[k] * re[k] + im[k] * im[k]).toDouble() / (fs.toDouble() * window.sum().toDouble())
            }
        }
        val freqs = FloatArray(nBins) { k -> k * fs / seg.toFloat() }
        val psd = FloatArray(nBins) { k -> (power[k] / nSeg).toFloat() }
        return freqs to psd
    }

    /** Hann window of length n. */
    fun hann(n: Int): FloatArray = FloatArray(n) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))).toFloat()
    }

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** In-place iterative radix-2 FFT (Cooley-Tukey, public-domain algorithm). */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT length must be power of 2" }
        // Bit reversal
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun ifft(re: FloatArray, im: FloatArray) {
        val n = re.size
        for (i in im.indices) im[i] = -im[i]
        fft(re, im)
        val inv = 1f / n
        for (i in re.indices) {
            re[i] *= inv
            im[i] = -im[i] * inv
        }
    }

    /** Standard deviation helper. */
    fun std(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        val mean = x.average().toFloat()
        var acc = 0.0
        for (v in x) acc += (v - mean).toDouble() * (v - mean)
        return sqrt((acc / x.size)).toFloat()
    }

    /** Median of a float array (copy-safe). */
    fun median(src: FloatArray): Float {
        if (src.isEmpty()) return 0f
        val a = src.copyOf()
        a.sort()
        val n = a.size
        return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) / 2f
    }
}

/**
 * Minimal RBJ audio-EQ-cookbook biquad, Butterworth Q. Direct-form-1,
 * stateful. Same math as berndporr/iirj Butterworth sections.
 */
class Biquad(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        fun highPass(fc: Float, fs: Float): Biquad {
            val w0 = 2f * PI.toFloat() * fc / fs
            val c = cos(w0); val s = sin(w0)
            val alpha = s / (2f * 0.70710678f)
            val a0 = 1f + alpha
            return Biquad(
                ((1f + c) / 2f) / a0, (-(1f + c)) / a0, ((1f + c) / 2f) / a0,
                (-2f * c) / a0, (1f - alpha) / a0
            )
        }

        fun lowPass(fc: Float, fs: Float): Biquad {
            val w0 = 2f * PI.toFloat() * fc / fs
            val c = cos(w0); val s = sin(w0)
            val alpha = s / (2f * 0.70710678f)
            val a0 = 1f + alpha
            return Biquad(
                ((1f - c) / 2f) / a0, (1f - c) / a0, ((1f - c) / 2f) / a0,
                (-2f * c) / a0, (1f - alpha) / a0
            )
        }
    }
}
