package app.longscreenshot.capture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val GUIDE_UPDATE_INTERVAL_MS = 40L

// Called only on the capture thread; leave frames in ImageReader until the next allowed update.
internal class GuideFrameScheduler(
    private val now: () -> Long,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val consume: () -> Unit,
) {
    private var enabled = false
    private var lastRun: Long? = null
    private var pending = false
    private val update = Runnable {
        pending = false
        lastRun = now()
        consume()
    }

    fun onFrame() {
        if (!enabled || pending) return
        val delay = lastRun?.let { (GUIDE_UPDATE_INTERVAL_MS - (now() - it)).coerceAtLeast(0) } ?: 0L
        if (delay == 0L) update.run() else {
            pending = true
            post(update, delay)
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            // The latest frame may have arrived before tracking became ready.
            onFrame()
        } else {
            remove(update)
            pending = false
            lastRun = null
        }
    }
}

internal data class CaptureGuideGeometry(val screenTop: Int, val height: Int)

internal fun captureGuideGeometry(
    region: VerticalRegion,
    imageWidth: Int,
    imageHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    topInset: Int,
    bottomInset: Int,
): CaptureGuideGeometry? {
    if (imageWidth <= 0 || imageHeight <= 0 || imageWidth != screenWidth || region.bottom > imageHeight) return null
    val offset = when {
        imageHeight == screenHeight -> 0
        topInset >= 0 && bottomInset >= 0 &&
            imageHeight.toLong() + topInset + bottomInset == screenHeight.toLong() -> topInset
        // shortcut: arbitrary window origins are unknown; add calibration before supporting floating windows.
        else -> return null
    }
    return CaptureGuideGeometry(offset + region.top, region.height)
}

// Keep source rows for pixel-accurate vertical motion; average only across columns.
internal inline fun sampleGuideLuma(width: Int, region: VerticalRegion, lumaAt: (Int, Int) -> Int): LumaImage {
    require(width > 0)
    val sampledWidth = minOf(48, width)
    val sampledHeight = region.height
    val columns = IntArray(sampledWidth * 4) { ((it * 2L + 1) * width / (sampledWidth * 8)).toInt() }
    val pixels = ByteArray(sampledWidth * sampledHeight)
    for (y in 0 until sampledHeight) {
        for (x in 0 until sampledWidth) {
            var sum = 0
            for (dx in 0 until 4) {
                sum += lumaAt(columns[x * 4 + dx], region.top + y)
            }
            pixels[y * sampledWidth + x] = (sum / 4).toByte()
        }
    }
    return LumaImage(sampledWidth, sampledHeight, pixels)
}

internal class CaptureGuideTracker(private val reference: LumaImage) {
    private var previous = reference
    private var previousShift = 0
    private var lost = false

    fun locate(current: LumaImage): Float? {
        if (reference.width != current.width || reference.height != current.height || reference.height < 16) return null
        val tolerance = 2 * maxOf(1, reference.height / 160)
        var shift = StitchMatcher.track(reference, current)
        val movement = recentMovement(current, shift?.minus(previousShift))
        if (shift == null && movement != null) {
            shift = StitchMatcher.track(reference, current, previousShift + movement)
        }
        if (shift == null) {
            lost = true
            return null
        }
        if (movement != null && abs(shift - previousShift - movement) > tolerance) {
            lost = true
            return null
        }
        if (movement == null && (lost || shift < previousShift - tolerance)) {
            // Large reverse moves and recovery still need confirmation beyond the recent-motion range.
            val delta = shift - previousShift
            val confirmedMovement = if (delta < 0) StitchMatcher.track(current, previous)
                else StitchMatcher.track(previous, current)
            if (confirmedMovement == null || abs(confirmedMovement - abs(delta)) > tolerance) {
                lost = true
                return null
            }
        }
        lost = false
        previous = current
        previousShift = shift
        return 1f - shift.toFloat() / reference.height
    }

    private fun recentMovement(current: LumaImage, expectedShift: Int?): Int? {
        if (expectedShift != null && StitchMatcher.confirmsRecentMovement(previous, current, expectedShift)) {
            return expectedShift
        }
        // Require a large shared area so a short repeated block cannot invent motion.
        val maximum = reference.height / 2
        val forward = StitchMatcher.track(previous, current)?.takeIf { it <= maximum }
        val backward = StitchMatcher.track(current, previous)?.takeIf { it <= maximum }
        return when {
            forward == 0 && backward == 0 -> 0
            forward != null && backward == null -> forward
            backward != null && forward == null -> -backward
            else -> null
        }
    }
}

internal class CaptureGuideView(context: Context) : View(context) {
    private var displayedBottom: Float? = null
    private var motion: ValueAnimator? = null
    var previousBottom: Float? = null
        set(value) {
            if (field == value) return
            field = value
            motion?.cancel()
            val start = displayedBottom
            if (value == null || start == null) {
                displayedBottom = value
                invalidate()
            } else {
                motion = ValueAnimator.ofFloat(start, value).apply {
                    duration = GUIDE_UPDATE_INTERVAL_MS
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        displayedBottom = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val dash = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            motion?.cancel()
            displayedBottom = previousBottom
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(80, 170, 255)
        paint.pathEffect = null
        canvas.drawLine(0f, density, width.toFloat(), density, paint)
        canvas.drawLine(0f, height - density, width.toFloat(), height - density, paint)
        if (height <= 2 * density) return
        val bottom = displayedBottom
        if (bottom == null) {
            canvas.drawText("無法確認重疊，請往回捲一些", 8 * density, paint.textSize + 6 * density, paint)
            return
        }
        val y = (bottom * height).coerceIn(density, height - density)
        paint.color = if (bottom < 0.15f) Color.rgb(255, 160, 70) else Color.rgb(255, 200, 70)
        paint.pathEffect = dash
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
        paint.pathEffect = null
        val percent = (bottom * 100).roundToInt()
        val label = "重疊約 $percent%" + if (bottom < 0.15f) "・建議現在截圖" else "・上一張底部"
        canvas.drawText(label, 8 * density, maxOf(paint.textSize + density, y - 5 * density), paint)
    }
}
