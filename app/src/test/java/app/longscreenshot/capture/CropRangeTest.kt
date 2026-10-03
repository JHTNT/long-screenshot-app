package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropRangeTest {
    @Test
    fun rangeHandlesStopWithoutPushingTheOtherBoundary() {
        val crop = ManualCrop(20, 80)
        assertEquals(ManualCrop(79, 80), adjustedManualCrop(crop, 80f..80f, 100))
        val narrowed = adjustedManualCrop(crop, 20f..20f, 100)
        assertEquals(ManualCrop(20, 21), narrowed)
        assertEquals(crop, adjustedManualCrop(narrowed, 20f..80f, 100))
    }

    @Test
    fun rangeHandlesCanRestoreBothImageEdges() {
        val crop = ManualCrop(20, 80)
        val restoredTop = adjustedManualCrop(crop, 0f..80f, 100)
        assertEquals(ManualCrop(0, 80), restoredTop)
        assertEquals(ManualCrop(0, 100), adjustedManualCrop(restoredTop, 0f..100f, 100))
    }

    @Test
    fun rangeHandlesRoundToSourcePixels() {
        val crop = ManualCrop(20, 80)
        assertEquals(ManualCrop(21, 80), adjustedManualCrop(crop, 20.6f..80f, 100))
        assertEquals(ManualCrop(20, 79), adjustedManualCrop(crop, 20f..79.4f, 100))
    }

    @Test
    fun rangeSliderKeepsSinglePixelImageValid() {
        val crop = ManualCrop(0, 1)
        assertEquals(crop, adjustedManualCrop(crop, 1f..1f, 1))
        assertEquals(crop, adjustedManualCrop(crop, 0f..0f, 1))
    }
    @Test
    fun draggingEdgeKeepsMinimumRange() {
        val top = requireNotNull(draggedCropRange(0.2f..0.8f, true, 0.4f, 0.25f))
        assertEquals(0.4f, top.start, 0f)
        assertEquals(0.8f, top.endInclusive, 0f)
        assertNull(draggedCropRange(0.2f..0.8f, true, 0.6f, 0.25f))

        val bottom = requireNotNull(draggedCropRange(0.2f..0.8f, false, 0.55f, 0.25f))
        assertEquals(0.2f, bottom.start, 0f)
        assertEquals(0.55f, bottom.endInclusive, 0f)
    }
}
