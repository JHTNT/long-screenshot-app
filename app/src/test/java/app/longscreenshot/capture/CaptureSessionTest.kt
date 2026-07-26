package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionTest {
    @Test
    fun destroyReturnsToIdleWithoutAnExistingDirectory() {
        CaptureSession.directory = null
        CaptureSession.status = CaptureStatus.Failed("測試")

        assertTrue(CaptureSession.destroy())
        assertEquals(CaptureStatus.Idle, CaptureSession.status)
    }
}
