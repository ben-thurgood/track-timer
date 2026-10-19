package com.thurgood.hotwheelstimer

import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Watches the START and FINISH boxes on every camera frame and times a car between them.
 *
 * How it works:
 *  1. When you press "Ready!" it records what the empty track looks like inside each box
 *     (averaged over a few frames) - the "baseline".
 *  2. Each frame it compares the brightness of a grid of sample pixels in each box with the
 *     baseline. If enough of them have changed, something (a car!) is in that box.
 *  3. Start box changes -> clock starts. Finish box changes -> clock stops.
 *
 * Times come from the camera's own per-frame timestamps, not the phone's clock, so they are
 * accurate to one frame (about 17 ms at 60 fps, 33 ms at 30 fps).
 *
 * Tip: a car already sitting in the start box when you press Ready is fine - it becomes part of
 * the baseline and the clock starts the moment it moves away.
 */
class TrackTimer(private val listener: Listener) : ImageAnalysis.Analyzer {

    interface Listener {
        /** Called on the camera thread for every analysed frame. */
        fun onFrame(info: FrameInfo)
        fun onRaceFinished(elapsedNanos: Long)
        fun onRaceTimedOut()
    }

    enum class State { IDLE, ARMING, READY, RUNNING }

    data class FrameInfo(
        val state: State,
        val imageAspect: Float,
        val fps: Float,
        val startLevel: Float,
        val finishLevel: Float,
        val elapsedNanos: Long,
    )

    /** Fraction (0..1) of a box's sample pixels that must change to count as a car. */
    @Volatile var triggerFraction = 0.15f

    /** How much (0-255) a pixel's brightness must change to count as "changed". */
    @Volatile var pixelThreshold = 28

    private enum class Command { ARM, DISARM }
    private val pendingCommand = java.util.concurrent.atomic.AtomicReference<Command?>(null)

    fun arm() { pendingCommand.set(Command.ARM) }
    fun disarm() { pendingCommand.set(Command.DISARM) }

    // Boxes are written by the UI thread and read on the camera thread.
    private val boxLock = Any()
    private val startBoxN = RectF()
    private val finishBoxN = RectF()
    private var boxesDirty = true

    fun setBoxes(start: RectF, finish: RectF) {
        synchronized(boxLock) {
            startBoxN.set(start)
            finishBoxN.set(finish)
            boxesDirty = true
        }
    }

    // ---- state (camera thread only) ----
    private var state = State.IDLE
    private var startTimeNs = 0L

    private var geomKey = ""
    private var startIdx = IntArray(0)
    private var finishIdx = IntArray(0)
    private var startCur = IntArray(0)
    private var finishCur = IntArray(0)
    private var startRef = IntArray(0)   // baseline (or previous frame while idle)
    private var finishRef = IntArray(0)
    private var startSum = IntArray(0)
    private var finishSum = IntArray(0)
    private var armFrames = 0

    private val tsRing = LongArray(30)
    private var tsCount = 0

    private companion object {
        const val ARM_FRAME_COUNT = 8
        const val GRID = 40                       // up to 40x40 samples per box
        const val MIN_RACE_NS = 100_000_000L      // ignore finish triggers in first 0.1 s
        const val TIMEOUT_NS = 30_000_000_000L    // give up after 30 s
    }

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        val ts = image.imageInfo.timestamp
        val rot = image.imageInfo.rotationDegrees
        val w = image.width
        val h = image.height
        val plane = image.planes[0]               // Y (brightness) plane
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val aspect = if (rot % 180 == 0) w.toFloat() / h else h.toFloat() / w

        val fps = recordTimestamp(ts)

        // Rebuild sample positions if the boxes or frame geometry changed.
        val key = "$w,$h,$rot,$rowStride,$pixStride"
        synchronized(boxLock) {
            if (boxesDirty || key != geomKey) {
                geomKey = key
                startIdx = buildSamples(startBoxN, w, h, rot, rowStride, pixStride)
                finishIdx = buildSamples(finishBoxN, w, h, rot, rowStride, pixStride)
                startCur = IntArray(startIdx.size); finishCur = IntArray(finishIdx.size)
                startRef = IntArray(startIdx.size); finishRef = IntArray(finishIdx.size)
                startSum = IntArray(startIdx.size); finishSum = IntArray(finishIdx.size)
                boxesDirty = false
                // Geometry changed under an armed race: re-learn the baseline.
                if (state != State.IDLE) beginArming() else armFrames = -1
            }
        }

