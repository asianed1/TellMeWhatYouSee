package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.model.CameraAimZone
import com.example.safety.OnDeviceSafetyEngine
import com.example.sensor.DeviceSensorAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnDeviceSafetyEngineTest {

    private val engine = OnDeviceSafetyEngine()

    @Test
    fun `uniform surface does not trigger stairs or dropoff`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)

        val stairs = engine.detectParallelStepLines(bitmap)
        val dropOff = engine.detectGroundDiscontinuity(bitmap)

        assertFalse("Uniform surface should not detect stairs", stairs)
        assertFalse("Uniform surface should not detect drop-off", dropOff)
    }

    @Test
    fun `parallel horizontal bars trigger stairs pattern detection`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)

        for (y in 100 until 180 step 12) {
            for (x in 40 until 160) {
                for (offset in 0..4) {
                    if (y + offset < 200) {
                        bitmap.setPixel(x, y + offset, Color.BLACK)
                    }
                }
            }
        }

        val hasStairs = engine.detectParallelStepLines(bitmap)
        assertTrue("Alternating step stripes in lower frame should trigger stairs detection", hasStairs)
    }

    @Test
    fun `ground void discontinuity triggers drop-off detection`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)

        for (y in 175 until 200) {
            for (x in 0 until 200) {
                bitmap.setPixel(x, y, Color.WHITE)
            }
        }

        val hasDropOff = engine.detectGroundDiscontinuity(bitmap)
        assertTrue("Sudden black void in ground walking zone should trigger drop-off detection", hasDropOff)
    }

    @Test
    fun `pothole cavity in pavement triggers pothole detection`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)

        for (y in 125 until 150) {
            for (x in 75 until 125) {
                bitmap.setPixel(x, y, Color.BLACK)
            }
        }

        val hasPothole = engine.detectPotholeOrGroundCavity(bitmap)
        assertTrue("Localized dark cavity in ground corridor should detect pothole", hasPothole)
    }

    @Test
    fun `uniform pavement does not trigger pothole`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)

        val hasPothole = engine.detectPotholeOrGroundCavity(bitmap)
        assertFalse("Uniform pavement must not trigger pothole", hasPothole)
    }

    @Test
    fun `overhead clearance aim ignores ground pothole patterns`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        for (y in 125 until 150) {
            for (x in 75 until 125) {
                bitmap.setPixel(x, y, Color.BLACK)
            }
        }

        val hasPotholeWhenAimedUp = engine.detectPotholeOrGroundCavity(bitmap, CameraAimZone.OVERHEAD_CLEARANCE)
        assertFalse("Overhead aim must not misread dark ceiling features as ground potholes", hasPotholeWhenAimedUp)
    }

    @Test
    fun `downward ground near aim expands pothole scan to upper center`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        // Pothole centered higher in frame (y: 55..90) when user aims downward
        for (y in 55 until 90) {
            for (x in 75 until 125) {
                bitmap.setPixel(x, y, Color.BLACK)
            }
        }

        val detectedInForward = engine.detectPotholeOrGroundCavity(bitmap, CameraAimZone.FORWARD_PATH)
        val detectedInGroundNear = engine.detectPotholeOrGroundCavity(bitmap, CameraAimZone.GROUND_NEAR)
        assertFalse("Upper-mid cavity is horizon/above ground in straight-ahead aim", detectedInForward)
        assertTrue("Downward ground aim should detect cavity in expanded center zone", detectedInGroundNear)
    }

    @Test
    fun `sensor analyzer classifies aim zones and motion shake accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val analyzer = DeviceSensorAnalyzer(context)

        assertEquals(CameraAimZone.FORWARD_PATH, analyzer.classifyAimZone(0f))
        assertEquals(CameraAimZone.FORWARD_PATH, analyzer.classifyAimZone(-8f))
        assertEquals(CameraAimZone.GROUND_NEAR, analyzer.classifyAimZone(-25f))
        assertEquals(CameraAimZone.OVERHEAD_CLEARANCE, analyzer.classifyAimZone(25f))

        assertFalse("Steady hand must not trigger blur guard", analyzer.isExcessiveShake(0.3f, 0.4f))
        assertTrue("Fast rotational jerk must trigger blur guard", analyzer.isExcessiveShake(2.2f, 0.5f))
        assertTrue("Heavy linear jolt must trigger blur guard", analyzer.isExcessiveShake(0.2f, 5.5f))
    }
}
