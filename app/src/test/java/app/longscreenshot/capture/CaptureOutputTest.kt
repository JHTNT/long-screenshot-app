package app.longscreenshot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOutputTest {
    @Test
    fun sharedFilesExpireAtTwentyFourHours() {
        val day = 24 * 60 * 60 * 1000L

        assertFalse(CaptureOutput.isExpired(lastModified = 1, now = day))
        assertTrue(CaptureOutput.isExpired(lastModified = 1, now = day + 1))
    }

    @Test
    fun cropPercentagesMapToSourceRows() {
        assertEquals(100 to 900, CaptureOutput.cropRows(1_000, 0.1f..0.9f))
        assertEquals(0 to 1_000, CaptureOutput.cropRows(1_000, 0f..1f))
    }
}
