package app.longscreenshot.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.FileOutputStream
import kotlin.math.max

class CaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("long-screenshot-capture")
    private lateinit var captureHandler: Handler
    private lateinit var guideFrames: GuideFrameScheduler
    private lateinit var windowManager: WindowManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: View? = null
    private var guide: CaptureGuideView? = null
    @Volatile private var guideTracker: CaptureGuideTracker? = null
    @Volatile private var guideGeneration = 0
    @Volatile private var trackingEnabled = false
    @Volatile private var captureReady = false
    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 0
    private var count = 0
    private var stopping = false
    private var capturedContentVisible = true

    @Volatile private var captureRequested = false
    @Volatile private var captureBusy = false
    @Volatile private var captureToken = 0

    override fun onCreate() {
        super.onCreate()
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        guideFrames = GuideFrameScheduler(
            now = SystemClock::uptimeMillis,
            post = { task, delay -> captureHandler.postDelayed(task, delay) },
            remove = { captureHandler.removeCallbacks(it) },
            consume = {
                if (trackingEnabled && !captureRequested && !captureBusy) imageReader?.let(::consumeImage)
            },
        )
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_FINISH -> finishCapture()
            ACTION_CANCEL -> cancelCapture()
            ACTION_DELETE -> deleteCapture(intent.getIntExtra(EXTRA_INDEX, 0))
            ACTION_SELECT_REGION -> selectGuideRegion()
            ACTION_RESUME -> resumeCapture()
            ACTION_UPDATE_VISIBILITY -> updateOverlayVisibility()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        releaseProjection()
        captureThread.quitSafely()
        super.onDestroy()
    }

    private fun startProjection(intent: Intent) {
        if (mediaProjection != null) return
        startForeground(
            NOTIFICATION_ID,
            notification(0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        try {
            val consent = projectionConsent(intent) ?: error("缺少螢幕擷取同意")
            val manager = getSystemService(MediaProjectionManager::class.java)
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val projection = manager.getMediaProjection(resultCode, consent)
                ?: error("無法取得螢幕擷取")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val bounds = screenBounds()
            captureWidth = bounds.width()
            captureHeight = bounds.height()
            densityDpi = resources.configuration.densityDpi
            CaptureSession.create(this)
            updateSystemInsets()
            replaceImageReader(captureWidth, captureHeight)
            virtualDisplay = projection.createVirtualDisplay(
                "LongScreenshot",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                null,
                mainHandler,
            )
            showOverlay()
            CaptureSession.status = CaptureStatus.Capturing(0)
        } catch (error: Throwable) {
            stopping = true
            releaseProjection()
            CaptureSession.destroy()
            CaptureSession.status = CaptureStatus.Failed(
                error.message ?: "無法開始螢幕擷取，請重新授權。",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun capture() {
        if (captureRequested || captureBusy || stopping) return
        if (count > 0 && (CaptureSession.contentRegion == null ||
                (CaptureSession.status as? CaptureStatus.SelectingRegion)?.forCapture == true)) {
            selectGuideRegion()
            return
        }
        if (!capturedContentVisible) {
            updateState("回到你分享的 App 再截圖。")
            return
        }
        if (Build.VERSION.SDK_INT < 34) {
            val bounds = screenBounds()
            if (bounds.width() != captureWidth || bounds.height() != captureHeight) {
                stopForSizeChange()
                return
            }
        }

        captureReady = false
        captureRequested = true
        virtualDisplay?.setSurface(null)
        captureToken += 1
        val token = captureToken
        guideGeneration += 1
        updateOverlayVisibility()
        captureHandler.postDelayed({
            if (captureRequested && captureToken == token) {
                imageReader?.let {
                    it.acquireLatestImage()?.close()
                    captureReady = true
                    virtualDisplay?.setSurface(it.surface)
                }
            }
        }, 50)
        mainHandler.postDelayed({
            if (captureRequested && captureToken == token) failCapture("截圖逾時，請再試一次。")
        }, 2_500)
    }

    private fun handleImage(reader: ImageReader) {
        if (reader !== imageReader) return
        if (!captureRequested && !captureBusy) {
            guideFrames.onFrame()
        } else {
            consumeImage(reader)
        }
    }

    private fun consumeImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        if (!captureRequested || !captureReady) {
            try {
                if (!captureRequested && trackingEnabled) trackGuide(image)
            } finally {
                image.close()
            }
            return
        }
        captureReady = false
        captureRequested = false
        captureBusy = true
        virtualDisplay?.setSurface(null)
        val result = try {
            runCatching { saveImage(image) }
        } finally {
            image.close()
        }
        mainHandler.post {
            captureBusy = false
            if (stopping) return@post
            result.fold(
                onSuccess = {
                    count += 1
                    updateBadge()
                    updateState(null)
                    if (CaptureSession.contentRegion == null) selectGuideRegion() else refreshGuide()
                    updateOverlayVisibility()
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(count))
                },
                onFailure = { error ->
                    failCapture(
                        if (error.message == "畫面受保護或為空白") error.message!! else "截圖保存失敗，請再試一次。",
                    )
                },
            )
        }
    }

    private fun saveImage(image: Image) {
        val plane = image.planes.first()
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val bitmap = if (paddedWidth == image.width) padded else {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        }
        try {
            val samples = sample(bitmap)
            check(!CaptureChecks.isNearlyBlack(samples)) { "畫面受保護或為空白" }
            val target = CaptureSession.sourceFile(count + 1)
            FileOutputStream(target).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG 寫入失敗" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sample(bitmap: Bitmap): IntArray {
        val columns = 24
        val rows = 24
        return IntArray(columns * rows) { index ->
            val x = index % columns * max(1, bitmap.width - 1) / (columns - 1)
            val y = index / columns * max(1, bitmap.height - 1) / (rows - 1)
            bitmap.getPixel(x, y)
        }
    }

    private fun failCapture(message: String) {
        captureReady = false
        captureRequested = false
        virtualDisplay?.setSurface(null)
        updateState(message)
        if (guideTracker == null) refreshGuide()
        updateOverlayVisibility()
    }

    private fun updateState(message: String?) {
        CaptureSession.status = CaptureStatus.Capturing(count, message)
    }

    private fun deleteCapture(index: Int) {
        if (stopping || captureRequested || captureBusy || index !in 1..count) return
        if (!CaptureSession.deleteSource(index, count)) {
            updateState("刪除失敗，截圖仍保留。")
            return
        }
        count -= 1
        if (count == 0) CaptureSession.contentRegion = null
        updateBadge()
        updateState(if (count == 0) "目前沒有已擷取的圖片。" else null)
        refreshGuide()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(count))
    }

    private fun finishCapture() {
        if (stopping) return
        if (captureRequested || captureBusy) {
            updateState("截圖仍在處理，請稍後再按完成。")
            return
        }
        stopping = true
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (count == 0) {
            CaptureSession.destroy()
            CaptureSession.status = CaptureStatus.Failed("尚未擷取任何畫面。")
            stopSelf()
            return
        }
        CaptureSession.status = CaptureStatus.SelectingRegion(count)
        stopSelf()
    }

    private fun cancelCapture() {
        if (stopping) return
        if (captureBusy) {
            mainHandler.postDelayed(::cancelCapture, 50)
            return
        }
        stopping = true
        releaseProjection()
        val deleted = CaptureSession.destroy()
        if (!deleted) CaptureSession.status = CaptureStatus.Failed("暫存刪除失敗，尚未宣稱銷毀。", count)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopForSizeChange() {
        stopping = true
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        CaptureSession.status = CaptureStatus.Failed("畫面尺寸已改變，已停止接受新截圖。", count)
        stopSelf()
    }

    private fun replaceImageReader(width: Int, height: Int) {
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        next.setOnImageAvailableListener(::handleImage, captureHandler)
        virtualDisplay?.apply {
            setSurface(null)
            resize(width, height, densityDpi)
        }
        imageReader?.close()
        imageReader = next
        captureWidth = width
        captureHeight = height
        updateSystemInsets()
    }

    private fun updateSystemInsets() {
        val screen = screenBounds()
        if (captureWidth != screen.width() || captureHeight != screen.height()) {
            CaptureSession.systemTopInset = resolveSystemInset(
                0,
                systemDimension("status_bar_height"),
            )
            CaptureSession.systemBottomInset = resolveSystemInset(
                0,
                systemDimension("navigation_bar_height"),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            CaptureSession.systemTopInset = resolveSystemInset(
                insets.top,
                systemDimension("status_bar_height"),
            )
            CaptureSession.systemBottomInset = resolveSystemInset(
                insets.bottom,
                systemDimension("navigation_bar_height"),
            )
            return
        }
        CaptureSession.systemTopInset = systemDimension("status_bar_height")
        CaptureSession.systemBottomInset = systemDimension("navigation_bar_height")
    }

    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id == 0) 0 else resources.getDimensionPixelSize(id)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stopping) return
            stopping = true
            releaseProjection(stopProjection = false)
            CaptureSession.status = CaptureStatus.Failed("螢幕擷取已被系統停止。", count)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || width == captureWidth && height == captureHeight) return
            if (count > 0) {
                stopForSizeChange()
            } else {
                replaceImageReader(width, height)
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            capturedContentVisible = isVisible
            updateOverlayVisibility()
        }
    }

    private fun releaseProjection(stopProjection: Boolean = true) {
        captureRequested = false
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        clearGuide()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        projection?.apply {
            unregisterCallback(projectionCallback)
            if (stopProjection) stop()
        }
    }

    private fun selectGuideRegion() {
        if (stopping || captureRequested || captureBusy || count == 0) return
        clearGuide()
        CaptureSession.status = CaptureStatus.SelectingRegion(count, forCapture = true)
        updateOverlayVisibility()
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        // The notification also opens the selector if the system blocks a background launch.
    }

    private fun resumeCapture() {
        if (stopping || count == 0) return
        updateState(
            if (CaptureSession.contentRegion == null) "按截圖鈕設定上下固定區域後繼續。"
            else "上界到虛線是重疊範圍，建議保留 15–25%；無法確認時，請往回捲一些。",
        )
        refreshGuide()
        updateOverlayVisibility()
    }

    private fun updateOverlayVisibility() {
        val visible = !stopping && !captureRequested && !captureBusy && !CaptureSession.appVisible
        overlay?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        val state = CaptureOverlayState(
            visible = visible,
            capturing = CaptureSession.status is CaptureStatus.Capturing,
            contentVisible = capturedContentVisible,
            guideReady = guideTracker != null,
        )
        guide?.visibility = if (state.showGuide) View.VISIBLE else View.INVISIBLE
        trackingEnabled = state.trackingEnabled
        captureHandler.post { guideFrames.setEnabled(state.trackingEnabled) }
        if (!captureRequested && !captureBusy) {
            virtualDisplay?.setSurface(if (state.streamFrames) imageReader?.surface else null)
        }
    }

    private fun clearGuide() {
        guideGeneration += 1
        trackingEnabled = false
        captureHandler.post { guideFrames.setEnabled(false) }
        guideTracker = null
        guide?.let { runCatching { windowManager.removeView(it) } }
        guide = null
        if (!captureRequested && !captureBusy) virtualDisplay?.setSurface(null)
    }

    private fun refreshGuide() {
        clearGuide()
        val region = CaptureSession.contentRegion ?: return
        if (stopping || count == 0) return
        val screen = screenBounds()
        val geometry = captureGuideGeometry(
            region, captureWidth, captureHeight, screen.width(), screen.height(),
            CaptureSession.systemTopInset, CaptureSession.systemBottomInset,
        )
        if (geometry == null) {
            updateState("目前擷取視窗無法對齊螢幕，暫不顯示提示線；請保留約 15–25% 的共同內容。")
            return
        }
        val view = CaptureGuideView(this)
        val params = WindowManager.LayoutParams(
            captureWidth, geometry.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = geometry.screenTop
            // Window opacity permits touch-through on Android 12+.
            alpha = 0.65f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        guide = view
        updateOverlayVisibility()
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            clearGuide()
            updateState("提示線無法顯示，截圖仍保留。")
            return
        }
        val source = CaptureSession.sourceFile(count)
        val generation = guideGeneration
        val width = captureWidth
        val height = captureHeight
        captureHandler.post {
            val result = runCatching {
                val bitmap = checkNotNull(BitmapFactory.decodeFile(source.path))
                try {
                    check(bitmap.width == width && bitmap.height == height)
                    val pixels = IntArray(width * region.height)
                    bitmap.getPixels(pixels, 0, width, 0, region.top, width, region.height)
                    sampleGuideLuma(width, region) { x, y ->
                        val color = pixels[(y - region.top) * width + x]
                        ((color ushr 16 and 0xff) * 77 + (color ushr 8 and 0xff) * 150 +
                            (color and 0xff) * 29) ushr 8
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            mainHandler.post referenceReady@{
                if (stopping || generation != guideGeneration) return@referenceReady
                guideTracker = result.getOrNull()?.let(::CaptureGuideTracker)
                if (result.isFailure) updateState("上一張位置無法讀取，截圖仍保留；可重新調整範圍。")
                updateOverlayVisibility()
            }
        }
    }

    private fun trackGuide(image: Image) {
        val tracker = guideTracker ?: return
        val region = CaptureSession.contentRegion ?: return
        val generation = guideGeneration
        val bottom = runCatching {
            require(region.bottom <= image.height)
            val plane = image.planes.first()
            val buffer = plane.buffer
            val current = sampleGuideLuma(image.width, region) { x, y ->
                val offset = y * plane.rowStride + x * plane.pixelStride
                ((buffer.get(offset).toInt() and 0xff) * 77 +
                    (buffer.get(offset + 1).toInt() and 0xff) * 150 +
                    (buffer.get(offset + 2).toInt() and 0xff) * 29) ushr 8
            }
            tracker.locate(current)
        }.getOrNull()
        mainHandler.post {
            if (!stopping && trackingEnabled && generation == guideGeneration) {
                guide?.previousBottom = bottom
            }
        }
    }

    private fun showOverlay() {
        val size = 56.dp
        val container = FrameLayout(this).apply {
            contentDescription = "擷取目前畫面"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(227, 74, 40))
            }
            elevation = 12.dp.toFloat()
        }
        val camera = ImageView(this).apply {
            setImageResource(R.drawable.ic_capture)
            contentDescription = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
        container.addView(camera, FrameLayout.LayoutParams(-1, -1))
        container.addView(
            TextView(this).apply {
                id = BADGE_ID
                text = "0"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 11f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(25, 25, 22))
                }
            },
            FrameLayout.LayoutParams(20.dp, 20.dp, Gravity.TOP or Gravity.END),
        )

        val bounds = screenBounds()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.width() - size - 18.dp
            y = (bounds.height() * 0.64f).toInt().coerceAtMost(bounds.height() - size - 48.dp)
        }
        container.setOnClickListener { capture() }
        container.setOnTouchListener(OverlayTouchListener(params))
        windowManager.addView(container, params)
        overlay = container
        updateOverlayVisibility()
    }

    private inner class OverlayTouchListener(
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val beginDrag = Runnable { dragging = true }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    mainHandler.postDelayed(beginDrag, 350)
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val bounds = screenBounds()
                    params.x = (startX + event.rawX - downX).toInt()
                        .coerceIn(0, bounds.width() - view.width)
                    params.y = (startY + event.rawY - downY).toInt()
                        .coerceIn(0, bounds.height() - view.height - 32.dp)
                    windowManager.updateViewLayout(view, params)
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(beginDrag)
                    if (!dragging) view.performClick()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(beginDrag)
                    dragging = false
                }
            }
            return true
        }
    }

    private fun updateBadge() {
        overlay?.findViewById<TextView>(BADGE_ID)?.text = count.toString()
    }

    private fun notification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val finish = PendingIntent.getService(
            this,
            1,
            actionIntent(this, ACTION_FINISH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_CONFIRM_CANCEL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle("長截圖進行中")
            .setContentText("已擷取 $count 張")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "完成", finish).build())
            .addAction(Notification.Action.Builder(null, "取消", cancel).build())
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "長截圖進行中", NotificationManager.IMPORTANCE_LOW),
        )
    }

    @Suppress("DEPRECATION")
    private fun projectionConsent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }

    companion object {
        const val ACTION_START = "app.longscreenshot.capture.START"
        const val ACTION_FINISH = "app.longscreenshot.capture.FINISH"
        const val ACTION_CANCEL = "app.longscreenshot.capture.CANCEL"
        const val ACTION_SELECT_REGION = "app.longscreenshot.capture.SELECT_REGION"
        const val ACTION_RESUME = "app.longscreenshot.capture.RESUME"
        const val ACTION_UPDATE_VISIBILITY = "app.longscreenshot.capture.UPDATE_VISIBILITY"
        const val ACTION_CONFIRM_CANCEL = "app.longscreenshot.capture.CONFIRM_CANCEL"
        private const val ACTION_DELETE = "app.longscreenshot.capture.DELETE"
        private const val EXTRA_RESULT_DATA = "projection-result-data"
        private const val EXTRA_RESULT_CODE = "projection-result-code"
        private const val EXTRA_INDEX = "capture-index"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001
        private const val BADGE_ID = 42

        fun startIntent(context: Context, resultCode: Int, data: Intent) =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_RESULT_CODE, resultCode)
            }

        fun actionIntent(context: Context, actionName: String) =
            Intent(context, CaptureService::class.java).apply { action = actionName }

        fun deleteIntent(context: Context, index: Int) =
            actionIntent(context, ACTION_DELETE).putExtra(EXTRA_INDEX, index)
    }
}

internal fun resolveSystemInset(windowInset: Int, resourceInset: Int): Int =
    max(windowInset, resourceInset)

internal class CaptureOverlayState(visible: Boolean, capturing: Boolean, contentVisible: Boolean, guideReady: Boolean) {
    val showGuide = visible && capturing && contentVisible
    val trackingEnabled = showGuide && guideReady
    // Visibility callbacks can recover only after the projection output is attached again.
    val streamFrames = visible && capturing
}
