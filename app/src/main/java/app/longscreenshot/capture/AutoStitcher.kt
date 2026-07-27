package app.longscreenshot.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import java.io.File
import kotlin.math.abs
import kotlin.math.max

internal enum class StitchReason(val message: String) {
    Matched("已找到可靠重疊"),
    Duplicate("與前一張重複"),
    LowTexture("可辨識內容不足"),
    Ambiguous("重疊位置不明確"),
    Inconsistent("畫面內容不一致"),
    NoOverlap("找不到重疊"),
    ReverseScroll("可能向上捲動"),
}

internal data class StitchProposal(
    val shift: Int,
    val overlap: Int,
    val bottomCrop: Int,
    val score: Double,
    val secondBestGap: Double,
    val confident: Boolean,
    val reason: StitchReason,
)

internal data class LumaImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff
}

internal data class VerticalRegion(val top: Int, val bottom: Int) {
    init {
        require(top >= 0 && bottom > top) { "指定區域無效" }
    }

    val height: Int get() = bottom - top
}

internal object StitchMatcher {
    private const val MIN_SCORE = 0.96
    private const val MIN_ZONE_SCORE = 0.94
    private const val MIN_TEXTURE = 0.08
    private const val MIN_GAP = 0.01
    private const val EXACT_SCORE = 0.999
    private const val EXACT_GAP = 0.005

    fun find(
        a: LumaImage,
        b: LumaImage,
        topInset: Int = 0,
        bottomInset: Int = 0,
        region: VerticalRegion? = null,
    ): StitchProposal {
        require(a.width == b.width && a.height == b.height) { "圖片尺寸不一致" }
        require(region == null || region.bottom <= a.height) { "指定區域超出圖片" }
        val top = region?.top ?: topInset.coerceIn(0, a.height - 1)
        val bottom = region?.let { a.height - it.bottom }
            ?: fixedBottom(a, b, bottomInset.coerceIn(0, a.height - top - 1))
        val contentHeight = a.height - top - bottom
        require(contentHeight > 0) { "內容高度無效" }

        val duplicate = score(a, b, 0, top, bottom)
        if (duplicate.score >= 0.995 && duplicate.texture >= MIN_TEXTURE) {
            return StitchProposal(0, contentHeight, bottom, duplicate.score, 1.0, true, StitchReason.Duplicate)
        }

        val forward = search(a, b, top, bottom)
        val reverse = search(b, a, top, bottom)
        val zoneSpread = forward.zoneBest.maxOrNull()!! - forward.zoneBest.minOrNull()!!
        val exact = region != null && forward.best.score >= EXACT_SCORE && forward.gap >= EXACT_GAP
        val reason = when {
            forward.best.texture < MIN_TEXTURE -> StitchReason.LowTexture
            reverse.best.score >= MIN_SCORE && reverse.best.score > forward.best.score + MIN_GAP ->
                StitchReason.ReverseScroll
            forward.best.score < MIN_SCORE -> StitchReason.NoOverlap
            forward.gap < MIN_GAP && !exact -> StitchReason.Ambiguous
            !exact && (forward.best.zoneScores.count { it >= MIN_ZONE_SCORE } < 2 || zoneSpread > 2) ->
                StitchReason.Inconsistent
            else -> StitchReason.Matched
        }
        return StitchProposal(
            shift = forward.best.shift,
            overlap = contentHeight - forward.best.shift,
            bottomCrop = bottom,
            score = forward.best.score,
            secondBestGap = forward.gap,
            confident = reason == StitchReason.Matched,
            reason = reason,
        )
    }

    private data class Score(
        val shift: Int,
        val score: Double,
        val texture: Double,
        val zoneScores: DoubleArray,
    )

    private data class Search(
        val best: Score,
        val gap: Double,
        val zoneBest: IntArray,
    )