        read(buf, startIdx, startCur)
        read(buf, finishIdx, finishCur)

        when (pendingCommand.getAndSet(null)) {
            Command.ARM -> beginArming()
            Command.DISARM -> state = State.IDLE
            null -> {}
        }

        var startLevel = 0f
        var finishLevel = 0f
        var elapsed = 0L

        when (state) {
            State.IDLE -> {
                // Show frame-to-frame motion so you can see detection working while setting up.
                if (armFrames != -1) {
                    startLevel = changedFraction(startCur, startRef)
                    finishLevel = changedFraction(finishCur, finishRef)
                }
                startCur.copyInto(startRef); finishCur.copyInto(finishRef)
                armFrames = 0
            }

            State.ARMING -> {
                for (i in startCur.indices) startSum[i] += startCur[i]
                for (i in finishCur.indices) finishSum[i] += finishCur[i]
                armFrames++
                if (armFrames >= ARM_FRAME_COUNT) {
                    for (i in startSum.indices) startRef[i] = startSum[i] / armFrames
                    for (i in finishSum.indices) finishRef[i] = finishSum[i] / armFrames
                    state = State.READY
                }
            }

            State.READY -> {
                startLevel = changedFraction(startCur, startRef)
                finishLevel = changedFraction(finishCur, finishRef)
                if (startLevel >= triggerFraction) {
                    startTimeNs = ts
                    state = State.RUNNING
                }
            }

            State.RUNNING -> {
                startLevel = changedFraction(startCur, startRef)
                finishLevel = changedFraction(finishCur, finishRef)
                elapsed = ts - startTimeNs
                if (elapsed > MIN_RACE_NS && finishLevel >= triggerFraction) {
                    state = State.IDLE
                    listener.onRaceFinished(elapsed)
                } else if (elapsed > TIMEOUT_NS) {
                    state = State.IDLE
                    listener.onRaceTimedOut()
                }
            }
        }

        listener.onFrame(
            FrameInfo(state, aspect, fps, startLevel / triggerFraction, finishLevel / triggerFraction, elapsed)
        )
    }

    private fun beginArming() {
        startSum.fill(0); finishSum.fill(0)
        armFrames = 0
        state = State.ARMING
    }

    private fun read(buf: java.nio.ByteBuffer, idx: IntArray, out: IntArray) {
        val limit = buf.limit()
        for (i in idx.indices) {
            val p = idx[i]
            out[i] = if (p < limit) buf.get(p).toInt() and 0xFF else 0
        }
    }

    private fun changedFraction(cur: IntArray, ref: IntArray): Float {
        if (cur.isEmpty()) return 0f
        val t = pixelThreshold
        var n = 0
        for (i in cur.indices) if (abs(cur[i] - ref[i]) > t) n++
        return n.toFloat() / cur.size
    }

    /**
     * Converts a box given in normalised *upright* image coordinates (what you see on screen)
     * into byte offsets in the camera's Y plane, which may be stored rotated.
     */
    private fun buildSamples(
        box: RectF, w: Int, h: Int, rot: Int, rowStride: Int, pixStride: Int
    ): IntArray {
        val upW = if (rot % 180 == 0) w else h
        val upH = if (rot % 180 == 0) h else w
        val cols = max(1, min(GRID, (box.width() * upW).toInt()))
        val rows = max(1, min(GRID, (box.height() * upH).toInt()))
        val out = IntArray(cols * rows)
        var k = 0
        for (r in 0 until rows) {
            val v = box.top + (r + 0.5f) / rows * box.height()
            for (c in 0 until cols) {
                val u = box.left + (c + 0.5f) / cols * box.width()
                val (fx, fy) = when (rot) {
                    90 -> v to (1f - u)
                    180 -> (1f - u) to (1f - v)
                    270 -> (1f - v) to u
                    else -> u to v
                }
                val x = (fx * w).toInt().coerceIn(0, w - 1)
                val y = (fy * h).toInt().coerceIn(0, h - 1)
                out[k++] = y * rowStride + x * pixStride
            }
        }
        return out
    }

    private fun recordTimestamp(ts: Long): Float {
        tsRing[tsCount % tsRing.size] = ts
        tsCount++
        val n = min(tsCount, tsRing.size)
        if (n < 2) return 0f
        val newest = tsRing[(tsCount - 1) % tsRing.size]
        val oldest = tsRing[(tsCount - n) % tsRing.size]
        val span = newest - oldest
        return if (span > 0) (n - 1) * 1e9f / span else 0f
    }
}
