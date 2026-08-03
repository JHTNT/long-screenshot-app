package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
