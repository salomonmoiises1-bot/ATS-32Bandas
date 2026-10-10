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
        private const val FRAME_MS_NORMAL = 80f
        // "Detailed bass" mode: a longer frame halves the FFT bin width (finer low-frequency resolution)
        // at the cost of more latency. The system may round the preferred value.
        private const val FRAME_MS_DETAIL = 160f
        private const val MIN_INPUT_GAIN_DB = -24f
        // Dynamic bass: MBC band 1 (<120 Hz) compresses boosted bass peaks.
        private const val DYN_BASS_THRESHOLD_DB = -22f
        private const val DYN_BASS_RATIO = 3f
        private const val DYN_BASS_ATTACK_MS = 10f
        private const val DYN_BASS_RELEASE_MS = 180f
        private const val MIN_EQ_WRITE_SPACING_MS = 16L
        private const val MIN_CUTOFF_HZ = 20f
        private const val MAX_CUTOFF_HZ = 22000f
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentSessionId: Int = 0
    private var isEffectEnabled: Boolean = false
    private var lastMdrcEnabled: Boolean? = null
    private var lastPreampDb: Float? = null
    private var lastBassDetail: Boolean? = null
    private var lastLimiter: Pair<Float, Float>? = null
    private var lastAppliedState: EqState32WithMDRC? = null
    // Cache of what is currently written in the effect, so only changed EQ bands are sent.
    @Volatile private var effectGeneration = 0
    @Volatile private var cacheGeneration = -1
    @Volatile private var writtenCutoffs: FloatArray? = null
    @Volatile private var writtenGains: FloatArray? = null
    @Volatile private var writtenEnabled: Boolean? = null
    @Volatile private var eqDirty = true
    private data class MdrcSnapshot(
        val threshold: List<Float>, val ratio: List<Float>, val attack: List<Float>,
        val release: List<Float>, val makeup: List<Float>
    )
    private var lastMdrcSnapshot: MdrcSnapshot? = null
    // Only the inputs that change the EQ response / input gain. Moving a limiter or MDRC slider must not
    // re-run the whole response conversion.
    private data class EqKey(
        val fixed: List<Float>, val tone: List<Float>, val boostDb: Float, val boostHz: Float,
        val smooth: Boolean, val enabled: Boolean, val autoHeadroom: Boolean, val preampDb: Float
    )
    private fun eqKey(s: EqState32WithMDRC) = EqKey(
        s.fixedGains.toList(), s.toneGains.toList(), s.bassBoostDb, s.bassBoostHz,
        s.smoothCurve, s.isEnabled, s.autoHeadroom, if (s.autoHeadroom) s.preampDb else 0f
    )
    @Volatile private var lastEqKey: EqKey? = null
    @Volatile private var latestState: EqState32WithMDRC = EqState32WithMDRC()
    @Volatile private var pendingEqWrite: Runnable? = null
    @Volatile private var eqWriteRevision: Long = 0L
    @Volatile private var lastEqWriteMs: Long = 0L
    private val eqWorkerThread = HandlerThread("ATS-Eq32-DpWorker").apply { start() }
    private val eqWorker = Handler(eqWorkerThread.looper)

    @Synchronized
    fun attachToSession(sessionId: Int, state: EqState32WithMDRC): Boolean {
        val stableState = snapshotState(state)
        latestState = stableState
        releaseEffect()
        effectGeneration++
        currentSessionId = sessionId
        return try {
            val config = buildDynamicsConfig(stableState)
            val dp = DynamicsProcessing(0, sessionId, config)
            dp.enabled = stableState.isEnabled
            applyPreamp(dp, stableState.preampDb)
            dynamicsProcessing = dp
            isEffectEnabled = stableState.isEnabled
            lastMdrcEnabled = mbcActive(stableState)
            lastMdrcSnapshot = mdrcSnapshot(stableState)
            lastBassDetail = stableState.bassDetail
            lastLimiter = limiterOf(stableState)
            lastAppliedState = null
            ParametricToDpConverter.deviceSampleRateHz = currentSampleRate().toFloat()
            ParametricToDpConverter.frameDurationMs = frameMs(stableState)
            ParametricToDpConverter.layoutFrozen = true
            scheduleEqWrite(stableState)
            Log.i(TAG, "Attached EQ32 DSP to session=$sessionId (32 graphic bands + 4-band MDRC + limiter)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach DynamicsProcessing to session $sessionId", e)
            dynamicsProcessing = null
            isEffectEnabled = false
            false
        }
    }

    /** The 32 logical band centres mapped into the representable sub-Nyquist range (log layout preserved). */
    private fun safeBandFrequencies(sampleRate: Int): FloatArray {
        val usableMaxHz = minOf(20000.0, sampleRate * 0.45).coerceAtLeast(20.0)
        val sourceLogSpan = kotlin.math.ln(20000.0 / 20.0)
        return FloatArray(EqState32WithMDRC.FREQS.size) { index ->
            val sourceFrequency = EqState32WithMDRC.FREQS[index].coerceIn(20.0, 20000.0)
            val fraction = (kotlin.math.ln(sourceFrequency / 20.0) / sourceLogSpan).coerceIn(0.0, 1.0)
            (20.0 * kotlin.math.exp(fraction * kotlin.math.ln(usableMaxHz / 20.0))).toFloat()
        }
    }

    private fun buildDynamicsConfig(state: EqState32WithMDRC): Config {
        val bandCount = EqState32WithMDRC.FREQS.size
        require(bandCount == REQUESTED_EQ_BANDS) { "EQ32 requires exactly 32 bands" }
        val preEq = Eq(true, true, bandCount)
        safeBandFrequencies(currentSampleRate()).forEachIndexed { index, safeFrequency ->
            preEq.setBand(index, EqBand(state.isEnabled, safeFrequency, 0f))
        }

        // The MBC stage is ALWAYS part of the effect (bands configured from the saved values) and only its
        // "enabled" flag follows the MDRC switch. Toggling MDRC at runtime then never rebuilds the effect,
        // which is what used to cause a short click/noise.
        val snap = mdrcSnapshot(state)
        val mbc = Mbc(true, mbcActive(state), MDRC_BANDS)
        run {
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
                        snap.attack[band],
                        snap.release[band],
                        snap.ratio[band],
                        snap.threshold[band],
                        0f,      // kneeWidth: hard knee
                        -90f,    // noiseGateThreshold: effectively disabled for normal audio
                        1f,      // expanderRatio: neutral; 0 is invalid/unsafe
                        0f,      // preGain dB
                        snap.makeup[band] // postGain dB
                    )
                )
            }
        }

        val (limiterThreshold, limiterRelease) = limiterOf(state)
        val limiter = Limiter(true, true, 0, 1f, limiterRelease, 20f, limiterThreshold, 0f)
        val builder = Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true, bandCount,
            true, MDRC_BANDS,
            false, 0,
            true
        )
        builder.setPreferredFrameDuration(frameMs(state))
        val config = builder.build()
        for (channel in 0 until 2) {
            config.setPreEqByChannelIndex(channel, preEq)
            config.setMbcByChannelIndex(channel, mbc)
            config.setLimiterByChannelIndex(channel, limiter)
        }
        return config
    }

    @Synchronized
    fun applyState(state: EqState32WithMDRC) {
        val stableState = snapshotState(state)
        latestState = stableState
        val dp = dynamicsProcessing ?: return
        // Periodic refreshes (e.g. every 5 s) with an unchanged state must not rewrite anything.
        if (stableState == lastAppliedState && !eqDirty) return
        // A different processing frame needs a new effect (rare, user-triggered; brief gap is expected).
        if (lastBassDetail != null && lastBassDetail != stableState.bassDetail) {
            attachToSession(currentSessionId, stableState)
            return
        }
        try {
            if (isEffectEnabled != stableState.isEnabled) {
                dp.enabled = stableState.isEnabled
                isEffectEnabled = stableState.isEnabled
            }
            if (!stableState.isEnabled) {
                lastAppliedState = stableState
                return
            }

            // With auto headroom the worker sets the input gain once it knows the strongest boost.
            if (!stableState.autoHeadroom) applyPreamp(dp, stableState.preampDb)
            applyLimiter(dp, stableState)
            if (eqDirty || eqKey(stableState) != lastEqKey) scheduleEqWrite(stableState)

            // MBC parameters first (only when they changed), then the enable flag, so the compressor
            // never starts with stale values. No effect rebuild is involved.
            val snapshot = mdrcSnapshot(stableState)
            if (snapshot != lastMdrcSnapshot) {
                for (channel in 0 until dp.channelCount) {
                    for (band in 0 until MDRC_BANDS) {
                        val nativeBand = dp.getMbcBandByChannelIndex(channel, band)
                        nativeBand.threshold = snapshot.threshold[band]
                        nativeBand.ratio = snapshot.ratio[band]
                        nativeBand.attackTime = snapshot.attack[band]
                        nativeBand.releaseTime = snapshot.release[band]
                        nativeBand.postGain = snapshot.makeup[band]
                        dp.setMbcBandByChannelIndex(channel, band, nativeBand)
                    }
                }
                lastMdrcSnapshot = snapshot
            }
            val mbcOn = mbcActive(stableState)
            if (lastMdrcEnabled != mbcOn) {
                for (channel in 0 until dp.channelCount) {
                    val mbc = dp.getMbcByChannelIndex(channel)
                    mbc.isEnabled = mbcOn
                    dp.setMbcByChannelIndex(channel, mbc)
                }
                lastMdrcEnabled = mbcOn
            }
            lastAppliedState = stableState
        } catch (e: Exception) {
            Log.w(TAG, "Realtime update failed; rebuilding session config", e)
            attachToSession(currentSessionId, stableState)
        }
    }

    private fun scheduleEqWrite(state: EqState32WithMDRC) {
        // Coalesce rapid slider updates without cancelling the queued DSP write.
        // Snapshot mutable arrays before the worker reads them.
        latestState = snapshotState(state)
        eqWriteRevision++
        eqDirty = true
        if (pendingEqWrite != null) return
        val job = Runnable {
            val revisionAtStart = eqWriteRevision
            val generationAtStart = effectGeneration
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
                val safeFrequencies = safeBandFrequencies(sampleRate)
                for (index in EqState32WithMDRC.FREQS.indices) {
                    // At low output rates, preserve the 32-band logarithmic layout
                    // while mapping it into the representable sub-Nyquist range.
                    eq.addBand(
                        safeFrequencies[index],
                        // Smooth mode: the 32 bells stay at 0 dB only as cutoff anchors; the slider
                        // values enter the target response through the interpolated curve below.
                        if (latest.smoothCurve) 0f else latest.fixedGains.getOrElse(index) { 0f }.coerceIn(-15f, 15f),
                        BiquadFilter.FilterType.BELL,
                        EQ_BAND_Q
                    )
                }
                if (latest.bassBoostDb != 0f) {
                    // BassBoost is just one more biquad in the same response that is folded into the 32 DP bands.
                    eq.addBand(minOf(latest.bassBoostHz.coerceIn(EqState32WithMDRC.BASS_BOOST_MIN_HZ, EqState32WithMDRC.BASS_BOOST_MAX_HZ), sampleRate * 0.40f),
                        latest.bassBoostDb.coerceIn(0f, EqState32WithMDRC.BASS_BOOST_MAX_DB), BiquadFilter.FilterType.LOW_SHELF, TONE_Q)
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
                ParametricToDpConverter.frameDurationMs = frameMs(latest)
                ParametricToDpConverter.layoutFrozen = true
                val smooth = if (latest.smoothCurve) {
                    SmoothCurve(FloatArray(EqState32WithMDRC.FREQS.size) { latest.fixedGains.getOrElse(it) { 0f }.coerceIn(-15f, 15f) })
                } else null
                val converted = ParametricToDpConverter.convertFeatureAware(eq, smooth?.let { c -> { f: Float -> c.at(f.toDouble()) } })
                if (converted.cutoffs.size != REQUESTED_EQ_BANDS || converted.gains.size != REQUESTED_EQ_BANDS) {
                    error("Converter must return exactly 32 bands")
                }
                val channels = dp.channelCount.coerceAtMost(2)
                val cutoffs = FloatArray(REQUESTED_EQ_BANDS) { converted.cutoffs[it].coerceIn(MIN_CUTOFF_HZ, MAX_CUTOFF_HZ) }
                val gains = FloatArray(REQUESTED_EQ_BANDS) {
                    if (latest.isEnabled) converted.gains[it].coerceIn(-15f, 15f) else 0f
                }
                if (latest.autoHeadroom) {
                    // Auto headroom: lower the input gain by the strongest boost actually applied.
                    val peak = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
                    synchronized(this@DynamicsProcessingManager) {
                        if (generationAtStart == effectGeneration && dynamicsProcessing === dp) {
                            applyPreamp(dp, latest.preampDb - peak)
                        }
                    }
                }
                val prevCutoffs = writtenCutoffs
                val prevGains = writtenGains
                val sameLayout = cacheGeneration == generationAtStart && generationAtStart == effectGeneration &&
                    prevCutoffs != null && prevGains != null && writtenEnabled == latest.isEnabled &&
                    prevCutoffs.contentEquals(cutoffs)
                try {
                    if (sameLayout && prevGains != null) {
                        // Same band layout: send only the bands whose gain changed.
                        for (index in 0 until REQUESTED_EQ_BANDS) {
                            if (gains[index] != prevGains[index]) {
                                val band = EqBand(latest.isEnabled, cutoffs[index], gains[index])
                                for (channel in 0 until channels) dp.setPreEqBandByChannelIndex(channel, index, band)
                            }
                        }
                    } else {
                        for (channel in 0 until channels) {
                            val stage = Eq(true, true, REQUESTED_EQ_BANDS)
                            for (index in 0 until REQUESTED_EQ_BANDS) {
                                stage.setBand(index, EqBand(latest.isEnabled, cutoffs[index], gains[index]))
                            }
                            dp.setPreEqByChannelIndex(channel, stage)
                        }
                    }
                } catch (e: Exception) {
                    writtenCutoffs = null; writtenGains = null; writtenEnabled = null; cacheGeneration = -1
                    lastEqKey = null
                    throw e
                }
                if (generationAtStart == effectGeneration) {
                    writtenCutoffs = cutoffs; writtenGains = gains; writtenEnabled = latest.isEnabled
                    cacheGeneration = generationAtStart
                }
                if (eqWriteRevision == revisionAtStart) eqDirty = false
                if (generationAtStart == effectGeneration) lastEqKey = eqKey(latest)
                lastEqWriteMs = SystemClock.uptimeMillis()
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

    /** Preamp = DynamicsProcessing input gain (dB), applied ahead of the EQ on every channel. */
    private fun applyPreamp(dp: DynamicsProcessing, preampDb: Float) {
        val value = preampDb.coerceIn(MIN_INPUT_GAIN_DB, EqState32WithMDRC.PREAMP_MAX_DB)
        if (lastPreampDb == value) return
        try {
            dp.setInputGainAllChannelsTo(value)
            lastPreampDb = value
        } catch (e: Exception) {
            Log.w(TAG, "Preamp (input gain) update failed", e)
        }
    }

    private fun frameMs(state: EqState32WithMDRC): Float = if (state.bassDetail) FRAME_MS_DETAIL else FRAME_MS_NORMAL

    /** The MBC stage runs when the user enabled MDRC OR the dynamic-bass stage needs it. */
    private fun mbcActive(state: EqState32WithMDRC): Boolean = state.mdrcEnabled || state.dynamicBass

    private fun limiterOf(state: EqState32WithMDRC): Pair<Float, Float> = Pair(
        state.limiterThresholdDb.coerceIn(EqState32WithMDRC.LIMITER_MIN_DB, EqState32WithMDRC.LIMITER_MAX_DB),
        state.limiterReleaseMs.coerceIn(EqState32WithMDRC.LIMITER_RELEASE_MIN_MS, EqState32WithMDRC.LIMITER_RELEASE_MAX_MS)
    )

    private fun applyLimiter(dp: DynamicsProcessing, state: EqState32WithMDRC) {
        val wanted = limiterOf(state)
        if (wanted == lastLimiter) return
        for (channel in 0 until dp.channelCount) {
            val limiter = dp.getLimiterByChannelIndex(channel)
            limiter.threshold = wanted.first
            limiter.releaseTime = wanted.second
            dp.setLimiterByChannelIndex(channel, limiter)
        }
        lastLimiter = wanted
    }

    /**
     * Effective MBC parameters. With MDRC off the bands are neutral (ratio 1:1, no makeup); with dynamic
     * bass on, band 1 (<120 Hz) gets a bass-tamer setting on top of whatever MDRC says.
     */
    private fun mdrcSnapshot(state: EqState32WithMDRC): MdrcSnapshot {
        val on = state.mdrcEnabled
        val threshold = FloatArray(MDRC_BANDS) { if (on) state.mdrcThreshold.getOrElse(it) { -20f }.coerceIn(-40f, 0f) else 0f }
        val ratio = FloatArray(MDRC_BANDS) { if (on) state.mdrcRatio.getOrElse(it) { 2f }.coerceIn(1f, 20f) else 1f }
        val attack = FloatArray(MDRC_BANDS) { if (on) state.mdrcAttack.getOrElse(it) { 20f }.coerceIn(1f, 100f) else 20f }
        val release = FloatArray(MDRC_BANDS) { if (on) state.mdrcRelease.getOrElse(it) { 200f }.coerceIn(10f, 500f) else 200f }
        val makeup = FloatArray(MDRC_BANDS) { if (on) state.mdrcMakeup.getOrElse(it) { 0f }.coerceIn(0f, 18f) else 0f }
        if (state.dynamicBass) {
            threshold[0] = DYN_BASS_THRESHOLD_DB
            ratio[0] = DYN_BASS_RATIO
            attack[0] = DYN_BASS_ATTACK_MS
            release[0] = DYN_BASS_RELEASE_MS
            makeup[0] = 0f
        }
        return MdrcSnapshot(threshold.toList(), ratio.toList(), attack.toList(), release.toList(), makeup.toList())
    }

    private fun snapshotState(state: EqState32WithMDRC): EqState32WithMDRC = state.copy(
        fixedGains = state.fixedGains.copyOf(),
        toneGains = state.toneGains.copyOf(),
        mdrcThreshold = state.mdrcThreshold.copyOf(),
        mdrcRatio = state.mdrcRatio.copyOf(),
        mdrcAttack = state.mdrcAttack.copyOf(),
        mdrcRelease = state.mdrcRelease.copyOf(),
        mdrcMakeup = state.mdrcMakeup.copyOf()
    )

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
            lastMdrcSnapshot = null
            lastPreampDb = null
            lastBassDetail = null
            lastLimiter = null
            lastAppliedState = null
            writtenCutoffs = null; writtenGains = null; writtenEnabled = null; cacheGeneration = -1
            lastEqKey = null
            isEffectEnabled = false
        }
    }
}
