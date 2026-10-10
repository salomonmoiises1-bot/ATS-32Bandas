package com.sjbstudio.eq32.core

import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.DynamicsProcessing.Config
import android.media.audiofx.DynamicsProcessing.Eq
import android.media.audiofx.DynamicsProcessing.EqBand
import android.media.audiofx.DynamicsProcessing.Mbc
import android.media.audiofx.DynamicsProcessing.MbcBand
import android.media.audiofx.DynamicsProcessing.Limiter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.sjbstudio.eq32.state.EqState32WithMDRC
import kotlin.math.abs

/**
 * Android DynamicsProcessing adapter aligned to the EQ314-style 32-band path:
 * logical 32-band parametric response -> feature-aware 32-band DP conversion -> MBC -> limiter.
 * Tone controls are included in the target response and do not create bands 33-35.
 */
class DynamicsProcessingManager {
    companion object {
        private const val TAG = "DynamicsProcMgr"
        private const val REQUESTED_EQ_BANDS = 32
        private const val MDRC_BANDS = 4
        private const val EQ_BAND_Q = 4.318
        private const val TONE_Q = 0.707
        private const val DP_FRAME_DURATION_MS = 80f
        private const val MIN_EQ_WRITE_SPACING_MS = 16L
        private const val MIN_CUTOFF_HZ = 20f
        private const val MAX_CUTOFF_HZ = 22000f
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentSessionId: Int = 0
    private var isEffectEnabled: Boolean = false
    private var lastMdrcEnabled: Boolean? = null
    @Volatile private var latestState: EqState32WithMDRC = EqState32WithMDRC()
    @Volatile private var pendingEqWrite: Runnable? = null
    @Volatile private var eqWriteRevision: Long = 0L
    @Volatile private var lastEqWriteMs: Long = 0L
    private val eqWorkerThread = HandlerThread("ATS-Eq32-DpWorker").apply { start() }
    private val eqWorker = Handler(eqWorkerThread.looper)

    @Synchronized
    fun attachToSession(sessionId: Int, state: EqState32WithMDRC): Boolean {
        latestState = state
        releaseEffect()
        currentSessionId = sessionId
        return try {
            val config = buildDynamicsConfig(state)
            val dp = DynamicsProcessing(0, sessionId, config)
            dp.enabled = state.isEnabled
            dynamicsProcessing = dp
            isEffectEnabled = state.isEnabled
            lastMdrcEnabled = state.mdrcEnabled
            ParametricToDpConverter.deviceSampleRateHz = currentSampleRate().toFloat()
            ParametricToDpConverter.frameDurationMs = DP_FRAME_DURATION_MS
            ParametricToDpConverter.layoutFrozen = true
            scheduleEqWrite(state)
            Log.i(TAG, "Attached EQ32 DSP to session=$sessionId (32 graphic bands + 4-band MDRC + limiter)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach DynamicsProcessing to session $sessionId", e)
            dynamicsProcessing = null
            isEffectEnabled = false
            false
        }
    }

    private fun buildDynamicsConfig(state: EqState32WithMDRC): Config {
        val bandCount = EqState32WithMDRC.FREQS.size
        require(bandCount == REQUESTED_EQ_BANDS) { "EQ32 requires exactly 32 bands" }
        val preEq = Eq(true, true, bandCount)
        val outputRate = currentSampleRate().toDouble()
        val usableMaxHz = minOf(20000.0, outputRate * 0.45).coerceAtLeast(20.0)
        val sourceLogSpan = kotlin.math.ln(20000.0 / 20.0)
        EqState32WithMDRC.FREQS.forEachIndexed { index, frequency ->
            val sourceFrequency = frequency.coerceIn(20.0, 20000.0)
            val fraction = (kotlin.math.ln(sourceFrequency / 20.0) / sourceLogSpan).coerceIn(0.0, 1.0)
            val safeFrequency = (20.0 * kotlin.math.exp(fraction * kotlin.math.ln(usableMaxHz / 20.0))).toFloat()
            preEq.setBand(index, EqBand(state.isEnabled, safeFrequency, 0f))
        }

        val mbc = Mbc(state.mdrcEnabled, true, MDRC_BANDS)
        if (state.mdrcEnabled) {
            val nyquistSafe = (currentSampleRate() * 0.45f).coerceAtLeast(20f)
            val mdrcSource = floatArrayOf(120f, 1000f, 6000f, 20000f)
            val mdrcLogSpan = kotlin.math.ln(20000.0 / 120.0)
            val cutoffs = FloatArray(MDRC_BANDS) { i ->
                val fraction = (kotlin.math.ln(mdrcSource[i].toDouble() / 120.0) / mdrcLogSpan).coerceIn(0.0, 1.0)
                (120.0 * kotlin.math.exp(fraction * kotlin.math.ln(nyquistSafe.toDouble() / 120.0))).toFloat()
            }
            for (band in 0 until MDRC_BANDS) {
                mbc.setBand(
                    band,
                    MbcBand(
                        true,
                        cutoffs[band],
                        state.mdrcAttack.getOrElse(band) { 20f }.coerceIn(1f, 100f),
                        state.mdrcRelease.getOrElse(band) { 200f }.coerceIn(10f, 500f),
                        state.mdrcRatio.getOrElse(band) { 2f }.coerceIn(1f, 20f),
                        state.mdrcThreshold.getOrElse(band) { -20f }.coerceIn(-60f, 0f),
                        0f,      // kneeWidth: hard knee
                        -90f,    // noiseGateThreshold: effectively disabled for normal audio
                        1f,      // expanderRatio: neutral; 0 is invalid/unsafe
                        0f,      // preGain dB
                        state.mdrcMakeup.getOrElse(band) { 0f }.coerceIn(0f, 18f) // postGain dB
                    )
                )
            }
        }

        val limiter = Limiter(true, true, 0, 1f, 50f, 20f, -0.5f, 0f)
        val builder = Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true, bandCount,
            state.mdrcEnabled, MDRC_BANDS,
            false, 0,
            true
        )
        builder.setPreferredFrameDuration(DP_FRAME_DURATION_MS)
        val config = builder.build()
        for (channel in 0 until 2) {
            config.setPreEqByChannelIndex(channel, preEq)
            if (state.mdrcEnabled) config.setMbcByChannelIndex(channel, mbc)
            config.setLimiterByChannelIndex(channel, limiter)
        }
        return config
    }

