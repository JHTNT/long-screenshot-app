package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StitchMatcherTest {
    @Test
    fun findsShiftWithSixPercentOverlap() {
        val width = 40
        val height = 200
        val shift = 188
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
        assertEquals(12, seam.overlap)
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

    @Test
    fun acceptsNearExactOverlapInRepeatedRegion() {
        val width = 60
        val height = 240
        val shift = 80
        fun pixels(offset: Int, brightness: Int) = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width + offset
            (40 + (x * 7 + y % 40 * 5) % 160 + y / 40 * 3 + brightness).toByte()
        }

        val seam = StitchMatcher.find(
            LumaImage(width, height, pixels(0, 0)),
            LumaImage(width, height, pixels(shift, 1)),
            region = VerticalRegion(20, 220),
        )

        assertTrue(seam.toString(), seam.confident)
        assertEquals(shift, seam.shift)
    }

    @Test
    fun ignoresTexturelessSliverButKeepsSmallOverlapSearch() {
        val width = 30
        val height = 200
        val shift = 60
        fun pixels(offset: Int, brightness: Int) = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width + offset
            (30 + (x * 13 + y * 7 + x * y % 29) % 180 + brightness).toByte()
        }
        val first = pixels(0, 0)
        val second = pixels(shift, 1)
        for (y in 160 until height) {
            for (x in 0 until width) first[y * width + x] = 80
        }
        for (y in 100 until 140) {
            for (x in 0 until width) second[y * width + x] = 80
        }
        for (y in 0 until 16) {
            for (x in 0 until width) second[y * width + x] = 80
        }

        val seam = StitchMatcher.find(
            LumaImage(width, height, first),
            LumaImage(width, height, second),
            region = VerticalRegion(0, height),
        )

        assertEquals(shift, seam.shift)
        assertTrue(seam.toString(), seam.confident)
    }
}
