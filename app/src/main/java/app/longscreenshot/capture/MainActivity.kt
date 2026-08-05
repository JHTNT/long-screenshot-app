package app.longscreenshot.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val Paper = Color(0xFF111310)
private val Ink = Color(0xFFF2F0E9)
private val Quiet = Color(0xFFAAA9A2)

private val AccentPresets = listOf(
    Color(0xFFFF7353),
    Color(0xFFFFA94D),
    Color(0xFFFFD43B),
    Color(0xFFA9E34B),
    Color(0xFF62D6A7),
    Color(0xFF3BC9DB),
    Color(0xFF6DB7FF),
    Color(0xFF748FFC),
    Color(0xFFB197FC),
    Color(0xFFF783AC),
)

private enum class PermissionStep { Overlay, Notification }

private enum class ManualPreviewMode { Overlap, Composite }

class MainActivity : ComponentActivity() {
    private var permissionStep by mutableStateOf<PermissionStep?>(null)
    private var notificationAsked = false
    private var homeMessage by mutableStateOf<String?>(null)
    private var showCancelDialog by mutableStateOf(false)
    private var outputBusy by mutableStateOf(false)
    private var outputMessage by mutableStateOf<String?>(null)
    private var selectedMode by mutableStateOf(CaptureMode.General)
    private var accent by mutableStateOf(AccentPresets.first())

    private lateinit var overlayLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!CaptureSession.cleanOrphans(this)) {
            homeMessage = "部分舊暫存無法清除，請稍後再試。"
        }
        if (!CaptureOutput.cleanExpiredShares(this)) {
            homeMessage = "部分過期剪貼簿圖片無法清除，請稍後再試。"
        }

        overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            continuePermissionFlow()
        }
        notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            continuePermissionFlow()
        }
        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            permissionStep = null
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                CaptureSession.status = CaptureStatus.Idle
                homeMessage = "已取消螢幕擷取。"
                return@registerForActivityResult
            }
            CaptureSession.status = CaptureStatus.Starting
            ContextCompat.startForegroundService(
                this,
                CaptureService.startIntent(this, result.resultCode, data, selectedMode),
            )
        }

        handleIntent(intent)
        setContent {
            LongScreenshotTheme(accent) {
                App(
                    status = CaptureSession.status,
                    permissionStep = permissionStep,
                    homeMessage = homeMessage,
                    mode = selectedMode,
                    onModeChange = { selectedMode = it },
                    accent = accent,
                    onAccentChange = { accent = it },
                    onStart = {
                        homeMessage = null
                        outputMessage = null
                        notificationAsked = false
                        continuePermissionFlow()
                    },
                    onPermission = ::requestCurrentPermission,
                    onFinish = { startService(CaptureService.actionIntent(this, CaptureService.ACTION_FINISH)) },
                    onDelete = { startService(CaptureService.deleteIntent(this, it)) },
                    onRegionConfirm = ::stitchSelectedRegion,
                    onManual = ::openManualEditor,
                    onManualApply = ::applyManualPlan,
                    onCancel = { showCancelDialog = true },
                    outputBusy = outputBusy,
                    outputMessage = outputMessage,
                    onSave = { format, crop -> runOutput("已存到相簿", format) {
                        CaptureOutput.saveToGallery(this, currentOutput(), format, crop)
                    } },
                    onCopy = { format, crop -> runOutput("已複製到剪貼簿", format, destroySource = false) {
                        CaptureOutput.copyToClipboard(this, currentOutput(), format, crop)
                    } },
                )
                if (showCancelDialog) {
                    AlertDialog(
                        onDismissRequest = { showCancelDialog = false },
                        title = { Text("銷毀這次截圖？") },
                        text = { Text("這會刪除本次已擷取的所有圖片，無法復原。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showCancelDialog = false
                                when (CaptureSession.status) {
                                    CaptureStatus.Starting, is CaptureStatus.Capturing ->
                                        startService(CaptureService.actionIntent(this, CaptureService.ACTION_CANCEL))
                                    else -> {
                                        if (CaptureSession.destroy()) {
                                            Toast.makeText(this, "已銷毀這次截圖", Toast.LENGTH_SHORT).show()
                                        } else {
                                            outputMessage = "暫存刪除失敗，尚未宣稱銷毀。"
                                        }
                                    }
                                }
                            }) { Text("銷毀", color = MaterialTheme.colorScheme.primary) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCancelDialog = false }) { Text("繼續拼接") }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == CaptureService.ACTION_CONFIRM_CANCEL) showCancelDialog = true
    }

    private fun continuePermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            permissionStep = PermissionStep.Overlay
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationAsked
        ) {
            permissionStep = PermissionStep.Notification
            return
        }
        permissionStep = null
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestCurrentPermission() {
        when (permissionStep) {
            PermissionStep.Overlay -> overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            PermissionStep.Notification -> {
                notificationAsked = true
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    continuePermissionFlow()
                }
            }
            null -> Unit
        }
    }

    private fun stitchSelectedRegion(range: ClosedFloatingPointRange<Float>) {
        val selecting = CaptureSession.status as? CaptureStatus.SelectingRegion ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(CaptureSession.sourceFile(1).path, bounds)
        if (bounds.outHeight <= 0) {
            CaptureSession.status = CaptureStatus.Failed("無法讀取區域選取圖片。", selecting.count)
            return
        }
        val top = (bounds.outHeight * range.start).roundToInt()
        val bottom = (bounds.outHeight * range.endInclusive).roundToInt()
        val region = VerticalRegion(top, bottom)
        CaptureSession.status = CaptureStatus.Stitching(selecting.count)
        Thread({
            val result = runCatching {
                AutoStitcher.stitch(
                    sources = (1..selecting.count).map(CaptureSession::sourceFile),
                    target = CaptureSession.resultFile(),
                    region = region,
                )
            }
                runOnUiThread {
                CaptureSession.status = result.fold(
                    onSuccess = {
                        CaptureStatus.Finished(
                            selecting.count,
                            it.output,
                            it.message,
                            it.manualPlan,
                        )
                    },
                    onFailure = {
                        CaptureStatus.Finished(
                            selecting.count,
                            null,
                            it.message ?: "指定區域拼接失敗，來源圖片已保留",
                        )
                    },
                )
            }
        }, "region-stitch").start()
    }

    private fun openManualEditor() {
        val finished = CaptureSession.status as? CaptureStatus.Finished ?: return
        val plan = finished.manualPlan ?: return
        CaptureSession.status = CaptureStatus.Manual(
            finished.count,
            plan,
            finished.message.takeIf { finished.output == null },
        )
    }

    private fun applyManualPlan(plan: ManualStitchPlan) {
        val editing = CaptureSession.status as? CaptureStatus.Manual ?: return
        if (outputBusy || !plan.isReady()) return
        outputBusy = true
        Thread({
            val result = runCatching {
                ManualStitcher.stitch(
                    sources = (1..editing.count).map(CaptureSession::sourceFile),
                    target = CaptureSession.resultFile(),
                    plan = plan,
                )
            }
            runOnUiThread {
                outputBusy = false
                CaptureSession.status = result.fold(
                    onSuccess = {
                        CaptureStatus.Finished(
                            editing.count,
                            it,
                            "已套用手動調整",
                            plan,
                        )
                    },
                    onFailure = {
                        CaptureStatus.Manual(
                            editing.count,
                            plan,
                            it.message ?: "手動拼接失敗，來源圖片已保留",
                        )
                    },
                )
            }
        }, "manual-stitch").start()
    }

    private fun runOutput(
        successMessage: String,
        format: OutputFormat,
        destroySource: Boolean = true,
        operation: (OutputFormat) -> Unit,
    ) {
        if (outputBusy) return
        outputBusy = true
        outputMessage = null
        Thread({
            val error = runCatching { operation(format) }.exceptionOrNull()
            runOnUiThread {
                outputBusy = false
                if (error != null) {
                    outputMessage = "輸出失敗，來源圖片仍保留，請重試。"
                } else if (!destroySource) {
                    outputMessage = successMessage
                } else if (CaptureSession.destroy()) {
                    homeMessage = successMessage
                } else {
                    outputMessage = "$successMessage，但暫存清除失敗。"
                }
            }
        }, "capture-output").start()
    }

    private fun currentOutput() =
        checkNotNull((CaptureSession.status as? CaptureStatus.Finished)?.output) { "尚無可輸出的成品" }
}

