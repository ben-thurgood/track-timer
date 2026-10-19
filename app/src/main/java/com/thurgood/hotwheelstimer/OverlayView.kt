package com.thurgood.hotwheelstimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the START and FINISH boxes over the camera preview and lets you drag / resize them.
 *
 * Box positions are stored *normalised* (0..1) relative to the visible camera image, not the
 * screen, so the analyser can map them straight onto the camera frame whatever the screen size.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Width / height of the upright camera image. Set from the analyser once frames arrive. */
    var imageAspect = 4f / 3f
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    val startBox = RectF(0.10f, 0.35f, 0.25f, 0.65f)
    val finishBox = RectF(0.75f, 0.35f, 0.90f, 0.65f)

    /** 0..1 how "changed" each box currently looks; drawn as a fill so you can see detection live. */
    var startLevel = 0f
    var finishLevel = 0f

    /** When false (a race is armed) the boxes are locked. */
    var editable = true

    /** Called when the user finishes moving a box. */
    var onBoxesChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handleRadius = 14 * density
    private val touchSlop = 24 * density

    private val startPaint = strokePaint(Color.rgb(0, 200, 83))
    private val finishPaint = strokePaint(Color.rgb(255, 61, 0))
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16 * density
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun strokePaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }

    /** The rectangle (in view pixels) where the camera image is actually drawn (fitCenter). */
    private fun contentRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return RectF()
        return if (vw / vh > imageAspect) {
            val w = vh * imageAspect
            RectF((vw - w) / 2, 0f, (vw + w) / 2, vh)
        } else {
            val h = vw / imageAspect
            RectF(0f, (vh - h) / 2, vw, (vh + h) / 2)
        }
    }

    private fun toScreen(n: RectF, c: RectF) = RectF(
        c.left + n.left * c.width(), c.top + n.top * c.height(),
        c.left + n.right * c.width(), c.top + n.bottom * c.height()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = contentRect()
        drawBox(canvas, toScreen(startBox, c), startPaint, "START", startLevel)
        drawBox(canvas, toScreen(finishBox, c), finishPaint, "FINISH", finishLevel)
    }

    private fun drawBox(canvas: Canvas, r: RectF, stroke: Paint, label: String, level: Float) {
        fillPaint.color = stroke.color
        fillPaint.alpha = (min(level, 1f) * 160).toInt()
        canvas.drawRect(r, fillPaint)
        canvas.drawRect(r, stroke)
        canvas.drawText(label, r.left + 4 * density, r.top - 6 * density, labelPaint)
        if (editable) {
            fillPaint.color = stroke.color
            fillPaint.alpha = 255
            canvas.drawCircle(r.right, r.bottom, handleRadius, fillPaint)
        }
    }

    // ---------- touch handling ----------

    private enum class Mode { NONE, MOVE, RESIZE }
    private var mode = Mode.NONE
    private var active: RectF? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editable) return false
        val c = contentRect()
        if (c.isEmpty) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.NONE
                // Check resize handles first, then box bodies. Finish first so it wins if overlapping.
                for (box in listOf(finishBox, startBox)) {
                    val s = toScreen(box, c)
                    if (dist(e.x, e.y, s.right, s.bottom) < handleRadius + touchSlop) {
                        mode = Mode.RESIZE; active = box; break
                    }
                }
                if (mode == Mode.NONE) {
                    for (box in listOf(finishBox, startBox)) {
                        val s = toScreen(box, c)
                        s.inset(-touchSlop, -touchSlop)
                        if (s.contains(e.x, e.y)) { mode = Mode.MOVE; active = box; break }
                    }
                }
                lastX = e.x; lastY = e.y
                return mode != Mode.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                val box = active ?: return false
                val dx = (e.x - lastX) / c.width()
                val dy = (e.y - lastY) / c.height()
                lastX = e.x; lastY = e.y
                if (mode == Mode.MOVE) {
                    val ox = clampOffset(box.left, box.right, dx)
                    val oy = clampOffset(box.top, box.bottom, dy)
                    box.offset(ox, oy)
                } else if (mode == Mode.RESIZE) {
                    val minSize = 0.03f
                    box.right = (box.right + dx).coerceIn(box.left + minSize, 1f)
                    box.bottom = (box.bottom + dy).coerceIn(box.top + minSize, 1f)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != Mode.NONE) onBoxesChanged?.invoke()
                mode = Mode.NONE; active = null
                return true
            }
        }
        return false
    }

    private fun clampOffset(lo: Float, hi: Float, d: Float) = max(-lo, min(1f - hi, d))

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
