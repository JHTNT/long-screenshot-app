package app.longscreenshot.capture

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

enum class CaptureMode { General, ContentRegion }

sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data object Starting : CaptureStatus
    data class Capturing(val count: Int, val message: String? = null) : CaptureStatus
    data class SelectingRegion(val count: Int) : CaptureStatus
    data class Stitching(val count: Int) : CaptureStatus
    data class Finished(
        val count: Int,
        val output: File?,
        val message: String,
    ) : CaptureStatus
    data class Failed(val message: String, val retainedCount: Int = 0) : CaptureStatus
}

object CaptureSession {
    var status by mutableStateOf<CaptureStatus>(CaptureStatus.Idle)
        internal set

    var directory: File? = null
        internal set

    var systemTopInset = 0
        internal set

    var systemBottomInset = 0
        internal set

    var mode = CaptureMode.General
        internal set

    fun create(context: Context): File {
        val root = File(context.cacheDir, "capture-sessions").apply { mkdirs() }
        return File(root, System.currentTimeMillis().toString()).apply {
            check(mkdir()) { "無法建立暫存目錄" }
            directory = this
        }
    }

    fun sourceFile(index: Int) =
        checkNotNull(directory).resolve("source-${index.toString().padStart(4, '0')}.png")

    fun resultFile() = checkNotNull(directory).resolve("result.png")

    fun destroy(): Boolean {
        val target = directory
        val deleted = target == null || !target.exists() || target.deleteRecursively()
        if (deleted) {
            directory = null
            systemTopInset = 0
            systemBottomInset = 0
            mode = CaptureMode.General
            status = CaptureStatus.Idle
        }
        return deleted
    }

    fun cleanOrphans(context: Context): Boolean {
        if (status !is CaptureStatus.Idle) return true
        val root = File(context.cacheDir, "capture-sessions")
        return !root.exists() || root.deleteRecursively()
    }
}
