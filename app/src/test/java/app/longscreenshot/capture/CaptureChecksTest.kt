package app.longscreenshot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureChecksTest {
    @Test
    fun protectedBlackFrameIsRejectedWithoutRejectingWhiteContent() {
        assertTrue(CaptureChecks.isNearlyBlack(IntArray(400) { 0xff000000.toInt() }))
        assertFalse(CaptureChecks.isNearlyBlack(IntArray(400) { 0xffffffff.toInt() }))
    }
}
