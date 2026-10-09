package com.sjbstudio.eq32.core

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.DynamicsProcessing.Config
import android.media.audiofx.DynamicsProcessing.Eq
import android.media.audiofx.DynamicsProcessing.EqBand
import android.media.audiofx.DynamicsProcessing.Mbc
import android.media.audiofx.DynamicsProcessing.MbcBand
import android.media.audiofx.DynamicsProcessing.Limiter
import android.util.Log
import com.sjbstudio.eq32.state.BiquadConfig
import com.sjbstudio.eq32.state.EqState32WithMDRC
import kotlin.math.log10
import kotlin.math.pow

class DynamicsProcessingManager {

    companion object {
        private const val TAG = "DynamicsProcMgr"
        private const val DEFAULT_SAMPLE_RATE = 48000
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentSessionId: Int = 0
    private var isEffectEnabled: Boolean = false

    /**
     * Initializes or re-attaches DynamicsProcessing to an AudioSession ID.
     * AudioSession 0 targets global mix on supporting chipsets (Snapdragon/MediaTek/Pixel);
     * specific session IDs target individual apps (Spotify, Deezer, etc.)
     */
    fun attachToSession(sessionId: Int, state: EqState32WithMDRC): Boolean {
        try {
            release()
            currentSessionId = sessionId

            val config = buildDynamicsConfig(state)
            dynamicsProcessing = DynamicsProcessing(0, sessionId, config).apply {
                enabled = state.isEnabled
            }
            isEffectEnabled = state.isEnabled
            Log.d(TAG, "Attached DynamicsProcessing to AudioSession: $sessionId successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach DynamicsProcessing to session $sessionId: ${e.message}", e)
            dynamicsProcessing = null
            return false
        }
    }

    /**
     * Builds complete DSP pipeline configuration:
     * Pre-EQ (35 bands or logarithmically downmixed) -> MDRC (4-band MBC) -> Post-EQ -> Limiter
     */
    private fun buildDynamicsConfig(state: EqState32WithMDRC): Config {
        val biquads = state.toBiquads(DEFAULT_SAMPLE_RATE)
        val bandCount = biquads.size // 35 bands

        // 1. Configure Pre-EQ
        val preEq = Eq(true, true, bandCount)
        biquads.forEachIndexed { index, bq ->
            val freq = when (bq) {
                is BiquadConfig.LowShelf -> bq.freq.toFloat()
                is BiquadConfig.Peaking -> bq.freq.toFloat()
                is BiquadConfig.HighShelf -> bq.freq.toFloat()
            }
            val gain = when (bq) {
                is BiquadConfig.LowShelf -> bq.gainDb
                is BiquadConfig.Peaking -> bq.gainDb
                is BiquadConfig.HighShelf -> bq.gainDb
            }
            val eqBand = EqBand(true, freq, gain)
            preEq.setBand(index, eqBand)
        }

        // 2. Configure Multi-Band Dynamic Range Compression (MDRC - 4 bands)
        val mbc = Mbc(state.mdrcEnabled, true, 4)
        if (state.mdrcEnabled) {
            val cutoffs = floatArrayOf(120f, 1000f, 6000f, 20000f)
            for (band in 0 until 4) {
                val threshold = state.mdrcThreshold[band]
                val ratio = state.mdrcRatio[band]
                val attack = state.mdrcAttack[band]
                val release = state.mdrcRelease[band]
                val makeup = state.mdrcMakeup[band]

                val mbcBand = MbcBand(
                    true,
                    cutoffs[band],
                    attack,
                    release,
                    ratio,
                    threshold,
                    0.0f, // knee width
                    0.0f, // noise gate
                    0.0f, // expander ratio
                    0.0f, // pre-gain
                    makeup // post-gain makeup
                )
                mbc.setBand(band, mbcBand)
            }
        }

        // 3. Configure Final Output Peak Limiter (Brickwall anti-clipping protection)
        val limiter = Limiter(
            true,   // inUse
            true,   // enabled
            0,      // linkGroup
            1.0f,   // attackTime ms
            50.0f,  // releaseTime ms
            20.0f,  // ratio (brickwall)
            -0.5f,  // threshold in dB
            0.0f    // postGain
        )

        // Assembly of full stereo channels
        val builder = Config.Builder(
            Config.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,                  // stereo (2 channels)
            true, bandCount,    // Pre-EQ enabled with 35 bands
            state.mdrcEnabled, 4, // MBC enabled with 4 bands
            false, 0,           // Post-EQ disabled to reduce latency
            true                // Limiter enabled
        )

        builder.setPreferredFrameDuration(5.0f) // 5ms buffer for low latency
        val config = builder.build()

        // Assign channel configurations
        for (ch in 0 until 2) {
            config.setPreEqByChannelIndex(ch, preEq)
            if (state.mdrcEnabled) {
                config.setMbcByChannelIndex(ch, mbc)
            }
            config.setLimiterByChannelIndex(ch, limiter)
        }

        return config
    }

    /**
     * Applies full EqState update without interrupting audio stream.
     */
    fun applyState(state: EqState32WithMDRC) {
        val dp = dynamicsProcessing ?: return
        try {
            dp.enabled = state.isEnabled

            if (!state.isEnabled) return

            val biquads = state.toBiquads(DEFAULT_SAMPLE_RATE)

            // Update Pre-EQ bands dynamically
            for (ch in 0 until 2) {
                biquads.forEachIndexed { index, bq ->
                    val gain = when (bq) {
                        is BiquadConfig.LowShelf -> bq.gainDb
                        is BiquadConfig.Peaking -> bq.gainDb
                        is BiquadConfig.HighShelf -> bq.gainDb
                    }
                    val band = dp.getPreEqBandByChannelIndex(ch, index)
                    band.gain = gain
                    dp.setPreEqBandByChannelIndex(ch, index, band)
                }

                // Update MDRC if enabled
                if (state.mdrcEnabled) {
                    for (b in 0 until 4) {
                        val mbcBand = dp.getMbcBandByChannelIndex(ch, b)
                        mbcBand.threshold = state.mdrcThreshold[b]
                        mbcBand.ratio = state.mdrcRatio[b]
                        mbcBand.attackTime = state.mdrcAttack[b]
                        mbcBand.releaseTime = state.mdrcRelease[b]
                        mbcBand.postGain = state.mdrcMakeup[b]
                        dp.setMbcBandByChannelIndex(ch, b, mbcBand)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic parameter update fallback, rebuilding config: ${e.message}")
            attachToSession(currentSessionId, state)
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        try {
            isEffectEnabled = enabled
            dynamicsProcessing?.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error setting master enabled: ${e.message}")
        }
    }

    fun isEnabled(): Boolean = isEffectEnabled

    fun release() {
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing DynamicsProcessing: ${e.message}")
        } finally {
            dynamicsProcessing = null
        }
    }
}
