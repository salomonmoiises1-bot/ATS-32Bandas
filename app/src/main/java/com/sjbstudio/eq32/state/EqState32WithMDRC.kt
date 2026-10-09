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
        const val Q = 1.4142

        // MDRC Band Split Crossover Frequencies (4 bands)
        val MDRC_SPLITS = doubleArrayOf(120.0, 1000.0, 6000.0)
    }

    /**
     * Converts current state into 35 Bi-quadratic filter configurations:
     * 3 macro Tone filters + 32 parametric peaking bands.
     */
    fun toBiquads(fs: Int = 48000): List<BiquadConfig> {
        val list = mutableListOf<BiquadConfig>()
        // Macro Tone controls
        list.add(BiquadConfig.LowShelf(100.0, toneGains[0]))
        list.add(BiquadConfig.Peaking(1000.0, Q, toneGains[1]))
        list.add(BiquadConfig.HighShelf(8000.0, toneGains[2]))

        // 32 Precision ISO Bands
        FREQS.forEachIndexed { i, f ->
            val gain = if (i < fixedGains.size) fixedGains[i] else 0f
            list.add(BiquadConfig.Peaking(f, Q, gain))
        }
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
