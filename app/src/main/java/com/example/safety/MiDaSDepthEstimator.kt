package com.example.safety

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable

private const val TAG = "MiDaSDepthEstimator"
private const val MODEL_ASSET_NAME = "midas_small_256_fp16.tflite"
private const val DEPTH_INPUT_SIZE = 256

/**
 * Result of a relative inverse-depth estimation pass.
 * Values are normalized to [0f..1f] where higher values represent closer surfaces.
 */
data class DepthMapResult(
    val depthGrid: FloatArray,
    val size: Int = DEPTH_INPUT_SIZE,
    val rawRange: Float = 0f,
    val activeAccelerator: String = "CPU"
)

interface DepthEstimator : Closeable {
    fun estimateDepthMap(bitmap: Bitmap): DepthMapResult?
    fun warmUp(): Boolean
}

/**
 * 100% On-Device Monocular Depth Estimator backed by Google AI Edge LiteRT (MiDaS v2.1 Small FP16).
 * Operates completely offline on single snapshots with zero network or API key dependency.
 */
class MiDaSDepthEstimator(
    private val context: Context
) : DepthEstimator {

    private val lock = Any()
    private var compiledModel: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    private var activeAcceleratorName: String = "CPU"
    private var initializationFailed: Boolean = false

    // Reusable buffers to minimize GC allocation per snapshot
    private val pixelBuffer = IntArray(DEPTH_INPUT_SIZE * DEPTH_INPUT_SIZE)
    private val floatInputBuffer = FloatArray(DEPTH_INPUT_SIZE * DEPTH_INPUT_SIZE * 3)

    override fun warmUp(): Boolean {
        synchronized(lock) {
            return ensureInitialized()
        }
    }

    private fun ensureInitialized(): Boolean {
        if (compiledModel != null) return true
        if (initializationFailed) return false

        return try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val options = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = threads)
            }
            val model = CompiledModel.create(
                context.assets,
                MODEL_ASSET_NAME,
                options
            )

            compiledModel = model
            activeAcceleratorName = "CPU"
            inputBuffers = model.createInputBuffers()
            outputBuffers = model.createOutputBuffers()
            Log.i(TAG, "MiDaS LiteRT initialized on CPU ($threads threads)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize LiteRT MiDaS model", t)
            initializationFailed = true
            false
        }
    }

    override fun estimateDepthMap(bitmap: Bitmap): DepthMapResult? {
        synchronized(lock) {
            if (!ensureInitialized()) return null
            val model = compiledModel ?: return null
            if (inputBuffers.isEmpty() || outputBuffers.isEmpty()) return null

            val scaledBitmap = if (bitmap.width == DEPTH_INPUT_SIZE && bitmap.height == DEPTH_INPUT_SIZE) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, DEPTH_INPUT_SIZE, DEPTH_INPUT_SIZE, true)
            }

            return try {
                scaledBitmap.getPixels(
                    pixelBuffer,
                    0,
                    DEPTH_INPUT_SIZE,
                    0,
                    0,
                    DEPTH_INPUT_SIZE,
                    DEPTH_INPUT_SIZE
                )

                // Convert ARGB -> ImageNet-normalized NHWC float32 [1, 256, 256, 3]
                var outIdx = 0
                for (i in pixelBuffer.indices) {
                    val px = pixelBuffer[i]
                    val r = ((px shr 16) and 0xFF) * INV_255
                    val g = ((px shr 8) and 0xFF) * INV_255
                    val b = (px and 0xFF) * INV_255

                    floatInputBuffer[outIdx++] = (r - MEAN_R) / STD_R
                    floatInputBuffer[outIdx++] = (g - MEAN_G) / STD_G
                    floatInputBuffer[outIdx++] = (b - MEAN_B) / STD_B
                }

                inputBuffers[0].writeFloat(floatInputBuffer)
                model.run(inputBuffers, outputBuffers)
                val rawDepth = outputBuffers[0].readFloat()

                normalizePercentile(rawDepth)
            } catch (t: Throwable) {
                Log.e(TAG, "MiDaS depth inference failed", t)
                null
            } finally {
                if (scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                }
            }
        }
    }

    private fun normalizePercentile(raw: FloatArray): DepthMapResult {
        val expectedCount = DEPTH_INPUT_SIZE * DEPTH_INPUT_SIZE
        if (raw.size < expectedCount) {
            return DepthMapResult(FloatArray(expectedCount), DEPTH_INPUT_SIZE, 0f, activeAcceleratorName)
        }

        // Sub-sample every 4th value for fast p2/p98 robust range estimation
        val sampleCount = expectedCount / 4
        val samples = FloatArray(sampleCount)
        var sIdx = 0
        var i = 0
        while (i < expectedCount && sIdx < sampleCount) {
            samples[sIdx++] = raw[i]
            i += 4
        }
        samples.sort()

        val p2 = samples[(sampleCount * 0.02f).toInt().coerceIn(0, sampleCount - 1)]
        val p98 = samples[(sampleCount * 0.98f).toInt().coerceIn(0, sampleCount - 1)]
        val range = (p98 - p2).coerceAtLeast(1e-4f)

        val normalized = FloatArray(expectedCount)
        for (idx in 0 until expectedCount) {
            normalized[idx] = ((raw[idx] - p2) / range).coerceIn(0f, 1f)
        }

        return DepthMapResult(
            depthGrid = normalized,
            size = DEPTH_INPUT_SIZE,
            rawRange = range,
            activeAccelerator = activeAcceleratorName
        )
    }

    override fun close() {
        synchronized(lock) {
            try {
                inputBuffers.forEach { it.close() }
                outputBuffers.forEach { it.close() }
                compiledModel?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing LiteRT resources", t)
            } finally {
                inputBuffers = emptyList()
                outputBuffers = emptyList()
                compiledModel = null
            }
        }
    }

    private companion object {
        const val INV_255 = 1f / 255f
        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f
    }
}
