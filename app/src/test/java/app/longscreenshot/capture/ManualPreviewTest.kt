package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualPreviewTest {
    @Test
    fun initialViewportShowsSeamWithinImageBounds() {
        assertEquals(900f, manualSeamViewTop(2400, 1300, 800f), 0f)
        assertEquals(1600f, manualSeamViewTop(2400, 2400, 800f), 0f)
        assertEquals(0f, manualSeamViewTop(2400, 200, 800f), 0f)
        assertEquals(0f, manualSeamViewTop(2400, 2400, 2600f), 0f)
    }

    @Test
    fun enteringCropKeepsContentAtSameScreenPositionForEitherImage() {
        val viewTop = 1300f
        val contentRow = 1600f
        val scale = 0.4f
        val paddingPx = 36
        val alignedY = (contentRow - viewTop) * scale

        for (imageTop in listOf(0, 900)) {
            val scroll = manualCropScroll(viewTop, imageTop, scale, paddingPx)
            val croppedY = paddingPx + (contentRow - imageTop) * scale - scroll
            assertEquals(alignedY, croppedY, 0.5f)
            assertEquals(viewTop, manualCropViewTop(scroll, imageTop, scale, paddingPx), 0.001f)
        }
    }

    @Test
    fun returningToAlignmentRetainsPositionAfterCropScrolling() {
        for (imageTop in listOf(0, 900)) {
            val scroll = manualCropScroll(1300f, imageTop, 0.4f, 36) + 100
            val viewTop = manualCropViewTop(scroll, imageTop, 0.4f, 36)
            assertEquals(1550f, viewTop, 0.001f)
            assertEquals(scroll, manualCropScroll(viewTop, imageTop, 0.4f, 36))
        }
    }

    @Test
    fun fractionalScaleDoesNotAccumulatePositionDriftAcrossModeSwitches() {
        val initialTop = 1234.5f
        var viewTop = initialTop
        repeat(20) {
            val scroll = manualCropScroll(viewTop, 900, 0.37f, 31)
            viewTop = manualCropViewTop(scroll, 900, 0.37f, 31)
        }
        assertEquals(initialTop * 0.37f, viewTop * 0.37f, 0.5f)
    }

    @Test
    fun viewAboveSelectedImageStopsAtScrollableTopPadding() {
        val scroll = manualCropScroll(100f, 900, 0.4f, 36)
        assertEquals(0, scroll)
        assertEquals(810f, manualCropViewTop(scroll, 900, 0.4f, 36), 0.001f)
    }
}