    private fun search(a: LumaImage, b: LumaImage, top: Int, bottom: Int): Search {
        val contentHeight = a.height - top - bottom
        val minimum = max(1, (contentHeight * 0.05).toInt())
        val minimumOverlap = max(
            8,
            max((contentHeight * 0.05).toInt(), (a.width * 0.08).toInt()),
        )
        val maximum = max(minimum, contentHeight - minimumOverlap)
        val step = max(1, contentHeight / 200)
        val coarse = (minimum..maximum step step).map { score(a, b, it, top, bottom) }
        val seeds = coarse
            .sortedByDescending { it.score }
            .take(5)
        val fine = seeds
            .flatMap { seed ->
                (max(minimum, seed.shift - step)..minOf(maximum, seed.shift + step))
                    .map { score(a, b, it, top, bottom) }
            }
            .distinctBy { it.shift }
        val best = fine.maxBy { it.score }
        val candidates = coarse + fine
        val second = candidates
            .asSequence()
            .filter { abs(it.shift - best.shift) > step * 2 }
            .maxOfOrNull { it.score }
            ?: 0.0
        val local = fine.filter { abs(it.shift - best.shift) <= step }
        val zoneBest = IntArray(3) { zone ->
            local.maxBy { it.zoneScores[zone] }.shift
        }
        return Search(best, best.score - second, zoneBest)
    }

    private fun score(a: LumaImage, b: LumaImage, shift: Int, top: Int, bottom: Int): Score {
        val end = a.height - bottom - shift
        val xStep = max(1, a.width / 180)
        val yStep = max(1, (end - top) / 240)
        val rows = ArrayList<Pair<DoubleArray, Double>>()

        var y = max(top + 1, 1)
        while (y < end) {
            val sums = DoubleArray(3)
            val counts = IntArray(3)
            var texture = 0
            var count = 0
            var x = 1
            while (x < a.width) {
                val ay = y + shift
                val av = a[x, ay]
                val bv = b[x, y]
                val ag = (abs(av - a[x - 1, ay]) + abs(av - a[x, ay - 1])).coerceAtMost(255)
                val bg = (abs(bv - b[x - 1, y]) + abs(bv - b[x, y - 1])).coerceAtMost(255)
                val similarity = 1.0 - (abs(av - bv) * 0.35 + abs(ag - bg) * 0.65) / 255.0
                val zone = minOf(2, x * 3 / a.width)
                sums[zone] += similarity
                counts[zone] += 1
                if (max(ag, bg) >= 12) texture += 1
                count += 1
                x += xStep
            }
            val textureRatio = if (count == 0) 0.0 else texture.toDouble() / count
            rows += DoubleArray(3) {
                if (counts[it] == 0) 0.0 else sums[it] / counts[it]
            } to textureRatio
            y += yStep
        }
        if (rows.isEmpty()) return Score(shift, 0.0, 0.0, DoubleArray(3))
        val kept = rows
            .sortedByDescending { it.first.average() }
            .take(max(1, (rows.size * 0.8).toInt()))
        val zones = DoubleArray(3) { zone -> kept.map { it.first[zone] }.average() }
        return Score(
            shift,
            zones.average(),
            kept.count { it.second >= 0.05 }.toDouble() / kept.size,
            zones,
        )
    }

    private fun fixedBottom(a: LumaImage, b: LumaImage, minimum: Int): Int {
        val xStep = max(1, a.width / 180)
        val limit = max(minimum, (a.height * 0.35).toInt())
        var top = a.height - minimum
        var badRows = 0
        var y = top - 1
        while (y >= a.height - limit) {
            var difference = 0L
            var count = 0
            var x = 0
            while (x < a.width) {
                difference += abs(a[x, y] - b[x, y])
                count += 1
                x += xStep
            }
            if (difference.toDouble() / count <= 3.0) {
                top = y
                badRows = 0
            } else if (++badRows >= 6) {
                break
            }
            y -= 1
        }
        return a.height - top
    }
}

