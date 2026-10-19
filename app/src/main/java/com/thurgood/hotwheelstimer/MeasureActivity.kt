package com.thurgood.hotwheelstimer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.thurgood.hotwheelstimer.databinding.ActivityMeasureBinding
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Measures the track with ARCore. Aim the crosshair at the start line and press "Add point", then add
 * a point at each bend (and up/down ramps) until the finish. The real-world length is the sum of the
 * straight pieces between points. "Use" sends the total back to the timer.
 */
class MeasureActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        const val EXTRA_METERS = "meters"
        /** Set on a cancelled result when this phone can't do AR, so the caller can offer typing it in. */
        const val EXTRA_AR_UNAVAILABLE = "arUnavailable"
        private const val MAX_POINTS = 60
    }

    private lateinit var binding: ActivityMeasureBinding
    private var session: Session? = null
    private var installRequested = false
    private val background = BackgroundRenderer()

    // GL-thread state
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastRotation = -1
    private var geometryDirty = true
    private var textureBound = false
    private val anchors = mutableListOf<Anchor>()

    private sealed class Action {
        class Tap(val x: Float, val y: Float) : Action()
        object AddAtCrosshair : Action()
        object Undo : Action()
        object Clear : Action()
    }
    private val actions = ConcurrentLinkedQueue<Action>()

    @Volatile private var totalMeters = 0.0
    private var shownTotal = ""
    private var shownHint = ""
    private var shownCanUse = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeasureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterFullScreen(binding.root)

        binding.glView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MeasureActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }

        // Tapping the picture also drops a point exactly where you tapped.
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                actions.add(Action.Tap(e.x, e.y))
                return true
            }
            override fun onDown(e: MotionEvent) = true
        })
        binding.overlay.setOnTouchListener { _, e -> taps.onTouchEvent(e) }

        binding.backButton.setOnClickListener { finish() }
        binding.addButton.setOnClickListener { actions.add(Action.AddAtCrosshair) }
        binding.undoButton.setOnClickListener { actions.add(Action.Undo) }
        binding.clearButton.setOnClickListener { actions.add(Action.Clear) }
        binding.doneButton.setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_METERS, totalMeters))
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                // First run on a phone without "Google Play Services for AR": ask Play Store to install it.
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED
                ) {
                    installRequested = true
                    return
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    giveUp("Camera permission is needed to measure.", arUnavailable = false)
                    return
                }
                val s = Session(this)
                val config = Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    // Depth lets points land on ramps and bumps, not just flat floor.
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                }
                s.configure(config)
                session = s
                textureBound = false
            } catch (e: UnavailableUserDeclinedInstallationException) {
                giveUp("AR measuring needs \"Google Play Services for AR\" from the Play Store.", arUnavailable = true)
                return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                giveUp("Sorry, this phone doesn't support AR measuring.", arUnavailable = true)
                return
            } catch (e: Exception) {
                giveUp("Couldn't start AR: ${e.message}", arUnavailable = true)
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session?.close()
            session = null
            giveUp("The camera is busy – go back and try again.", arUnavailable = false)
            return
        }
        binding.glView.onResume()
        geometryDirty = true
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            binding.glView.onPause()   // waits for the GL thread to stop before pausing ARCore
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun giveUp(message: String, arUnavailable: Boolean) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_AR_UNAVAILABLE, arUnavailable))
        finish()
    }

    // ---------------- GL thread ----------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (viewWidth == 0) return

        if (!textureBound) {
            s.setCameraTextureName(background.textureId)
            textureBound = true
        }
        val rotation = binding.glView.display?.rotation ?: 0
        if (geometryDirty || rotation != lastRotation) {
            s.setDisplayGeometry(rotation, viewWidth, viewHeight)
            lastRotation = rotation
            geometryDirty = false
        }

        val frame = try { s.update() } catch (e: Exception) { return }
        background.draw(frame)
        val camera = frame.camera

        // Button presses and taps from the UI thread.
        while (true) {
            when (val a = actions.poll() ?: break) {
                is Action.Tap -> placePoint(frame, a.x, a.y)
                Action.AddAtCrosshair -> placePoint(frame, viewWidth / 2f, viewHeight / 2f)
                Action.Undo -> anchors.removeLastOrNull()?.detach()
                Action.Clear -> { anchors.forEach { it.detach() }; anchors.clear() }
            }
        }

        if (camera.trackingState != TrackingState.TRACKING) {
            binding.overlay.state = null
            binding.overlay.postInvalidate()
            publish(totalOf(positions()), trackingHint(camera.trackingFailureReason))
            return
        }

        val proj = FloatArray(16)
        val view = FloatArray(16)
        val viewProj = FloatArray(16)
        camera.getProjectionMatrix(proj, 0, 0.02f, 50f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        val pts = positions()
        val aim = bestHit(frame, viewWidth / 2f, viewHeight / 2f)?.hitPose?.let { floatArrayOf(it.tx(), it.ty(), it.tz()) }
        val total = totalOf(pts)

        // Project the 3D points onto the screen for the overlay.
        val dots = pts.mapNotNull { p -> toClip(viewProj, p).takeIf { it[3] > NEAR }?.let { toScreen(it) } }
        val segments = (1 until pts.size).mapNotNull { i ->
            screenSegment(viewProj, pts[i - 1], pts[i], cm(dist(pts[i - 1], pts[i])))
        }
        val preview = if (aim != null && pts.isNotEmpty())
            screenSegment(viewProj, pts.last(), aim, "+" + cm(dist(pts.last(), aim))) else null

        binding.overlay.state = MeasureOverlay.State(dots, segments, preview, aimOk = aim != null)
        binding.overlay.postInvalidate()

        val hint = when {
            pts.isEmpty() && aim == null -> "Slowly sweep the phone over the track and floor…"
            pts.isEmpty() -> "Aim the ＋ at the START line, then press Add point"
            pts.size == 1 -> "Follow the track – add a point at each bend, ramp and at the FINISH"
            else -> "${pts.size} points. Keep going to the finish, then press ✓ Use"
        }
        publish(total, hint)
    }

    private fun positions(): List<FloatArray> = anchors
        .filter { it.trackingState != TrackingState.STOPPED }
        .map { val p = it.pose; floatArrayOf(p.tx(), p.ty(), p.tz()) }

    private fun placePoint(frame: Frame, x: Float, y: Float) {
        if (frame.camera.trackingState != TrackingState.TRACKING) return
        if (anchors.size >= MAX_POINTS) {
            toast("That's plenty of points! Press ✓ Use or Undo some.")
            return
        }
        val hit = bestHit(frame, x, y)
        if (hit == null) {
            toast("Can't find a surface there yet – move the phone around a little")
            return
        }
        anchors.add(hit.createAnchor())
    }

    /** First hit on something real: a detected surface, a depth-mapped point, or a textured feature. */
    private fun bestHit(frame: Frame, x: Float, y: Float): HitResult? =
        frame.hitTest(x, y).firstOrNull { hit ->
            when (val t = hit.trackable) {
                is Plane -> t.isPoseInPolygon(hit.hitPose) && hit.distance > 0f
                is DepthPoint -> true
                is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }

    private fun trackingHint(reason: TrackingFailureReason) = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark – switch a light on"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast – go slower"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at something with more detail (not a plain floor)"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera busy – try again"
        else -> "Move the phone slowly from side to side to start…"
    }

    private fun publish(total: Double, hint: String) {
        totalMeters = total
        val totalText = String.format(Locale.UK, "📏 %.2f m", total)
        val canUse = anchors.size >= 2 && total > 0.01
        if (totalText == shownTotal && hint == shownHint && canUse == shownCanUse) return
        shownTotal = totalText
        shownHint = hint
        shownCanUse = canUse
        runOnUiThread {
            binding.totalText.text = totalText
            binding.hintText.text = hint
            binding.doneButton.isEnabled = canUse
            binding.doneButton.text = if (canUse) String.format(Locale.UK, "✓ Use %.2f m", total) else "✓ Use"
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ---------------- maths ----------------

    private val NEAR = 0.01f

    private fun totalOf(pts: List<FloatArray>): Double =
        (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]).toDouble() }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun cm(m: Float) = String.format(Locale.UK, "%.0f cm", m * 100)

    private fun toClip(viewProj: FloatArray, p: FloatArray): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, viewProj, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
        return out
    }

    private fun toScreen(c: FloatArray) = PointF(
        (c[0] / c[3] * 0.5f + 0.5f) * viewWidth,
        (1f - (c[1] / c[3] * 0.5f + 0.5f)) * viewHeight
    )

    /** Projects a 3D line onto the screen, trimming any part that's behind the camera. */
    private fun screenSegment(viewProj: FloatArray, a: FloatArray, b: FloatArray, label: String): MeasureOverlay.Segment? {
        var ca = toClip(viewProj, a)
        var cb = toClip(viewProj, b)
        if (ca[3] <= NEAR && cb[3] <= NEAR) return null
        if (ca[3] <= NEAR) ca = lerp(ca, cb, (NEAR - ca[3]) / (cb[3] - ca[3]))
        if (cb[3] <= NEAR) cb = lerp(cb, ca, (NEAR - cb[3]) / (ca[3] - cb[3]))
        val pa = toScreen(ca)
        val pb = toScreen(cb)
        return MeasureOverlay.Segment(pa.x, pa.y, pb.x, pb.y, label)
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = FloatArray(4) { a[it] + (b[it] - a[it]) * t }
}
