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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Paper = Color(0xFF111310)
private val Ink = Color(0xFFF2F0E9)
private val Accent = Color(0xFFFF7353)
private val Quiet = Color(0xFFAAA9A2)

private enum class PermissionStep { Overlay, Notification }

class MainActivity : ComponentActivity() {
    private var permissionStep by mutableStateOf<PermissionStep?>(null)
    private var notificationAsked = false
    private var homeMessage by mutableStateOf<String?>(null)
    private var showCancelDialog by mutableStateOf(false)
    private var outputBusy by mutableStateOf(false)
    private var outputMessage by mutableStateOf<String?>(null)
    private var selectedMode by mutableStateOf(CaptureMode.General)

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
            LongScreenshotTheme {
                App(
                    status = CaptureSession.status,
                    permissionStep = permissionStep,
                    homeMessage = homeMessage,
                    mode = selectedMode,
                    onModeChange = { selectedMode = it },
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
                    onCancel = { showCancelDialog = true },
                    outputBusy = outputBusy,
                    outputMessage = outputMessage,
                    onSave = { format -> runOutput("已存到相簿", format) {
                        CaptureOutput.saveToGallery(this, currentOutput(), format)
                    } },
                    onCopy = { format -> runOutput("已複製到剪貼簿", format, destroySource = false) {
                        CaptureOutput.copyToClipboard(this, currentOutput(), format)
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
                            }) { Text("銷毀", color = Accent) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCancelDialog = false }) { Text("繼續擷取") }
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
                    onSuccess = { CaptureStatus.Finished(selecting.count, it.output, it.message) },
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
    onStart: () -> Unit,
    onPermission: () -> Unit,
    onFinish: () -> Unit,
    onDelete: (Int) -> Unit,
    onRegionConfirm: (ClosedFloatingPointRange<Float>) -> Unit,
    onCancel: () -> Unit,
    outputBusy: Boolean,
    outputMessage: String?,
    onSave: (OutputFormat) -> Unit,
    onCopy: (OutputFormat) -> Unit,
) {
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        when (val target = permissionStep ?: status) {
            is PermissionStep -> PermissionScreen(target, onPermission)
            CaptureStatus.Idle -> HomeScreen(homeMessage, mode, onModeChange, onStart)
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
            is CaptureStatus.Finished -> PreviewScreen(
                sources = target.output?.let(::listOf)
                    ?: (1..target.count).map(CaptureSession::sourceFile),
                canOutput = target.output != null,
                busy = outputBusy,
                message = outputMessage ?: target.message,
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
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader()
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
                Text(message, color = Accent, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
        ) {
            Text("開始擷取", fontWeight = FontWeight.SemiBold)
        }
    }
}

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
            ) { Text("取消", color = Accent) }
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
                }) { Text("刪除", color = Accent) }
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
                CircularProgressIndicator(color = Accent)
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
                    CircularProgressIndicator(color = Accent)
                }
                else -> Box {
                    Image(
                        bitmap = preview.bitmap.asImageBitmap(),
                        contentDescription = "選擇要拼接的內容區域",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(Modifier.matchParentSize()) {
                        val scale = minOf(
                            size.width / preview.width,
                            size.height / preview.height,
                        )
                        val imageSize = Size(preview.width * scale, preview.height * scale)
                        val imageTopLeft = Offset(
                            (size.width - imageSize.width) / 2f,
                            (size.height - imageSize.height) / 2f,
                        )
                        val top = imageTopLeft.y + imageSize.height * range.start
                        val bottom = imageTopLeft.y + imageSize.height * range.endInclusive
                        drawRect(
                            Paper.copy(alpha = 0.72f),
                            topLeft = imageTopLeft,
                            size = imageSize.copy(height = top - imageTopLeft.y),
                        )
                        drawRect(
                            Paper.copy(alpha = 0.72f),
                            topLeft = Offset(imageTopLeft.x, bottom),
                            size = imageSize.copy(
                                height = imageTopLeft.y + imageSize.height - bottom,
                            ),
                        )
                        drawLine(
                            color = Accent,
                            start = Offset(imageTopLeft.x, top),
                            end = Offset(imageTopLeft.x + imageSize.width, top),
                            strokeWidth = 1.dp.toPx(),
                        )
                        drawLine(
                            color = Accent,
                            start = Offset(imageTopLeft.x, bottom),
                            end = Offset(imageTopLeft.x + imageSize.width, bottom),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "保留 ${(range.start * 100).roundToInt()}% ～ ${(range.endInclusive * 100).roundToInt()}%",
            color = Ink,
        )
        RangeSlider(
            value = range,
            onValueChange = {
                if (it.endInclusive - it.start >= 0.25f) range = it
            },
            valueRange = 0f..1f,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
            ) { Text("不保存", color = Accent) }
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
private fun PreviewScreen(
    sources: List<java.io.File>,
    canOutput: Boolean,
    busy: Boolean,
    message: String?,
    onSave: (OutputFormat) -> Unit,
    onCopy: (OutputFormat) -> Unit,
    onDestroy: () -> Unit,
) {
    var format by remember(sources.size) { mutableStateOf(OutputFormat.Jpg) }

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
                    color = Accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        message ?: "目前顯示原始截圖；低信心接縫需手動確認後才能輸出。",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        color = Accent,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        itemsIndexed(sources, key = { _, source -> source.path }) { index, source ->
            PreviewImage(source, index, sources.size)
        }
        if (canOutput) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (message != null) {
                        Text(message, color = Accent, style = MaterialTheme.typography.bodyMedium)
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
                        onClick = { onSave(format) },
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
                        onClick = { onCopy(format) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("複製到剪貼簿", color = Ink) }
                }
            }
        }
        if (!busy) {
            item {
                TextButton(
                    onClick = onDestroy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(48.dp),
                ) { Text("銷毀、不保存", color = Accent) }
            }
        }
    }
}

@Composable
private fun PreviewImage(source: java.io.File, index: Int, count: Int) {
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
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("正在載入預覽", color = Quiet)
                }
                else -> Image(
                    bitmap = preview.bitmap.asImageBitmap(),
                    contentDescription = if (count == 1) "預覽圖片"
                    else "第 ${index + 1} 張，共 $count 張截圖",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
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
        ) { Text("銷毀暫存", color = Accent) }
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
private fun AppHeader(status: String? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("長截圖", style = MaterialTheme.typography.titleMedium, color = Ink)
        if (status != null) {
            Surface(
                color = Accent.copy(alpha = 0.10f),
                shape = RoundedCornerShape(99.dp),
            ) {
                Text(
                    status,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent,
                )
            }
        }
    }
}

@Composable
private fun LongScreenshotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Paper,
            background = Paper,
            onBackground = Ink,
            surface = Paper,
            onSurface = Ink,
            surfaceVariant = Color(0xFF20231E),
            onSurfaceVariant = Quiet,
            outline = Color(0xFF464942),
            error = Accent,
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
