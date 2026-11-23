package com.scantruth.ai.ui.components

import android.graphics.Color as AndroidColor
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
            isClickable = false
            isFocusable = false
            setOnTouchListener { _, _ -> false }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )

    DisposableEffect(lifecycleOwner, lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val bindCamera = Runnable {
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)

                state.imageCapture = imageCapture
                state.errorMessage = null
            } catch (error: Exception) {
                state.imageCapture = null
                state.errorMessage = "Camera unavailable on this device."
            }
        }

        providerFuture.addListener(bindCamera, mainExecutor)

        onDispose {
            state.imageCapture = null
            if (providerFuture.isDone) {
                runCatching { providerFuture.get().unbindAll() }
            }
        }
    }
}
