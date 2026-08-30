package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavigationTest {
    @Test
    fun returnsToPreviousCaptureStepWithoutDiscardingSources() {
        assertEquals(
            CaptureStatus.Reviewing(3),
            previousCaptureScreen(CaptureStatus.SelectingRegion(3)),
        )
        assertEquals(
            CaptureStatus.SelectingRegion(3),
            previousCaptureScreen(CaptureStatus.Stitching(3)),
        )
        assertEquals(
            CaptureStatus.Reviewing(3),
            previousCaptureScreen(CaptureStatus.Failed("停止", retainedCount = 3)),
        )
        assertEquals(
            CaptureStatus.Idle,
            previousCaptureScreen(CaptureStatus.Failed("無可保留內容")),
        )
    }
}
