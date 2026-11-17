package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGuideTest {
    @Test
    fun distinguishesHorizontalDetailsWhenRowSummariesTie() {
        val width = 48
        val height = 1600
        for (columns in listOf(width / 2, width / 8)) {
            val world = ByteArray(width * height * 2) { 255.toByte() }
            val random = java.util.Random(812)
            var previousColumn = -1
            for (y in 0 until height * 2) {
                var column: Int
                do { column = random.nextInt(columns) * 2 + 1 } while (column == previousColumn)
                world[y * width + column] = 0
                previousColumn = column
            }
            fun frame(shift: Int) = LumaImage(width, height,
                world.copyOfRange(width * shift, width * (shift + height)))
            val reference = frame(0)
            for (detailLuma in listOf(0, 40)) {
                val tracker = CaptureGuideTracker(reference)
                for (shift in listOf(0, 3, 5, 30, 500, 800, 500, 0)) {
                    val current = frame(shift)
                    for (i in current.pixels.indices) {
                        if (current.pixels[i] == 0.toByte()) current.pixels[i] = detailLuma.toByte()
                    }
                    assertEquals("columns=$columns shift=$shift luma=$detailLuma",
                        shift, StitchMatcher.track(reference, current))
                    assertEquals(1f - shift.toFloat() / height, tracker.locate(current)!!, 0.001f)
                }
            }
        }
    }

    @Test
    fun consumesTheLatestFrameAtTheDeadlineEvenWhenScrollingStops() {
        var time = 0L
        var latestFrame = 0
        val consumed = mutableListOf<Int>()
        val queued = mutableListOf<Pair<Runnable, Long>>()
        val scheduler = GuideFrameScheduler(
            now = { time },
            post = { task, delay -> queued += task to (time + delay) },
            remove = { task -> queued.removeAll { it.first === task } },
            consume = { consumed += latestFrame },
        )
        scheduler.setEnabled(true)
        time = 10
        latestFrame = 1
        scheduler.onFrame()
        time = 30
        latestFrame = 2
        scheduler.onFrame()
        assertEquals(listOf(0), consumed)
        assertEquals(1, queued.size)
        assertEquals(40L, queued.single().second)
        time = 40
        queued.removeAt(0).first.run()
        assertEquals(listOf(0, 2), consumed)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun cancelsThePendingFrameAndResumesImmediately() {
        var time = 0L
        var consumed = 0
        val queued = mutableListOf<Runnable>()
        val scheduler = GuideFrameScheduler(
            now = { time },
            post = { task, _ -> queued += task },
            remove = { queued.remove(it) },
            consume = { consumed++ },
        )
        scheduler.setEnabled(true)
        time = 10
        scheduler.onFrame()
        assertEquals(1, queued.size)
        scheduler.setEnabled(false)
        assertTrue(queued.isEmpty())
        assertEquals(1, consumed)
        scheduler.setEnabled(true)
        assertEquals(2, consumed)
    }

    @Test
    fun consumesFramesReceivedBeforeTrackingIsReadyWithoutAnotherArrival() {
        var latestFrame: Int? = null
        val consumed = mutableListOf<Int>()
        val queued = mutableListOf<Runnable>()
        val scheduler = GuideFrameScheduler(
            now = { 0L },
            post = { task, _ -> queued += task },
            remove = { queued.remove(it) },
            consume = {
                latestFrame?.let { consumed += it }
                latestFrame = null
            },
        )
        latestFrame = 1
        scheduler.onFrame()
        latestFrame = 2
        scheduler.onFrame()
        assertTrue(consumed.isEmpty())
        scheduler.setEnabled(true)
        assertEquals(listOf(2), consumed)
        scheduler.setEnabled(true)
        assertTrue(queued.isEmpty())

        scheduler.setEnabled(false)
        latestFrame = 3
        scheduler.onFrame()
        assertEquals(listOf(2), consumed)
        scheduler.setEnabled(true)
        assertEquals(listOf(2, 3), consumed)
    }

    @Test
    fun distinguishesSparseDetailsOnRepeatedWhiteCards() {
        val width = 96
        val height = 150
        fun frame(offset: Int) = LumaImage(width, height, ByteArray(width * height) { i ->
            val x = i % width
            val y = i / width + offset
            if (x in 20 until 32 && y % 30 in 6 until 12) {
                (30 + (x * 17 + y % 30 * 31 + y / 30 * 53) % 180).toByte()
            } else 255.toByte()
        })
        assertEquals(120, StitchMatcher.track(frame(0), frame(120)))
    }

    @Test
    fun recoversAnchoredMotionWhenADistantRepeatedCardMakesRecentFramesAmbiguous() {
        val width = 48
        val height = 1804
        val world = ByteArray(width * height * 3).also(java.util.Random(74)::nextBytes)
        // A later card repeats the top of the last reliable frame, outside the saved reference.
        world.copyInto(world, 1980 * width, 583 * width, 990 * width)
        fun frame(shift: Int) = LumaImage(width, height,
            world.copyOfRange(width * shift, width * (shift + height)))
        val reference = frame(0)
        val previous = frame(583)
        val current = frame(657)
        for (y in 500..503) for (x in 0 until width) {
            val i = y * width + x
            current.pixels[i] = ((current.pixels[i].toInt() and 255) + 20).coerceAtMost(255).toByte()
        }
        assertEquals(657, StitchMatcher.track(reference, current))
        assertNull(StitchMatcher.track(previous, current))
        val tracker = CaptureGuideTracker(reference)
        assertNotNull(tracker.locate(previous))
        val interrupted = LumaImage(width, height, ByteArray(width * height).also(java.util.Random(75)::nextBytes))
        assertNull(tracker.locate(interrupted))
        repeat(3) { assertEquals(1f - 657f / height, tracker.locate(current)!!, 0.001f) }
    }

    @Test
    fun rejectsForwardLookalikesThatConflictWithRecentMotion() {
        val width = 96
        val height = 150
        val world = ByteArray(width * height * 3).also(java.util.Random(63)::nextBytes)
        fun frame(shift: Int) = LumaImage(width, height,
            world.copyOfRange(width * shift, width * (shift + height)))
        val tracker = CaptureGuideTracker(frame(0))
        assertEquals(1f / 3, tracker.locate(frame(100))!!, 0.001f)
        val lookalike = frame(101).pixels
        frame(130).pixels.copyInto(lookalike, endIndex = width * 20)
        assertNull(tracker.locate(LumaImage(width, height, lookalike)))
        assertEquals(1f - 102f / height, tracker.locate(frame(102))!!, 0.001f)
    }

    @Test
    fun tracksSmallSourcePixelScrollsThroughTheSamplingPipeline() {
        val width = 1220
        val height = 1972
        val page = java.awt.image.BufferedImage(width, height * 3, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = page.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, page.width, page.height)
            graphics.font = java.awt.Font("Dialog", java.awt.Font.PLAIN, 36)
            graphics.color = java.awt.Color.BLACK
            val random = java.util.Random(123)
            for (y in 50 until page.height step 60) {
                val line = buildString { repeat(45) { append('a' + random.nextInt(26)) } }
                graphics.drawString(line, 30, y)
            }
        } finally {
            graphics.dispose()
        }
        fun frame(scroll: Int) = sampleGuideLuma(width, VerticalRegion(0, height)) { x, y ->
            page.getRGB(x, y + scroll) and 0xff
        }
        val tracker = CaptureGuideTracker(frame(0))
        for (scroll in listOf(0, 12, 13, 18, 19, 25, 26, 100, 101, 1520, 1525, 1527, 1530, 1575, 1580, 1585, 100, 0)) {
            val bottom = tracker.locate(frame(scroll))
            assertNotNull("scroll=$scroll", bottom)
            assertEquals(1f - scroll.toFloat() / height, bottom!!, 0.01f)
        }
    }

    @Test
    fun usesOnlyScrollableContentBetweenBothFixedBars() {
        assertEquals(
            CaptureGuideGeometry(screenTop = 291, height = 1972),
            captureGuideGeometry(VerticalRegion(291, 2263), 1220, 2712, 1220, 2712, 96, 80),
        )
    }

    @Test
    fun offsetsAppOnlyCaptureByItsSystemTopInset() {
        assertEquals(
            CaptureGuideGeometry(screenTop = 196, height = 2000),
            captureGuideGeometry(VerticalRegion(100, 2100), 1220, 2536, 1220, 2712, 96, 80),
        )
    }

    @Test
    fun refusesUnknownWindowPositionAndInvalidDimensions() {
        val region = VerticalRegion(100, 500)
        assertNull(captureGuideGeometry(region, 800, 2000, 1220, 2712, 96, 80))
        assertNull(captureGuideGeometry(region, 1220, 2000, 1220, 2712, 96, 80))
        assertNull(captureGuideGeometry(region, 1220, 400, 1220, 400, 0, 0))
    }

    @Test
    fun samplesOnlyTheSelectedRegionAndPreservesLuma() {
        val region = VerticalRegion(80, 680)
        val sampled = sampleGuideLuma(384, region) { x, y ->
            assertTrue(x in 0 until 384 && y in 80 until 680)
            123
        }
        assertEquals(48, sampled.width)
        assertEquals(region.height, sampled.height)
        assertTrue(sampled.pixels.all { (it.toInt() and 0xff) == 123 })
    }

    @Test
    fun toleratesThinGuideLinesInTheLiveFrame() {
        val width = 96
        val height = 150
        val shift = 45
        val random = java.util.Random(42)
        val world = ByteArray(width * height * 2).also(random::nextBytes)
        val reference = LumaImage(width, height, world.copyOfRange(0, width * height))
        val live = world.copyOfRange(width * shift, width * (shift + height))
        for (y in listOf(0, 1, 98, 99, 148, 149)) {
            for (x in 0 until width) live[y * width + x] = 110
        }
        assertEquals(0.7f, CaptureGuideTracker(reference).locate(LumaImage(width, height, live))!!, 0.001f)
    }

    @Test
    fun refusesRepeatedPatternsAndScrollingAboveTheReference() {
        val width = 96
        val height = 150
        val repeated = LumaImage(width, height, ByteArray(width * height) {
            if (it / width % 10 < 5) 30 else 220.toByte()
        })
        assertNull(CaptureGuideTracker(repeated).locate(repeated))
        val random = java.util.Random(42)
        val world = ByteArray(width * height * 2).also(random::nextBytes)
        val reference = LumaImage(width, height, world.copyOfRange(width * 30, width * (30 + height)))
        val earlier = LumaImage(width, height, world.copyOfRange(0, width * height))
        assertNull(CaptureGuideTracker(reference).locate(earlier))
    }

    @Test
    fun tracksAtTheMinimumOverlapButDoesNotInventAPositionBelowIt() {
        val width = 96
        val height = 150
        val random = java.util.Random(17)
        val world = ByteArray(width * height * 2).also(random::nextBytes)
        val reference = LumaImage(width, height, world.copyOfRange(0, width * height))
        fun shifted(shift: Int) = LumaImage(width, height,
            world.copyOfRange(width * shift, width * (shift + height)))
        assertEquals(0.08f, CaptureGuideTracker(reference).locate(shifted(138))!!, 0.001f)
        assertNull(CaptureGuideTracker(reference).locate(shifted(145)))
    }

    @Test
    fun rejectsAShortTrueOverlapInsteadOfChoosingALongerLookalike() {
        val width = 96
        val height = 150
        val random = java.util.Random(26)
        val referencePixels = ByteArray(width * height).also(random::nextBytes)
        val currentPixels = ByteArray(width * height).also(random::nextBytes)
        // A similar earlier block would win if the exact six-row overlap were excluded.
        for (y in 0 until 30) for (x in 0 until width) {
            val value = referencePixels[(120 + y) * width + x].toInt() and 0xff
            currentPixels[y * width + x] = (value + if (value < 128) 12 else -12).toByte()
        }
        for (y in 0 until 6) for (x in 0 until width) {
            referencePixels[(144 + y) * width + x] = currentPixels[y * width + x]
            currentPixels[(24 + y) * width + x] = currentPixels[y * width + x]
        }
        val reference = LumaImage(width, height, referencePixels)
        val current = LumaImage(width, height, currentPixels)
        assertNull(CaptureGuideTracker(reference).locate(current))
        assertNull(StitchMatcher.track(reference, current, expectedShift = 120))
        assertNull(StitchMatcher.track(repeatRows(reference, 12), repeatRows(current, 12), expectedShift = 1440))
    }

    @Test
    fun rejectsFalseBackwardJumpsAfterLossButRecoversOnRealBackwardScroll() {
        val width = 96
        val height = 150
        val random = java.util.Random(63)
        val world = ByteArray(width * height * 2).also(random::nextBytes)
        fun frame(shift: Int) = LumaImage(width, height,
            world.copyOfRange(width * shift, width * (shift + height)))
        val tracker = CaptureGuideTracker(frame(0))
        assertEquals(0.1f, tracker.locate(frame(135))!!, 0.001f)
        assertNull(tracker.locate(frame(146)))
        for (falseShift in listOf(120, 135, 138)) {
            val lookalike = ByteArray(width * height).also(random::nextBytes)
            // Only the apparent overlap matches; the remaining live content did not move as proposed.
            frame(falseShift).pixels.copyInto(lookalike, endIndex = width * (height - falseShift))
            assertEquals(falseShift, StitchMatcher.track(frame(0), LumaImage(width, height, lookalike)))
            repeat(3) { assertNull(tracker.locate(LumaImage(width, height, lookalike))) }
        }
        assertEquals(0.2f, tracker.locate(frame(120))!!, 0.001f)
        assertEquals(0.8f, tracker.locate(frame(30))!!, 0.001f)
    }

    @Test
    fun usesRecentMotionToResolveRepeatedContentAtLowOverlap() {
        val width = 96
        val height = 150
        for (variation in listOf(0, 2)) {
            val random = java.util.Random(71)
            val world = ByteArray(width * height * 2).also(random::nextBytes)
            for (y in 120 until 150) for (x in 0 until width) {
                val value = world[(110 + y % 10) * width + x].toInt() and 0xff
                world[y * width + x] = (value + variation * (y / 10 - 11)).coerceAtMost(255).toByte()
            }
            fun frame(shift: Int) = LumaImage(width, height,
                world.copyOfRange(width * shift, width * (shift + height)))
            assertNull(StitchMatcher.track(frame(0), frame(120)))
            // Without a recent reliable frame, similar blocks remain ambiguous.
            assertNull(CaptureGuideTracker(frame(0)).locate(frame(120)))
            val tracker = CaptureGuideTracker(frame(0))
            for (shift in listOf(0, 90, 110, 120, 125, 130, 135, 138, 138, 130, 120)) {
                val bottom = tracker.locate(frame(shift))
                assertNotNull("shift=$shift variation=$variation", bottom)
                assertEquals(1f - shift.toFloat() / height, bottom!!, 0.001f)
            }
            assertNull(tracker.locate(frame(145)))
            assertNull(tracker.locate(frame(150)))
            assertEquals(0.2f, tracker.locate(frame(120))!!, 0.001f)
        }
    }

    @Test
    fun hidesTheMarkerWhenThereIsNoReliablePosition() {
        val width = 96
        val height = 150
        val blank = LumaImage(width, height, ByteArray(width * height) { 16 })
        assertNull(CaptureGuideTracker(blank).locate(blank))
        val random = java.util.Random(42)
        fun noise() = LumaImage(width, height, ByteArray(width * height).also(random::nextBytes))
        assertNull(CaptureGuideTracker(noise()).locate(noise()))
    }

    @Test
    fun fullHeightCandidatesPreserveAmbiguityAndTrackShortOverlaps() {
        val width = 48
        val height = 150
        val world = ByteArray(width * height * 2).also(java.util.Random(73)::nextBytes)
        fun frame(shift: Int) = repeatRows(LumaImage(width, height,
            world.copyOfRange(width * shift, width * (shift + height))), 12)
        val tracker = CaptureGuideTracker(frame(0))
        for (shift in listOf(0, 30, 80, 120, 130, 138)) {
            assertEquals(1f - shift.toFloat() / height, tracker.locate(frame(shift))!!, 0.01f)
        }
        assertNull(tracker.locate(frame(145)))
        val repeated = repeatRows(LumaImage(width, height, ByteArray(width * height) {
            if (it / width % 10 < 5) 30 else 220.toByte()
        }), 12)
        assertNull(CaptureGuideTracker(repeated).locate(repeated))
    }

    private fun repeatRows(image: LumaImage, factor: Int) = LumaImage(
        image.width, image.height * factor,
        ByteArray(image.pixels.size * factor) { image.pixels[(it / image.width / factor) * image.width + it % image.width] },
    )
}
