package com.example.personcounter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.personcounter.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), DetectionHelper.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private lateinit var detectionHelper: DetectionHelper
    private lateinit var ipCameraManager: IpCameraManager

    // Single background thread for CameraX analysis + TFLite + ML Kit
    private lateinit var cameraExecutor: ExecutorService

    // Scheduler for IP camera frame polling
    private val ipScheduler = ScheduledThreadPoolExecutor(1)
    private var ipPollFuture: ScheduledFuture<*>? = null

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Tracks whether settings changed while SettingsActivity was open
    private var settingsChanged = false

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showCameraUi() else showPermissionDeniedUi()
            if (granted) startActiveSource()
        }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding  = ActivityMainBinding.inflate(layoutInflater)
        settings = SettingsManager(this)
        setContentView(binding.root)

        cameraExecutor  = Executors.newSingleThreadExecutor()
        ipCameraManager = IpCameraManager(this)

        detectionHelper = DetectionHelper(
            context   = this,
            threshold = settings.getThreshold(),
            listener  = this
        )
        cameraExecutor.execute { detectionHelper.setup() }

        ipCameraManager.onStateChanged = { connected, error ->
            runOnUiThread {
                if (!connected && error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
                binding.tvIpStatus.text  = if (connected) "● Connected" else "● Disconnected"
                binding.tvIpStatus.setTextColor(
                    ContextCompat.getColor(this, if (connected) android.R.color.holo_green_light
                                                 else android.R.color.holo_red_light)
                )
            }
        }

        binding.btnRequestPermission.setOnClickListener {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        binding.fabSettings.setOnClickListener {
            startActivityForResult(
                Intent(this, SettingsActivity::class.java),
                REQUEST_SETTINGS
            )
        }

        checkPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (settingsChanged) {
            settingsChanged = false
            restartActiveSource()
        } else if (hasCameraPermission() && cameraProvider == null
                   && settings.getActiveCamera() != SettingsManager.CAMERA_IP) {
            startActiveSource()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopIpPolling()
        ipCameraManager.release()
        detectionHelper.release()
        cameraExecutor.shutdown()
        ipScheduler.shutdown()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS && resultCode == Activity.RESULT_OK) {
            // Apply updated threshold immediately; camera restart happens in onResume
            detectionHelper.threshold = settings.getThreshold()
            settingsChanged = true
        }
    }

    // -------------------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkPermissionAndStart() {
        val needsCamera = settings.getActiveCamera() != SettingsManager.CAMERA_IP
        if (!needsCamera || hasCameraPermission()) {
            showCameraUi()
            startActiveSource()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // -------------------------------------------------------------------------
    // UI visibility helpers
    // -------------------------------------------------------------------------

    private fun showCameraUi() {
        binding.permissionLayout.visibility = View.GONE
    }

    private fun showPermissionDeniedUi() {
        binding.previewView.visibility   = View.GONE
        binding.overlayView.visibility   = View.GONE
        binding.countsLayout.visibility  = View.GONE
        binding.permissionLayout.visibility = View.VISIBLE
    }

    private fun switchToRegularCameraUi() {
        binding.previewView.visibility   = View.VISIBLE
        binding.overlayView.visibility   = View.VISIBLE
        binding.ipCameraLayout.visibility = View.GONE
        binding.tvIpStatus.visibility    = View.GONE
        binding.countsLayout.visibility  = View.VISIBLE
    }

    private fun switchToIpCameraUi() {
        binding.previewView.visibility    = View.GONE
        binding.overlayView.visibility    = View.VISIBLE   // still used for drawing boxes
        binding.ipCameraLayout.visibility = View.VISIBLE
        binding.tvIpStatus.visibility     = View.VISIBLE
        binding.countsLayout.visibility   = View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Start / stop camera sources
    // -------------------------------------------------------------------------

    private fun startActiveSource() {
        val active = settings.getActiveCamera()
        if (active == SettingsManager.CAMERA_IP) {
            stopPhysicalCamera()
            switchToIpCameraUi()
            startIpCamera()
        } else {
            stopIpPolling()
            switchToRegularCameraUi()
            startPhysicalCamera(active)
        }
    }

    private fun restartActiveSource() {
        stopIpPolling()
        ipCameraManager.release()
        cameraProvider?.unbindAll()
        cameraProvider = null
        startActiveSource()
    }

    // ── Physical camera ──────────────────────────────────────────────────────

    private fun startPhysicalCamera(cameraId: String) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCameraUseCases(cameraId)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPhysicalCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    private fun bindCameraUseCases(cameraId: String) {
        val provider = cameraProvider ?: return

        val lensFacing = if (cameraId == SettingsManager.CAMERA_FRONT)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val analyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(cameraExecutor) { proxy -> processPhysicalFrame(proxy) } }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, analyzer)
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processPhysicalFrame(proxy: ImageProxy) {
        val bitmap = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(proxy.planes[0].buffer)
        val rotation = proxy.imageInfo.rotationDegrees
        proxy.close()

        val rotated = if (rotation != 0) {
            val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } else bitmap

        detectionHelper.detect(rotated)
    }

    // ── IP camera ────────────────────────────────────────────────────────────

    private fun startIpCamera() {
        val url = settings.buildAuthenticatedUrl()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.error_no_ip_url, Toast.LENGTH_LONG).show()
            return
        }

        val textureView = binding.ipTextureView
        ipCameraManager.connect(url, textureView)

        // Poll frames every 200 ms on the background executor thread
        ipPollFuture = ipScheduler.scheduleAtFixedRate({
            if (!ipCameraManager.isConnected) return@scheduleAtFixedRate
            val bmp = ipCameraManager.getFrameBitmap() ?: return@scheduleAtFixedRate
            detectionHelper.detect(bmp)
        }, 500, 200, TimeUnit.MILLISECONDS)
    }

    private fun stopIpPolling() {
        ipPollFuture?.cancel(false)
        ipPollFuture = null
    }

    // -------------------------------------------------------------------------
    // DetectionHelper.DetectorListener callbacks  (called on background thread)
    // -------------------------------------------------------------------------

    override fun onError(error: String) {
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_LONG).show() }
    }

    override fun onResults(result: DetectionHelper.DetectionResult) {
        runOnUiThread {
            binding.tvFaceCount.text   = result.faceCount.toString()
            binding.tvPersonCount.text = result.personCount.toString()
            binding.tvRaisedCount.text = result.raisedHandCount.toString()
            binding.tvInferenceTime.text = "${result.inferenceMs}ms"
            binding.overlayView.setResults(result)
        }
    }

    companion object {
        private const val TAG             = "MainActivity"
        private const val REQUEST_SETTINGS = 1001
    }
}
