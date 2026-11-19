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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val AccentPresets = listOf(
    DefaultAccent,
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
    Color(0xFF868E96),
)

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
    private var homeMessageIsError by mutableStateOf(false)
    private var showCancelDialog by mutableStateOf(false)
    private var manualBusy by mutableStateOf(false)
    private var accent by mutableStateOf(DefaultAccent)
    private var systemColors by mutableStateOf(false)
    private var stitchGeneration = 0
    private val editBackStack = ArrayDeque<CaptureStatus>()

    private lateinit var overlayLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences(PreferencesName, MODE_PRIVATE)
        accent = Color(preferences.getInt(AccentPreference, DefaultAccent.toArgb()))
        systemColors = preferences.getBoolean(SystemColorsPreference, false)

        if (!CaptureSession.cleanOrphans(this)) {
            homeMessage = "部分舊暫存無法清除，請稍後再試。"
            homeMessageIsError = true
        }
        if (!CaptureOutput.cleanExpiredShares(this)) {
            homeMessage = "部分過期剪貼簿圖片無法清除，請稍後再試。"
            homeMessageIsError = true
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
                homeMessageIsError = false
                return@registerForActivityResult
            }
            CaptureSession.status = CaptureStatus.Starting
            ContextCompat.startForegroundService(
                this,
                CaptureService.startIntent(this, result.resultCode, data),
            )
        }

        handleIntent(intent)
        val appContext = applicationContext
        // 輸出屬於擷取工作階段，畫面重建時仍須完成。
        val outputScope = CoroutineScope(Dispatchers.Main.immediate)
        setContent {
            LongScreenshotTheme(accent, systemColors) {
                BackHandler(
                    enabled = permissionStep != null || CaptureSession.status !is CaptureStatus.Idle,
                    onBack = ::navigateBack,
                )
                App(
                    status = CaptureSession.status,
                    permissionStep = permissionStep,
                    homeMessage = homeMessage,
                    homeMessageIsError = homeMessageIsError,
                    accent = accent,
                    systemColors = systemColors,
                    onSystemColorsChange = {
                        systemColors = it
                        preferences.edit().putBoolean(SystemColorsPreference, it).apply()
                    },
                    onAccentChange = {
                        accent = it
                        systemColors = false
                        preferences.edit().putInt(AccentPreference, it.toArgb())
                            .putBoolean(SystemColorsPreference, false).apply()
                    },
                    onBack = ::navigateBack,
                    onStart = {
                        homeMessage = null
                        homeMessageIsError = false
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
                    manualBusy = manualBusy,
                    onSave = { format, crop ->
                        outputScope.launch {
                            CaptureOutput.runOutput("儲存中…", "已存到相簿") { source ->
                                CaptureOutput.saveToGallery(appContext, source, format, crop)
                            }
                        }
                    },
                    onCopy = { format, crop ->
                        outputScope.launch {
                            CaptureOutput.runOutput("複製中…", "已複製到剪貼簿") { source ->
                                CaptureOutput.copyToClipboard(appContext, source, format, crop)
                            }
                        }
                    },
                )
                if (showCancelDialog && (CaptureSession.status as? CaptureStatus.Finished)?.outputAction == null) {
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
                                        }
                                    }
                                }
                            }) { Text("銷毀", color = MaterialTheme.colorScheme.error) }
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
        if (intent?.action == CaptureService.ACTION_CONFIRM_CANCEL &&
            (CaptureSession.status as? CaptureStatus.Finished)?.outputAction == null
        ) showCancelDialog = true
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
                if (manualBusy) {
                    stitchGeneration += 1
                    manualBusy = false
                }
                returnToPreviousEdit(status.count)
            }
            is CaptureStatus.Finished -> if (status.outputAction == null) {
                returnToPreviousEdit(status.count)
            }
            is CaptureStatus.SelectingRegion -> if (status.forCapture) {
                startService(CaptureService.actionIntent(this, CaptureService.ACTION_RESUME))
                moveTaskToBack(true)
            } else {
                CaptureSession.status = checkNotNull(previousCaptureScreen(status))
            }
            is CaptureStatus.Failed -> if (status.retainedCount == 0) {
                CaptureSession.destroy()
            } else {
                CaptureSession.status = checkNotNull(previousCaptureScreen(status))
            }
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
        if (finished.outputAction != null) return
        val plan = finished.manualPlan ?: return
        editBackStack.addLast(finished.copy(outputMessage = null))
        CaptureSession.status = CaptureStatus.Manual(
            finished.count,
            plan,
            finished.message.takeIf { finished.output == null },
        )
    }

    private fun applyManualPlan(plan: ManualStitchPlan) {
        val editing = CaptureSession.status as? CaptureStatus.Manual ?: return
        if (manualBusy || !plan.isReady()) return
        manualBusy = true
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
                manualBusy = false
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
}

