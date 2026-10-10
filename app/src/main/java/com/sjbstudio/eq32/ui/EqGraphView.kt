package com.sjbstudio.eq32.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.sjbstudio.eq32.core.Biquad
import com.sjbstudio.eq32.core.SmoothCurve
import com.sjbstudio.eq32.state.BiquadConfig
import com.sjbstudio.eq32.state.EqState32WithMDRC
import kotlin.math.log10
import kotlin.math.pow

class EqGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val minFreq = 20.0
    private val maxFreq = 20000.0
    private val minDb = -15.0f
    private val maxDb = 15.0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1e293b") // slate-800
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748b")
        textSize = 22f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#06b6d4") // Cyan
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val biquadFilters = mutableListOf<Biquad>()
    private var currentState: EqState32WithMDRC? = null
    private var smoothCurve: SmoothCurve? = null

    init {
        // Enable hardware acceleration for fluid curve rasterization
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateCurve(state: EqState32WithMDRC) {
        currentState = state
        rebuildBiquadCascade(state)
        invalidate()
    }

    private fun rebuildBiquadCascade(state: EqState32WithMDRC) {
        biquadFilters.clear()
        smoothCurve = if (state.smoothCurve) SmoothCurve(state.fixedGains) else null
        if (!state.isEnabled) return

        val biquadConfigs = state.toBiquads(48000, includeBands = !state.smoothCurve)
        for (config in biquadConfigs) {
            val bq = Biquad()
            when (config) {
                is BiquadConfig.LowShelf -> bq.setLowShelf(config.freq, 48000.0, config.gainDb.toDouble(), config.s)
                is BiquadConfig.Peaking -> bq.setPeaking(config.freq, 48000.0, config.q, config.gainDb.toDouble())
                is BiquadConfig.HighShelf -> bq.setHighShelf(config.freq, 48000.0, config.gainDb.toDouble(), config.s)
            }
            biquadFilters.add(bq)
        }
    }

    private fun freqToX(freq: Double, w: Float): Float {
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val logF = log10(freq)
        return (((logF - logMin) / (logMax - logMin)) * w).toFloat()
    }

    private fun dbToY(db: Float, h: Float): Float {
        val norm = (db - minDb) / (maxDb - minDb)
        return h - (norm * h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw horizontal dB grid lines (+12, +6, 0, -6, -12)
        val dbSteps = floatArrayOf(12f, 6f, 0f, -6f, -12f)
        for (db in dbSteps) {
            val y = dbToY(db, h)
            val p = if (db == 0f) centerLinePaint else gridPaint
            canvas.drawLine(0f, y, w, y, p)
            canvas.drawText("${if (db > 0) "+" else ""}${db.toInt()}dB", 12f, y - 4f, textPaint)
        }

        // 2. Draw vertical frequency grid lines (50, 100, 200, 500, 1k, 2k, 5k, 10k, 20k)
        val freqGrid = doubleArrayOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 20000.0)
        val freqLabels = arrayOf("50", "100", "250", "500", "1k", "2.5k", "5k", "10k", "20k")
        freqGrid.forEachIndexed { idx, f ->
            val x = freqToX(f, w)
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawText(freqLabels[idx], x + 4f, h - 10f, textPaint)
        }

        // 3. Calculate and render composite curve
        curvePath.reset()
        fillPath.reset()

        val zeroY = dbToY(0f, h)
        val numPoints = 180
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)

        for (i in 0 until numPoints) {
            val t = i.toDouble() / (numPoints - 1)
            val f = 10.0.pow(logMin + t * (logMax - logMin))
            val x = (t * w).toFloat()

            var totalDb = 0.0
            if (currentState?.isEnabled == true) {
                for (bq in biquadFilters) {
                    totalDb += bq.magnitudeAtDb(f, 48000.0)
                }
                smoothCurve?.let { totalDb += it.at(f) }
            }

            val y = dbToY(totalDb.toFloat().coerceIn(minDb, maxDb), h)

            if (i == 0) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, zeroY)
                fillPath.lineTo(x, y)
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(w, zeroY)
        fillPath.close()

        // Gradient shader for fill
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#3306b6d4"), Color.parseColor("#0506b6d4")),
            null, Shader.TileMode.CLAMP
        )

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, curvePaint)
    }
}
