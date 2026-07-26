package app.longscreenshot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOutputTest {
    @Test
    fun sharedFilesExpireAtTwentyFourHours() {
        val day = 24 * 60 * 60 * 1000L

        assertFalse(CaptureOutput.isExpired(lastModified = 1, now = day))
        assertTrue(CaptureOutput.isExpired(lastModified = 1, now = day + 1))
    }
}
