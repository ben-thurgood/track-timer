package com.thurgood.hotwheelstimer

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Range
import android.util.Size
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
    private var tone: ToneGenerator? = null

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

        cameraExecutor = Executors.newSingleThreadExecutor()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

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

        // Same 4:3 shape for preview and analysis so the boxes line up with what's analysed.
        val previewBuilder = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
        )

        // Small frames are plenty for spotting a car and keep the analysis fast.
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
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

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val analysis = analysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor, timer)
        }

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, selector, preview, analysis)
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
                Locale.UK, "%s\n%.0f fps (asked %s) · ±%.0f ms",
                label, info.fps, fpsRangeText, if (info.fps > 0) 1000 / info.fps else 0f
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
        cameraExecutor.shutdown()
        tone?.release()
    }
}