@Composable
private fun App(
    status: CaptureStatus,
    permissionStep: PermissionStep?,
    homeMessage: String?,
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    accent: Color,
    onAccentChange: (Color) -> Unit,
    onStart: () -> Unit,
    onPermission: () -> Unit,
    onFinish: () -> Unit,
    onDelete: (Int) -> Unit,
    onRegionConfirm: (ClosedFloatingPointRange<Float>) -> Unit,
    onManual: () -> Unit,
    onManualApply: (ManualStitchPlan) -> Unit,
    onCancel: () -> Unit,
    outputBusy: Boolean,
    outputMessage: String?,
    onSave: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
    onCopy: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
) {
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        when (val target = permissionStep ?: status) {
            is PermissionStep -> PermissionScreen(target, onPermission)
            CaptureStatus.Idle -> HomeScreen(
                homeMessage,
                mode,
                onModeChange,
                accent,
                onAccentChange,
                onStart,
            )
            CaptureStatus.Starting -> CenterStatus("正在準備", "即將顯示懸浮截圖按鈕。")
            is CaptureStatus.Capturing -> CapturingScreen(target, onFinish, onCancel, onDelete)
            is CaptureStatus.SelectingRegion -> RegionSelectionScreen(
                source = CaptureSession.sourceFile(1),
                onConfirm = onRegionConfirm,
                onCancel = onCancel,
            )
            is CaptureStatus.Stitching -> CenterStatus(
                "正在自動拼接",
                "正在比對 ${target.count - 1} 個接縫，來源圖片會完整保留。",
            )
            is CaptureStatus.Manual -> ManualStitchScreen(
                sources = (1..target.count).map(CaptureSession::sourceFile),
                initialPlan = target.plan,
                busy = outputBusy,
                message = target.message,
                onApply = onManualApply,
                onCancel = onCancel,
            )
            is CaptureStatus.Finished -> PreviewScreen(
                sources = target.output?.let(::listOf)
                    ?: (1..target.count).map(CaptureSession::sourceFile),
                canOutput = target.output != null,
                busy = outputBusy,
                message = outputMessage ?: target.message,
                manualPlan = target.manualPlan,
                onManual = onManual,
                onSave = onSave,
                onCopy = onCopy,
                onDestroy = onCancel,
            )
            is CaptureStatus.Failed -> FailureScreen(target, onCancel)
            else -> Unit
        }
    }
}

