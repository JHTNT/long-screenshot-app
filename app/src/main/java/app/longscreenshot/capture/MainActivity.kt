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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
private const val PreferencesName = "appearance"
private const val AccentPreference = "accent"

private enum class PermissionStep { Overlay, Notification }

private enum class ManualPreviewMode { Overlap, Composite }

internal fun previousCaptureScreen(status: CaptureStatus): CaptureStatus? = when (status) {
    is CaptureStatus.SelectingRegion -> if (status.forCapture) CaptureStatus.Capturing(status.count)
    else CaptureStatus.Reviewing(status.count)
    is CaptureStatus.Stitching -> CaptureStatus.SelectingRegion(status.count)
    is CaptureStatus.Failed -> if (status.retainedCount > 0) {
        CaptureStatus.Reviewing(status.retainedCount)
    } else {
        CaptureStatus.Idle
    }
    else -> null
}

class MainActivity : ComponentActivity() {
    private var permissionStep by mutableStateOf<PermissionStep?>(null)
    private var notificationAsked = false
    private var homeMessage by mutableStateOf<String?>(null)
    private var showCancelDialog by mutableStateOf(false)
    private var outputBusy by mutableStateOf(false)
    private var outputMessage by mutableStateOf<String?>(null)
    private var accent by mutableStateOf(AccentPresets.first())
    private var stitchGeneration = 0
    private val editBackStack = ArrayDeque<CaptureStatus>()