@Composable
private fun App(
    status: CaptureStatus,
    permissionStep: PermissionStep?,
    homeMessage: String?,
    homeMessageIsError: Boolean,
    accent: Color,
    systemColors: Boolean,
    onSystemColorsChange: (Boolean) -> Unit,
    onAccentChange: (Color) -> Unit,
    onBack: () -> Unit,
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
    manualBusy: Boolean,
    onSave: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
    onCopy: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        when (val target = permissionStep ?: status) {
            is PermissionStep -> PermissionScreen(target, onPermission, onBack)
            CaptureStatus.Idle -> HomeScreen(
                homeMessage,
                homeMessageIsError,
                accent,
                systemColors,
                onSystemColorsChange,
                onAccentChange,
                onStart,
            )
            CaptureStatus.Starting -> CenterStatus("正在準備", "即將顯示懸浮截圖按鈕。")
            is CaptureStatus.Capturing -> CapturingScreen(
                target, onFinish, onCancel, onDelete, onGuideRegion = onGuideRegion,
                onReturnToCapture = onReturnToCapture,
                onBack = onBack,
            )
            is CaptureStatus.Reviewing -> CapturingScreen(
                status = CaptureStatus.Capturing(target.count, target.message),
                onFinish = {
                    if (target.count > 0) CaptureSession.status = CaptureStatus.SelectingRegion(target.count)
                },
                onCancel = onCancel,
                onDelete = onReviewDelete,
                active = false,
                onBack = onBack,
            )
            is CaptureStatus.SelectingRegion -> RegionSelectionScreen(
                source = CaptureSession.sourceFile(if (target.forCapture) target.count else 1),
                forCapture = target.forCapture,
                initialRegion = CaptureSession.contentRegion,
                onConfirm = onRegionConfirm,
                onCancel = onCancel,
                onBack = onBack,
            )
            is CaptureStatus.Stitching -> CenterStatus(
                "正在自動拼接",
                "正在比對 ${target.count - 1} 個接縫，來源圖片會完整保留。",
            )
            is CaptureStatus.Manual -> ManualStitchScreen(
                sources = (1..target.count).map(CaptureSession::sourceFile),
                initialPlan = target.plan,
                busy = manualBusy,
                message = target.message,
                onApply = onManualApply,
                onCancel = onCancel,
                onBack = onBack,
            )
            is CaptureStatus.Finished -> key(CaptureSession.directory?.path) {
                PreviewScreen(
                    sources = target.output?.let(::listOf)
                        ?: (1..target.count).map(CaptureSession::sourceFile),
                    canOutput = target.output != null,
                    busy = target.outputAction != null,
                    busyAction = target.outputAction,
                    statusMessage = target.message,
                    outputMessage = target.outputMessage,
                    onOutputMessageConsumed = {
                        if (CaptureSession.status === target) CaptureSession.status = target.copy(outputMessage = null)
                    },
                    manualPlan = target.manualPlan,
                    onManual = onManual,
                    onSave = onSave,
                    onCopy = onCopy,
                    onDestroy = onCancel,
                    onBack = onBack,
                    sourceCount = target.count,
                )
            }
            is CaptureStatus.Failed -> FailureScreen(target, onCancel, onBack)
            else -> Unit
        }
    }
}

