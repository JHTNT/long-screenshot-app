package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StitchMatcherTest {
    @Test
    fun findsShiftWithTenPercentOverlap() {
        val width = 40
        val height = 200
        val shift = 180
        fun pixels(offset: Int) = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width + offset
            ((x * 37 + y * 19 + x * y % 71) and 0xff).toByte()
        }

        val seam = StitchMatcher.find(
            LumaImage(width, height, pixels(0)),
            LumaImage(width, height, pixels(shift)),
            region = VerticalRegion(0, height),
        )

        assertTrue(seam.confident)
        assertEquals(shift, seam.shift)
        assertEquals(20, seam.overlap)
    }

    @Test
    fun findsShiftInsideSelectedRegion() {
        val width = 30
        val height = 120
        val shift = 35
        fun pixels(offset: Int) = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val contentY = y + offset
            ((x * 37 + contentY * 19 + x * contentY % 71) and 0xff).toByte()
        }
        val first = pixels(0)
        val second = pixels(shift)
        for (y in 0 until 20) {
            for (x in 0 until width) {
                first[y * width + x] = 40
                second[y * width + x] = 90
            }
        }
        for (y in 100 until height) {
            for (x in 0 until width) {
                first[y * width + x] = 60
                second[y * width + x] = 120
            }
        }

        val seam = StitchMatcher.find(
            LumaImage(width, height, first),
            LumaImage(width, height, second),
            region = VerticalRegion(20, 100),
        )

        assertTrue(seam.confident)
        assertEquals(shift, seam.shift)
    }

    @Test
    fun acceptsSparseTexture() {
        val width = 30
        val height = 120
        val shift = 35
        fun pixels(offset: Int) = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width + offset
            if (y % 10 == 0) ((y * 3 + x * 37) and 0xff).toByte()
            else ((y * 3) and 0xff).toByte()
        }

        val seam = StitchMatcher.find(
            LumaImage(width, height, pixels(0)),
            LumaImage(width, height, pixels(shift)),
        )

        assertTrue(seam.confident)
        assertEquals(shift, seam.shift)
    }

    @Test
    fun findsShiftAroundDynamicBannerAndFixedBottomBar() {
        val width = 30
        val height = 120
        val shift = 35
        fun pixels(offset: Int) =
            ByteArray(width * height) { index ->
                val x = index % width
                val y = index / width + offset
                ((x * 37 + y * 19 + (x * y) % 71) and 0xff).toByte()
            }
        val first = pixels(0)
        val second = pixels(shift)
        for (y in 0 until 12) {
            for (x in 0 until width) second[y * width + x] = (180 + x % 20).toByte()
        }
        for (y in height - 18 until height) {
            for (x in 0 until width) {
                val fixed = (40 + x * 3 + y % 5).toByte()
                first[y * width + x] = fixed
                second[y * width + x] = fixed
            }
        }

        val seam = StitchMatcher.find(
            LumaImage(width, height, first),
            LumaImage(width, height, second),
        )

        assertTrue(seam.confident)
        assertEquals(shift, seam.shift)
        assertTrue(seam.bottomCrop >= 18)
    }
}
