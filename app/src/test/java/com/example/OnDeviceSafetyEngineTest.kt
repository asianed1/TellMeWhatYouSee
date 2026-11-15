package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.model.CameraAimZone
import com.example.model.HazardSeverity
import com.example.safety.DepthMapResult
import com.example.safety.OnDeviceSafetyEngine
import com.example.sensor.DeviceSensorAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnDeviceSafetyEngineTest {

    private val engine = OnDeviceSafetyEngine()
    private val gridSize = 256

    private fun buildClearWalkingDepthGrid(): FloatArray {
        val grid = FloatArray(gridSize * gridSize)
        for (y in 0 until gridSize) {
            val depth = if (y < 140) {
                // Distant open horizon (0.05f .. 0.25f)
                0.05f + (y / 140f) * 0.20f
            } else {
                // Smooth monotonic sidewalk rising smoothly toward user's feet (0.25f .. 0.90f)
                0.25f + ((y - 140) / 115f) * 0.65f
            }
            val rowOffset = y * gridSize
            for (x in 0 until gridSize) {
                grid[rowOffset + x] = depth
            }
        }
        return grid
    }

    @Test
    fun `clear monotonic sidewalk depth plane returns safe with null speech even with dark shadow on bitmap`() {
        val grid = buildClearWalkingDepthGrid()
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        // Paint a high-contrast dark tree shadow across the pavement bitmap
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        for (y in 180 until 220) {
            for (x in 80 until 176) {
                bitmap.setPixel(x, y, Color.DKGRAY)
            }
        }

        val result = engine.analyzeDepthMap(depthMap, bitmap, CameraAimZone.FORWARD_PATH)

        assertEquals(HazardSeverity.SAFE, result.severity)
        assertNull("Clear path must remain silent under Quiet-by-Default", result.spokenAlert)
        assertTrue(result.obstacles.isEmpty())
    }

    @Test
    fun `localized ground recess in floor plane detects ground hazard`() {
        val grid = buildClearWalkingDepthGrid()
        // Carve a deep recess (pothole / curb cavity) in ahead corridor around y=195..218
        for (y in 195 until 218) {
            for (x in 100 until 156) {
                grid[y * gridSize + x] = 0.12f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible ground hazard, near ahead.", result.spokenAlert)
        assertTrue(result.hasPotholeOrVoid)
    }

    @Test
    fun `forward depth cliff with descending step edges detects downward stairs`() {
        val grid = buildClearWalkingDepthGrid()
        // Sharp drop-off ahead of near-foot landing (y < 208 falls away cleanly)
        for (y in 140 until 208) {
            for (x in 70 until 186) {
                grid[y * gridSize + x] = 0.18f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        // Bitmap with horizontal step nosings and darker descending stairwell in mid-lower frame
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 105 until 170) {
            for (x in 30 until 170) {
                val color = if ((y % 12) < 4) Color.BLACK else Color.DKGRAY
                bitmap.setPixel(x, y, color)
            }
        }

        val result = engine.analyzeDepthMap(depthMap, bitmap, CameraAimZone.FORWARD_PATH)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible downward stairs, near ahead.", result.spokenAlert)
        assertTrue(result.hasStairsDown)
    }

    @Test
    fun `knee-level protruding obstacle slightly left produces concise caution alert`() {
        val grid = buildClearWalkingDepthGrid()
        // Protruding low bollard / chair in LEFT lane (x: 24..80, y: 140..182)
        for (y in 140 until 182) {
            for (x in 24 until 80) {
                grid[y * gridSize + x] = 0.86f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH)

        assertEquals(HazardSeverity.CAUTION, result.severity)
        assertEquals("Possible knee hazard, near slightly left.", result.spokenAlert)
    }

    @Test
    fun `tall obstacle spanning knee and eye-level ahead promotes cleanly to eye-level hazard`() {
        val grid = buildClearWalkingDepthGrid()
        // Tall trunk / pole / wall directly ahead across eye & knee bands (x: 96..160, y: 70..182)
        for (y in 70 until 182) {
            for (x in 96 until 160) {
                grid[y * gridSize + x] = 0.88f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible eye-level hazard, near ahead.", result.spokenAlert)
        assertEquals(1, result.obstacles.size)
        assertEquals("Eye level", result.obstacles.first().elevation)
    }

    @Test
    fun `overhead clearance aim detects overhanging branch or signage without ground false positives`() {
        val grid = FloatArray(gridSize * gridSize) { 0.12f }
        // Close overhanging mass in upper-mid center when camera is aimed upward
        for (y in 44 until 136) {
            for (x in 96 until 160) {
                grid[y * gridSize + x] = 0.84f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.72f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.OVERHEAD_CLEARANCE)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible overhead hazard, near ahead.", result.spokenAlert)
        assertTrue(result.hasHeadOverhang)
        assertFalse(result.hasPotholeOrVoid)
    }

    @Test
    fun `wide parked car two meters ahead is detected even when near pavement dominates top depth scale`() {
        val grid = buildClearWalkingDepthGrid()
        // Sky/trees at top remain far (0.05..0.12), while a wide car body fills ALL 3 lanes (y: 62..178)
        // at ~0.54f because closer pavement at the user's feet (y: 195..255) occupies 0.70..0.90
        for (y in 62 until 178) {
            for (x in 16 until 240) {
                grid[y * gridSize + x] = 0.54f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.90f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH, pitchDegrees = 0f)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible eye-level hazard, near ahead.", result.spokenAlert)
    }

    @Test
    fun `tall straight-ahead obstacle reaching top edge reports eye-level rather than overhead hazard`() {
        val grid = buildClearWalkingDepthGrid()
        // Solid tall obstacle extending from near top edge (y: 14) down through eye-level (y: 170)
        for (y in 14 until 170) {
            for (x in 92 until 164) {
                grid[y * gridSize + x] = 0.86f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH, pitchDegrees = 0f)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible eye-level hazard, near ahead.", result.spokenAlert)
        assertFalse("Continuous straight-ahead obstacle must not trigger overhead", result.hasHeadOverhang)
    }

    @Test
    fun `isolated overhanging branch in forward path with open space below triggers overhead hazard`() {
        val grid = buildClearWalkingDepthGrid()
        // Low awning/branch across top strip (y: 8..44) while eye-level below (y: 60..136) stays clear/far
        for (y in 8 until 44) {
            for (x in 92 until 164) {
                grid[y * gridSize + x] = 0.84f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH, pitchDegrees = 0f)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible overhead hazard, near ahead.", result.spokenAlert)
        assertTrue(result.hasHeadOverhang)
    }

    @Test
    fun `clear indoor hallway with flanking walls and open center stays safe`() {
        val grid = buildClearWalkingDepthGrid()
        // Side hallway walls in LEFT and RIGHT lanes at moderate depth (0.48f), center AHEAD remains open
        for (y in 40 until 180) {
            for (x in 16 until 80) grid[y * gridSize + x] = 0.48f
            for (x in 176 until 240) grid[y * gridSize + x] = 0.48f
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH, pitchDegrees = 0f)

        assertEquals(HazardSeverity.SAFE, result.severity)
        assertNull("Clear hallway center should remain silent", result.spokenAlert)
    }

    @Test
    fun `slight downward phone tilt shifts eye-level band upward instead of triggering overhead`() {
        val grid = buildClearWalkingDepthGrid()
        // With -10 deg pitch (pitchShiftY = -25), eye band shifts to y=35..111.
        // An obstacle sitting at y=36..108 is eye-level in front of the user, not overhead.
        for (y in 36 until 108) {
            for (x in 92 until 164) {
                grid[y * gridSize + x] = 0.84f
            }
        }
        val depthMap = DepthMapResult(depthGrid = grid, size = gridSize, rawRange = 0.85f)

        val result = engine.analyzeDepthMap(depthMap, null, CameraAimZone.FORWARD_PATH, pitchDegrees = -10f)

        assertEquals(HazardSeverity.IMMINENT_DANGER, result.severity)
        assertEquals("STOP! Possible eye-level hazard, near ahead.", result.spokenAlert)
        assertFalse(result.hasHeadOverhang)
    }

    @Test
    fun `uniform surface does not trigger stairs or dropoff fallback`() {
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
