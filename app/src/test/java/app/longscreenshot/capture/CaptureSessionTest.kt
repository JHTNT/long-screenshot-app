package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CaptureSessionTest {
    @Test
    fun partiallyDeletedSessionOffersCleanupInsteadOfMissingThumbnails() {
        val directory = Files.createTempDirectory("capture-cleanup-test").toFile()
        try {
            CaptureSession.directory = object : File(directory.path) {
                override fun delete() = false
            }
            CaptureSession.status = CaptureStatus.Reviewing(2)
            (1..2).forEach { CaptureSession.sourceFile(it).writeText("source") }

            assertFalse(CaptureSession.destroy())
            assertFalse(CaptureSession.sourceFile(1).exists())
            val failure = CaptureSession.status as CaptureStatus.Failed
            assertEquals(0, failure.retainedCount)
            assertEquals(CaptureStatus.Idle, previousCaptureScreen(failure))

            CaptureSession.directory = directory
            assertTrue(CaptureSession.destroy())
            assertFalse(directory.exists())
            assertEquals(CaptureStatus.Idle, CaptureSession.status)
        } finally {
            directory.deleteRecursively()
            CaptureSession.directory = null
            CaptureSession.status = CaptureStatus.Idle
        }
    }

    @Test
    fun destroyReturnsToIdleWithoutAnExistingDirectory() {
        CaptureSession.directory = null
        CaptureSession.contentRegion = VerticalRegion(10, 100)
        CaptureSession.status = CaptureStatus.Failed("測試")

        assertTrue(CaptureSession.destroy())
        assertEquals(CaptureStatus.Idle, CaptureSession.status)
        assertEquals(null, CaptureSession.contentRegion)
    }

    @Test
    fun deletingSourceClosesTheNumberingGap() {
        val directory = Files.createTempDirectory("capture-session-test").toFile()
        try {
            CaptureSession.directory = directory
            (1..3).forEach { CaptureSession.sourceFile(it).writeText("$it") }

            assertTrue(CaptureSession.deleteSource(2, 3))
            assertEquals("1", CaptureSession.sourceFile(1).readText())
            assertEquals("3", CaptureSession.sourceFile(2).readText())
            assertEquals(false, CaptureSession.sourceFile(3).exists())
        } finally {
            directory.deleteRecursively()
            CaptureSession.directory = null
        }
    }
}