    private lateinit var overlayLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences(PreferencesName, MODE_PRIVATE)
        accent = Color(preferences.getInt(AccentPreference, AccentPresets.first().toArgb()))

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
                CaptureService.startIntent(this, result.resultCode, data),
            )
        }

        handleIntent(intent)
        setContent {
            LongScreenshotTheme(accent) {
                BackHandler(
                    enabled = permissionStep != null || CaptureSession.status !is CaptureStatus.Idle,
                    onBack = ::navigateBack,
                )
                App(
                    status = CaptureSession.status,
                    permissionStep = permissionStep,
                    homeMessage = homeMessage,
                    accent = accent,
                    onAccentChange = {
                        accent = it
                        preferences.edit().putInt(AccentPreference, it.toArgb()).apply()
                    },
                    onStart = {
                        homeMessage = null
                        outputMessage = null
                        notificationAsked = false
                        editBackStack.clear()
                        continuePermissionFlow()
                    },
                    onPermission = ::requestCurrentPermission,
                    onFinish = { startService(CaptureService.actionIntent(this, CaptureService.ACTION_FINISH)) },
                    onReturnToCapture = ::navigateBack,
                    onDelete = { startService(CaptureService.deleteIntent(this, it)) },
                    onReviewDelete = ::deleteReviewedSource,
                    onGuideRegion = {
                        startService(CaptureService.actionIntent(this, CaptureService.ACTION_SELECT_REGION))
                    },
                    onRegionConfirm = ::stitchSelectedRegion,
                    onManual = ::openManualEditor,
                    onManualApply = ::applyManualPlan,
                    onCancel = { showCancelDialog = true },
                    outputBusy = outputBusy,
                    outputMessage = outputMessage,
                    onSave = { format, crop -> runOutput("已存到相簿", format) {
                        CaptureOutput.saveToGallery(this, currentOutput(), format, crop)
                    } },
                    onCopy = { format, crop -> runOutput("已複製到剪貼簿", format) {
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
                                val status = CaptureSession.status
                                when {
                                    status is CaptureStatus.Starting || status is CaptureStatus.Capturing ||
                                        status is CaptureStatus.SelectingRegion && status.forCapture ->
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

    override fun onStart() {
        super.onStart()
        setCaptureAppVisible(true)
    }

    override fun onStop() {
        setCaptureAppVisible(false)
        super.onStop()
    }

    private fun setCaptureAppVisible(visible: Boolean) {
        CaptureSession.appVisible = visible
        val status = CaptureSession.status
        if (status is CaptureStatus.Capturing || status is CaptureStatus.SelectingRegion && status.forCapture) {
            startService(CaptureService.actionIntent(this, CaptureService.ACTION_UPDATE_VISIBILITY))
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

    private fun navigateBack() {
        if (permissionStep != null) {
            permissionStep = null
            CaptureSession.status = CaptureStatus.Idle
            return
        }
        val status = CaptureSession.status
        when (status) {
            CaptureStatus.Starting -> {
                startService(CaptureService.actionIntent(this, CaptureService.ACTION_CANCEL))
                CaptureSession.status = CaptureStatus.Idle
            }
            is CaptureStatus.Capturing -> moveTaskToBack(true)
            is CaptureStatus.Reviewing -> showCancelDialog = true
            is CaptureStatus.Stitching -> {
                stitchGeneration += 1
                CaptureSession.status = checkNotNull(previousCaptureScreen(status))
            }
            is CaptureStatus.Manual -> {
                if (outputBusy) {
                    stitchGeneration += 1
                    outputBusy = false
                }
                returnToPreviousEdit(status.count)
            }
            is CaptureStatus.Finished -> if (!outputBusy) returnToPreviousEdit(status.count)
            is CaptureStatus.SelectingRegion -> if (status.forCapture) {
                startService(CaptureService.actionIntent(this, CaptureService.ACTION_RESUME))
                moveTaskToBack(true)
            } else {
                CaptureSession.status = checkNotNull(previousCaptureScreen(status))
            }
            is CaptureStatus.Failed -> CaptureSession.status = checkNotNull(previousCaptureScreen(status))
            CaptureStatus.Idle -> Unit
        }
    }

    private fun returnToPreviousEdit(count: Int) {
        CaptureSession.status = if (editBackStack.isEmpty()) CaptureStatus.SelectingRegion(count)
        else editBackStack.removeLast()
    }

    private fun deleteReviewedSource(index: Int) {
        val reviewing = CaptureSession.status as? CaptureStatus.Reviewing ?: return
        if (!CaptureSession.deleteSource(index, reviewing.count)) {
            CaptureSession.status = reviewing.copy(message = "刪除失敗，截圖仍保留。")
            return
        }
        CaptureSession.status = CaptureStatus.Reviewing(
            reviewing.count - 1,
            if (reviewing.count == 1) "目前沒有已擷取的圖片。" else null,
        )
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
            if (selecting.forCapture) {
                Toast.makeText(this, "無法讀取圖片，請返回檢查截圖。", Toast.LENGTH_SHORT).show()
            } else {
                CaptureSession.status = CaptureStatus.Failed("無法讀取區域選取圖片。", selecting.count)
            }
            return
        }
        val top = (bounds.outHeight * range.start).roundToInt()
        val bottom = (bounds.outHeight * range.endInclusive).roundToInt()
        val region = VerticalRegion(top, bottom)
        CaptureSession.contentRegion = region
        if (selecting.forCapture) {
            startService(CaptureService.actionIntent(this, CaptureService.ACTION_RESUME))
            moveTaskToBack(true)
            return
        }
        val generation = ++stitchGeneration
        val operationTarget = CaptureSession.resultFile().resolveSibling("result-$generation.png")
        CaptureSession.status = CaptureStatus.Stitching(selecting.count)
        Thread({
            val result = runCatching {
                AutoStitcher.stitch(
                    sources = (1..selecting.count).map(CaptureSession::sourceFile),
                    target = operationTarget,
                    region = region,
                )
            }
                runOnUiThread {
                if (generation != stitchGeneration || CaptureSession.status !is CaptureStatus.Stitching) {
                    operationTarget.delete()
                    return@runOnUiThread
                }
                editBackStack.addLast(selecting)
                val committed = result.map { auto ->
                    val output = auto.output?.let { file ->
                        if (file == operationTarget) promoteResult(file) else file
                    }
                    auto.copy(output = output)
                }
                CaptureSession.status = committed.fold(
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
        editBackStack.addLast(finished)
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
        val generation = ++stitchGeneration
        val operationTarget = CaptureSession.resultFile().resolveSibling("result-$generation.png")
        Thread({
            val result = runCatching {
                ManualStitcher.stitch(
                    sources = (1..editing.count).map(CaptureSession::sourceFile),
                    target = operationTarget,
                    plan = plan,
                )
            }
            runOnUiThread {
                if (generation != stitchGeneration || CaptureSession.status !is CaptureStatus.Manual) {
                    operationTarget.delete()
                    return@runOnUiThread
                }
                outputBusy = false
                val committed = result.map(::promoteResult)
                CaptureSession.status = committed.fold(
                    onSuccess = {
                        editBackStack.addLast(CaptureStatus.Manual(editing.count, plan))
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

    private fun promoteResult(candidate: java.io.File): java.io.File {
        val target = CaptureSession.resultFile()
        if (candidate == target) return target
        java.nio.file.Files.move(
            candidate.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        return target
    }

    private fun runOutput(
        successMessage: String,
        format: OutputFormat,
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
                } else {
                    outputMessage = successMessage
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
    accent: Color,
    onAccentChange: (Color) -> Unit,
    onStart: () -> Unit,
    onPermission: () -> Unit,
    onFinish: () -> Unit,
    onReturnToCapture: () -> Unit,
    onDelete: (Int) -> Unit,
    onReviewDelete: (Int) -> Unit,
    onGuideRegion: () -> Unit,
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
                accent,
                onAccentChange,
                onStart,
            )
            CaptureStatus.Starting -> CenterStatus("正在準備", "即將顯示懸浮截圖按鈕。")
            is CaptureStatus.Capturing -> CapturingScreen(
                target, onFinish, onCancel, onDelete, onGuideRegion = onGuideRegion,
                onReturnToCapture = onReturnToCapture,
            )
            is CaptureStatus.Reviewing -> CapturingScreen(
                status = CaptureStatus.Capturing(target.count, target.message),
                onFinish = {
                    if (target.count > 0) CaptureSession.status = CaptureStatus.SelectingRegion(target.count)
                },
                onCancel = onCancel,
                onDelete = onReviewDelete,
                active = false,
            )
            is CaptureStatus.SelectingRegion -> RegionSelectionScreen(
                source = CaptureSession.sourceFile(if (target.forCapture) target.count else 1),
                forCapture = target.forCapture,
                initialRegion = CaptureSession.contentRegion,
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
            Spacer(Modifier.height(10.dp))
            Text("第一張截圖後，框出可捲動內容，排除上下固定區域，再依提示線保留重疊內容。", color = Quiet)
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
    active: Boolean = true,
    onGuideRegion: (() -> Unit)? = null,
    onReturnToCapture: (() -> Unit)? = null,
) {
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader(if (active) "擷取中" else "已完成擷取")
        Spacer(Modifier.height(28.dp))
        Column {
            Text("已擷取 ${status.count} 張", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                status.message ?: if (active) "切回要截圖的 App 繼續擷取。"
                else "可檢查截圖後再次進入區域選擇。",
                style = MaterialTheme.typography.bodyLarge,
                color = Quiet,
            )
        }
        if (active && status.count > 0 && onGuideRegion != null) {
            TextButton(onClick = onGuideRegion) { Text("調整上下固定區域") }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active && onReturnToCapture != null) {
                    OutlinedButton(
                        onClick = onReturnToCapture,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("返回截圖") }
                }
                Button(
                    onClick = onFinish,
                    enabled = active || status.count > 0,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                ) { Text("完成") }
            }
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
    forCapture: Boolean,
    initialRegion: VerticalRegion?,
    onConfirm: (ClosedFloatingPointRange<Float>) -> Unit,
    onCancel: () -> Unit,
) {
    var range by remember(source.path, forCapture) { mutableStateOf(0.12f..0.78f) }
    val loaded by produceState<LoadedPreview?>(null, source.path) {
        value = withContext(Dispatchers.IO) { loadPreview(source) }
    }

    LaunchedEffect(loaded, initialRegion) {
        val preview = loaded ?: return@LaunchedEffect
        initialRegion?.takeIf { it.bottom <= preview.height }?.let {
            range = it.top.toFloat() / preview.height..it.bottom.toFloat() / preview.height
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AppHeader(if (forCapture) "排除固定區域" else "確認拼接區域")
        Spacer(Modifier.height(8.dp))
        Text(
            if (forCapture) "把上方標題列、下方工具列或輸入框留在暗色區域，只框出會捲動的內容。"
            else "已沿用擷取時的範圍，可調整後拼接。首張頂端與末張底端會保留。",
            color = Quiet,
        )
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
            if (forCapture) "上界到虛線是重疊範圍，建議保留 15–25%；無法確認時，請往回捲一些。"
            else "拖曳上下邊界線調整",
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
            ) { Text(if (forCapture) "確認並繼續擷取" else "開始拼接") }
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
            TextButton(onClick = onCancel) { Text("銷毀、不保存") }
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
    var showGuides by remember { mutableStateOf(false) }
    var cropMode by remember { mutableStateOf(false) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var previewViewTop by remember(initialPlan, seamIndex) { mutableStateOf<Float?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showResetDialog by remember(initialPlan, seamIndex) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message, busy) {
        snackbar.currentSnackbarData?.dismiss()
        if (message != null && !busy) {
            snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    val seam = plan.seams[seamIndex]
    val previousSource = sources[seamIndex]
    val nextSource = sources[seamIndex + 1]
    val previous by produceState<LoadedPreview?>(null, previousSource.path) {
        value = withContext(Dispatchers.IO) { loadPreview(previousSource) }
    }
    val next by produceState<LoadedPreview?>(null, nextSource.path) {
        value = withContext(Dispatchers.IO) { loadPreview(nextSource) }
    }
    val validation = ManualStitcher.validate(plan)
    val seamError = validation.seamErrors[seamIndex]
    val otherPending = nextManualSeamToReview(plan, seamIndex)
    val remaining = plan.seams.indices.count {
        !plan.seams[it].skipped && (!plan.seams[it].confirmed || validation.seamErrors[it] != null)
    }
    val selectSeam: (Int) -> Unit = {
        seamIndex = it
        selectedImage = it
        cropMode = false
    }
    val crop = plan.crops[selectedImage]
    val updateCrop: (ManualCrop) -> Unit = {
        if (!busy && it != plan.crops[selectedImage]) plan = plan.withCrop(selectedImage, it)
    }
    val drag by rememberUpdatedState<(Float) -> Unit> { delta ->
        val rows = delta.roundToInt()
        if (rows != 0 && !busy) plan = plan.withShift(seamIndex, rows)
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val previewHeight = (maxHeight * 0.40f).coerceIn(120.dp, 360.dp)
        val compact = maxHeight < 700.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手動拼接", style = MaterialTheme.typography.titleLarge, color = Ink)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showResetDialog = true },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "重置位置與裁切" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_reset),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (busy) Ink.copy(alpha = 0.38f) else Ink,
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "更多拼接選項" },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_horiz),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (busy) Ink.copy(alpha = 0.38f) else Ink,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("拼接說明與數值") },
                            onClick = { menuExpanded = false; showDetails = true },
                        )
                        DropdownMenuItem(
                            text = { Text("銷毀、不保存", color = MaterialTheme.colorScheme.primary) },
                            onClick = { menuExpanded = false; onCancel() },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { selectSeam(seamIndex - 1) },
                    enabled = !busy && seamIndex > 0,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "上一處接縫" },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("‹", fontSize = 26.sp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("接縫 ${seamIndex + 1}／${plan.seams.size}", color = Ink,
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        "第 ${seamIndex + 1}、${seamIndex + 2} 張之間 · " + when {
                            seam.skipped -> "重複圖片已略過"
                            seamError != null -> "需要調整"
                            seam.confirmed -> "已確認"
                            else -> "待確認"
                        },
                        color = Quiet, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = { selectSeam(seamIndex + 1) },
                    enabled = !busy && seamIndex < plan.seams.lastIndex,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "下一處接縫" },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("›", fontSize = 26.sp) }
            }
            Column(
                Modifier.weight(1f).then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(previewHeight).onSizeChanged { previewSize = it },
                    color = Color(0xFF20231E), shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    when {
                        seam.skipped -> Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("這張內容已經出現過", color = Ink, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("第 ${seamIndex + 2} 張目前不加入成品。\n如果仍需要這張，可以保留後重新對齊。",
                                color = Quiet, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { plan = plan.keepDuplicate(seamIndex) }, enabled = !busy) {
                                Text("保留這張圖片")
                            }
                        }
                        previous == null || next == null -> Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        cropMode -> {
                            val selected = checkNotNull(if (selectedImage == seamIndex) previous else next)
                            val scale = manualPreviewScale(plan, previewSize.width)
                            val imageTop = if (selectedImage == seamIndex) 0 else seam.shift
                            val paddingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.roundToPx() }
                            val cropScroll = remember(seamIndex, selectedImage, previewSize) {
                                val viewTop = previewViewTop ?: manualSeamViewTop(
                                    plan.height, plan.crops[seamIndex].bottom, previewSize.height / scale,
                                )
                                ScrollState(manualCropScroll(viewTop, imageTop, scale, paddingPx))
                            }
                            LaunchedEffect(cropScroll, imageTop, scale, paddingPx) {
                                snapshotFlow { cropScroll.value }.collect { scroll ->
                                    previewViewTop = manualCropViewTop(scroll, imageTop, scale, paddingPx)
                                }
                            }
                            Box(Modifier.fillMaxSize().verticalScroll(cropScroll)) {
                                Box(Modifier.padding(vertical = 12.dp)) {
                                    Image(
                                        bitmap = selected.bitmap.asImageBitmap(),
                                        contentDescription = "第 ${selectedImage + 1} 張的裁切預覽",
                                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
                                    )
                                    CropOverlay(
                                        modifier = Modifier.matchParentSize(),
                                        imageWidth = selected.bitmap.width, imageHeight = selected.bitmap.height,
                                        range = (crop.top.toFloat() / plan.height)..(crop.bottom.toFloat() / plan.height),
                                        minimumRange = 1f / plan.height, fitImage = false, enabled = !busy,
                                        onRangeChange = { range ->
                                            val top = (range.start * plan.height).roundToInt().coerceIn(0, plan.height - 1)
                                            val bottom = (range.endInclusive * plan.height).roundToInt().coerceIn(top + 1, plan.height)
                                            updateCrop(ManualCrop(top, bottom))
                                        },
                                    )
                                }
                            }
                        }
                        else -> ManualSeamCanvas(
                            previous = checkNotNull(previous), next = checkNotNull(next),
                            plan = plan, seamIndex = seamIndex, mode = previewMode,
                            showGuides = showGuides, onDrag = drag, viewportTop = previewViewTop,
                            enabled = !busy && previewMode == ManualPreviewMode.Overlap,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    when {
                        seam.skipped -> "略過重複圖片，避免成品出現相同內容。"
                        cropMode -> "上下捲動查看圖片，拖曳邊界裁切暗色區域。"
                        previewMode == ManualPreviewMode.Composite -> "檢查接合處的文字與圖案是否連續。"
                        else -> "上下拖曳預覽，讓兩張圖的重複內容重合。"
                    },
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Quiet, style = MaterialTheme.typography.bodyMedium,
                )
                if (!seam.skipped) {
                    PrimaryTabRow(selectedTabIndex = if (cropMode) 1 else 0, containerColor = Paper) {
                        Tab(selected = !cropMode, onClick = { cropMode = false }, enabled = !busy,
                            text = { Text("對齊位置") })
                        Tab(selected = cropMode, onClick = { cropMode = true }, enabled = !busy,
                            text = { Text("裁切邊緣") })
                    }
                }
                Column(
                    modifier = (if (compact) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()))
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!seam.skipped && cropMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactChoice("第 ${seamIndex + 1} 張圖片", selectedImage == seamIndex,
                                { selectedImage = seamIndex }, Modifier.weight(1f), !busy)
                            CompactChoice("第 ${seamIndex + 2} 張圖片", selectedImage == seamIndex + 1,
                                { selectedImage = seamIndex + 1 }, Modifier.weight(1f), !busy)
                        }
                        ManualCropSlider(crop, plan.height, !busy, updateCrop)
                        TextButton(
                            onClick = { plan = plan.resetImage(selectedImage) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("還原這張的裁切與對齊", textAlign = TextAlign.Center)
                        }
                    } else if (!seam.skipped) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactChoice("重疊對照", previewMode == ManualPreviewMode.Overlap,
                                { previewMode = ManualPreviewMode.Overlap }, Modifier.weight(1f), !busy)
                            CompactChoice("拼接結果", previewMode == ManualPreviewMode.Composite,
                                { previewMode = ManualPreviewMode.Composite }, Modifier.weight(1f), !busy)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("微調第 ${seamIndex + 2} 張圖片", color = Ink, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Row(
                                modifier = Modifier.heightIn(min = 48.dp).toggleable(
                                    value = showGuides,
                                    enabled = !busy,
                                    role = Role.Switch,
                                    onValueChange = { showGuides = it },
                                ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("輔助線", color = if (busy) Quiet else Ink,
                                    style = MaterialTheme.typography.labelMedium)
                                Switch(checked = showGuides, onCheckedChange = null, enabled = !busy)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(-10 to "↑ 10 px", -1 to "↑ 1 px", 1 to "↓ 1 px", 10 to "↓ 10 px")
                                .forEach { (delta, label) ->
                                    OutlinedButton(
                                        onClick = { plan = plan.withShift(seamIndex, delta) }, enabled = !busy,
                                        modifier = Modifier.weight(1f).height(48.dp).semantics {
                                            contentDescription = "第 ${seamIndex + 2} 張圖片${if (delta < 0) "上移" else "下移"} ${abs(delta)} 像素"
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                    ) { Text(label, fontSize = 12.sp) }
                                }
                        }
                        if (showGuides) {
                            Text("綠線：第 ${seamIndex + 1} 張邊界 · 主題色：第 ${seamIndex + 2} 張邊界\n白線：重疊範圍", color = Quiet,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }

                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
                Text(
                    seamError ?: when {
                        cropMode -> "裁切後，回到對齊位置檢查接合處。"
                        remaining == 0 -> "所有接縫已確認，可以查看成品。"
                        else -> "還有 $remaining 處待確認 · 對齊後按下方按鈕"
                    },
                    color = if (seamError != null) MaterialTheme.colorScheme.primary else Quiet,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                Button(
                    onClick = {
                        if (cropMode) {
                            cropMode = false
                            previewMode = ManualPreviewMode.Overlap
                        } else {
                            val confirmed = if (seam.skipped || seam.confirmed) plan else plan.confirm(seamIndex)
                            plan = confirmed
                            if (confirmed.isReady()) onApply(confirmed)
                            else nextManualSeamToReview(confirmed, seamIndex)?.let(selectSeam)
                        }
                    },
                    enabled = !busy && (cropMode || seamError == null),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Paper, strokeWidth = 2.dp)
                    else Text(
                        when {
                            cropMode -> "完成裁切，返回對齊"
                            plan.isReady() -> "預覽成品"
                            seam.skipped || seam.confirmed -> "繼續處理下一處"
                            otherPending != null -> "確認並繼續"
                            else -> "確認並預覽成品"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 96.dp))
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置位置與裁切？") },
            text = { Text("目前的手動調整將還原為自動建議。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        plan = plan.resetSeam(seamIndex)
                        if (plan.seams[seamIndex].skipped) cropMode = false
                        showResetDialog = false
                    },
                    enabled = !busy,
                ) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("拼接說明") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("第 ${seamIndex + 1} 張圖片固定，移動第 ${seamIndex + 2} 張圖片，讓相同內容重合。切換「拼接結果」檢查是否有重複或缺漏，再確認接縫。")
                    Text("位移 ${seam.shift} px · 重疊 ${ManualStitcher.overlap(plan, seamIndex)} px")
                    if (message != null) Text(message)
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("知道了") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualCropSlider(crop: ManualCrop, height: Int, enabled: Boolean, onChange: (ManualCrop) -> Unit) {
    val colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outline)
    val thumb: @Composable (String, Int) -> Unit = { label, removed ->
        Surface(
            modifier = Modifier.size(16.dp).semantics {
                contentDescription = label
                stateDescription = "裁掉 $removed 像素"
            },
            shape = CircleShape,
            color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
        ) {}
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("頂部裁掉 ${crop.top} px", Modifier.weight(1f), color = Quiet,
                style = MaterialTheme.typography.labelMedium)
            Text("底部裁掉 ${height - crop.bottom} px", Modifier.weight(1f), color = Quiet,
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        RangeSlider(
            value = crop.top.toFloat()..crop.bottom.toFloat(),
            onValueChange = { onChange(adjustedManualCrop(crop, it, height)) },
            valueRange = 0f..height.toFloat(), enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            startThumb = { thumb("頂部裁切邊界", crop.top) },
            endThumb = { thumb("底部裁切邊界", height - crop.bottom) },
            track = { state ->
                SliderDefaults.Track(
                    rangeSliderState = state,
                    modifier = Modifier.height(4.dp),
                    enabled = enabled,
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
    }
}

internal fun adjustedManualCrop(
    crop: ManualCrop,
    range: ClosedFloatingPointRange<Float>,
    height: Int,
): ManualCrop {
    val top = range.start.roundToInt().coerceIn(0, height - 1)
    val bottom = range.endInclusive.roundToInt().coerceIn(1, height)
    return when {
        top < bottom -> ManualCrop(top, bottom)
        top != crop.top -> crop.copy(top = crop.bottom - 1)
        else -> crop.copy(bottom = crop.top + 1)
    }
}

@Composable
private fun CompactChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp).semantics { stateDescription = if (selected) "已選取" else "未選取" },
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp).semantics { stateDescription = if (selected) "已選取" else "未選取" },
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
    showGuides: Boolean,
    onDrag: (Float) -> Unit,
    viewportTop: Float?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
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
        modifier
            .semantics {
                contentDescription = if (enabled) "上下拖曳以對齊第 ${seamIndex + 2} 張圖片" else "接縫拼接結果預覽"
            }
            .onSizeChanged { canvasSize = it }
            .pointerInput(enabled, seamIndex) {
                if (!enabled) return@pointerInput
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
        val scale = manualPreviewScale(plan, size.width.roundToInt())
        val shift = plan.seams[seamIndex].shift
        val previousCrop = plan.crops[seamIndex]
        val nextCrop = plan.crops[seamIndex + 1]
        val nextTop = shift.toLong() + nextCrop.top
        val nextBottom = shift.toLong() + nextCrop.bottom
        val overlapStart = maxOf(previousCrop.top.toLong(), nextTop)
        val overlapEnd = minOf(previousCrop.bottom.toLong(), nextBottom)
        val viewTop = viewportTop ?: manualSeamViewTop(plan.height, previousCrop.bottom, size.height / scale)

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
        if (showGuides) {
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
}

internal fun manualSeamViewTop(imageHeight: Int, cropBottom: Int, visibleRows: Float): Float {
    val viewportRows = visibleRows.roundToInt().coerceIn(1, imageHeight)
    val center = cropBottom.toFloat().coerceIn(0f, imageHeight.toFloat())
    return (center - viewportRows / 2f)
        .coerceIn(0f, (imageHeight - viewportRows).toFloat())
}

// Translate between the shared seam coordinates and the selected image's padded scroll content.
internal fun manualCropScroll(viewTop: Float, imageTop: Int, scale: Float, paddingPx: Int): Int =
    ((viewTop - imageTop) * scale + paddingPx).roundToInt().coerceAtLeast(0)

internal fun manualCropViewTop(scroll: Int, imageTop: Int, scale: Float, paddingPx: Int): Float =
    imageTop + (scroll - paddingPx) / scale

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