@Composable
private fun HomeScreen(
    message: String?,
    messageIsError: Boolean,
    accent: Color,
    systemColors: Boolean,
    onSystemColorsChange: (Boolean) -> Unit,
    onAccentChange: (Color) -> Unit,
    onStart: () -> Unit,
) {
    var showAccentDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        AppHeader(onThemeClick = { showAccentDialog = true })
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "逐張擷取其他 App 的畫面，自動拼接成長圖。可調整接縫、裁切，再儲存或複製。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            listOf(
                "開啟要截圖的 App" to "用懸浮按鈕擷取第一張。",
                "框選內容，捲動擷取" to "排除上下固定區域，依提示線保留重疊。",
                "檢查長圖，再輸出" to "存到相簿或複製到剪貼簿。",
            ).forEachIndexed { index, (title, detail) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (message != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (messageIsError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "圖片只在這台裝置處理",
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("開始擷取", fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (showAccentDialog) {
        AccentDialog(
            accent = accent,
            systemColors = systemColors,
            onSystemColorsChange = onSystemColorsChange,
            onAccentChange = onAccentChange,
            onDismiss = { showAccentDialog = false },
        )
    }
}

@Composable
private fun AccentDialog(
    accent: Color,
    systemColors: Boolean,
    onSystemColorsChange: (Boolean) -> Unit,
    onAccentChange: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(accent.toArgb(), it) }
    val manualAlpha = if (systemColors) 0.55f else 1f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主題顏色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = systemColors, role = Role.Switch, onValueChange = onSystemColorsChange,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("使用系統配色", Modifier.weight(1f))
                        Switch(checked = systemColors, onCheckedChange = null)
                    }
                    if (systemColors) {
                        Text(
                            "點選下方顏色將改用自訂主題色",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Column(Modifier.alpha(manualAlpha)) {
                    AccentPresets.chunked(4).forEachIndexed { rowIndex, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            row.forEachIndexed { columnIndex, color ->
                                Surface(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .selectable(
                                            selected = !systemColors && color == accent,
                                            onClick = { onAccentChange(color) },
                                            role = Role.RadioButton,
                                        )
                                        .semantics {
                                            contentDescription =
                                                "預設顏色 ${rowIndex * 4 + columnIndex + 1}"
                                        },
                                    shape = CircleShape,
                                    color = color,
                                    border = BorderStroke(
                                        if (!systemColors && color == accent) 3.dp else 1.dp,
                                        if (!systemColors && color == accent) MaterialTheme.colorScheme.onSurface
                                        else color.copy(alpha = 0.45f),
                                    ),
                                ) {}
                            }
                            repeat(4 - row.size) { Spacer(Modifier.size(48.dp)) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("自訂顏色", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "實際套用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            modifier = Modifier.size(20.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {}
                    }
                    Spacer(Modifier.height(10.dp))
                    ColorPlane(hsv, onAccentChange)
                    Spacer(Modifier.height(12.dp))
                    HueBar(hsv, onAccentChange)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun ColorPlane(hsv: FloatArray, onColorChange: (Color) -> Unit) {
    val saturation = hsv[1].coerceIn(0f, 1f)
    val darkness = ((1f - hsv[2]) / 0.75f).coerceIn(0f, 1f)
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
                listOf(Color.White, Color.hsv(hsv[0], 1f, 1f)),
            ),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
            ),
        )
        val marker = Offset(size.width * saturation, size.height * darkness)
        drawCircle(Color.Black, 9.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun HueBar(hsv: FloatArray, onColorChange: (Color) -> Unit) {
    val update: (Offset, Size) -> Unit = { point, bounds ->
        onColorChange(
            pickerColor(
                point.x / bounds.width * 360f,
                hsv[1],
                (1f - hsv[2]) / 0.75f,
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
        drawLine(Color.Black, Offset(markerX, 0f), Offset(markerX, size.height), 4.dp.toPx())
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
        xFraction.coerceIn(0f, 1f),
        1f - yFraction.coerceIn(0f, 1f) * 0.75f,
    )

@Composable
private fun PermissionScreen(step: PermissionStep, onContinue: () -> Unit, onBack: () -> Unit) {
    val (number, title, body, button) = when (step) {
        PermissionStep.Overlay -> listOf(
            "設定 1 / 2", "允許懸浮按鈕", "讓截圖按鈕顯示在其他 App 上方。", "前往設定",
        )
        PermissionStep.Notification -> listOf(
            "設定 2 / 2", "允許通知", "在通知中提供完成與取消操作；拒絕也能繼續。", "繼續",
        )
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        AppHeader(number, onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(button, fontWeight = FontWeight.SemiBold) }
        }
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
    onBack: () -> Unit,
) {
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        AppHeader(if (active) "擷取中" else "已完成擷取", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("已擷取 ${status.count} 張", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                status.message ?: if (active) "切回要截圖的 App 繼續擷取。"
                else "可檢查截圖後再次進入區域選擇。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active && status.count > 0 && onGuideRegion != null) {
                TextButton(onClick = onGuideRegion) { Text("調整上下固定區域") }
            }
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(16.dp))
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active && onReturnToCapture != null) {
                        OutlinedButton(
                            onClick = onReturnToCapture,
                            modifier = Modifier.weight(1f),
                        ) { Text("返回截圖") }
                    }
                    Button(
                        onClick = onFinish,
                        enabled = active || status.count > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (active) "完成" else "選擇拼接區域") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("取消", color = MaterialTheme.colorScheme.error) }
            }
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
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
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
        color = MaterialTheme.colorScheme.surfaceContainer,
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
    onBack: () -> Unit,
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
        Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        AppHeader(if (forCapture) "排除固定區域" else "確認拼接區域", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                if (forCapture) "把上方標題列、下方工具列或輸入框留在暗色區域，只框出會捲動的內容。"
                else "已沿用擷取時的範圍，可調整後拼接。首張頂端與末張底端會保留。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                when (val preview = loaded) {
                    null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    else -> Box(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
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
            Spacer(Modifier.height(8.dp))
            Text(
                if (forCapture) "上界到虛線是重疊範圍，建議保留 15–25%；無法確認時，請往回捲一些。"
                else "拖曳上下邊界線調整",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) { Text("不保存", color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { onConfirm(range) },
                    enabled = loaded != null,
                    modifier = Modifier.weight(2f),
                ) { Text(if (forCapture) "確認並繼續擷取" else "開始拼接") }
            }
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
    showHandles: Boolean = true,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val currentRange by rememberUpdatedState(range)
    val currentOnRangeChange by rememberUpdatedState(onRangeChange)
    val accentColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.surface

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
            Color.Black.copy(alpha = 0.65f),
            topLeft = Offset(frame.left, frame.top),
            size = Size(frame.width, (top - frame.top).coerceIn(0f, frame.height)),
        )
        drawRect(
            Color.Black.copy(alpha = 0.65f),
            topLeft = Offset(frame.left, bottom),
            size = Size(frame.width, (frame.bottom - bottom).coerceIn(0f, frame.height)),
        )
        if (!showHandles) return@Canvas
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
                color = handleColor,
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
    onBack: () -> Unit,
) {
    if (initialPlan.seams.isEmpty()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("沒有可調整的接縫", color = MaterialTheme.colorScheme.onSurface)
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
        val compact = maxHeight < 640.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f
        val narrowTools = compact || maxWidth < 360.dp
        val previewHeight = if (compact) (maxHeight * 0.40f).coerceIn(120.dp, 320.dp)
            else (maxHeight - 400.dp).coerceIn(160.dp, 480.dp)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                }
                Text("接縫修正", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
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
                        tint = if (busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
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
                            tint = if (busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("拼接說明與數值") },
                            onClick = { menuExpanded = false; showDetails = true },
                        )
                        DropdownMenuItem(
                            text = { Text("銷毀、不保存", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onCancel() },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { selectSeam(seamIndex - 1) },
                        enabled = !busy && seamIndex > 0,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "上一處接縫" },
                    ) { Text("‹", fontSize = 26.sp) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("接縫 ${seamIndex + 1}／${plan.seams.size}", color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            "第 ${seamIndex + 1}、${seamIndex + 2} 張之間 · " + when {
                                seam.skipped -> "重複圖片已略過"
                                seamError != null -> "需要調整"
                                seam.confirmed -> "已確認"
                                else -> "待確認"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                        )
                    }
                    IconButton(
                        onClick = { selectSeam(seamIndex + 1) },
                        enabled = !busy && seamIndex < plan.seams.lastIndex,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "下一處接縫" },
                    ) { Text("›", fontSize = 26.sp) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(previewHeight).onSizeChanged { previewSize = it },
                            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        ) {
                            when {
                                seam.skipped -> Column(
                                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text("這張內容已經出現過", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    Text("第 ${seamIndex + 2} 張目前不加入成品。\n如果仍需要這張，可以保留後重新對齊。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                        )
                        if (!seam.skipped) {
                            PrimaryTabRow(selectedTabIndex = if (cropMode) 1 else 0, containerColor = MaterialTheme.colorScheme.surface) {
                                Tab(selected = !cropMode, onClick = { cropMode = false }, enabled = !busy,
                                    text = { Text("對齊位置") })
                                Tab(selected = cropMode, onClick = { cropMode = true }, enabled = !busy,
                                    text = { Text("裁切邊緣") })
                            }
                        }
                        Column(
                            modifier = (if (compact) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()))
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (!seam.skipped && cropMode) {
                                SegmentedChoiceRow(
                                    options = listOf("第 ${seamIndex + 1} 張圖片", "第 ${seamIndex + 2} 張圖片"),
                                    selectedIndex = if (selectedImage == seamIndex) 0 else 1,
                                    onSelect = { selectedImage = seamIndex + it },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy,
                                )
                                ManualCropSlider(crop, plan.height, !busy, updateCrop)
                                TextButton(
                                    onClick = { plan = plan.resetImage(selectedImage) },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("還原這張的裁切與對齊", textAlign = TextAlign.Center)
                                }
                            } else if (!seam.skipped) {
                                val guidesToggle: @Composable () -> Unit = {
                                    Row(
                                        modifier = Modifier.heightIn(min = 48.dp).toggleable(
                                            value = showGuides,
                                            enabled = !busy,
                                            role = Role.Switch,
                                            onValueChange = { showGuides = it },
                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "輔助線",
                                            color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                        Switch(checked = showGuides, onCheckedChange = null, enabled = !busy)
                                    }
                                }
                                if (narrowTools) {
                                    SegmentedChoiceRow(
                                        options = listOf("重疊對照", "拼接結果"),
                                        selectedIndex = if (previewMode == ManualPreviewMode.Overlap) 0 else 1,
                                        onSelect = {
                                            previewMode = if (it == 0) ManualPreviewMode.Overlap else ManualPreviewMode.Composite
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !busy,
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        guidesToggle()
                                    }
                                } else {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SegmentedChoiceRow(
                                            options = listOf("重疊對照", "拼接結果"),
                                            selectedIndex = if (previewMode == ManualPreviewMode.Overlap) 0 else 1,
                                            onSelect = {
                                                previewMode = if (it == 0) ManualPreviewMode.Overlap else ManualPreviewMode.Composite
                                            },
                                            modifier = Modifier.weight(1f),
                                            enabled = !busy,
                                        )
                                        guidesToggle()
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("微調第 ${seamIndex + 2} 張圖片 (px)", Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium)
                                    Text("${seam.shift} px", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(-10 to "↑10", -1 to "↑1", 1 to "↓1", 10 to "↓10")
                                        .forEach { (delta, label) ->
                                            FilledTonalButton(
                                                onClick = { plan = plan.withShift(seamIndex, delta) }, enabled = !busy,
                                                modifier = Modifier.weight(1f).semantics {
                                                    contentDescription = "第 ${seamIndex + 2} 張圖片${if (delta < 0) "上移" else "下移"} ${abs(delta)} 像素"
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                }
                                if (showGuides) {
                                    Text(
                                        "綠線：第 ${seamIndex + 1} 張邊界 · 主題色：第 ${seamIndex + 2} 張邊界\n白線：重疊範圍",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        seamError ?: when {
                            cropMode -> "裁切後，回到對齊位置檢查接合處。"
                            remaining == 0 -> "所有接縫已確認，可以查看成品。"
                            else -> "還有 $remaining 處待確認 · 對齊後按下方按鈕"
                        },
                        color = if (seamError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
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
        }
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
            Text("頂部裁掉 ${crop.top} px", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            Text("底部裁掉 ${height - crop.bottom} px", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SegmentedChoiceRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
                icon = {},
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
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
    val canvasColor = MaterialTheme.colorScheme.surfaceContainer
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
        drawRect(canvasColor)
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
            fun drawGuide(row: Long, color: Color, width: Float = 1.5.dp.toPx()) {
                val y = (row - viewTop) * scale
                if (y >= -width && y <= size.height + width) {
                    drawLine(
                        Color.Black.copy(alpha = 0.65f),
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = width + 2.dp.toPx(),
                    )
                    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = width)
                }
            }
            drawGuide(previousCrop.top.toLong(), Color(0xFF00C853))
            drawGuide(previousCrop.bottom.toLong(), Color(0xFF00C853))
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
    busyAction: String?,
    statusMessage: String?,
    outputMessage: String?,
    onOutputMessageConsumed: () -> Unit,
    manualPlan: ManualStitchPlan?,
    onManual: () -> Unit,
    onSave: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
    onCopy: (OutputFormat, ClosedFloatingPointRange<Float>) -> Unit,
    onDestroy: () -> Unit,
    onBack: () -> Unit,
    sourceCount: Int,
) {
    var format by rememberSaveable(sources.size) { mutableStateOf(OutputFormat.Jpg) }
    var crop by rememberSaveable(
        sources.firstOrNull()?.path,
        stateSaver = listSaver(
            save = { listOf(it.start, it.endInclusive) },
            restore = { it[0]..it[1] },
        ),
    ) { mutableStateOf(0f..1f) }
    var cropMode by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var outputDimensions by remember(sources.firstOrNull()?.path) { mutableStateOf<Pair<Int, Int>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val isCropped = crop != 0f..1f
    val back: () -> Unit = {
        if (!busy) {
            when {
                showOutput -> showOutput = false
                cropMode -> cropMode = false
                else -> onBack()
            }
        }
    }
    BackHandler(enabled = busy || showOutput || cropMode, onBack = back)
    LaunchedEffect(statusMessage, canOutput) {
        if (!busy && canOutput && statusMessage != null && outputMessage == null) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(statusMessage, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    LaunchedEffect(outputMessage, busy) {
        if (!busy && canOutput && outputMessage != null) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(outputMessage, withDismissAction = true, duration = SnackbarDuration.Long)
            onOutputMessageConsumed()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val panelMaxHeight = maxHeight * 0.65f
        Column(Modifier.fillMaxSize()) {
            AppHeader("成品預覽", onBack = back, enabled = !busy, onDiscard = onDestroy)
            Text(
                "$sourceCount 張截圖 · " + if (canOutput) "可以輸出" else "接縫待確認",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (!canOutput) {
                Text(
                    outputMessage ?: statusMessage ?: "請先確認接縫，再輸出長圖。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(sources, key = { _, source -> source.path }) { index, source ->
                        PreviewImage(
                            source = source,
                            index = index,
                            count = sources.size,
                            crop = if (canOutput) crop else 0f..1f,
                            cropEnabled = canOutput && cropMode && !busy,
                            showCropOverlay = canOutput,
                            onCropChange = { crop = it },
                            onDimensionsLoaded = if (sources.size == 1) { w, h -> outputDimensions = w to h } else null,
                        )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (showOutput && canOutput) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = panelMaxHeight)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("輸出圖片", style = MaterialTheme.typography.titleMedium)
                                outputDimensions?.let { (width, height) ->
                                    val (top, bottom) = CaptureOutput.cropRows(height, crop)
                                    Text(
                                        "$width × ${bottom - top} px" + if (isCropped) " · 已裁切" else "",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = { showOutput = false }, enabled = !busy) { Text("關閉") }
                        }
                        Spacer(Modifier.height(8.dp))
                        SegmentedChoiceRow(
                            options = listOf("JPG", "PNG"),
                            selectedIndex = if (format == OutputFormat.Jpg) 0 else 1,
                            onSelect = { format = if (it == 0) OutputFormat.Jpg else OutputFormat.Png },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                        Text(
                            if (format == OutputFormat.Png) "原始尺寸、無損；檔案通常較大。"
                            else "原始尺寸、JPG 品質 95；有損壓縮。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Button(
                            onClick = { showOutput = false; onSave(format, crop) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("存到相簿") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showOutput = false; onCopy(format, crop) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("複製到剪貼簿") }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "超過 24 小時的暫存會自動清理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (cropMode) {
                BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.5f)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("拖曳圖片上下邊界裁切", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { crop = 0f..1f }, enabled = !busy && isCropped) { Text("還原") }
                        }
                        if (sources.size == 1) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { scope.launch { listState.animateScrollToItem(0, 0) } },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("捲到頂部") }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val itemHeight = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0
                                            val viewportHeight = listState.layoutInfo.viewportSize.height
                                            listState.animateScrollToItem(
                                                sources.lastIndex,
                                                (itemHeight - viewportHeight).coerceAtLeast(0),
                                            )
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("捲到底部") }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(onClick = { cropMode = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("完成裁切")
                        }
                    }
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canOutput) {
                            TextButton(onClick = { cropMode = true }, enabled = !busy) {
                                Text(if (isCropped) "已裁切" else "裁切")
                            }
                            if (manualPlan != null) {
                                TextButton(onClick = onManual, enabled = !busy) { Text("接縫") }
                            }
                            Button(onClick = { showOutput = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                if (busy) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = LocalContentColor.current,
                                        )
                                        Text(busyAction ?: "處理中…")
                                    }
                                } else {
                                    Text("輸出")
                                }
                            }
                        } else if (manualPlan != null) {
                            Button(onClick = onManual, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("檢查接縫")
                            }
                        } else {
                            Button(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("重新選擇拼接區域")
                            }
                        }
                    }
                }
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
    onDimensionsLoaded: ((Int, Int) -> Unit)? = null,
) {
    val loaded by produceState<LoadedPreview?>(null, source.path) {
        value = withContext(Dispatchers.IO) { loadPreview(source) }
    }
    LaunchedEffect(loaded) {
        loaded?.let { onDimensionsLoaded?.invoke(it.width, it.height) }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (count > 1) {
                Text("第 ${index + 1} 張", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.weight(1f))
            Text(
                loaded?.let {
                    val (top, bottom) = CaptureOutput.cropRows(it.height, crop)
                    "${it.width} × ${bottom - top} px"
                } ?: "載入中",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            when (val preview = loaded) {
                null -> Column(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("正在載入預覽", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Box(Modifier.padding(vertical = 12.dp)) {
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
                            showHandles = cropEnabled,
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
private fun FailureScreen(status: CaptureStatus.Failed, onDestroy: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        AppHeader("需要處理", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(status.message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (status.retainedCount > 0) {
                Spacer(Modifier.height(10.dp))
                Text("已保留 ${status.retainedCount} 張有效圖片。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    status.retainedCount > 0 -> "返回來源檢查"
                    CaptureSession.directory != null -> "清理暫存並回首頁"
                    else -> "回首頁"
                })
            }
            if (status.retainedCount > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDestroy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("銷毀暫存", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun CenterStatus(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppHeader(
    status: String? = null,
    onThemeClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(
                start = if (onBack != null) 4.dp else 16.dp,
                end = if (onThemeClick != null || onDiscard != null) 4.dp else 16.dp,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
            }
        }
        Text(
            status ?: "長截圖",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onThemeClick != null) {
            IconButton(
                onClick = onThemeClick,
                modifier = Modifier.semantics { contentDescription = "調整主題顏色" },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_palette),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (onDiscard != null) {
            Box {
                IconButton(onClick = { showMenu = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_more_horiz), contentDescription = "更多選項")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("銷毀、不保存", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDiscard() }, enabled = enabled,
                    )
                }
            }
        }
    }
}
