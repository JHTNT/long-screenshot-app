package app.longscreenshot.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOverlayStateTest {
    @Test
    fun returningFromPreviewResumesFramesBeforeContentBecomesVisible() {
        val preview = CaptureOverlayState(visible = false, capturing = true, contentVisible = false, guideReady = true)
        assertFalse(preview.streamFrames)
        assertFalse(preview.showGuide)
        assertFalse(preview.trackingEnabled)

        val returning = CaptureOverlayState(visible = true, capturing = true, contentVisible = false, guideReady = true)
        assertTrue(returning.streamFrames)
        assertFalse(returning.showGuide)
        assertFalse(returning.trackingEnabled)

        val restored = CaptureOverlayState(visible = true, capturing = true, contentVisible = true, guideReady = true)
        assertTrue(restored.streamFrames)
        assertTrue(restored.showGuide)
        assertTrue(restored.trackingEnabled)
    }

    @Test
    fun resumesVisibilityUpdatesEvenBeforeTheFirstGuideIsReady() {
        val state = CaptureOverlayState(visible = true, capturing = true, contentVisible = false, guideReady = false)
        assertTrue(state.streamFrames)
        assertFalse(state.showGuide)
        assertFalse(state.trackingEnabled)
    }

    @Test
    fun hidesAndStopsFramesDuringCaptureOrWhenTheSessionIsInactive() {
        for (state in listOf(
            CaptureOverlayState(visible = false, capturing = true, contentVisible = true, guideReady = true),
            CaptureOverlayState(visible = true, capturing = false, contentVisible = true, guideReady = true),
        )) {
            assertFalse(state.streamFrames)
            assertFalse(state.showGuide)
            assertFalse(state.trackingEnabled)
        }
    }
}
