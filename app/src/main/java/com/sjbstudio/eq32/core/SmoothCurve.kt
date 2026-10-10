package com.sjbstudio.eq32.core

import com.sjbstudio.eq32.state.EqState32WithMDRC
import kotlin.math.ln

/**
 * "Smooth" graphic-EQ curve: a monotone cubic (Fritsch-Butland) interpolation, over log-frequency,
 * that passes EXACTLY through the 32 slider values.
 *
 * Unlike summing 32 peaking bells, neighbouring sliders do not add up: all sliders at +6 dB give a
 * flat +6 dB, a single slider gives its own value at its own frequency, and the curve never
 * overshoots between two sliders. Outside 20 Hz..20 kHz the end values are held.
 */
class SmoothCurve(gains: FloatArray) {
    private val n = EqState32WithMDRC.FREQS.size
    private val x = DoubleArray(n) { ln(EqState32WithMDRC.FREQS[it]) }
    private val y = DoubleArray(n) { gains.getOrElse(it) { 0f }.toDouble() }
    private val h = DoubleArray(n - 1) { x[it + 1] - x[it] }
    private val m = DoubleArray(n)

    init {
        val d = DoubleArray(n - 1) { (y[it + 1] - y[it]) / h[it] }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) {
            m[k] = if (d[k - 1] * d[k] <= 0.0) 0.0 else {
                val w1 = 2 * h[k] + h[k - 1]
                val w2 = h[k] + 2 * h[k - 1]
                (w1 + w2) / (w1 / d[k - 1] + w2 / d[k])
            }
        }
    }

    /** Gain in dB at [freqHz]. */
    fun at(freqHz: Double): Float {
        val t = ln(freqHz.coerceAtLeast(1.0))
        if (t <= x[0]) return y[0].toFloat()
        if (t >= x[n - 1]) return y[n - 1].toFloat()
        var k = 0
        while (k < n - 2 && t > x[k + 1]) k++
        val u = (t - x[k]) / h[k]
        val u2 = u * u
        val u3 = u2 * u
        val v = (2 * u3 - 3 * u2 + 1) * y[k] + (u3 - 2 * u2 + u) * h[k] * m[k] +
            (-2 * u3 + 3 * u2) * y[k + 1] + (u3 - u2) * h[k] * m[k + 1]
        return v.toFloat()
    }
}