    @Synchronized
    fun applyState(state: EqState32WithMDRC) {
        latestState = state
        val dp = dynamicsProcessing ?: return
        try {
            if (lastMdrcEnabled != null && lastMdrcEnabled != state.mdrcEnabled) {
                attachToSession(currentSessionId, state)
                return
            }
            dp.enabled = state.isEnabled
            isEffectEnabled = state.isEnabled
            if (!state.isEnabled) return
            scheduleEqWrite(state)
            for (channel in 0 until dp.channelCount) {
                if (state.mdrcEnabled) {
                    for (band in 0 until MDRC_BANDS) {
                        val nativeBand = dp.getMbcBandByChannelIndex(channel, band)
                        nativeBand.threshold = state.mdrcThreshold.getOrElse(band) { -20f }.coerceIn(-60f, 0f)
                        nativeBand.ratio = state.mdrcRatio.getOrElse(band) { 2f }.coerceIn(1f, 20f)
                        nativeBand.attackTime = state.mdrcAttack.getOrElse(band) { 20f }.coerceIn(1f, 100f)
                        nativeBand.releaseTime = state.mdrcRelease.getOrElse(band) { 200f }.coerceIn(10f, 500f)
                        nativeBand.postGain = state.mdrcMakeup.getOrElse(band) { 0f }.coerceIn(0f, 18f)
                        dp.setMbcBandByChannelIndex(channel, band, nativeBand)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Realtime update failed; rebuilding session config", e)
            attachToSession(currentSessionId, state)
        }
    }

    private fun scheduleEqWrite(state: EqState32WithMDRC) {
        // Coalesce rapid slider updates without cancelling the queued DSP write.
        // Cancelling/reposting on every touch frame can starve the EQ32 stage.
        latestState = state
        eqWriteRevision++
        if (pendingEqWrite != null) return
        val job = Runnable {
            val revisionAtStart = eqWriteRevision
            val latest = latestState
            val dp = dynamicsProcessing
            if (dp == null) {
                pendingEqWrite = null
                return@Runnable
            }
            try {
                val sampleRate = currentSampleRate()
                val eq = ParametricEqualizer(sampleRate)
                eq.clearBands()
                val usableMaxHz = minOf(20000.0, sampleRate * 0.45).coerceAtLeast(20.0)
                val sourceLogSpan = kotlin.math.ln(20000.0 / 20.0)
                for (index in EqState32WithMDRC.FREQS.indices) {
                    // At low output rates, preserve the 32-band logarithmic layout
                    // while mapping it into the representable sub-Nyquist range.
                    val sourceFrequency = EqState32WithMDRC.FREQS[index].coerceIn(20.0, 20000.0)
                    val fraction = (kotlin.math.ln(sourceFrequency / 20.0) / sourceLogSpan).coerceIn(0.0, 1.0)
                    val safeFrequency = (20.0 * kotlin.math.exp(fraction * kotlin.math.ln(usableMaxHz / 20.0))).toFloat()
                    eq.addBand(
                        safeFrequency,
                        latest.fixedGains.getOrElse(index) { 0f }.coerceIn(-15f, 15f),
                        BiquadFilter.FilterType.BELL,
                        EQ_BAND_Q
                    )
                }
                if (latest.toneGains.getOrElse(0) { 0f } != 0f) {
                    eq.addBand(minOf(100f, sampleRate * 0.40f), latest.toneGains[0].coerceIn(-12f, 12f), BiquadFilter.FilterType.LOW_SHELF, TONE_Q)
                }
                if (latest.toneGains.getOrElse(1) { 0f } != 0f) {
                    eq.addBand(minOf(1000f, sampleRate * 0.40f), latest.toneGains[1].coerceIn(-12f, 12f), BiquadFilter.FilterType.BELL, TONE_Q)
                }
                if (latest.toneGains.getOrElse(2) { 0f } != 0f) {
                    eq.addBand(minOf(8000f, sampleRate * 0.40f), latest.toneGains[2].coerceIn(-12f, 12f), BiquadFilter.FilterType.HIGH_SHELF, TONE_Q)
                }
                eq.isEnabled = latest.isEnabled
                ParametricToDpConverter.deviceSampleRateHz = sampleRate.toFloat()
                ParametricToDpConverter.frameDurationMs = DP_FRAME_DURATION_MS
                ParametricToDpConverter.layoutFrozen = true
                val converted = ParametricToDpConverter.convertFeatureAware(eq)
                if (converted.cutoffs.size != REQUESTED_EQ_BANDS || converted.gains.size != REQUESTED_EQ_BANDS) {
                    error("Converter must return exactly 32 bands")
                }
                val channels = dp.channelCount.coerceAtMost(2)
                for (channel in 0 until channels) {
                    val stage = Eq(true, true, REQUESTED_EQ_BANDS)
                    for (index in 0 until REQUESTED_EQ_BANDS) {
                        val cutoff = converted.cutoffs[index].coerceIn(MIN_CUTOFF_HZ, MAX_CUTOFF_HZ)
                        val gain = if (latest.isEnabled) converted.gains[index].coerceIn(-15f, 15f) else 0f
                        stage.setBand(index, EqBand(latest.isEnabled, cutoff, gain))
                    }
                    if (channel == 0) dp.setPreEqByChannelIndex(0, stage)
                    else dp.setPreEqByChannelIndex(channel, stage)
                }
                lastEqWriteMs = SystemClock.uptimeMillis()
                Log.d(TAG, "Applied converted EQ32 response to session=$currentSessionId")
            } catch (e: Exception) {
                Log.e(TAG, "EQ32 response conversion/write failed for session=$currentSessionId", e)
            } finally {
                pendingEqWrite = null
                // If the user changed a band while this write was running, queue
                // one more pass using the newest state rather than losing it.
                if (eqWriteRevision != revisionAtStart && dynamicsProcessing != null) {
                    scheduleEqWrite(latestState)
                }
            }
        }
        pendingEqWrite = job
        val delay = (lastEqWriteMs + MIN_EQ_WRITE_SPACING_MS - SystemClock.uptimeMillis()).coerceIn(0L, MIN_EQ_WRITE_SPACING_MS)
        eqWorker.postDelayed(job, delay)
    }

    private fun currentSampleRate(): Int = try {
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).coerceIn(8000, 192000)
    } catch (_: Exception) {
        48000
    }

    fun setMasterEnabled(enabled: Boolean) {
        try {
            isEffectEnabled = enabled
            dynamicsProcessing?.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error setting master enabled", e)
        }
    }

    fun isEnabled(): Boolean = isEffectEnabled

    @Synchronized
    fun release() {
        pendingEqWrite?.let(eqWorker::removeCallbacks)
        pendingEqWrite = null
        releaseEffect()
        eqWorkerThread.quitSafely()
    }

    private fun releaseEffect() {
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing DynamicsProcessing", e)
        } finally {
            dynamicsProcessing = null
            lastMdrcEnabled = null
            isEffectEnabled = false
        }
    }
}
