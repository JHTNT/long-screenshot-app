package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ManualStitcherTest {
    @Test
    fun defaultLayoutKeepsOnlyNewRows() {
        val plan = plan(
            proposal(60),
            proposal(50),
        )

        val layout = ManualStitcher.layout(plan)

        assertEquals(210, layout.height)
        assertEquals(
            listOf(
                ManualSlice(0, 0, 100, 0),
                ManualSlice(1, 40, 100, 100),
                ManualSlice(2, 50, 100, 160),
            ),
            layout.slices,
        )
    }

    @Test
    fun changingCropInvalidatesBothAdjacentSeams() {
        val plan = plan(proposal(60), proposal(50)).withCrop(1, ManualCrop(20, 90))

        assertFalse(plan.seams[0].confirmed)
        assertFalse(plan.seams[1].confirmed)
        assertEquals(20, ManualStitcher.overlap(plan, 0))
        assertEquals(40, ManualStitcher.overlap(plan, 1))
    }

    @Test
    fun invalidCropAndGapAreRejected() {
        val plan = plan(proposal(60), proposal(50))
        val gap = plan.withCrop(1, ManualCrop(80, 90))

        assertEquals("接縫之間沒有重疊", ManualStitcher.validate(gap).seamErrors[0])
        assertTrue(runCatching { plan.withCrop(1, ManualCrop(20, 20)) }.isFailure)
    }

    @Test
    fun croppedNextCannotAddOnlyDuplicateRows() {
        val adjusted = plan(proposal(60)).withCrop(1, ManualCrop(0, 40))

        assertEquals("接縫沒有新增內容", ManualStitcher.validate(adjusted).seamErrors[0])
    }

    @Test
    fun resetRestoresAutomaticCropAndAdjacentProposal() {
        val changed = plan(proposal(60), proposal(50))
            .withShift(0, -5)
            .withCrop(1, ManualCrop(10, 90))

        val reset = changed.resetImage(1)

        assertEquals(60, reset.seams[0].shift)
        assertEquals(ManualCrop(0, 100), reset.crops[1])
        assertTrue(reset.seams[0].confirmed)
    }

    @Test
    fun resetSeamRestoresBothCropsAndOnlyCurrentShift() {
        val initial = plan(proposal(60), proposal(50), proposal(50), proposal(50))
        val edited = initial.withCrop(0, ManualCrop(3, 95))
            .withCrop(1, ManualCrop(5, 80))
            .withCrop(2, ManualCrop(15, 90))
            .withCrop(3, ManualCrop(7, 95))
            .withShift(0, -4).withShift(1, 5).withShift(2, -7).withShift(3, -3)
        val confirmed = edited.seams.indices.fold(edited) { current, index -> current.confirm(index) }

        val reset = confirmed.resetSeam(1)

        assertEquals(initial.defaultCrops[1], reset.crops[1])
        assertEquals(initial.defaultCrops[2], reset.crops[2])
        assertEquals(initial.defaultSeams[1], reset.seams[1])
        for (index in listOf(0, 3, 4)) assertEquals(confirmed.crops[index], reset.crops[index])
        for (index in listOf(0, 2, 3)) assertEquals(confirmed.seams[index].shift, reset.seams[index].shift)
        assertFalse(reset.seams[0].confirmed)
        assertFalse(reset.seams[2].confirmed)
        assertEquals(confirmed.seams[3], reset.seams[3])
    }

    @Test
    fun resettingOnlyShiftPreservesOtherSeamConfirmations() {
        val edited = plan(proposal(60), proposal(50), proposal(50))
            .withShift(0, -4).confirm(0)
            .withShift(1, 5).confirm(1)

        val reset = edited.resetSeam(1)

        assertEquals(edited.defaultSeams[1], reset.seams[1])
        assertEquals(edited.seams[0], reset.seams[0])
        assertEquals(edited.seams[2], reset.seams[2])
    }

    @Test
    fun resetFirstAndLastSeamsRestoresLowConfidenceDefaults() {
        val initial = plan(proposal(60, confident = false), proposal(50), proposal(50, confident = false))
        for (index in listOf(0, initial.seams.lastIndex)) {
            val edited = initial.withShift(index, -5)
                .withCrop(index, ManualCrop(10, 90))
                .withCrop(index + 1, ManualCrop(10, 95))
                .confirm(index)

            val reset = edited.resetSeam(index)

            assertEquals(initial.defaultCrops[index], reset.crops[index])
            assertEquals(initial.defaultCrops[index + 1], reset.crops[index + 1])
            assertEquals(initial.defaultSeams[index], reset.seams[index])
            assertFalse(reset.seams[index].confirmed)
        }
    }

    @Test
    fun resettingKeptDuplicateRestoresSkipAndInvalidatesFollowingReview() {
        val initial = plan(proposal(0, reason = StitchReason.Duplicate), proposal(50))
        val edited = initial.keepDuplicate(0).withShift(1, -5).confirm(0).confirm(1)

        val reset = edited.resetSeam(0)

        assertEquals(initial.defaultSeams[0], reset.seams[0])
        assertEquals(edited.seams[1].shift, reset.seams[1].shift)
        assertFalse(reset.seams[1].confirmed)
    }

    @Test
    fun lowConfidenceSeamMustBeConfirmedBeforeApply() {
        val low = plan(proposal(60, confident = false))

        assertFalse(low.isReady())
        assertTrue(low.confirm(0).isReady())
    }

    @Test
    fun duplicateCanBeSkippedOrKeptForManualConfirmation() {
        val duplicate = plan(
            proposal(0, reason = StitchReason.Duplicate),
            proposal(50),
        )

        assertTrue(duplicate.seams[0].skipped)
        assertEquals(150, ManualStitcher.layout(duplicate).height)

        val kept = duplicate.keepDuplicate(0)
        assertFalse(kept.seams[0].skipped)
        assertFalse(kept.seams[1].confirmed)
        assertEquals(50, kept.seams[0].shift)
        assertFalse(kept.isReady())
        assertFalse(kept.confirm(0).isReady())
    }

    @Test
    fun nextReviewSkipsConfirmedAndDuplicateSeams() {
        val plan = plan(
            proposal(60, confident = false),
            proposal(50),
            proposal(0, confident = false, reason = StitchReason.Duplicate),
            proposal(50, confident = false),
        )

        assertEquals(3, nextManualSeamToReview(plan, 0))
    }

    @Test
    fun nextReviewWrapsToEarlierSeamInvalidatedByCrop() {
        val adjusted = plan(proposal(60), proposal(50))
            .withCrop(1, ManualCrop(20, 90))
            .confirm(1)

        assertFalse(adjusted.seams[0].confirmed)
        assertEquals(0, nextManualSeamToReview(adjusted, 1))
    }

    @Test
    fun nextReviewReturnsNullWhenAllSeamsAreComplete() {
        val complete = plan(
            proposal(60),
            proposal(0, confident = false, reason = StitchReason.Duplicate),
            proposal(50),
        )

        assertTrue(complete.isReady())
        assertNull(nextManualSeamToReview(complete, 0))
    }

    @Test
    fun nextReviewIncludesConfirmedSeamWithInvalidGeometry() {
        val initial = plan(proposal(60), proposal(50))
        val invalid = initial.copy(
            crops = initial.crops.toMutableList().apply { this[2] = ManualCrop(0, 10) },
        )

        assertTrue(invalid.seams[1].confirmed)
        assertEquals("接縫沒有新增內容", ManualStitcher.validate(invalid).seamErrors[1])
        assertEquals(1, nextManualSeamToReview(invalid, 0))
    }

    @Test
    fun nextReviewExcludesCurrentUnconfirmedSeam() {
        val currentOnly = plan(proposal(60, confident = false))

        assertNull(nextManualSeamToReview(currentOnly, 0))
    }

    @Test
    fun invalidPlanDoesNotTouchExistingOutput() {
        val directory = Files.createTempDirectory("manual-stitch-test").toFile()
        try {
            val target = directory.resolve("result.png").apply { writeText("old") }
            val invalid = plan(proposal(60)).withCrop(1, ManualCrop(80, 90))

            assertTrue(runCatching {
                ManualStitcher.stitch(listOf(directory.resolve("source.png"), directory.resolve("source-2.png")), target, invalid)
            }.isFailure)
            assertEquals("old", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun memoryRequirementOverflowIsRejectedBeforeReadingSources() {
        val directory = Files.createTempDirectory("manual-stitch-test").toFile()
        try {
            val height = 1_000_000_000
            val seam = ManualSeam(1, confirmed = true, skipped = false)
            val crop = ManualCrop(0, height)
            val plan = ManualStitchPlan(
                width = Int.MAX_VALUE,
                height = height,
                defaultCrops = listOf(crop, crop),
                crops = listOf(crop, crop),
                defaultSeams = listOf(seam),
                seams = listOf(seam),
            )

            val error = runCatching {
                ManualStitcher.stitch(
                    listOf(directory.resolve("source-1.png"), directory.resolve("source-2.png")),
                    directory.resolve("result.png"),
                    plan,
                )
            }.exceptionOrNull()

            assertEquals("記憶體需求溢位", error?.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun plan(vararg proposals: StitchProposal): ManualStitchPlan =
        ManualStitcher.initialPlan(10, 100, proposals.toList())

    private fun proposal(
        shift: Int,
        confident: Boolean = true,
        reason: StitchReason = StitchReason.Matched,
    ) = StitchProposal(
        shift = shift,
        overlap = 100 - shift,
        bottomCrop = 0,
        score = 1.0,
        secondBestGap = 1.0,
        confident = confident,
        reason = reason,
    )
}