@Composable
private fun HomeScreen(
    message: String?,
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    accent: Color,
    onAccentChange: (Color) -> Unit,
    onStart: () -> Unit,
) {
    var showAccentDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader(onThemeClick = { showAccentDialog = true })
        Spacer(Modifier.height(48.dp))
        Column {
            Text("擷取可捲動畫面", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "切換到目標 App，捲動畫面並點擊懸浮按鈕。",
                style = MaterialTheme.typography.bodyLarge,
                color = Quiet,
            )
            Spacer(Modifier.height(24.dp))
            Text("拼接模式", style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatButton(
                    label = "一般",
                    detail = "自動判斷",
                    selected = mode == CaptureMode.General,
                    onClick = { onModeChange(CaptureMode.General) },
                    modifier = Modifier.weight(1f),
                )
                FormatButton(
                    label = "指定區域",
                    detail = "框選內容",
                    selected = mode == CaptureMode.ContentRegion,
                    onClick = { onModeChange(CaptureMode.ContentRegion) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (mode == CaptureMode.ContentRegion) {
                Spacer(Modifier.height(10.dp))
                Text("完成擷取後，選擇拼接時要用於判斷的範圍。", color = Quiet)
            }
            if (message != null) {
                Spacer(Modifier.height(14.dp))
                Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Paper,
            ),
        ) {
            Text("開始擷取", fontWeight = FontWeight.SemiBold)
        }
    }
    if (showAccentDialog) {
        AccentDialog(
            accent = accent,
            onAccentChange = onAccentChange,
            onDismiss = { showAccentDialog = false },
        )
    }
}

@Composable
private fun AccentDialog(
    accent: Color,
    onAccentChange: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(accent.toArgb(), it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主題顏色") },
        text = {
            Column {
                AccentPresets.chunked(5).forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        row.forEachIndexed { columnIndex, color ->
                            Surface(
                                modifier = Modifier
                                    .size(48.dp)
                                    .selectable(
                                        selected = color == accent,
                                        onClick = { onAccentChange(color) },
                                        role = Role.RadioButton,
                                    )
                                    .semantics {
                                        contentDescription =
                                            "預設顏色 ${rowIndex * 5 + columnIndex + 1}"
                                    },
                                shape = CircleShape,
                                color = color,
                                border = BorderStroke(
                                    if (color == accent) 3.dp else 1.dp,
                                    if (color == accent) Ink else color.copy(alpha = 0.45f),
                                ),
                            ) {}
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("自訂顏色", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                ColorPlane(hsv, onAccentChange)
                Spacer(Modifier.height(12.dp))
                HueBar(hsv, onAccentChange)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun ColorPlane(hsv: FloatArray, onColorChange: (Color) -> Unit) {
    val saturation = (hsv[1] / 0.50f).coerceIn(0f, 1f)
    val darkness = ((1f - hsv[2]) / 0.10f).coerceIn(0f, 1f)
    val update: (Offset, Size) -> Unit = { point, bounds ->
        onColorChange(pickerColor(hsv[0], point.x / bounds.width, point.y / bounds.height))
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .colorPointer(update)
            .semantics { contentDescription = "自訂顏色區域" },
    ) {
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.White, Color.hsv(hsv[0], 0.50f, 1f)),
            ),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f)),
            ),
        )
        val marker = Offset(size.width * saturation, size.height * darkness)
        drawCircle(Ink, 9.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun HueBar(hsv: FloatArray, onColorChange: (Color) -> Unit) {
    val update: (Offset, Size) -> Unit = { point, bounds ->
        onColorChange(
            pickerColor(
                point.x / bounds.width * 360f,
                hsv[1] / 0.50f,
                (1f - hsv[2]) / 0.10f,
            ),
        )
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .colorPointer(update)
            .semantics { contentDescription = "色相選擇" },
    ) {
        drawRect(
            Brush.horizontalGradient(
                (0..6).map { Color.hsv(it * 60f, 1f, 1f) },
            ),
        )
        val markerX = size.width * hsv[0] / 360f
        drawLine(Ink, Offset(markerX, 0f), Offset(markerX, size.height), 4.dp.toPx())
        drawLine(Color.White, Offset(markerX, 0f), Offset(markerX, size.height), 2.dp.toPx())
    }
}

private fun Modifier.colorPointer(onPosition: (Offset, Size) -> Unit) = composed {
    val currentOnPosition by rememberUpdatedState(onPosition)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val bounds = Size(size.width.toFloat(), size.height.toFloat())
            currentOnPosition(down.position, bounds)
            do {
                val change = awaitPointerEvent().changes.first()
                currentOnPosition(change.position, bounds)
                change.consume()
            } while (change.pressed)
        }
    }
}

internal fun pickerColor(hue: Float, xFraction: Float, yFraction: Float): Color =
    Color.hsv(
        ((hue % 360f) + 360f) % 360f,
        xFraction.coerceIn(0f, 1f) * 0.50f,
        1f - yFraction.coerceIn(0f, 1f) * 0.10f,
    )

@Composable
private fun PermissionScreen(step: PermissionStep, onContinue: () -> Unit) {
    val (number, title, body, button) = when (step) {
        PermissionStep.Overlay -> listOf(
            "設定 1 / 2", "允許懸浮按鈕", "讓截圖按鈕顯示在其他 App 上方。", "前往設定",
        )
        PermissionStep.Notification -> listOf(
            "設定 2 / 2", "允許通知", "在通知中提供完成與取消操作；拒絕也能繼續。", "繼續",
        )
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader(number)
        Spacer(Modifier.height(48.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Quiet)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
        ) { Text(button, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun CapturingScreen(
    status: CaptureStatus.Capturing,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (Int) -> Unit,
) {
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader("擷取中")
        Spacer(Modifier.height(28.dp))
        Column {
            Text("已擷取 ${status.count} 張", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                status.message ?: "切回要截圖的 App 繼續擷取。",
                style = MaterialTheme.typography.bodyLarge,
                color = Quiet,
            )
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                (1..status.count).map(CaptureSession::sourceFile).chunked(2),
                key = { _, row -> row.first().path },
            ) { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { columnIndex, source ->
                        CaptureThumbnail(
                            source = source,
                            index = rowIndex * 2 + columnIndex,
                            count = status.count,
                            onDelete = { deleteIndex = rowIndex * 2 + columnIndex + 1 },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Column {
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) { Text("完成") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
            ) { Text("取消", color = MaterialTheme.colorScheme.primary) }
        }
    }
    deleteIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("刪除第 $index 張截圖？") },
            text = { Text("刪除後無法復原，其餘截圖會保留。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteIndex = null
                    onDelete(index)
                }) { Text("刪除", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { deleteIndex = null }) { Text("保留") }
            },
        )
    }
}

@Composable
private fun CaptureThumbnail(
    source: java.io.File,
    index: Int,
    count: Int,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded by produceState<LoadedPreview?>(null, source.path, source.lastModified()) {
        value = withContext(Dispatchers.IO) { loadPreview(source) }
    }

    Surface(
        modifier = modifier.combinedClickable(onClick = {}, onLongClick = onDelete),
        color = Color(0xFF20231E),
        shape = RoundedCornerShape(8.dp),
    ) {
        when (val preview = loaded) {
            null -> Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            else -> Image(
                bitmap = preview.bitmap.asImageBitmap(),
                contentDescription = "第 ${index + 1} 張，共 $count 張截圖",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun RegionSelectionScreen(
    source: java.io.File,
    onConfirm: (ClosedFloatingPointRange<Float>) -> Unit,
    onCancel: () -> Unit,
) {
    var range by remember(source.path) { mutableStateOf(0.12f..0.78f) }
    val loaded by produceState<LoadedPreview?>(null, source.path) {
        value = withContext(Dispatchers.IO) { loadPreview(source) }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AppHeader("指定區域")
        Spacer(Modifier.height(8.dp))
        Text("完整保留第一張與最後一張，中間依選取範圍拼接。", color = Quiet)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = Color(0xFF20231E),
            shape = RoundedCornerShape(8.dp),
        ) {
            when (val preview = loaded) {
                null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                else -> Box {
                    Image(
                        bitmap = preview.bitmap.asImageBitmap(),
                        contentDescription = "選擇要拼接的內容區域",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    CropOverlay(
                        modifier = Modifier.matchParentSize(),
                        imageWidth = preview.bitmap.width,
                        imageHeight = preview.bitmap.height,
                        range = range,
                        minimumRange = 0.25f,
                        fitImage = true,
                        onRangeChange = { range = it },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "拖曳上下邊界線調整",
            color = Quiet,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
            ) { Text("不保存", color = MaterialTheme.colorScheme.primary) }
            Button(
                onClick = { onConfirm(range) },
                enabled = loaded != null,
                modifier = Modifier.weight(2f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) { Text("開始拼接") }
        }
    }
}

@Composable
internal fun CropOverlay(
    modifier: Modifier,
    imageWidth: Int,
    imageHeight: Int,
    range: ClosedFloatingPointRange<Float>,
    minimumRange: Float,
    fitImage: Boolean,
    enabled: Boolean = true,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val currentRange by rememberUpdatedState(range)
    val currentOnRangeChange by rememberUpdatedState(onRangeChange)
    val accentColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .semantics {
                contentDescription = "拖曳上下邊界線調整裁切範圍"
                stateDescription =
                    "目前保留 ${(range.start * 100).roundToInt()}% 至 ${(range.endInclusive * 100).roundToInt()}%"
                if (enabled) {
                    fun moveHandle(dragTop: Boolean, delta: Float): Boolean {
                        val current = if (dragTop) range.start else range.endInclusive
                        val updated = draggedCropRange(
                            range = range,
                            dragTop = dragTop,
                            fraction = current + delta,
                            minimumRange = minimumRange,
                        ) ?: return false
                        onRangeChange(updated)
                        return true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction("上邊界往上") { moveHandle(true, -0.01f) },
                        CustomAccessibilityAction("上邊界往下") { moveHandle(true, 0.01f) },
                        CustomAccessibilityAction("下邊界往上") { moveHandle(false, -0.01f) },
                        CustomAccessibilityAction("下邊界往下") { moveHandle(false, 0.01f) },
                    )
                } else {
                    customActions = emptyList()
                }
            }
            .pointerInput(imageWidth, imageHeight, fitImage, minimumRange, enabled) {
                if (enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val frame = cropImageFrame(
                            Size(size.width.toFloat(), size.height.toFloat()),
                            imageWidth,
                            imageHeight,
                            fitImage,
                        )
                        if (frame.height <= 0f) return@awaitEachGesture
                        if (down.position.x < frame.left || down.position.x > frame.right) {
                            return@awaitEachGesture
                        }

                        val topY = frame.top + frame.height * currentRange.start
                        val bottomY = frame.top + frame.height * currentRange.endInclusive
                        val hitSlop = with(density) { 28.dp.toPx() }
                        val topDistance = abs(down.position.y - topY)
                        val bottomDistance = abs(down.position.y - bottomY)
                        val dragTop = when {
                            topDistance <= hitSlop && topDistance <= bottomDistance -> true
                            bottomDistance <= hitSlop -> false
                            else -> null
                        } ?: return@awaitEachGesture

                        down.consume()
                        var draggedRange = currentRange
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val fraction =
                                ((change.position.y - frame.top) / frame.height).coerceIn(0f, 1f)
                            draggedCropRange(
                                range = draggedRange,
                                dragTop = dragTop,
                                fraction = fraction,
                                minimumRange = minimumRange,
                            )?.let {
                                draggedRange = it
                                currentOnRangeChange(it)
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val frame = cropImageFrame(size, imageWidth, imageHeight, fitImage)
        val top = frame.top + frame.height * range.start
        val bottom = frame.top + frame.height * range.endInclusive
        drawRect(
            Paper.copy(alpha = 0.72f),
            topLeft = Offset(frame.left, frame.top),
            size = Size(frame.width, (top - frame.top).coerceIn(0f, frame.height)),
        )
        drawRect(
            Paper.copy(alpha = 0.72f),
            topLeft = Offset(frame.left, bottom),
            size = Size(frame.width, (frame.bottom - bottom).coerceIn(0f, frame.height)),
        )
        drawLine(
            color = accentColor,
            start = Offset(frame.left, top),
            end = Offset(frame.right, top),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = accentColor,
            start = Offset(frame.left, bottom),
            end = Offset(frame.right, bottom),
            strokeWidth = 1.dp.toPx(),
        )
        fun drawDragIcon(y: Float) {
            val centerX = frame.left + frame.width / 2f
            val length = 7.dp.toPx()
            val head = 3.dp.toPx()
            val stroke = 1.5.dp.toPx()
            drawCircle(
                color = Paper.copy(alpha = 0.9f),
                radius = 10.dp.toPx(),
                center = Offset(centerX, y),
            )
            drawLine(
                color = accentColor,
                start = Offset(centerX, y - length),
                end = Offset(centerX, y + length),
                strokeWidth = stroke,
            )
            drawLine(
                color = accentColor,
                start = Offset(centerX, y - length),
                end = Offset(centerX - head, y - length + head),
                strokeWidth = stroke,
            )
            drawLine(
                color = accentColor,
                start = Offset(centerX, y - length),
                end = Offset(centerX + head, y - length + head),
                strokeWidth = stroke,
            )
            drawLine(
                color = accentColor,
                start = Offset(centerX, y + length),
                end = Offset(centerX - head, y + length - head),
                strokeWidth = stroke,
            )
            drawLine(
                color = accentColor,
                start = Offset(centerX, y + length),
                end = Offset(centerX + head, y + length - head),
                strokeWidth = stroke,
            )
        }
        drawDragIcon(top)
        drawDragIcon(bottom)
    }
}

private fun cropImageFrame(
    canvasSize: Size,
    imageWidth: Int,
    imageHeight: Int,
    fitImage: Boolean,
): androidx.compose.ui.geometry.Rect {
    if (imageWidth <= 0 || imageHeight <= 0) {
        return androidx.compose.ui.geometry.Rect(0f, 0f, 0f, 0f)
    }
    val scale = if (fitImage) {
        minOf(canvasSize.width / imageWidth, canvasSize.height / imageHeight)
    } else {
        canvasSize.width / imageWidth
    }
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (canvasSize.width - width) / 2f
    val top = (canvasSize.height - height) / 2f
    return androidx.compose.ui.geometry.Rect(left, top, left + width, top + height)
}

internal fun draggedCropRange(
    range: ClosedFloatingPointRange<Float>,
    dragTop: Boolean,
    fraction: Float,
    minimumRange: Float,
): ClosedFloatingPointRange<Float>? {
    val value = fraction.coerceIn(0f, 1f)
    return if (dragTop) {
        if (range.endInclusive - value < minimumRange) null else value..range.endInclusive
    } else {
        if (value - range.start < minimumRange) null else range.start..value
    }
}

@Composable
private fun ManualStitchScreen(
    sources: List<java.io.File>,
    initialPlan: ManualStitchPlan,
    busy: Boolean,
    message: String?,
    onApply: (ManualStitchPlan) -> Unit,
    onCancel: () -> Unit,
) {
    if (initialPlan.seams.isEmpty()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("沒有可調整的接縫", color = Ink)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onCancel) { Text("返回") }
        }
        return
    }

    var plan by remember(initialPlan) { mutableStateOf(initialPlan) }
    val firstUnconfirmed = remember(initialPlan) {
        initialPlan.seams.indexOfFirst { !it.confirmed && !it.skipped }
            .takeIf { it >= 0 } ?: 0
    }
    var seamIndex by remember(initialPlan) { mutableStateOf(firstUnconfirmed) }
    var selectedImage by remember(initialPlan) { mutableStateOf(firstUnconfirmed) }
    var previewMode by remember { mutableStateOf(ManualPreviewMode.Overlap) }
    val seam = plan.seams[seamIndex]
    val previousSource = sources[seamIndex]
    val nextSource = sources[seamIndex + 1]
    val previous by produceState<LoadedPreview?>(null, previousSource.path) {
        value = withContext(Dispatchers.IO) { loadPreview(previousSource) }
    }
    val next by produceState<LoadedPreview?>(null, nextSource.path) {
        value = withContext(Dispatchers.IO) { loadPreview(nextSource) }
    }
    val previousPreview = previous
    val nextPreview = next
    val validation = ManualStitcher.validate(plan)
    val seamError = validation.seamErrors[seamIndex]
    val drag by rememberUpdatedState<(Float) -> Unit> { delta ->
        val rows = delta.roundToInt()
        if (rows != 0 && !busy) plan = plan.withShift(seamIndex, rows)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            AppHeader("手動調整")
            Spacer(Modifier.height(4.dp))
            Text(
                "接縫 ${seamIndex + 1}／${plan.seams.size}",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
            Text(
                if (seam.skipped) "第 ${seamIndex + 2} 張與前一張幾乎相同，已略過"
                else if (seam.confirmed) "已確認"
                else "需要確認",
                color = if (seam.confirmed) MaterialTheme.colorScheme.primary else Quiet,
            )
        }
        if (message != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            if (seam.skipped) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF20231E),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("這張圖片目前不加入成品。", color = Ink)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { plan = plan.keepDuplicate(seamIndex) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                        ) { Text("保留這張") }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF20231E),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    when {
                        previous == null || next == null -> Box(
                            Modifier.fillMaxWidth().height(320.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                        else -> ManualSeamCanvas(
                            previous = checkNotNull(previousPreview),
                            next = checkNotNull(nextPreview),
                            plan = plan,
                            seamIndex = seamIndex,
                            mode = previewMode,
                            onDrag = drag,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactChoice(
                        label = "重疊顯示",
                        selected = previewMode == ManualPreviewMode.Overlap,
                        onClick = { previewMode = ManualPreviewMode.Overlap },
                        modifier = Modifier.weight(1f),
                    )
                    CompactChoice(
                        label = "合成顯示",
                        selected = previewMode == ManualPreviewMode.Composite,
                        onClick = { previewMode = ManualPreviewMode.Composite },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "綠線：前張　橘線：後張　白線：重疊範圍・可拖曳後張上下移動",
                    modifier = Modifier.fillMaxWidth(),
                    color = Quiet,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (!seam.skipped) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    color = if (seamError == null) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (seamError != null) {
                            Text(
                                seamError,
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        if (!seam.skipped) {
            item {
                Text(
                    "位移 ${seam.shift} px・重疊 ${ManualStitcher.overlap(plan, seamIndex)} px",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-10 to "上移 10", -1 to "上移 1", 1 to "下移 1", 10 to "下移 10")
                        .forEach { (delta, label) ->
                            OutlinedButton(
                                onClick = { plan = plan.withShift(seamIndex, delta) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentPadding = PaddingValues(horizontal = 1.dp),
                            ) { Text(label, fontSize = 11.sp) }
                        }
                }
            }
            item {
                val crop = plan.crops[selectedImage]
                val cropRange =
                    (crop.top.toFloat() / plan.height)..(crop.bottom.toFloat() / plan.height)
                Text("調整圖片裁切", style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactChoice(
                        label = "前張・第 ${seamIndex + 1} 張",
                        selected = selectedImage == seamIndex,
                        onClick = { selectedImage = seamIndex },
                        modifier = Modifier.weight(1f),
                    )
                    CompactChoice(
                        label = "後張・第 ${seamIndex + 2} 張",
                        selected = selectedImage == seamIndex + 1,
                        onClick = { selectedImage = seamIndex + 1 },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("保留 ${crop.top} ～ ${crop.bottom} px", color = Quiet)
                RangeSlider(
                    value = cropRange,
                    onValueChange = { range ->
                        if (!busy) {
                            val top = (range.start * plan.height).roundToInt()
                            val bottom = (range.endInclusive * plan.height).roundToInt()
                            if (bottom - top >= 1) {
                                plan = plan.withCrop(
                                    selectedImage,
                                    ManualCrop(
                                        top.coerceIn(0, plan.height - 1),
                                        bottom.coerceIn(1, plan.height),
                                    ),
                                )
                            }
                        }
                    },
                    valueRange = 0f..1f,
                    enabled = !busy,
                )
                OutlinedButton(
                    onClick = { plan = plan.resetImage(selectedImage) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                ) { Text("還原這張") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        seamIndex -= 1
                        selectedImage = seamIndex
                    },
                    enabled = !busy && seamIndex > 0,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text("上一個") }
                Button(
                    onClick = { plan = plan.confirm(seamIndex) },
                    enabled = !busy && !seam.skipped && !seam.confirmed && seamError == null,
                    modifier = Modifier.weight(1.5f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                ) { Text(if (seam.confirmed) "已確認" else "確認接縫") }
                OutlinedButton(
                    onClick = {
                        seamIndex += 1
                        selectedImage = seamIndex
                    },
                    enabled = !busy && seamIndex < plan.seams.lastIndex,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text("下一個") }
            }
        }
        item {
            if (!plan.isReady() && validation.message != null && seamError == null) {
                Text(validation.message!!, color = Quiet)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { onApply(plan) },
                enabled = !busy && plan.isReady(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.height(20.dp), color = Paper, strokeWidth = 2.dp)
                } else {
                    Text("套用並預覽", fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("銷毀、不保存", color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun CompactChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            content = { content() },
        )
    }
}

@Composable
private fun ManualSeamCanvas(
    previous: LoadedPreview,
    next: LoadedPreview,
    plan: ManualStitchPlan,
    seamIndex: Int,
    mode: ManualPreviewMode,
    onDrag: (Float) -> Unit,
) {
    var canvasSize by remember(plan.width) { mutableStateOf(IntSize.Zero) }
    val accentColor = MaterialTheme.colorScheme.primary
    val currentDrag by rememberUpdatedState(onDrag)
    val previewScale = remember(plan.width, canvasSize.width) {
        manualPreviewScale(plan, canvasSize.width)
    }
    val rowsPerPixel = 1f / previewScale.coerceAtLeast(0.001f)
    val currentRowsPerPixel by rememberUpdatedState(rowsPerPixel)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var lastY = down.position.y
                    var remainder = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        val delta = change.position.y - lastY
                        lastY = change.position.y
                        remainder += delta * currentRowsPerPixel
                        val rows = remainder.roundToInt()
                        if (rows != 0) {
                            currentDrag(rows.toFloat())
                            remainder -= rows
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        drawRect(Color(0xFF20231E))
        val scale = previewScale
        val shift = plan.seams[seamIndex].shift
        val previousCrop = plan.crops[seamIndex]
        val nextCrop = plan.crops[seamIndex + 1]
        val nextTop = shift.toLong() + nextCrop.top
        val nextBottom = shift.toLong() + nextCrop.bottom
        val overlapStart = maxOf(previousCrop.top.toLong(), nextTop)
        val overlapEnd = minOf(previousCrop.bottom.toLong(), nextBottom)
        val viewportRows = (size.height / scale).roundToInt().coerceIn(1, plan.height)
        val center = previousCrop.bottom.toFloat().coerceIn(0f, plan.height.toFloat())
        val viewTop = (center - viewportRows / 2f)
            .coerceIn(0f, (plan.height - viewportRows).coerceAtLeast(0).toFloat())

        when (mode) {
            ManualPreviewMode.Overlap -> {
                drawManualImage(
                    previous,
                    plan,
                    0,
                    previousCrop.top,
                    previousCrop.bottom,
                    viewTop,
                    scale,
                    0.56f,
                )
                drawManualImage(
                    next,
                    plan,
                    shift,
                    nextCrop.top,
                    nextCrop.bottom,
                    viewTop,
                    scale,
                    0.48f,
                )
            }
            ManualPreviewMode.Composite -> {
                drawManualImage(
                    previous,
                    plan,
                    0,
                    previousCrop.top,
                    previousCrop.bottom,
                    viewTop,
                    scale,
                    1f,
                )
                val sourceTop = (previousCrop.bottom - shift).coerceAtLeast(nextCrop.top)
                if (sourceTop < nextCrop.bottom) {
                    drawManualImage(
                        next,
                        plan,
                        shift,
                        sourceTop,
                        nextCrop.bottom,
                        viewTop,
                        scale,
                        1f,
                    )
                }
            }
        }
        if (overlapEnd > overlapStart) {
            drawRect(
                Color.White.copy(alpha = 0.08f),
                topLeft = Offset(0f, (overlapStart - viewTop) * scale),
                size = Size(
                    size.width,
                    ((overlapEnd - overlapStart) * scale).coerceAtLeast(1f),
                ),
            )
        }
        fun drawGuide(row: Long, color: Color, width: Float = 1.dp.toPx()) {
            val y = (row - viewTop) * scale
            if (y >= -width && y <= size.height + width) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = width)
            }
        }
        drawGuide(previousCrop.top.toLong(), Color(0xFF62D6A7))
        drawGuide(previousCrop.bottom.toLong(), Color(0xFF62D6A7))
        drawGuide(nextTop, accentColor)
        drawGuide(nextBottom, accentColor)
        if (overlapEnd > overlapStart) {
            drawGuide(overlapStart, Color.White, 1.5.dp.toPx())
            drawGuide(overlapEnd, Color.White, 1.5.dp.toPx())
        }
    }
}

private fun manualPreviewScale(
    plan: ManualStitchPlan,
    widthPx: Int,
): Float {
    if (widthPx <= 0) return 1f
    return widthPx.toFloat() / plan.width
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawManualImage(
    preview: LoadedPreview,
    plan: ManualStitchPlan,
    imageTop: Int,
    sourceTop: Int,
    sourceBottom: Int,
    viewTop: Float,
    scale: Float,
    alpha: Float,
) {
    val sourceHeight = sourceBottom - sourceTop
    if (sourceHeight <= 0) return
    val sourceBitmap = preview.bitmap.asImageBitmap()
    val sampledTop = (sourceTop * sourceBitmap.height / plan.height)
        .coerceIn(0, sourceBitmap.height - 1)
    val sampledBottom = (sourceBottom * sourceBitmap.height / plan.height)
        .coerceIn(sampledTop + 1, sourceBitmap.height)
    val scaledWidth = (plan.width * scale).roundToInt().coerceAtLeast(1)
    drawImage(
        sourceBitmap,
        srcOffset = IntOffset(0, sampledTop),
        srcSize = IntSize(sourceBitmap.width, sampledBottom - sampledTop),
        dstOffset = IntOffset(
            ((size.width - scaledWidth) / 2f).roundToInt(),
            ((imageTop + sourceTop - viewTop) * scale).roundToInt(),
        ),
        dstSize = IntSize(scaledWidth, (sourceHeight * scale).roundToInt().coerceAtLeast(1)),
        alpha = alpha,
    )
}

@Composable
private fun PreviewScreen(
    sources: List<java.io.File>,
    canOutput: Boolean,
    busy: Boolean,
    message: String?,
    manualPlan: ManualStitchPlan?,
    onManual: () -> Unit,
    onSave: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
    onCopy: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
    onDestroy: () -> Unit,
) {
    var format by remember(sources.size) { mutableStateOf(OutputFormat.Jpg) }
    var crop by remember(sources.firstOrNull()?.path) { mutableStateOf(0f..1f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                AppHeader("預覽")
            }
        }
        if (!canOutput) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        message ?: "目前顯示原始截圖；低信心接縫需手動確認後才能輸出。",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (manualPlan != null && !canOutput) {
            item {
                Button(
                    onClick = onManual,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("手動調整", fontWeight = FontWeight.SemiBold) }
            }
        }
        if (canOutput) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("上下裁切", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "拖曳圖片上的上下邊界線調整",
                        color = Quiet,
                    )
                }
            }
        }
        itemsIndexed(sources, key = { _, source -> source.path }) { index, source ->
            PreviewImage(
                source = source,
                index = index,
                count = sources.size,
                crop = if (canOutput) crop else 0f..1f,
                cropEnabled = canOutput && !busy,
                showCropOverlay = canOutput,
                onCropChange = { crop = it },
            )
        }
        if (canOutput) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (message != null) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text("輸出格式", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormatButton(
                            label = "JPG",
                            detail = "品質 95",
                            selected = format == OutputFormat.Jpg,
                            onClick = { format = OutputFormat.Jpg },
                            modifier = Modifier.weight(1f),
                        )
                        FormatButton(
                            label = "PNG",
                            detail = "無損",
                            selected = format == OutputFormat.Png,
                            onClick = { format = OutputFormat.Png },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (format == OutputFormat.Png) {
                        Spacer(Modifier.height(10.dp))
                        Text("原始尺寸、無損；檔案通常較大。", color = Quiet)
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = { onSave(format, crop) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                color = Paper,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("存到相簿", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onCopy(format, crop) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("複製到剪貼簿", color = Ink) }
                }
            }
        }
        if (canOutput && manualPlan != null) {
            item {
                OutlinedButton(
                    onClick = onManual,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("重新手動調整", color = Ink) }
            }
        }
        if (!busy) {
            item {
                TextButton(
                    onClick = onDestroy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(48.dp),
                ) { Text("銷毀、不保存", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun PreviewImage(
    source: java.io.File,
    index: Int,
    count: Int,
    crop: ClosedFloatingPointRange<Float>,
    cropEnabled: Boolean,
    showCropOverlay: Boolean,
    onCropChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    val loaded by produceState<LoadedPreview?>(null, source.path) {
        value = withContext(Dispatchers.IO) { loadPreview(source) }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (count > 1) {
                Text("第 ${index + 1} 張", style = MaterialTheme.typography.titleMedium, color = Ink)
            }
            Spacer(Modifier.weight(1f))
            Text(
                loaded?.let { "${it.width} × ${it.height} px" } ?: "載入中",
                style = MaterialTheme.typography.bodyMedium,
                color = Quiet,
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF20231E),
        ) {
            when (val preview = loaded) {
                null -> Column(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("正在載入預覽", color = Quiet)
                }
                else -> Box {
                    Image(
                        bitmap = preview.bitmap.asImageBitmap(),
                        contentDescription = if (count == 1) "裁切後預覽圖片"
                        else "第 ${index + 1} 張，共 $count 張截圖",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                    if (showCropOverlay) {
                        CropOverlay(
                            modifier = Modifier.matchParentSize(),
                            imageWidth = preview.bitmap.width,
                            imageHeight = preview.bitmap.height,
                            range = crop,
                            minimumRange = 0.02f,
                            fitImage = false,
                            enabled = cropEnabled,
                            onRangeChange = onCropChange,
                        )
                    }
                }
            }
        }
    }
}

private data class LoadedPreview(val bitmap: Bitmap, val width: Int, val height: Int)

private fun loadPreview(source: java.io.File): LoadedPreview {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "無法讀取預覽圖片" }
    var sample = 1
    while (
        bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 12_000_000
    ) {
        sample *= 2
    }
    val bitmap = checkNotNull(
        BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ),
    ) { "無法讀取預覽圖片" }
    return LoadedPreview(bitmap, bounds.outWidth, bounds.outHeight)
}

@Composable
private fun FormatButton(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(64.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(64.dp),
            shape = RoundedCornerShape(10.dp),
            content = { content() },
        )
    }
}

@Composable
private fun FailureScreen(status: CaptureStatus.Failed, onDestroy: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader("需要處理")
        Spacer(Modifier.height(48.dp))
        Column {
            Text(status.message, style = MaterialTheme.typography.titleMedium, color = Ink)
            if (status.retainedCount > 0) {
                Spacer(Modifier.height(10.dp))
                Text("已保留 ${status.retainedCount} 張有效圖片。", color = Quiet)
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onDestroy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
        ) { Text("銷毀暫存", color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun CenterStatus(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center, color = Quiet)
    }
}

@Composable
private fun AppHeader(
    status: String? = null,
    onThemeClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("長截圖", style = MaterialTheme.typography.titleMedium, color = Ink)
        if (onThemeClick != null) {
            IconButton(
                onClick = onThemeClick,
                modifier = Modifier.semantics { contentDescription = "調整主題顏色" },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_palette),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = Ink,
                )
            }
        } else if (status != null) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(99.dp),
            ) {
                Text(
                    status,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun LongScreenshotTheme(accent: Color, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Paper,
            background = Paper,
            onBackground = Ink,
            surface = Paper,
            onSurface = Ink,
            surfaceVariant = Color(0xFF20231E),
            onSurfaceVariant = Quiet,
            outline = Color(0xFF464942),
            error = accent,
        ),
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
            bodyLarge = TextStyle(
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        ),
        content = content,
    )
}
