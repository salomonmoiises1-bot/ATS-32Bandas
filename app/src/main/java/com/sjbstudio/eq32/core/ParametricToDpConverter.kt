package com.sjbstudio.eq32.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

private val EQ32_FREQUENCIES: FloatArray = EqState32WithMDRC.FREQS.map { it.toFloat() }.toFloatArray()

/**
 * Equalizer314-style bridge for sBz's 32 logical EQ bands.
 *
 * Important: sBz deliberately keeps ONLY 32 physical DP EQ bands.  We use
 * Equalizer314's useful conversion methods (feature-aware anchors, adaptive
 * cutoff placement and bin-aware band-space averaging) without expanding the
 * realtime graph to 127/128 bands.
 */
object ParametricToDpConverter {
    private val BAND_COUNT = EQ32_FREQUENCIES.size
    private const val MIN_FREQ = 20f
    private const val MAX_FREQ = 22000f
    private const val GRID_SIZE = 768
    private const val ADAPT_ITERS = 256

    @Volatile var deviceSampleRateHz: Float = 48000f
    @Volatile var frameDurationMs: Float = 80f
    @Volatile var overlayEq: ParametricEqualizer? = null

    @Volatile var layoutFrozen = false
        set(value) {
            field = value
            if (!value) frozenCutoffs = null
        }
    private var frozenCutoffs: FloatArray? = null
    private var frozenSampleRateHz: Float = 0f
    private var frozenFrameDurationMs: Float = 0f

    data class ConvertedBands(
        val cutoffs: FloatArray,
        val gains: FloatArray,
    )

    private fun respond(eq: ParametricEqualizer, f: Float): Float {
        val base = eq.getFrequencyResponse(f)
        val overlay = overlayEq
        return if (overlay != null && overlay !== eq) {
            base + overlay.getFrequencyResponse(f)
        } else base
    }

    /** Same DP FFT geometry used by Equalizer314's converter. */
    private fun dpBlockSize(fs: Float): Int {
        val samples = (frameDurationMs / 1000f * fs).toInt().coerceIn(8, 16384)
        var p = 8
        while (p < samples) p = p shl 1
        return p
    }

    private fun responseGrid(eq: ParametricEqualizer): FloatArray {
        val min = 10f
        val max = 20000f
        val span = ln(max / min)
        return FloatArray(GRID_SIZE) { i ->
            respond(eq, min * kotlin.math.exp(span * i / (GRID_SIZE - 1)))
        }
    }

