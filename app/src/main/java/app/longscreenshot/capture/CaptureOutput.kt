package app.longscreenshot.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

internal enum class OutputFormat(
    val extension: String,
    val mimeType: String,
) {
    Jpg("jpg", "image/jpeg"),
    Png("png", "image/png"),
}

internal object CaptureOutput {
    private const val SHARE_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L

    fun saveToGallery(
        context: Context,
        source: File,
        format: OutputFormat,
        crop: ClosedFloatingPointRange<Float>,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "long-screenshot-${System.currentTimeMillis()}.${format.extension}",
            )
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Long Screenshot",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "無法建立相簿項目" }

        try {
            resolver.openOutputStream(uri)?.use { write(source, format, crop, it) }
                ?: error("無法開啟相簿項目")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "無法完成相簿項目" }
            resolver.openFileDescriptor(uri, "r")?.use { } ?: error("相簿成品無法讀取")
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    fun copyToClipboard(
        context: Context,
        source: File,
        format: OutputFormat,
        crop: ClosedFloatingPointRange<Float>,
    ) {
        val directory = File(context.cacheDir, "shared").apply {
            check(isDirectory || mkdirs()) { "無法建立剪貼簿暫存目錄" }
        }
        val target = File(
            directory,
            "long-screenshot-${System.currentTimeMillis()}.${format.extension}",
        )
        try {
            target.outputStream().use { write(source, format, crop, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newUri(context.contentResolver, "長截圖", uri),
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun cleanExpiredShares(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val directory = File(context.cacheDir, "shared")
        if (!directory.exists()) return true
        val files = directory.listFiles() ?: return false
        return files.filter { isExpired(it.lastModified(), now) }.map(File::delete).all { it }
    }

    fun isExpired(lastModified: Long, now: Long): Boolean =
        now - lastModified >= SHARE_LIFETIME_MILLIS

    private fun write(
        source: File,
        format: OutputFormat,
        crop: ClosedFloatingPointRange<Float>,
        output: java.io.OutputStream,
    ) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "無法讀取來源圖片" }
        val (top, bottom) = cropRows(bounds.outHeight, crop)
        if (format == OutputFormat.Png && top == 0 && bottom == bounds.outHeight) {
            source.inputStream().use { it.copyTo(output) }
            return
        }

        @Suppress("DEPRECATION")
        val decoder = checkNotNull(BitmapRegionDecoder.newInstance(source.path, false)) {
            "無法讀取來源圖片"
        }
        val bitmap = try {
            checkNotNull(decoder.decodeRegion(Rect(0, top, bounds.outWidth, bottom), null)) {
                "無法裁切來源圖片"
            }
        } finally {
            @Suppress("DEPRECATION")
            decoder.recycle()
        }
        try {
            val compression = if (format == OutputFormat.Jpg) {
                check(bitmap.height <= 65_535) { "JPG 高度不可超過 65,535 px，請改用 PNG" }
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            }
            check(bitmap.compress(compression, 95, output)) { "${format.name} 寫入失敗" }
        } finally {
            bitmap.recycle()
        }
    }

    internal fun cropRows(
        height: Int,
        crop: ClosedFloatingPointRange<Float>,
    ): Pair<Int, Int> {
        require(height > 0)
        val top = (height * crop.start.coerceIn(0f, 1f)).toInt().coerceAtMost(height - 1)
        val bottom = (height * crop.endInclusive.coerceIn(0f, 1f)).toInt()
            .coerceIn(top + 1, height)
        return top to bottom
    }
}
