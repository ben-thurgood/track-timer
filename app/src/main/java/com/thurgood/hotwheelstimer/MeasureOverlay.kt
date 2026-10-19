package com.thurgood.hotwheelstimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the measuring line over the AR camera picture: a dot for each point placed, a yellow line
 * between them with each piece's length, a dashed line to where the crosshair is aiming, and the
 * crosshair itself (green when it's pointing at a surface it can measure to).
 */
class MeasureOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String)

    class State(
        val dots: List<PointF>,
        val segments: List<Segment>,
        val preview: Segment?,
        val aimOk: Boolean,
    )

    /** Written from the GL thread, read when drawing. */
    @Volatile var state: State? = null

    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 0)
        strokeWidth = 5 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val previewLine = Paint(line).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 3 * d
        pathEffect = DashPathEffect(floatArrayOf(10 * d, 8 * d), 0f)
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 61, 0) }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * d
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state

        s?.preview?.let { canvas.drawLine(it.x1, it.y1, it.x2, it.y2, previewLine) }
        s?.segments?.forEach { canvas.drawLine(it.x1, it.y1, it.x2, it.y2, line) }
        s?.dots?.forEach {
            canvas.drawCircle(it.x, it.y, 8 * d, dotFill)
            canvas.drawCircle(it.x, it.y, 8 * d, dotRing)
        }
        s?.segments?.forEach { drawLabel(canvas, it) }
        s?.preview?.let { drawLabel(canvas, it) }

        // Crosshair in the middle of the screen.
        val cx = width / 2f
        val cy = height / 2f
        val r = 18 * d
        cross.color = if (s?.aimOk == true) Color.rgb(0, 230, 118) else Color.argb(200, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, cross)
        canvas.drawLine(cx - r * 1.6f, cy, cx - r * 0.5f, cy, cross)
        canvas.drawLine(cx + r * 0.5f, cy, cx + r * 1.6f, cy, cross)
        canvas.drawLine(cx, cy - r * 1.6f, cx, cy - r * 0.5f, cross)
        canvas.drawLine(cx, cy + r * 0.5f, cx, cy + r * 1.6f, cross)
    }

    private fun drawLabel(canvas: Canvas, s: Segment) {
        if (s.label.isEmpty()) return
        val mx = (s.x1 + s.x2) / 2
        val my = (s.y1 + s.y2) / 2
        val w = labelText.measureText(s.label) / 2 + 6 * d
        canvas.drawRoundRect(mx - w, my - 14 * d, mx + w, my + 6 * d, 6 * d, 6 * d, labelBg)
        canvas.drawText(s.label, mx, my, labelText)
    }
}