    /**
     * Adaptively positions the 32 physical cutoffs. Logical band centres are
     * anchors, so the user's 32 controls never disappear. The remaining
     * budget is used where the response changes fastest.
     */
    private fun adaptiveCutoffs(
        grids: List<FloatArray>,
        anchors: List<Float>,
        total: Int,
        binHz: Float,
    ): FloatArray {
        val min = 10f
        val max = 20000f
        val span = ln(max / min)

        fun gi(f: Float): Int = ((ln(f.coerceIn(min, max) / min) / span) * (GRID_SIZE - 1) + 0.5f)
            .toInt().coerceIn(0, GRID_SIZE - 1)

        fun variation(lo: Float, hi: Float): Float {
            val a = gi(lo); val b = gi(hi)
            var worst = 0f
            for (grid in grids) {
                if (b <= a) {
                    worst = maxOf(worst, abs(grid[b] - grid[a]))
                } else {
                    var mn = Float.MAX_VALUE
                    var mx = -Float.MAX_VALUE
                    for (k in a..b) {
                        mn = minOf(mn, grid[k]); mx = maxOf(mx, grid[k])
                    }
                    worst = maxOf(worst, mx - mn)
                }
            }
            return worst
        }

        val seed = ArrayList<Pair<Float, Boolean>>(total + 140)
        // Equalizer314 seed geometry: two sub-bin LF splitters plus its
        // Wavelet-compatible frequency table. The 32 logical sBz centres are
        // then inserted as hard anchors. Because total == 32 and there are
        // exactly 32 logical anchors, the final physical slots remain one to
        // one with the user's 32 controls; the helper EQ stages only shape the
        // response, they do not consume graphic-EQ slots.
        seed += 5f to false
        seed += 15f to false
        for (f in WAVELET_FREQUENCIES) seed += f to false
        for (f in anchors) seed += f.coerceIn(min, max) to true
        seed.sortBy { it.first }

        val freqs = ArrayList<Float>(total + 8)
        val anchorFlags = ArrayList<Boolean>(total + 8)
        for ((f, anchor) in seed) {
            val last = freqs.lastOrNull()
            if (last == null || f - last > last * 0.003f) {
                freqs += f; anchorFlags += anchor
            } else if (anchor && !anchorFlags.last()) {
                freqs[freqs.lastIndex] = f
                anchorFlags[anchorFlags.lastIndex] = true
            }
        }

        // With 32 anchors this normally resolves to the 32 logical centres.
        // If a caller supplies fewer anchors, use Equalizer314's adaptive
        // redistribution rather than inventing a 127/128-band layout.
        while (freqs.size > total) {
            var best = -1
            var bestCost = Float.MAX_VALUE
            for (j in 1 until freqs.size - 1) {
                if (anchorFlags[j]) continue
                val cost = variation(freqs[j - 1], freqs[j + 1])
                if (cost < bestCost) { bestCost = cost; best = j }
            }
            if (best < 0) break
            freqs.removeAt(best); anchorFlags.removeAt(best)
        }

        while (freqs.size < total) {
            var bestGap = -1
            var bestLogGap = 0f
            for (i in 0 until freqs.size - 1) {
                val gap = ln(freqs[i + 1] / freqs[i])
                if (gap > bestLogGap) { bestLogGap = gap; bestGap = i }
            }
            if (bestGap < 0) break
            val mid = sqrt(freqs[bestGap] * freqs[bestGap + 1])
            freqs.add(bestGap + 1, mid)
            anchorFlags.add(bestGap + 1, false)
        }

        for (iter in 0 until ADAPT_ITERS) {
            var worst = -1
            var worstVariation = 0f
            for (i in 0 until freqs.size - 1) {
                if (freqs[i + 1] - freqs[i] < binHz) continue
                val v = variation(freqs[i], freqs[i + 1])
                if (v > worstVariation) { worstVariation = v; worst = i }
            }
            if (worst < 0) break

            var movable = -1
            var cheapest = Float.MAX_VALUE
            for (j in 1 until freqs.size - 1) {
                if (anchorFlags[j] || j == worst || j == worst + 1) continue
                val cost = variation(freqs[j - 1], freqs[j + 1])
                if (cost < cheapest) { cheapest = cost; movable = j }
            }
            if (movable < 0 || cheapest * 1.7f >= worstVariation) break

            val mid = sqrt(freqs[worst] * freqs[worst + 1])
            freqs.removeAt(movable); anchorFlags.removeAt(movable)
            var at = 0
            while (at < freqs.size && freqs[at] < mid) at++
            freqs.add(at, mid); anchorFlags.add(at, false)
        }

        // Keep exactly 32 monotonically increasing physical cutoffs.
        val out = FloatArray(total)
        for (i in 0 until total) {
            out[i] = freqs.getOrElse(i) { EQ32_FREQUENCIES[i] }
                .coerceIn(MIN_FREQ, MAX_FREQ)
            if (i > 0 && out[i] <= out[i - 1]) out[i] = (out[i - 1] + 0.01f).coerceAtMost(MAX_FREQ)
        }
        return out
    }

    /**
     * Equalizer314's band-space fit: average the analytic target over the
     * actual FFT bins covered by each DP staircase instead of sampling only
     * one point at the cutoff. This is particularly important at 20–40 Hz.
     */
    private fun bandSpaceDeconvolve(
        eq: ParametricEqualizer,
        cutoffs: FloatArray,
        n: Int,
        fs: Float,
    ): FloatArray {
        val half = n / 2 + 1
        val binHz = fs / n
        val target = FloatArray(half) { k -> respond(eq, (k * binHz).coerceAtLeast(1f)) }
        val result = FloatArray(cutoffs.size)
        var previousStop = -1

        for (i in cutoffs.indices) {
            val stop = (0.5f + cutoffs[i] * n / fs).toInt().coerceAtMost(half - 1)
            val start = previousStop + 1
            if (start <= stop) {
                var sum = 0f
                for (k in start..stop) sum += target[k]
                result[i] = sum / (stop - start + 1)
                previousStop = stop
            } else {
                result[i] = respond(eq, cutoffs[i])
            }
        }
        return result
    }

