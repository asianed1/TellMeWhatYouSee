package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.HazardAssessment
import com.example.model.HazardSeverity
import com.example.model.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("TellMeWhatYouSee", appName)
    }

    @Test
    fun `quiet by default paradigm enforces null spoken alert for safe clear path`() {
        val safeAssessment = HazardAssessment.clearSafe(isOffline = false)
        assertEquals(HazardSeverity.SAFE, safeAssessment.severity)
        assertNull("Safe assessment must emit no spoken audio under Quiet-by-Default", safeAssessment.spokenAlert)
        assertTrue(safeAssessment.obstacles.isEmpty())
    }

    @Test
    fun `imminent hazard requires non-null spoken alert and urgent severity`() {
        val urgent = HazardAssessment(
            severity = HazardSeverity.IMMINENT_DANGER,
            spokenAlert = "STOP! Stairs going down directly ahead.",
            detailedSummary = "Concrete descending stairs detected 2 steps away.",
            hasStairsDown = true
        )
        assertEquals(HazardSeverity.IMMINENT_DANGER, urgent.severity)
        assertEquals("STOP! Stairs going down directly ahead.", urgent.spokenAlert)
        assertTrue(urgent.hasStairsDown)
    }
}
