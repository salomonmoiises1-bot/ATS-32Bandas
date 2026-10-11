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
    val isEnabled: Boolean = true,
    val bassBoostDb: Float = 0f, // dB: 0 to 12, low-shelf biquad (same pipeline as tone)
    val bassBoostHz: Float = BASS_BOOST_HZ.toFloat(), // 30 to 200 Hz shelf frequency
    val preampDb: Float = 0f, // -12 to +12 dB input gain ahead of the EQ
    val smoothCurve: Boolean = false, // true: sliders are interpolated (no bell summation); false: classic 32 summed bells
    val dynamicBass: Boolean = false, // bass boost that is tamed on loud peaks (MBC band 1 acts as a dynamic bass stage)
    val limiterThresholdDb: Float = -0.5f, // output limiter threshold, -12 to 0 dB
    val limiterReleaseMs: Float = 50f, // output limiter release, 10 to 300 ms
    val autoHeadroom: Boolean = false, // lower the input gain automatically by the strongest boost
    val bassDetail: Boolean = false, // longer processing frame: finer bass resolution, more latency
    val highPrecision: Boolean = true // Pre+Post EQ interleave: 64 effective stairs from the 32 bands
) {
    companion object {
        // Custom 32-point logarithmic layout spanning 20 Hz to 20,000 Hz (not the ISO 31-band 1/3-octave centre table).
        val FREQS = doubleArrayOf(
            20.0, 24.99, 31.23, 39.03, 48.77, 60.94, 76.15, 95.16, 118.91, 148.59,
            185.68, 232.03, 289.95, 362.32, 452.76, 565.77, 707.0, 883.47, 1103.99, 1379.56,
            1723.91, 2154.21, 2691.92, 3363.85, 4203.5, 5252.73, 6563.86, 8202.25, 10249.61, 12808.01,
            16005.0, 20000.0
        )
        const val Q = 4.318
        const val BASS_BOOST_HZ = 60.0
        const val BASS_BOOST_MIN_HZ = 30f
        const val LIMITER_MIN_DB = -12f
        const val LIMITER_MAX_DB = 0f
        const val LIMITER_RELEASE_MIN_MS = 10f
        const val LIMITER_RELEASE_MAX_MS = 300f
        const val BASS_BOOST_MAX_HZ = 200f
        const val PREAMP_MIN_DB = -12f
        const val PREAMP_MAX_DB = 12f
        const val BASS_BOOST_MAX_DB = 12f

        // MDRC Band Split Crossover Frequencies (4 bands)
        val MDRC_SPLITS = doubleArrayOf(120.0, 1000.0, 6000.0)
    }

    /**
     * Analytic response for the graph only: 32 EQ controls plus three tone controls.
     * The live Android backend converts this combined response into exactly 32
     * physical Pre-EQ bands; these 35 curve components are not 35 DSP bands.
     */
    fun toBiquads(fs: Int = 48000, includeBands: Boolean = true): List<BiquadConfig> {
        val list = mutableListOf<BiquadConfig>()
        if (bassBoostDb != 0f) list.add(BiquadConfig.LowShelf(bassBoostHz.toDouble(), bassBoostDb, 1.0))
        if (toneGains.getOrElse(0) { 0f } != 0f) list.add(BiquadConfig.LowShelf(100.0, toneGains[0], 1.0))
        if (toneGains.getOrElse(1) { 0f } != 0f) list.add(BiquadConfig.Peaking(1000.0, 0.707, toneGains[1]))
        if (toneGains.getOrElse(2) { 0f } != 0f) list.add(BiquadConfig.HighShelf(8000.0, toneGains[2], 1.0))
        if (includeBands) FREQS.forEachIndexed { i, f -> list.add(BiquadConfig.Peaking(f, Q, fixedGains.getOrElse(i) { 0f })) }
        return list
    }

    /** Independent copy (arrays included) so the UI and the service never share mutable arrays. */
    fun deepCopy(): EqState32WithMDRC = copy(
        fixedGains = fixedGains.copyOf(), toneGains = toneGains.copyOf(),
        mdrcThreshold = mdrcThreshold.copyOf(), mdrcRatio = mdrcRatio.copyOf(),
        mdrcAttack = mdrcAttack.copyOf(), mdrcRelease = mdrcRelease.copyOf(),
        mdrcMakeup = mdrcMakeup.copyOf()
    )

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
        if (bassBoostDb != other.bassBoostDb) return false
        if (bassBoostHz != other.bassBoostHz) return false
        if (preampDb != other.preampDb) return false
        if (smoothCurve != other.smoothCurve) return false
        if (dynamicBass != other.dynamicBass) return false
        if (limiterThresholdDb != other.limiterThresholdDb) return false
        if (limiterReleaseMs != other.limiterReleaseMs) return false
        if (autoHeadroom != other.autoHeadroom) return false
        if (bassDetail != other.bassDetail) return false
        if (highPrecision != other.highPrecision) return false
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
        result = 31 * result + bassBoostDb.hashCode()
        result = 31 * result + bassBoostHz.hashCode()
        result = 31 * result + preampDb.hashCode()
        result = 31 * result + smoothCurve.hashCode()
        result = 31 * result + dynamicBass.hashCode()
        result = 31 * result + limiterThresholdDb.hashCode()
        result = 31 * result + limiterReleaseMs.hashCode()
        result = 31 * result + autoHeadroom.hashCode()
        result = 31 * result + bassDetail.hashCode()
        result = 31 * result + highPrecision.hashCode()
        return result
    }
}