    @Synchronized
    fun convertFeatureAware(eq: ParametricEqualizer): ConvertedBands {
        require(EQ32_FREQUENCIES.size == BAND_COUNT)
        val fs = deviceSampleRateHz.coerceIn(8000f, 192000f)
        val frameMs = frameDurationMs.coerceIn(1f, 500f)
        val n = dpBlockSize(fs)
        val binHz = fs / n

        // The converter is shared by session workers. Never reuse a frozen
        // cutoff layout calculated for another sample rate or frame geometry.
        val geometryChanged =
            frozenSampleRateHz != fs || frozenFrameDurationMs != frameMs
        val frozen = if (!geometryChanged) frozenCutoffs else null

        if (geometryChanged) {
            frozenCutoffs = null
            frozenSampleRateHz = fs
            frozenFrameDurationMs = frameMs
        }

        val cutoffs = if (layoutFrozen && frozen != null && frozen.size == BAND_COUNT) {
            frozen
        } else {
            adaptiveCutoffs(
                listOf(responseGrid(eq)),
                collectAnchors(eq),
                BAND_COUNT,
                binHz,
            ).also {
                if (layoutFrozen) {
                    frozenCutoffs = it
                    frozenSampleRateHz = fs
                    frozenFrameDurationMs = frameMs
                }
            }
        }

        return ConvertedBands(cutoffs, bandSpaceDeconvolve(eq, cutoffs, n, fs))
    }

    /** Kept for callers that use the previous sBz API name. */
    fun convertFixed32(eq: ParametricEqualizer, frequenciesHz: FloatArray = EQ32_FREQUENCIES): ConvertedBands {
        require(frequenciesHz.size == BAND_COUNT) { "sBz realtime EQ requires exactly 32 bands" }
        return convertFeatureAware(eq)
    }

    private fun collectAnchors(eq: ParametricEqualizer): List<Float> {
        // Only the 32 graphic-EQ bands are physical anchors. Tone/Bass Boost
        // helper filters remain part of the analytic response but must not
        // displace any of the 32 user-controlled EQ slots.
        val count = minOf(EQ32_FREQUENCIES.size, eq.getBandCount())
        return buildList(count) {
            for (i in 0 until count) {
                val band = eq.getBand(i) ?: continue
                if (band.enabled && band.frequency in MIN_FREQ..MAX_FREQ) add(band.frequency)
            }
        }
    }

    // Equalizer314/Wavelet-compatible seed table used by its adaptive
    // cutoff allocator. It is only a seed; sBz still emits exactly 32 bands.
    private val WAVELET_FREQUENCIES = floatArrayOf(
        20f,21f,22f,23f,24f,26f,27f,29f,30f,32f,34f,36f,38f,40f,43f,45f,48f,50f,53f,56f,
        59f,63f,66f,70f,74f,78f,83f,87f,92f,97f,103f,109f,115f,121f,128f,136f,143f,151f,160f,169f,
        178f,188f,199f,210f,222f,235f,248f,262f,277f,292f,309f,326f,345f,364f,385f,406f,429f,453f,479f,506f,
        534f,565f,596f,630f,665f,703f,743f,784f,829f,875f,924f,977f,1032f,1090f,1151f,1216f,1284f,1357f,1433f,1514f,
        1599f,1689f,1784f,1885f,1991f,2103f,2221f,2347f,2479f,2618f,2766f,2921f,3086f,3260f,3443f,3637f,3842f,4058f,
        4287f,4528f,4783f,5052f,5337f,5637f,5955f,6290f,6644f,7018f,7414f,7831f,8272f,8738f,9230f,9749f,10298f,10878f,
        11490f,12137f,12821f,13543f,14305f,15110f,15961f,16860f,17809f,18812f,19871f
    )
}
