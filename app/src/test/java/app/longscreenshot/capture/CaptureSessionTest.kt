package app.longscreenshot.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CaptureSessionTest {
    @Test
    fun destroyReturnsToIdleWithoutAnExistingDirectory() {
        CaptureSession.directory = null
        CaptureSession.status = CaptureStatus.Failed("測試")

        assertTrue(CaptureSession.destroy())
        assertEquals(CaptureStatus.Idle, CaptureSession.status)
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
