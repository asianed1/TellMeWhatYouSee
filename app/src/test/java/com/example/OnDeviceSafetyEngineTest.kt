package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.safety.OnDeviceSafetyEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceSafetyEngineTest {

    private val engine = OnDeviceSafetyEngine()

    @Test
    fun `uniform surface does not trigger stairs or dropoff`() {
        // 200x200 uniform grey pavement
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

        // Draw distinct alternating horizontal ridges in lower half (y: 100 to 180)
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
        // Upper and middle area is dark void / black pit
        bitmap.eraseColor(Color.BLACK)

        // Immediate foot area at bottom is illuminated pavement
        for (y in 175 until 200) {
            for (x in 0 until 200) {
                bitmap.setPixel(x, y, Color.WHITE)
            }
        }

        val hasDropOff = engine.detectGroundDiscontinuity(bitmap)
        assertTrue("Sudden black void in ground walking zone should trigger drop-off detection", hasDropOff)
    }
}
