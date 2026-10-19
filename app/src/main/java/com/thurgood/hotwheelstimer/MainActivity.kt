package com.thurgood.hotwheelstimer

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.thurgood.hotwheelstimer.TrackTimer.State
import com.thurgood.hotwheelstimer.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TrackTimer.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private val timer = TrackTimer(this)
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var tone: ToneGenerator? = null

    // Fires on every rotation, including 180° flips that don't trigger a configuration change.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = updateTargetRotation()
    }

    private var lastState = State.IDLE
    private var fpsRangeText = ""
    private val results = mutableListOf<Double>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else binding.statusText.text = "Camera permission is needed to time the cars."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        // Keep the timer and buttons clear of any camera hole-punch / notch.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            WindowInsetsCompat.CONSUMED
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

        arrangeFor(resources.configuration.orientation)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)

        binding.overlay.onBoxesChanged = { pushBoxes() }
        binding.overlay.post { pushBoxes() }

        binding.armButton.setOnClickListener {
            if (lastState == State.IDLE) {
                setAutoLocks(true)
                timer.arm()
            } else {
                timer.disarm()
                setAutoLocks(false)
            }
        }

        binding.clearButton.setOnClickListener {
            results.clear()
            showResults()
        }

        binding.sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = applySensitivity(progress)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        applySensitivity(binding.sensitivityBar.progress)
        showResults()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------------- rotation ----------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeFor(newConfig.orientation)
        updateTargetRotation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full screen: hide the status bar and the Android buttons. Swipe from an edge to show them briefly. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Puts the timer, results and controls into the black panels around the camera.
     * Portrait : timer + status on top, logo/buttons + slider underneath, results over the picture's corner.
     * Landscape: everything in one column on the right, camera fills the rest.
     */
    private fun arrangeFor(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val b = binding
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        fun lin(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

        // Detach the movable widgets from wherever they currently are.
        listOf(b.timeText, b.statusText, b.resultsText, b.logo, b.buttonRow, b.sliderRow)
            .forEach { (it.parent as? ViewGroup)?.removeView(it) }

        b.root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        b.cameraFrame.layoutParams = if (landscape) lin(0, match, 1f) else lin(match, 0, 1f)
        b.panelB.gravity = Gravity.CENTER_HORIZONTAL

        if (landscape) {
            b.panelA.visibility = View.GONE
            b.panelB.layoutParams = lin(dp(224), match)
            b.timeText.textSize = 34f
            b.resultsText.background = null
            b.panelB.addView(b.logo, lin(wrap, dp(40)).apply { topMargin = dp(4) })
            b.panelB.addView(b.timeText, lin(wrap, wrap).apply { topMargin = dp(4) })
            b.panelB.addView(b.statusText, lin(match, wrap))
            b.panelB.addView(b.resultsText, lin(match, 0, 1f).apply { topMargin = dp(6) })
            b.panelB.addView(b.buttonRow, lin(wrap, wrap))
            b.panelB.addView(b.sliderRow, lin(match, wrap))
        } else {
            b.panelA.visibility = View.VISIBLE
            b.panelA.layoutParams = lin(match, wrap)
            b.panelB.layoutParams = lin(match, wrap)
            b.timeText.textSize = 40f
            b.panelA.addView(b.timeText, lin(wrap, wrap))
            b.panelA.addView(b.statusText, lin(wrap, wrap))
            b.resultsText.setBackgroundColor(0x99000000.toInt())
            b.cameraFrame.addView(
                b.resultsText,
                FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
            )
            b.buttonRow.addView(b.logo, 0, lin(wrap, dp(44)).apply { marginEnd = dp(8) })
            b.panelB.addView(b.buttonRow, lin(wrap, wrap))
            b.panelB.addView(b.sliderRow, lin(match, wrap))
        }
    }

    /** Tell the camera which way up the screen is, so preview and analysis frames match it. */
    private fun updateTargetRotation() {
        val rotation = binding.previewView.display?.rotation ?: return
        preview?.targetRotation = rotation
        analysis?.targetRotation = rotation
    }

    /** Higher slider = more sensitive = fewer pixels need to change to trigger. */
    private fun applySensitivity(progress: Int) {
        val p = progress / 100f
        timer.triggerFraction = 0.50f - 0.47f * p   // 50% (dull) .. 3% (very twitchy)
    }

    private fun pushBoxes() {
        timer.setBoxes(binding.overlay.startBox, binding.overlay.finishBox)
    }

    // ---------------- camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bindCamera(future.get())
            } catch (e: Exception) {
                binding.statusText.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(provider: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val fpsRange = pickFpsRange(provider, selector)
        fpsRangeText = fpsRange?.let { "${it.lower}-${it.upper}" } ?: "default"

        // Same 16:9 shape for preview and analysis so the boxes line up with what's analysed.
        // 16:9 fills far more of a modern tall phone screen than 4:3.
        val previewBuilder = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
        )

        // Small frames are plenty for spotting a car and keep the analysis fast.
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 360),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        // Ask for the fastest frame rate the camera offers in a normal session.
        fpsRange?.let {
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }

        val rotation = binding.previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        previewBuilder.setTargetRotation(rotation)
        analysisBuilder.setTargetRotation(rotation)

        val p = previewBuilder.build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val a = analysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor, timer)
        }
        preview = p
        analysis = a

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, selector, p, a)
        binding.previewView.post { updateTargetRotation() }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun pickFpsRange(provider: ProcessCameraProvider, selector: CameraSelector): Range<Int>? {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return null
        val ranges = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        // Highest max fps, and prefer a fixed range (e.g. 60-60 over 15-60) for steady timing.
        return ranges.maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
    }

    /** Freeze exposure & white balance while racing so lighting tweaks don't look like cars. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setAutoLocks(locked: Boolean) {
        val cam = camera ?: return
        val ctl = Camera2CameraControl.from(cam.cameraControl)
        if (locked) {
            ctl.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build()
            )
        } else {
            ctl.clearCaptureRequestOptions()
        }
    }

    // ---------------- TrackTimer callbacks (camera thread) ----------------

    override fun onFrame(info: TrackTimer.FrameInfo) {
        runOnUiThread {
            val o = binding.overlay
            o.imageAspect = info.imageAspect
            o.startLevel = info.startLevel
            o.finishLevel = info.finishLevel
            o.editable = info.state == State.IDLE
            o.invalidate()

            if (info.state == State.RUNNING && lastState != State.RUNNING) beep(ToneGenerator.TONE_PROP_BEEP)
            if (info.state == State.RUNNING) binding.timeText.text = formatSecs(info.elapsedNanos / 1e9)
            if (info.state == State.READY && lastState != State.READY) binding.timeText.text = formatSecs(0.0)

            val label = when (info.state) {
                State.IDLE -> "Drag the boxes, then press Ready!"
                State.ARMING -> "Learning the empty track…"
                State.READY -> "Ready – let it go!"
                State.RUNNING -> "Racing!"
            }
            binding.statusText.text = String.format(
                Locale.UK, "%s · %.0f fps · ±%.0f ms",
                label, info.fps, if (info.fps > 0) 1000 / info.fps else 0f
            )
            binding.armButton.text = if (info.state == State.IDLE) "Ready!" else "Cancel"
            lastState = info.state
        }
    }

    override fun onRaceFinished(elapsedNanos: Long) {
        val secs = elapsedNanos / 1e9
        runOnUiThread {
            beep(ToneGenerator.TONE_PROP_ACK)
            setAutoLocks(false)
            results.add(0, secs)
            binding.timeText.text = formatSecs(secs)
            showResults()
        }
    }

    override fun onRaceTimedOut() {
        runOnUiThread {
            setAutoLocks(false)
            Toast.makeText(this, "No finish seen within 30 s – try moving the finish box", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBoxesRotated(start: RectF, finish: RectF) {
        runOnUiThread {
            binding.overlay.startBox.set(start)
            binding.overlay.finishBox.set(finish)
            binding.overlay.invalidate()
        }
    }

    // ---------------- helpers ----------------

    private fun showResults() {
        if (results.isEmpty()) {
            binding.resultsText.text = "Results\n–"
            return
        }
        val best = results.min()
        val sb = StringBuilder("Results\n")
        results.take(10).forEachIndexed { i, r ->
            val n = results.size - i
            sb.append(String.format(Locale.UK, "%2d. %.3f%s\n", n, r, if (r == best) " ★" else ""))
        }
        binding.resultsText.text = sb.trimEnd()
    }

    private fun formatSecs(s: Double) = String.format(Locale.UK, "%.3f s", s)

    private fun beep(toneType: Int) {
        tone?.startTone(toneType, 150)
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        cameraExecutor.shutdown()
        tone?.release()
    }
}
