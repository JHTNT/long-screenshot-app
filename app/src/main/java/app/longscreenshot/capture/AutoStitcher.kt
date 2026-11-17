package app.longscreenshot.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.max

private const val TRACKING_ROW_BANDS = 4

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
    val seamBottom: Int? = null,
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

    internal val trackingGradients by lazy(LazyThreadSafetyMode.NONE) {
        IntArray(pixels.size) { i ->
            val value = pixels[i].toInt() and 0xff
            val left = if (i % width == 0) value else pixels[i - 1].toInt() and 0xff
            val above = if (i < width) value else pixels[i - width].toInt() and 0xff
            (abs(value - left) + abs(value - above)).coerceAtMost(255)
        }
    }

    internal val trackingRows by lazy(LazyThreadSafetyMode.NONE) {
        val rowSize = TRACKING_ROW_BANDS * 2
        IntArray(height * rowSize).also { rows ->
            for (y in 0 until height) {
                for (x in 1 until width step 2) {
                    val band = x * TRACKING_ROW_BANDS / width
                    val offset = y * rowSize + band * 2
                    rows[offset] += this[x, y]
                    rows[offset + 1] += trackingGradients[y * width + x]
                }
            }
        }
    }
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
    private const val EXACT_SCORE = 0.998
    private const val EXACT_GAP = 0.001
    private const val MIN_TRACKING_SCORE = 0.94
    private const val MIN_TRACKING_GAP = 0.015

    // Preserve source rows; use row summaries to shortlist shifts before comparing pixels.
    fun track(a: LumaImage, b: LumaImage, expectedShift: Int? = null): Int? {
        require(a.width == b.width && a.height == b.height)
        if (a.trackingGradients.none { it >= 12 } && b.trackingGradients.none { it >= 12 }) return null
        val scale = max(1, a.height / 160)
        val minimumOverlap = max(12 * scale, a.height / 20)
        // Short overlaps must compete, or a weaker long lookalike can win after the true position leaves range.
        val maximum = a.height - 4 * scale
        if (maximum < 0) return null
        val candidates = if (scale == 1) (0..maximum).toList() else {
            val ar = a.trackingRows
            val br = b.trackingRows
            val rowSize = TRACKING_ROW_BANDS * 2
            val errors = DoubleArray(maximum + 1) { shift ->
                var error = 0L
                var samples = 0
                for (y in 1 until a.height - shift step max(1, (a.height - shift) / 64)) {
                    for (feature in 0 until rowSize) {
                        error += abs(ar[(y + shift) * rowSize + feature] - br[y * rowSize + feature])
                    }
                    samples++
                }
                error.toDouble() / max(1, samples)
            }
            val ranked = errors.indices.sortedBy { errors[it] }
            val seeds = ArrayList<Int>()
            for (shift in ranked) {
                if (seeds.none { abs(it - shift) <= 2 * scale }) seeds += shift
                if (seeds.size == 8) break
            }
            // Row sums can tie despite different pixels, including within one horizontal band.
            val seedErrors = seeds.map { errors[it] }.toSet()
            val tiedSeeds = ranked.filter { errors[it] in seedErrors }.toMutableList()
            if (expectedShift != null) tiedSeeds += expectedShift
            tiedSeeds.flatMap { max(0, it - 2)..minOf(maximum, it + 2) }.distinct()
        }
        val scores = DoubleArray(maximum + 1) { -1.0 }
        var bestShift = 0
        var bestScore = -1.0
        var bestTexture = 0.0
        var expectedBestShift = -1
        var expectedBestScore = -1.0
        var expectedTexture = 0.0
        for (shift in candidates) {
            val score = trackingScore(a, b, shift, max(MIN_TRACKING_SCORE, bestScore) - MIN_TRACKING_GAP)
            if (score.texture < MIN_TEXTURE) continue
            val similarity = score.similarity
            // Two separated perfect matches cannot become a unique match by examining more candidates.
            if (expectedShift == null && bestScore == 1.0 && similarity == 1.0 &&
                abs(shift - bestShift) > 2 * scale) return null
            scores[shift] = similarity
            if (similarity > bestScore) {
                bestScore = similarity
                bestShift = shift
                bestTexture = score.texture
            }
            if (expectedShift != null && abs(shift - expectedShift) <= 2 * scale && similarity > expectedBestScore) {
                expectedBestShift = shift
                expectedBestScore = similarity
                expectedTexture = score.texture
            }
        }
        // Recent motion can break a tie, but cannot override a better match elsewhere (including short overlaps).
        if (expectedShift != null) return expectedBestShift.takeIf {
            it >= 0 && a.height - it >= minimumOverlap && expectedTexture >= MIN_TEXTURE &&
                expectedBestScore >= MIN_TRACKING_SCORE && expectedBestScore >= bestScore
        }
        val second = candidates.filter { abs(it - bestShift) > 2 * scale }
            .maxOfOrNull { scores[it] } ?: 0.0
        return bestShift.takeIf {
            a.height - bestShift >= minimumOverlap && bestTexture >= MIN_TEXTURE &&
                bestScore >= MIN_TRACKING_SCORE && bestScore - second >= MIN_TRACKING_GAP
        }
    }

    // The saved reference supplies this displacement; distant repeated cards cannot veto its verification.
    fun confirmsRecentMovement(a: LumaImage, b: LumaImage, shift: Int): Boolean {
        require(a.width == b.width && a.height == b.height)
        if (shift !in -a.height / 2..a.height / 2) return false
        val first = if (shift < 0) b else a
        val second = if (shift < 0) a else b
        return (max(0, abs(shift) - 2)..minOf(a.height / 2, abs(shift) + 2)).any {
            val score = trackingScore(first, second, it)
            score.similarity >= 0.98 && score.texture >= MIN_TEXTURE
        }
    }

    private data class TrackingScore(val similarity: Double, val texture: Double)

    private fun trackingScore(a: LumaImage, b: LumaImage, shift: Int, minimumScore: Double = 0.0): TrackingScore {
        val ags = a.trackingGradients
        val bgs = b.trackingGradients
        var error = 0L
        var samples = 0
        var texturedRows = 0
        var rows = 0
        val end = a.height - shift
        val step = max(1, end / 160)
        val totalRows = if (end > 1) (end - 2) / step + 1 else 0
        for (y in 1 until end step step) {
            var textured = 0
            var columns = 0
            for (x in 1 until a.width step 2) {
                val av = a[x, y + shift]
                val bv = b[x, y]
                val ag = ags[(y + shift) * a.width + x]
                val bg = bgs[y * b.width + x]
                val hasDetail = max(ag, bg) >= 12
                // Repeated blank backgrounds must not drown out differences in text and edges.
                val weight = if (hasDetail) 4 else 1
                error += (abs(av - bv) * 35 + abs(ag - bg) * 65) * weight
                samples += weight
                if (hasDetail) textured += 1
                columns += 1
            }
            if (columns > 0 && textured.toDouble() / columns >= 0.05) texturedRows += 1
            rows++
            // Even perfect remaining pixels at maximum weight cannot rescue this candidate.
            val maximumSamples = samples + (totalRows - rows) * columns * 4
            if (error > (1.0 - minimumScore) * 25500.0 * maximumSamples) return TrackingScore(0.0, 0.0)
        }
        return TrackingScore(
            if (samples == 0) 0.0 else 1.0 - error / (25500.0 * samples),
            if (rows == 0) 0.0 else texturedRows.toDouble() / rows,
        )
    }

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
        val reverse = if (forward.best.score + MIN_GAP < 1.0) search(b, a, top, bottom) else null
        val zoneSpread = forward.zoneBest.maxOrNull()!! - forward.zoneBest.minOrNull()!!
        val exact = region != null && forward.best.score >= EXACT_SCORE && forward.gap >= EXACT_GAP
        val seamBottom = stableSeamBottom(a, b, forward.best.shift, top, bottom)
        val reason = when {
            forward.best.texture < MIN_TEXTURE -> StitchReason.LowTexture
            reverse != null && reverse.best.score >= MIN_SCORE && reverse.best.score > forward.best.score + MIN_GAP ->
                StitchReason.ReverseScroll
            forward.best.score < MIN_SCORE -> StitchReason.NoOverlap
            forward.gap < MIN_GAP && !exact -> StitchReason.Ambiguous
            seamBottom == null -> StitchReason.Inconsistent
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
            seamBottom = seamBottom,
        )
    }

    private data class Score(
        val shift: Int,
        val score: Double,
        val texture: Double,
        val zoneScores: DoubleArray,
        val untrimmedTexture: Double = texture,
        val untrimmedScore: Double = score,
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
        val rankedCoarse = coarse.sortedByDescending { it.score }
        // Misaligned text can be discarded with dynamic rows; keep it eligible for refinement.
        val seeds = (
            rankedCoarse.filter { it.texture >= MIN_TEXTURE }.take(5) +
                rankedCoarse.filter { it.untrimmedTexture >= MIN_TEXTURE }
                    .sortedByDescending { it.untrimmedScore }.take(5)
            ).ifEmpty { rankedCoarse.take(5) }.distinctBy { it.shift }
        val coarseByShift = coarse.associateBy { it.shift }
        val fine = seeds
            .flatMap { seed ->
                max(minimum, seed.shift - step)..minOf(maximum, seed.shift + step)
            }
            .distinct()
            .map { coarseByShift[it] ?: score(a, b, it, top, bottom) }
        val candidates = coarse + fine
        val ranked = candidates.filter { it.texture >= MIN_TEXTURE }.ifEmpty { candidates }
        val best = ranked.maxBy { it.score }
        val second = ranked
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
            .take(max(1, (rows.size * 0.55).toInt()))
        val zones = DoubleArray(3) { zone -> kept.map { it.first[zone] }.average() }
        return Score(
            shift,
            zones.average(),
            kept.count { it.second >= 0.05 }.toDouble() / kept.size,
            zones,
            rows.count { it.second >= 0.05 }.toDouble() / rows.size,
            rows.sumOf { it.first.average() } / rows.size,
        )
    }

    private fun stableSeamBottom(
        a: LumaImage,
        b: LumaImage,
        shift: Int,
        top: Int,
        bottom: Int,
    ): Int? {
        val xStep = max(1, a.width / 180)
        var stableRows = 0
        for (aY in a.height - bottom - 1 downTo top + shift) {
            var difference = 0L
            var count = 0
            var x = 0
            while (x < a.width) {
                difference += abs(a[x, aY] - b[x, aY - shift])
                count += 1
                x += xStep
            }
            if (difference.toDouble() / count <= 12.0) {
                stableRows += 1
                if (stableRows >= 6) return aY + stableRows
            } else {
                stableRows = 0
            }
        }
        return null
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
        val manualPlan: ManualStitchPlan? = null,
    )

    fun stitch(
        sources: List<File>,
        target: File,
        topInset: Int = 0,
        bottomInset: Int = 0,
        region: VerticalRegion? = null,
    ): Result {
        require(sources.isNotEmpty()) { "沒有來源圖片" }
        if (sources.size == 1) {
            return Result(sources.single(), emptyList(), "單張圖片不需拼接")
        }

        val seams = ArrayList<StitchProposal>(sources.size - 1)
        var previous = decodeLuma(sources.first())
        val width = previous.width
        val height = previous.height
        require(region == null || region.bottom <= height) { "指定區域超出圖片" }
        for (source in sources.drop(1)) {
            val next = decodeLuma(source)
            require(next.width == width && next.height == height) { "圖片尺寸不一致" }
            seams += StitchMatcher.find(previous, next, topInset, bottomInset, region)
            previous = next
        }

        val manualPlan = ManualStitcher.initialPlan(width, height, seams)
        val lowConfidenceCount = seams.count { !it.confident }
        if (lowConfidenceCount > 0) {
            return Result(
                output = null,
                seams = seams,
                message = "有 $lowConfidenceCount 個接縫需要確認",
                manualPlan = manualPlan,
            )
        }

        ManualStitcher.stitch(sources, target, manualPlan)
        val message = if (region == null) {
            "已自動拼接 ${seams.size} 個接縫"
        } else {
            "已依指定區域拼接 ${seams.size} 個接縫"
        }
        return Result(target, seams, message, manualPlan)
    }

    private fun decodeLuma(file: File): LumaImage {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "無法讀取來源圖片" }
        return try {
            bitmap.toLuma()
        } finally {
            bitmap.recycle()
        }
    }

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

}