internal object AutoStitcher {
    data class Result(
        val output: File?,
        val seams: List<StitchProposal>,
        val message: String,
    )

    fun stitch(
        sources: List<File>,
        target: File,
        topInset: Int = 0,
        bottomInset: Int = 0,
        region: VerticalRegion? = null,
    ): Result {
        require(sources.isNotEmpty()) { "沒有來源圖片" }
        if (sources.size == 1 && region == null) {
            return Result(sources.single(), emptyList(), "單張圖片不需拼接")
        }

        val seams = ArrayList<StitchProposal>(sources.size - 1)
        var previous = decode(sources.first())
        val width = previous.width
        val height = previous.height
        require(region == null || region.bottom <= height) { "指定區域超出圖片" }
        try {
            for (source in sources.drop(1)) {
                val next = decode(source)
                try {
                    require(next.width == width && next.height == height) { "圖片尺寸不一致" }
                    seams += StitchMatcher.find(
                        previous.toLuma(),
                        next.toLuma(),
                        topInset,
                        bottomInset,
                        region,
                    )
                } finally {
                    previous.recycle()
                    previous = next
                }
            }
        } finally {
            previous.recycle()
        }

        val firstLow = seams.indexOfFirst { !it.confident }
        if (firstLow >= 0) {
            return Result(null, seams, "第 ${firstLow + 1} 個接縫：${seams[firstLow].reason.message}")
        }

        val bottomCrop = seams.maxOfOrNull { it.bottomCrop } ?: (height - checkNotNull(region).bottom)
        val outputHeight = height.toLong() + seams.sumOf { it.shift.toLong() }
        require(outputHeight in 1..Int.MAX_VALUE.toLong()) { "成品高度無效" }
        val required = width.toLong() * (outputHeight + height) * 4
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        require(required <= available) { "可用記憶體不足，無法安全產生成品" }

        val output = Bitmap.createBitmap(width, outputHeight.toInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            var y = 0
            val contentBottom = region?.bottom ?: height - bottomCrop
            draw(canvas, decode(sources.first()), 0, contentBottom, y)
            y += contentBottom
            for (index in 1 until sources.size) {
                val seam = seams[index - 1]
                val shift = seam.shift
                if (shift > 0) {
                    draw(
                        canvas,
                        decode(sources[index]),
                        contentBottom - shift,
                        contentBottom,
                        y,
                    )
                    y += shift
                }
            }
            if (contentBottom < height) {
                draw(canvas, decode(sources.last()), contentBottom, height, y)
            }
            target.outputStream().use {
                check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) { "拼接圖片寫入失敗" }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            output.recycle()
        }
        val message = if (region == null) {
            "已自動拼接 ${seams.size} 個接縫"
        } else {
            "已依指定區域拼接 ${seams.size} 個接縫"
        }
        return Result(target, seams, message)
    }

    private fun decode(file: File): Bitmap =
        checkNotNull(BitmapFactory.decodeFile(file.path)) { "無法讀取來源圖片" }

    private fun Bitmap.toLuma(): LumaImage {
        val row = IntArray(width)
        val luma = ByteArray(width * height)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            row.forEachIndexed { x, color ->
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                luma[y * width + x] = ((red * 77 + green * 150 + blue * 29) ushr 8).toByte()
            }
        }
        return LumaImage(width, height, luma)
    }

    private fun draw(canvas: Canvas, bitmap: Bitmap, top: Int, bottom: Int, outputTop: Int) {
        draw(
            canvas,
            bitmap,
            Rect(0, top, bitmap.width, bottom),
            Rect(0, outputTop, bitmap.width, outputTop + bottom - top),
        )
    }

    private fun draw(canvas: Canvas, bitmap: Bitmap, source: Rect, target: Rect) {
        try {
            canvas.drawBitmap(bitmap, source, target, null)
        } finally {
            bitmap.recycle()
        }
    }
}
