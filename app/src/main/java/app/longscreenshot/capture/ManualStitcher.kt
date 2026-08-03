package app.longscreenshot.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class ManualCrop(val top: Int, val bottom: Int)

data class ManualSeam(
    val shift: Int,
    val confirmed: Boolean,
    val skipped: Boolean,
)

data class ManualStitchPlan(
    val width: Int,
    val height: Int,
    val defaultCrops: List<ManualCrop>,
    val crops: List<ManualCrop>,
    val defaultSeams: List<ManualSeam>,
    val seams: List<ManualSeam>,
) {
    init {
        require(width > 0 && height > 0)
        require(crops.size == seams.size + 1)
        require(defaultCrops.size == crops.size)
        require(defaultSeams.size == seams.size)
    }

    fun withShift(index: Int, delta: Int): ManualStitchPlan {
        require(index in seams.indices)
        val maxShift = (height - 1).coerceAtLeast(1)
        val next = seams.toMutableList()
        next[index] = next[index].copy(
            shift = (next[index].shift.toLong() + delta)
                .coerceIn(1L, maxShift.toLong())
                .toInt(),
            confirmed = false,
        )
        return copy(seams = next)
    }

    fun withCrop(index: Int, crop: ManualCrop): ManualStitchPlan {
        require(index in crops.indices)
        require(crop.top in 0 until crop.bottom && crop.bottom <= height) {
            "裁切後至少保留 1 px"
        }
        val nextCrops = crops.toMutableList()
        nextCrops[index] = crop
        val nextSeams = seams.mapIndexed { seamIndex, seam ->
            if (seamIndex == index - 1 || seamIndex == index) {
                seam.copy(confirmed = false)
            } else {
                seam
            }
        }
        return copy(crops = nextCrops, seams = nextSeams)
    }

    fun confirm(index: Int): ManualStitchPlan {
        require(index in seams.indices)
        if (seams[index].skipped) return this
        val error = ManualStitcher.validate(this).seamErrors[index]
        require(error == null) { error ?: "接縫幾何無效" }
        val next = seams.toMutableList()
        next[index] = next[index].copy(confirmed = true)
        return copy(seams = next)
    }

    fun resetImage(index: Int): ManualStitchPlan {
        require(index in crops.indices)
        val nextCrops = crops.toMutableList()
        nextCrops[index] = defaultCrops[index]
        val nextSeams = seams.toMutableList()
        listOf(index - 1, index).filter { it in nextSeams.indices }.forEach { seamIndex ->
            val default = defaultSeams[seamIndex]
            val cropsAreDefault =
                nextCrops[seamIndex] == defaultCrops[seamIndex] &&
                    nextCrops[seamIndex + 1] == defaultCrops[seamIndex + 1]
            nextSeams[seamIndex] = default.copy(
                confirmed = default.confirmed && cropsAreDefault,
            )
        }
        return copy(crops = nextCrops, seams = nextSeams)
    }

    fun keepDuplicate(index: Int): ManualStitchPlan {
        require(index in seams.indices && seams[index].skipped)
        val next = seams.toMutableList()
        next[index] = next[index].copy(
            shift = (height / 2).coerceIn(1, (height - 1).coerceAtLeast(1)),
            confirmed = false,
            skipped = false,
        )
        if (index < next.lastIndex) {
            next[index + 1] = next[index + 1].copy(confirmed = false)
        }
        return copy(seams = next)
    }

    fun isReady(): Boolean =
        ManualStitcher.validate(this).isValid && seams.all { it.skipped || it.confirmed }
}

internal data class ManualValidation(
    val seamErrors: List<String?>,
) {
    val isValid: Boolean get() = seamErrors.all { it == null }
    val message: String?
        get() = seamErrors.firstOrNull { it != null }?.let { "幾何無效：$it" }
}

internal data class ManualSlice(
    val imageIndex: Int,
    val sourceTop: Int,
    val sourceBottom: Int,
    val outputTop: Int,
)

internal data class ManualLayout(
    val width: Int,
    val height: Int,
    val slices: List<ManualSlice>,
)

internal object ManualStitcher {
    fun initialPlan(width: Int, height: Int, proposals: List<StitchProposal>): ManualStitchPlan {
        require(width > 0 && height > 0)
        require(proposals.isNotEmpty())
        val contentBottom = height - (proposals.maxOfOrNull { it.bottomCrop } ?: 0)
        require(contentBottom in 1..height) { "內容高度無效" }
        val defaultCrops = List(proposals.size + 1) { index ->
            ManualCrop(0, if (index == proposals.size) height else contentBottom)
        }
        val defaultSeams = proposals.map { proposal ->
            ManualSeam(
                shift = proposal.shift,
                confirmed = proposal.confident,
                skipped = proposal.reason == StitchReason.Duplicate,
            )
        }
        return ManualStitchPlan(
            width = width,
            height = height,
            defaultCrops = defaultCrops,
            crops = defaultCrops,
            defaultSeams = defaultSeams,
            seams = defaultSeams,
        )
    }

