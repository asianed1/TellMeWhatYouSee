package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "CameraViewfinder"

@Composable
fun CameraViewfinder(
    modifier: Modifier = Modifier,
    onCameraReady: (captureAction: suspend () -> Bitmap?, torchAction: (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                val camera: Camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                // Expose capture and torch functions
                onCameraReady(
                    { captureScaledBitmap(context, imageCapture) },
                    { enableTorch ->
                        try {
                            camera.cameraControl.enableTorch(enableTorch)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed toggling torch", e)
                        }
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Captures and scales down a camera frame to max 1024px for fast processing.
 */
suspend fun captureScaledBitmap(context: Context, imageCapture: ImageCapture): Bitmap? {
    return suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val bitmap = imageProxyToBitmap(image)
                        image.close()

                        val rotated = if (rotation != 0 && bitmap != null) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        } else {
                            bitmap
                        }

                        // Scale to max 1024px
                        val scaled = rotated?.let { scaleDown(it, 1024) }
                        continuation.resume(scaled)
                    } catch (e: Exception) {
                        Log.e(TAG, "Bitmap processing failed", e)
                        continuation.resume(null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    continuation.resume(null)
                }
            }
        )
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val plane = image.planes[0]
    val buffer: ByteBuffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun scaleDown(realImage: Bitmap, maxResolution: Int): Bitmap {
    val width = realImage.width
    val height = realImage.height
    val maxDim = maxOf(width, height)
    if (maxDim <= maxResolution) return realImage

    val ratio = maxResolution.toFloat() / maxDim
    val newWidth = (ratio * width).toInt()
    val newHeight = (ratio * height).toInt()
    return Bitmap.createScaledBitmap(realImage, newWidth, newHeight, true)
}
