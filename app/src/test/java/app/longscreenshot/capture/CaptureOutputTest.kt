package app.longscreenshot.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureOutputTest {
    @Test
    fun sessionKeepsOutputLockedAndReportsCompletionToTheCurrentPreview() = runBlocking {
        val preview = CaptureStatus.Finished(1, File("result.png"), "預覽")
        CaptureSession.status = preview
        val started = CompletableDeferred<File>()
        val finish = CountDownLatch(1)
        val saving = launch {
            CaptureOutput.runOutput("儲存中…", "已存到相簿") { source ->
                started.complete(source)
                check(finish.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertEquals(preview.output, withTimeout(5_000) { started.await() })
            val pending = preview.copy(outputAction = "儲存中…")
            assertEquals(pending, CaptureSession.status)
            var wroteAgain = false
            CaptureOutput.runOutput("複製中…", "已複製") { wroteAgain = true }
            assertFalse(wroteAgain)
            assertFalse(CaptureSession.destroy())
            assertEquals(pending, CaptureSession.status)
            finish.countDown()
            saving.join()
            assertEquals(preview.copy(outputMessage = "已存到相簿"), CaptureSession.status)

            CaptureOutput.runOutput("複製中…", "已複製") { error("寫入失敗") }
            assertEquals(preview.copy(outputMessage = "輸出失敗，來源圖片仍保留，請重試。"), CaptureSession.status)
            CaptureOutput.runOutput("複製中…", "已複製") {}
            assertEquals(preview.copy(outputMessage = "已複製"), CaptureSession.status)
        } finally {
            finish.countDown()
            saving.join()
            CaptureSession.status = CaptureStatus.Idle
        }
    }

    @Test
    fun sharedFilesExpireAtTwentyFourHours() {
        val day = 24 * 60 * 60 * 1000L

        assertFalse(CaptureOutput.isExpired(lastModified = 1, now = day))
        assertTrue(CaptureOutput.isExpired(lastModified = 1, now = day + 1))
    }

    @Test
    fun cropPercentagesMapToSourceRows() {
        assertEquals(100 to 900, CaptureOutput.cropRows(1_000, 0.1f..0.9f))
        assertEquals(0 to 1_000, CaptureOutput.cropRows(1_000, 0f..1f))
    }
}