    fun validate(plan: ManualStitchPlan): ManualValidation {
        val errors = MutableList<String?>(plan.seams.size) { null }
        if (plan.crops.size != plan.seams.size + 1) {
            return ManualValidation(List(plan.seams.size) { "圖片與接縫數量不一致" })
        }
        plan.crops.forEachIndexed { index, crop ->
            if (crop.top !in 0 until crop.bottom || crop.bottom > plan.height) {
                listOf(index - 1, index).filter { it in errors.indices }.forEach {
                    errors[it] = "第 ${it + 1} 個接縫的裁切無效"
                }
            }
        }
        if (errors.any { it != null }) return ManualValidation(errors)

        try {
            var origin = 0L
            var outputEnd = 0L
            for (index in plan.crops.indices) {
                val crop = plan.crops[index]
                val start = safeAdd(origin, crop.top.toLong())
                val end = safeAdd(origin, crop.bottom.toLong())
                if (index == 0) {
                    outputEnd = end
                } else {
                    val seam = plan.seams[index - 1]
                    if (!seam.skipped) {
                        errors[index - 1] = when {
                            seam.shift !in 1 until plan.height -> "位移必須保留正向順序"
                            start > outputEnd -> "接縫之間沒有重疊"
                            end <= outputEnd -> "接縫沒有新增內容"
                            else -> null
                        }
                        if (errors[index - 1] == null) outputEnd = end
                    }
                }
                if (index < plan.seams.size) {
                    val shift = plan.seams[index].shift
                    if (!plan.seams[index].skipped && shift !in 1 until plan.height) {
                        errors[index] = "位移必須保留正向順序"
                    }
                    origin = safeAdd(origin, shift.toLong())
                }
            }
        } catch (_: ArithmeticException) {
            return ManualValidation(List(plan.seams.size) { "成品高度溢位" })
        }
        return ManualValidation(errors)
    }

    fun overlap(plan: ManualStitchPlan, index: Int): Int {
        require(index in plan.seams.indices)
        var previousOrigin = 0L
        repeat(index) { previousOrigin = safeAdd(previousOrigin, plan.seams[it].shift.toLong()) }
        val nextOrigin = safeAdd(previousOrigin, plan.seams[index].shift.toLong())
        val previous = plan.crops[index]
        val next = plan.crops[index + 1]
        val start = max(
            safeAdd(previousOrigin, previous.top.toLong()),
            safeAdd(nextOrigin, next.top.toLong()),
        )
        val end = min(
            safeAdd(previousOrigin, previous.bottom.toLong()),
            safeAdd(nextOrigin, next.bottom.toLong()),
        )
        return (end - start).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun layout(plan: ManualStitchPlan): ManualLayout {
        val validation = validate(plan)
        require(validation.isValid) { validation.message ?: "手動拼接幾何無效" }

        val slices = ArrayList<ManualSlice>(plan.crops.size)
        var origin = 0L
        var outputStart = 0L
        var outputEnd = 0L
        for (index in plan.crops.indices) {
            val crop = plan.crops[index]
            val start = safeAdd(origin, crop.top.toLong())
            val end = safeAdd(origin, crop.bottom.toLong())
            if (index == 0) {
                outputStart = start
                outputEnd = end
                slices += ManualSlice(index, crop.top, crop.bottom, 0)
            } else if (!plan.seams[index - 1].skipped) {
                val outputOffset = outputEnd - outputStart
                require(outputOffset in 0..Int.MAX_VALUE.toLong()) { "成品高度無效" }
                val sourceTop = max(crop.top.toLong(), outputEnd - origin).toInt()
                slices += ManualSlice(
                    imageIndex = index,
                    sourceTop = sourceTop,
                    sourceBottom = crop.bottom,
                    outputTop = outputOffset.toInt(),
                )
                outputEnd = end
            }
            if (index < plan.seams.size) {
                origin = safeAdd(origin, plan.seams[index].shift.toLong())
            }
        }
        val outputHeight = outputEnd - outputStart
        require(outputHeight in 1..Int.MAX_VALUE.toLong()) { "成品高度無效" }
        return ManualLayout(plan.width, outputHeight.toInt(), slices)
    }

    fun stitch(sources: List<File>, target: File, plan: ManualStitchPlan): File {
        require(sources.size == plan.crops.size) { "來源圖片與拼接計畫數量不一致" }
        require(plan.isReady()) { "仍有未確認接縫或無效幾何" }
        val layout = layout(plan)
        val required = try {
            val rows = Math.addExact(layout.height.toLong(), plan.height.toLong())
            Math.multiplyExact(Math.multiplyExact(plan.width.toLong(), rows), 4L)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("記憶體需求溢位")
        }
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        require(required <= available) { "可用記憶體不足，無法安全產生成品" }

        val temporary = File(target.parentFile, "${target.name}.tmp")
        val output = Bitmap.createBitmap(plan.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            layout.slices.forEach { slice ->
                val bitmap = checkNotNull(BitmapFactory.decodeFile(sources[slice.imageIndex].path)) {
                    "無法讀取來源圖片"
                }
                try {
                    require(bitmap.width == plan.width && bitmap.height == plan.height) {
                        "圖片尺寸不一致"
                    }
                    canvas.drawBitmap(
                        bitmap,
                        Rect(0, slice.sourceTop, plan.width, slice.sourceBottom),
                        Rect(
                            0,
                            slice.outputTop,
                            plan.width,
                            slice.outputTop + slice.sourceBottom - slice.sourceTop,
                        ),
                        null,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
            temporary.delete()
            temporary.outputStream().use {
                check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) { "拼接圖片寫入失敗" }
            }
            if (target.exists()) check(target.delete()) { "無法更新拼接圖片" }
            check(temporary.renameTo(target)) { "無法完成拼接圖片" }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            output.recycle()
        }
    }

    private fun safeAdd(left: Long, right: Long): Long = Math.addExact(left, right)
}
