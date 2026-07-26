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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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

private val Paper = Color(0xFF111310)
private val Ink = Color(0xFFF2F0E9)
private val Accent = Color(0xFFFF7353)
private val Quiet = Color(0xFFAAA9A2)

private enum class PermissionStep { Overlay, Notification, Projection }

class MainActivity : ComponentActivity() {
    private var permissionStep by mutableStateOf<PermissionStep?>(null)
    private var notificationAsked = false
    private var notificationDenied by mutableStateOf(false)
    private var homeMessage by mutableStateOf<String?>(null)
    private var showCancelDialog by mutableStateOf(false)
    private var outputBusy by mutableStateOf(false)
    private var outputMessage by mutableStateOf<String?>(null)

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
            notificationDenied = !it
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
            LongScreenshotTheme {
                App(
                    status = CaptureSession.status,
                    permissionStep = permissionStep,
                    notificationDenied = notificationDenied,
                    homeMessage = homeMessage,
                    onStart = {
                        homeMessage = null
                        outputMessage = null
                        notificationAsked = false
                        continuePermissionFlow()
                    },
                    onPermission = ::requestCurrentPermission,
                    onFinish = { startService(CaptureService.actionIntent(this, CaptureService.ACTION_FINISH)) },
                    onCancel = { showCancelDialog = true },
                    outputBusy = outputBusy,
                    outputMessage = outputMessage,
                    onSave = { format -> runOutput("已存到相簿", format) {
                        CaptureOutput.saveToGallery(this, CaptureSession.sourceFile(1), format)
                    } },
                    onCopy = { format -> runOutput("已複製到剪貼簿", format) {
                        CaptureOutput.copyToClipboard(this, CaptureSession.sourceFile(1), format)
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
                                            homeMessage = "已銷毀這次截圖"
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
        permissionStep = PermissionStep.Projection
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
            PermissionStep.Projection -> {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
            null -> Unit
        }
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
                } else if (CaptureSession.destroy()) {
                    homeMessage = successMessage
                } else {
                    outputMessage = "$successMessage，但暫存清除失敗。"
                }
            }
        }, "capture-output").start()
    }
}

@Composable
private fun App(
    status: CaptureStatus,
    permissionStep: PermissionStep?,
    notificationDenied: Boolean,
    homeMessage: String?,
    onStart: () -> Unit,
    onPermission: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    outputBusy: Boolean,
    outputMessage: String?,
    onSave: (OutputFormat) -> Unit,
    onCopy: (OutputFormat) -> Unit,
) {
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        when (val target = permissionStep ?: status) {
            is PermissionStep -> PermissionScreen(target, notificationDenied, onPermission)
            CaptureStatus.Idle -> HomeScreen(homeMessage, onStart)
            CaptureStatus.Starting -> CenterStatus("正在準備", "即將顯示懸浮截圖按鈕。")
            is CaptureStatus.Capturing -> CapturingScreen(target, onFinish, onCancel)
            is CaptureStatus.Finished -> PreviewScreen(
                sources = (1..target.count).map(CaptureSession::sourceFile),
                busy = outputBusy,
                message = outputMessage,
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
private fun HomeScreen(message: String?, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader("本機處理")
        Spacer(Modifier.height(48.dp))
        Column {
            Text("擷取可捲動畫面", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "切換到目標 App，捲動畫面並點擊懸浮按鈕。",
                style = MaterialTheme.typography.bodyLarge,
                color = Quiet,
            )
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
private fun PermissionScreen(step: PermissionStep, notificationDenied: Boolean, onContinue: () -> Unit) {
    val (number, title, body, button) = when (step) {
        PermissionStep.Overlay -> listOf(
            "設定 1 / 3", "允許懸浮按鈕", "讓截圖按鈕顯示在其他 App 上方。", "前往設定",
        )
        PermissionStep.Notification -> listOf(
            "設定 2 / 3", "允許通知", "在通知中提供完成與取消操作；拒絕也能繼續。", "繼續",
        )
        PermissionStep.Projection -> listOf(
            "設定 3 / 3",
            "確認螢幕擷取",
            if (Build.VERSION.SDK_INT >= 34)
                "選擇整個螢幕或單一 App。受保護內容無法擷取。"
            else "Android 會要求你同意這次螢幕擷取。",
            "開始",
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
            if (step == PermissionStep.Projection && notificationDenied) {
                Spacer(Modifier.height(12.dp))
                Text("通知未允許；操作時請回到本 App。", color = Accent)
            }
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
private fun CapturingScreen(status: CaptureStatus.Capturing, onFinish: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
    ) {
        AppHeader("擷取中")
        Spacer(Modifier.height(48.dp))
        Column {
            Text("已擷取 ${status.count} 張", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                status.message ?: "切回要截圖的 App 繼續擷取。",
                style = MaterialTheme.typography.bodyLarge,
                color = Quiet,
            )
        }
        Spacer(Modifier.weight(1f))
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
}

@Composable
private fun PreviewScreen(
    sources: List<java.io.File>,
    busy: Boolean,
    message: String?,
    onSave: (OutputFormat) -> Unit,
    onCopy: (OutputFormat) -> Unit,
    onDestroy: () -> Unit,
) {
    var format by remember(sources.size) { mutableStateOf(OutputFormat.Jpg) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            AppHeader("預覽")
        }
        itemsIndexed(sources, key = { _, source -> source.path }) { index, source ->
            PreviewImage(source, index, sources.size)
        }
        if (sources.size > 1) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "目前顯示原始截圖；完成拼接後才會開放輸出。",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        color = Accent,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            item {
                Column {
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
                    if (message != null) {
                        Spacer(Modifier.height(14.dp))
                        Text(message, color = Accent, style = MaterialTheme.typography.bodyMedium)
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
                    Text(
                        "剪貼簿圖片最多保留 24 小時。",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Quiet,
                    )
                }
            }
        }
        if (!busy) {
            item {
                TextButton(
                    onClick = onDestroy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("銷毀、不保存", color = Accent) }
            }
        }
    }
}

@Composable
private fun PreviewImage(source: java.io.File, index: Int, count: Int) {
    val bitmap by produceState<Bitmap?>(null, source.path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(source.path) }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("第 ${index + 1} 張", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                bitmap?.let { "${it.width} × ${it.height} px" } ?: "載入中",
                style = MaterialTheme.typography.bodyMedium,
                color = Quiet,
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF20231E),
            shape = RoundedCornerShape(12.dp),
        ) {
            when (val preview = bitmap) {
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
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "第 ${index + 1} 張，共 $count 張截圖",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
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
        Column(horizontalAlignment = Alignment.Start) {
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
private fun AppHeader(status: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("長截圖", style = MaterialTheme.typography.titleMedium, color = Ink)
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
