package io.github.zeroblur

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import io.github.zeroblur.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.io.File
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.FrameLayout
import android.view.ViewGroup
import android.content.Context

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide system bars
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        viewBinding.imageCaptureButton.setOnClickListener { takeFocusBurst() }
        viewBinding.settingsButton.setOnClickListener { showSettingsDialog() }

        Log.i(TAG, "MainActivity onCreate")
        if (org.opencv.android.OpenCVLoader.initDebug()) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
        }
        
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Tap to Focus
        viewBinding.viewFinder.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val factory = viewBinding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = androidx.camera.core.FocusMeteringAction.Builder(point).build()
                
                camera?.cameraControl?.startFocusAndMetering(action)
                Log.i(TAG, "Tap to focus at ${event.x}, ${event.y}")
                view.performClick()
            }
            true
        }
    }

    private var burstStartTime: Long = 0
    private val burstUris = mutableListOf<Uri>()

    private fun takeFocusBurst() {
        Log.i(TAG, "takeFocusBurst called")
        val imageCapture = imageCapture
        if (imageCapture == null) {
            Log.e(TAG, "imageCapture is null")
            return
        }
        val camera = camera
        if (camera == null) {
            Log.e(TAG, "camera is null")
            return
        }

        burstUris.clear() // Clear previous burst

        val minFocus = getMinimumFocusDistance(camera)
        Log.i(TAG, "Minimum focus distance: $minFocus")
        
        // Hardcoded progressive steps (Diopters)
        // 0.0 (Infinity), 5.0 (20cm), 0.2 (5m), 0.5 (2m), 0.8 (1.25m),
        // 1.2 (83cm), 1.7 (58cm), 2.5 (40cm), 3.5 (28cm), MAX
        val rawSteps = floatArrayOf(0.0f, 5.0f, 0.2f, 0.5f, 0.8f, 1.2f, 1.7f, 2.5f, 3.5f)
        
        // Create final list ensuring we don't exceed minFocus (max diopter)
        // and always include the actual minFocus as the last step.
        val focusSteps = rawSteps.filter { it < minFocus }.toMutableList()
        focusSteps.add(minFocus)
        
        // Ensure we have exactly 10 steps if possible, or just use what we have.
        // If we have fewer than 10 (because minFocus is small), that's fine.
        // If we want exactly 10, we might need to interpolate, but for now let's stick to the specific meaningful values.
        // The user asked for "10 pictures", so if we have fewer, we might want to pad or just take fewer.
        // But "MAX" is dynamic. Let's just use this list. 
        // If minFocus is very large (e.g. 10.0), we get 0.0, 0.2, ... 5.0, 10.0. (10 items)
        // If minFocus is small (e.g. 4.0), we get 0.0, 0.2, ... 3.5, 4.0. (9 items)
        // To strictly respect "10 pictures", we could duplicate or interpolate, but meaningful distances are better.
        // Let's stick to the list size for now, it will be close to 10.
        
        // Show "Stay Still" banner
        viewBinding.bannerCard.visibility = android.view.View.VISIBLE
        viewBinding.bannerImage.setImageResource(R.drawable.illustration_stay_still)
        viewBinding.bannerText.text = "Stay still ..."
        viewBinding.bannerSubtitle.visibility = android.view.View.GONE
        viewBinding.focusLineContainer.visibility = android.view.View.VISIBLE
        viewBinding.imageCaptureButton.isEnabled = false // Disable button during burst

        burstStartTime = android.os.SystemClock.elapsedRealtime()
        captureStep(camera, 0, focusSteps)
    }

    private fun captureStep(camera: androidx.camera.core.Camera, currentStep: Int, focusSteps: List<Float>) {
        if (currentStep >= focusSteps.size) {
            val duration = android.os.SystemClock.elapsedRealtime() - burstStartTime
            Log.d(TAG, "Burst duration: ${duration}ms")
            
            // Reset to auto focus after burst
            clearFocusOverride(camera)
            
            // Switch to "Focus Stacking" banner
            viewBinding.bannerImage.setImageResource(R.drawable.illustration_stacking)
            viewBinding.bannerText.text = "Focus stacking..."
            viewBinding.bannerSubtitle.visibility = android.view.View.VISIBLE
            viewBinding.bannerSubtitle.text = "Feel free to move your device."
            viewBinding.focusLineContainer.visibility = android.view.View.GONE
            
            // Trigger stacking in background
            cameraExecutor.execute {
                // Wait for files to be written
                try { Thread.sleep(1000) } catch (e: InterruptedException) { e.printStackTrace() }
                
                val stacker = FocusStacker(this)
                // Use burstStartTime (which is elapsedRealtime) converted to wall clock time?
                // No, we need to capture wall clock time at start of burst.
                // But we only have burstStartTime as elapsedRealtime.
                // Let's use current time for now, or better, capture wall clock time in takeFocusBurst.
                // Actually, let's just use System.currentTimeMillis() - (elapsedRealtime - burstStartTime)
                // to approximate the start time.
                val burstStartWallTime = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - burstStartTime)
                
                // Clear logs
                runOnUiThread {
                    viewBinding.stackingLogText.text = ""
                    viewBinding.stackingLogText.visibility = android.view.View.VISIBLE
                }
                
                val resultUri = stacker.process(burstUris, burstStartWallTime) { logMsg ->
                    runOnUiThread {
                        viewBinding.stackingLogText.append("$logMsg\n")
                        // Auto-scroll
                        val layout = viewBinding.stackingLogText.layout
                        if (layout != null) {
                            val scrollAmount = layout.getLineTop(viewBinding.stackingLogText.lineCount) - viewBinding.stackingLogText.height
                            if (scrollAmount > 0) {
                                viewBinding.stackingLogText.scrollTo(0, scrollAmount)
                            }
                        }
                    }
                }

                // Cleanup intermediate pictures if needed
                val prefs = getSharedPreferences("FocusESettings", Context.MODE_PRIVATE)
                val keep = prefs.getBoolean("keep_intermediate", false)
                if (!keep && resultUri != null) {
                    burstUris.forEach { uri ->
                        try {
                            contentResolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete intermediate image: $uri", e)
                        }
                    }
                }
                runOnUiThread {
                    viewBinding.bannerCard.visibility = android.view.View.GONE
                    viewBinding.imageCaptureButton.isEnabled = true
                    
                    if (resultUri != null) {
                        Toast.makeText(baseContext, "Stacking complete!", Toast.LENGTH_LONG).show()
                        updateGalleryPreview(resultUri)
                    } else {
                        Toast.makeText(baseContext, "Stacking failed.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }

        val focusDistance = focusSteps[currentStep]
        Log.i(TAG, "Capturing step $currentStep with focus distance: $focusDistance")
        setFocusDistance(camera, focusDistance)
        
        // Update Focus Line
        // 0 (Macro/MaxDiopter) to 100 (Infinity/0Diopter)
        // focusDistance is in Diopters.
        // minFocus (from getMinimumFocusDistance) is the Max Diopter value.
        val minFocus = getMinimumFocusDistance(camera)
        val maxDiopter = if (minFocus > 0) minFocus else 10.0f
        
        // Map: MaxDiopter -> 0, 0.0 -> 100
        val progress = ((1.0f - (focusDistance / maxDiopter)) * 100).toInt().coerceIn(0, 100)
        viewBinding.focusSeekBar.progress = progress
        // Disable touch interaction for the SeekBar
        viewBinding.focusSeekBar.setOnTouchListener { _, _ -> true }

        // We don't strictly wait for focus to lock because we want speed, 
        // and manual focus distance is usually applied quickly with the request.
        // However, a tiny delay might ensure the lens starts moving.
        // But for "as short as possible", we might just proceed.
        // Let's rely on the order of operations in the camera pipeline.
        
        takePhotoFast(currentStep) { uri ->
            if (uri != null) {
                burstUris.add(uri)
            }
            captureStep(camera, currentStep + 1, focusSteps)
        }
    }

    private fun takePhotoFast(index: Int, onCaptureTriggered: (Uri?) -> Unit) {
        val imageCapture = imageCapture ?: return

        val prefs = getSharedPreferences("FocusESettings", Context.MODE_PRIVATE)
        val keep = prefs.getBoolean("keep_intermediate", false)

        if (keep) {
            // Save to MediaStore (Public)
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + "_burst_$index"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FocusE")
                }
            }

            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build(),
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        onCaptureTriggered(null)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri
                        Log.d(TAG, "Saved burst image to $savedUri")
                        onCaptureTriggered(savedUri)
                    }
                }
            )
        } else {
            // Save to Cache (Temporary)
            val photoFile = File(externalCacheDir, "burst_${System.currentTimeMillis()}_$index.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        onCaptureTriggered(null)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = Uri.fromFile(photoFile)
                        Log.d(TAG, "Saved burst image to $savedUri")
                        onCaptureTriggered(savedUri)
                    }
                }
            )
        }
    }

    private fun saveImageInBackground(image: ImageProxy, uri: Uri) {
        cameraExecutor.execute {
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                
                val outputStream = contentResolver.openOutputStream(uri)
                outputStream?.write(bytes)
                outputStream?.close()
                
                Log.d(TAG, "Saved burst image to $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image to $uri", e)
            } finally {
                image.close()
            }
        }
    }

    private fun setFocusDistance(camera: androidx.camera.core.Camera, distance: Float) {
        val cameraControl = androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
        val captureRequestOptions = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            .build()
        cameraControl.setCaptureRequestOptions(captureRequestOptions)
    }

    private fun clearFocusOverride(camera: androidx.camera.core.Camera) {
        val cameraControl = androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
        cameraControl.clearCaptureRequestOptions()
    }

    private fun getMinimumFocusDistance(camera: androidx.camera.core.Camera): Float {
        val cameraInfo = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
        return cameraInfo.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)
                Log.i(TAG, "Camera bound successfully")
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ZeroBlur"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("FocusESettings", Context.MODE_PRIVATE)
        val keep = prefs.getBoolean("keep_intermediate", false)

        val checkBox = CheckBox(this)
        checkBox.text = "Keep intermediate pictures"
        checkBox.isChecked = keep
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keep_intermediate", isChecked).apply()
        }

        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (24 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin, margin, margin)
        container.addView(checkBox, params)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(container)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateGalleryPreview(uri: Uri) {
        try {
            // Load image into preview
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            viewBinding.galleryPreview.setImageBitmap(bitmap)
            viewBinding.galleryCard.visibility = android.view.View.VISIBLE // Make visible only after success
            
            viewBinding.galleryCard.setOnClickListener {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/jpeg")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open gallery intent", e)
                    Toast.makeText(this, "Could not open gallery", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update gallery preview", e)
        }
    }
}
