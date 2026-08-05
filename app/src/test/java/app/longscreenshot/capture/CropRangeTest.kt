package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropRangeTest {
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
