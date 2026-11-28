package com.scantruth.ai.ui.components

import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Handle to the live camera, so a screen can trigger a still capture without
 * owning the CameraX binding itself.
 */
@Stable
class CameraPreviewState internal constructor() {
    /** Non-null once the preview is bound and the camera can take pictures. */
    var imageCapture: ImageCapture? by mutableStateOf(null)
        internal set

    internal var camera: Camera? by mutableStateOf(null)

    /** Human readable reason the camera could not start, if any. */
    var errorMessage: String? by mutableStateOf(null)
        internal set
}

@Composable
fun rememberCameraPreviewState(): CameraPreviewState = remember { CameraPreviewState() }

/**
 * Live CameraX preview. The camera is bound to the current lifecycle owner, so it
 * closes when the app is backgrounded and reopens on return, and it is unbound when
 * the composable leaves composition.
 *
 * Only call this when the camera permission has been granted.
 */
@Composable
fun CameraPreview(
    state: CameraPreviewState,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(AndroidColor.BLACK)
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val cameraControl = state.camera?.cameraControl
                    if (cameraControl != null && view.width > 0 && view.height > 0) {
                        val factory = meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        cameraControl.startFocusAndMetering(action)
                    }
                }
                false
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )

    DisposableEffect(lifecycleOwner, lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var isDisposed = false

        val bindCamera = Runnable {
            if (isDisposed) return@Runnable
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)

                state.camera = boundCamera
                state.imageCapture = imageCapture
                state.errorMessage = null

                // Trigger initial center focus/exposure so small ingredient text is crisp.
                previewView.post {
                    if (!isDisposed && previewView.width > 0 && previewView.height > 0) {
                        val center = previewView.meteringPointFactory.createPoint(
                            previewView.width / 2f,
                            previewView.height / 2f
                        )
                        val action = FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        boundCamera.cameraControl.startFocusAndMetering(action)
                    }
                }
            } catch (error: Exception) {
                Log.e("CameraPreview", "Failed to bind CameraX use cases", error)
                state.camera = null
                state.imageCapture = null
                state.errorMessage = "Camera unavailable on this device."
            }
        }

        providerFuture.addListener(bindCamera, mainExecutor)

        onDispose {
            isDisposed = true
            state.camera = null
            state.imageCapture = null
            if (providerFuture.isDone) {
                runCatching { providerFuture.get().unbindAll() }
            }
        }
    }
}
