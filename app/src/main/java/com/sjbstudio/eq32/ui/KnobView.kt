package com.sjbstudio.eq32.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class KnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var minValue = -12.0f
    var maxValue = 12.0f
    var currentValue = 0.0f
        private set

    var onValueChangedListener: ((Float) -> Unit)? = null

    private val minAngle = -135.0f
    private val maxAngle = 135.0f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18181b") // zinc-900
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#27272a") // zinc-800
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#27272a")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val activeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#06b6d4") // cyan-500
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38bdf8")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f4f4f5")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val arcBounds = RectF()
    private var lastTouchY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            setValue(0.0f)
            onValueChangedListener?.invoke(0.0f)
            return true
        }
    })

    fun setValue(value: Float) {
        currentValue = value.coerceIn(minValue, maxValue)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastTouchY - event.y
                lastTouchY = event.y
                val deltaValue = (deltaY / 200.0f) * (maxValue - minValue)
                val newValue = (currentValue + deltaValue).coerceIn(minValue, maxValue)
                if (newValue != currentValue) {
                    currentValue = newValue
                    invalidate()
                    onValueChangedListener?.invoke(currentValue)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (Math.min(width, height) / 2f) - 16f
        if (radius <= 0) return

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Draw background arc
        canvas.drawArc(arcBounds, 135f, 270f, false, trackPaint)

        // Draw active arc from 0 (top = -90 deg, which is at angle 0 in our coords)
        val norm = (currentValue - minValue) / (maxValue - minValue)
        val currentAngle = minAngle + norm * (maxAngle - minAngle)

        if (minValue >= 0f) {
            canvas.drawArc(arcBounds, 135f, norm * 270f, false, activeArcPaint)
        } else if (currentValue >= 0) {
            val sweep = (currentValue / maxValue) * 135f
            canvas.drawArc(arcBounds, 270f, sweep, false, activeArcPaint)
        } else {
            val sweep = (currentValue / minValue) * 135f
            canvas.drawArc(arcBounds, 270f, -sweep, false, activeArcPaint)
        }

        // Inner Knob Body
        val innerRadius = radius * 0.72f
        canvas.drawCircle(cx, cy, innerRadius, bodyPaint)
        canvas.drawCircle(cx, cy, innerRadius, borderPaint)

        // Indicator dot
        val angleRad = Math.toRadians((currentAngle - 90.0).toDouble())
        val dotRadius = innerRadius * 0.65f
        val dotX = (cx + dotRadius * cos(angleRad)).toFloat()
        val dotY = (cy + dotRadius * sin(angleRad)).toFloat()
        canvas.drawCircle(dotX, dotY, 7f, pointerPaint)

        // Center readout text
        val formatted = if (currentValue >= 0) "+%.1f".format(currentValue) else "%.1f".format(currentValue)
        canvas.drawText(formatted, cx, cy + (textPaint.textSize / 3f), textPaint)
    }
}
