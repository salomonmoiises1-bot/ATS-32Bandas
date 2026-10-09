package com.sjbstudio.eq32.state

sealed class BiquadConfig {
    data class LowShelf(val freq: Double, val gainDb: Float, val s: Double = 1.0) : BiquadConfig()
    data class Peaking(val freq: Double, val q: Double, val gainDb: Float) : BiquadConfig()
    data class HighShelf(val freq: Double, val gainDb: Float, val s: Double = 1.0) : BiquadConfig()
}

data class EqState32WithMDRC(
    val fixedGains: FloatArray = FloatArray(32),
    val toneGains: FloatArray = FloatArray(3) { 0f }, // [0] 100Hz LowShelf, [1] 1000Hz Peaking, [2] 8000Hz HighShelf
    val mdrcEnabled: Boolean = false,
    val mdrcThreshold: FloatArray = FloatArray(4) { -20f }, // dB: -40 to 0
    val mdrcRatio: FloatArray = FloatArray(4) { 2f },      // 1 to 20
    val mdrcAttack: FloatArray = FloatArray(4) { 20f },    // ms: 1 to 100
    val mdrcRelease: FloatArray = FloatArray(4) { 200f },  // ms: 10 to 500
    val mdrcMakeup: FloatArray = FloatArray(4) { 0f },     // dB: 0 to 18
    val isEnabled: Boolean = true
) {
    companion object {
        // Standard 1/3-octave ISO 32-band log-spaced frequencies from 20 Hz to 20,000 Hz
        val FREQS = doubleArrayOf(
            20.0, 24.99, 31.23, 39.03, 48.77, 60.94, 76.15, 95.16, 118.91, 148.59,
            185.68, 232.03, 289.95, 362.32, 452.76, 565.77, 707.0, 883.47, 1103.99, 1379.56,
            1723.91, 2154.21, 2691.92, 3363.85, 4203.5, 5252.73, 6563.86, 8202.25, 10249.61, 12808.01,
            16005.0, 20000.0
        )
        const val Q = 4.318

        // MDRC Band Split Crossover Frequencies (4 bands)
        val MDRC_SPLITS = doubleArrayOf(120.0, 1000.0, 6000.0)
    }

    /**
     * Analytic response for the graph only: 32 EQ controls plus three tone controls.
     * The live Android backend converts this combined response into exactly 32
     * physical Pre-EQ bands; these 35 curve components are not 35 DSP bands.
     */
    fun toBiquads(fs: Int = 48000): List<BiquadConfig> {
        val list = mutableListOf<BiquadConfig>()
        if (toneGains.getOrElse(0) { 0f } != 0f) list.add(BiquadConfig.Peaking(120.0, 0.707, toneGains[0]))
        if (toneGains.getOrElse(1) { 0f } != 0f) list.add(BiquadConfig.Peaking(1000.0, 0.707, toneGains[1]))
        if (toneGains.getOrElse(2) { 0f } != 0f) list.add(BiquadConfig.Peaking(8000.0, 0.707, toneGains[2]))
        FREQS.forEachIndexed { i, f -> list.add(BiquadConfig.Peaking(f, Q, fixedGains.getOrElse(i) { 0f })) }
        return list
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EqState32WithMDRC
        if (!fixedGains.contentEquals(other.fixedGains)) return false
        if (!toneGains.contentEquals(other.toneGains)) return false
        if (mdrcEnabled != other.mdrcEnabled) return false
        if (!mdrcThreshold.contentEquals(other.mdrcThreshold)) return false
        if (!mdrcRatio.contentEquals(other.mdrcRatio)) return false
        if (!mdrcAttack.contentEquals(other.mdrcAttack)) return false
        if (!mdrcRelease.contentEquals(other.mdrcRelease)) return false
        if (!mdrcMakeup.contentEquals(other.mdrcMakeup)) return false
        if (isEnabled != other.isEnabled) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fixedGains.contentHashCode()
        result = 31 * result + toneGains.contentHashCode()
        result = 31 * result + mdrcEnabled.hashCode()
        result = 31 * result + mdrcThreshold.contentHashCode()
        result = 31 * result + mdrcRatio.contentHashCode()
        result = 31 * result + mdrcAttack.contentHashCode()
        result = 31 * result + mdrcRelease.contentHashCode()
        result = 31 * result + mdrcMakeup.contentHashCode()
        result = 31 * result + isEnabled.hashCode()
        return result
    }
}
