package com.mirror.client

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class MirrorActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FPS = "extra_fps"
    }

    private lateinit var prefs: MirrorPreferences
    private lateinit var host: String
    private var port: Int = Protocol.DEFAULT_PORT
    private var bitrateBps: Int = 12_000_000
    private var fps: Int = 60

    private lateinit var mirrorRoot: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudBadge: TextView
    private lateinit var fabWrapper: LinearLayout
    private lateinit var toolboxDock: LinearLayout
    private lateinit var fabToggleBtn: FrameLayout
    private lateinit var fabChevronIcon: ImageView
    private lateinit var touchController: TouchOverlayController

    private var videoDecoder: VideoDecoder? = null
    private var mirrorClient: MirrorClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameCounter = AtomicInteger(0)
    private var lastFpsSampleMs = 0L

    private var currentFps: Int = 0
    private var currentMbps: Float = 0f
    private var currentPingMs: Int = -1
    private var isConnected: Boolean = false
    private var isToolboxOpen: Boolean = false

    private val hudFadeRunnable = Runnable {
        if (prefs.hudEnabled && prefs.hudAutoFade && prefs.hudFadeDelaySec > 0) {
            hudBadge.animate().alpha(0.20f).setDuration(350L).start()
        }
    }

    private val fabFadeRunnable = Runnable {
        if (prefs.fabEnabled && prefs.fabAutoFade && !isToolboxOpen) {
            fabWrapper.animate().alpha(0.20f).setDuration(350L).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MirrorPreferences(this)
        enableImmersiveFullscreen()
        applyKeepScreenAwake()
        setContentView(R.layout.activity_mirror)

        host = intent.getStringExtra(EXTRA_HOST) ?: prefs.hostIp
        port = intent.getIntExtra(EXTRA_PORT, prefs.port)
        bitrateBps = intent.getIntExtra(EXTRA_BITRATE, 12_000_000)
        fps = intent.getIntExtra(EXTRA_FPS, 60)

        mirrorRoot = findViewById(R.id.mirrorRoot)
        surfaceView = findViewById(R.id.mirrorSurfaceView)
        hudContainer = findViewById(R.id.hudContainer)
        hudBadge = findViewById(R.id.hudBadge)
        fabWrapper = findViewById(R.id.fabWrapper)
        toolboxDock = findViewById(R.id.toolboxDock)
        fabToggleBtn = findViewById(R.id.fabToggleBtn)
        fabChevronIcon = findViewById(R.id.fabChevronIcon)

        touchController = TouchOverlayController(
            container = mirrorRoot,
            surfaceView = surfaceView
        ) { action, pointerId, normX, normY ->
            mirrorClient?.sendTouch(action, pointerId, normX, normY)
            wakeHudFromIdle()
        }

        surfaceView.holder.addCallback(this)

        configureHudFromPrefs()
        configureFabFromPrefs()
        setupFabInteraction()
    }

    private fun applyKeepScreenAwake() {
        if (prefs.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun configureHudFromPrefs() {
        if (!prefs.hudEnabled) {
            hudContainer.visibility = View.GONE
            return
        }
        hudContainer.visibility = View.VISIBLE
        hudContainer.gravity = when (prefs.hudAlignment) {
            HudAlignment.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            HudAlignment.CENTER -> Gravity.CENTER
            HudAlignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
        }

        val soloFps = prefs.showFps && !prefs.showBitrate && !prefs.showHostAddress && !prefs.showPing
        val density = resources.displayMetrics.density
        if (soloFps) {
            hudBadge.setBackgroundResource(R.drawable.bg_hud_circle)
            hudBadge.minWidth = (40 * density).toInt()
            hudBadge.minHeight = (40 * density).toInt()
            hudBadge.setPadding(0, 0, 0, 0)
        } else {
            hudBadge.setBackgroundResource(R.drawable.bg_hud_pill)
            hudBadge.minWidth = 0
            hudBadge.minHeight = 0
            hudBadge.setPadding(
                (12 * density).toInt(),
                (6 * density).toInt(),
                (12 * density).toInt(),
                (6 * density).toInt()
            )
        }

        hudBadge.alpha = prefs.hudRestingOpacity / 100f
        renderHudText()
        scheduleHudAutoFade()
    }

    private fun renderHudText() {
        if (!prefs.hudEnabled) return
        if (!isConnected) {
            hudBadge.text = "..."
            return
        }

        val soloFps = prefs.showFps && !prefs.showBitrate && !prefs.showHostAddress && !prefs.showPing
        if (soloFps) {
            hudBadge.text = if (currentFps > 0) "$currentFps" else "--"
            return
        }

        val parts = mutableListOf<String>()
        if (prefs.showFps) {
            parts.add(if (currentFps > 0) "$currentFps FPS" else "-- FPS")
        }
        if (prefs.showBitrate) {
            val bwText = when (prefs.bitrateUnit) {
                BitrateUnit.MBPS -> String.format(Locale.US, "%.1f Mbps", currentMbps)
                BitrateUnit.MB_S -> String.format(Locale.US, "%.2f MB/s", currentMbps / 8f)
            }
            parts.add(bwText)
        }
        if (prefs.showHostAddress) {
            parts.add(host)
        }
        if (prefs.showPing) {
            parts.add(if (currentPingMs > 0) "${currentPingMs}ms" else "--ms")
        }

        hudBadge.text = if (parts.isEmpty()) "LIVE" else parts.joinToString(" • ")
    }

    private fun wakeHudFromIdle() {
        if (!prefs.hudEnabled) return
        mainHandler.removeCallbacks(hudFadeRunnable)
        hudBadge.animate().alpha(prefs.hudRestingOpacity / 100f).setDuration(150L).start()
        scheduleHudAutoFade()
    }

    private fun scheduleHudAutoFade() {
        mainHandler.removeCallbacks(hudFadeRunnable)
        if (prefs.hudEnabled && prefs.hudAutoFade && prefs.hudFadeDelaySec > 0) {
            mainHandler.postDelayed(hudFadeRunnable, prefs.hudFadeDelaySec * 1000L)
        }
    }

    private fun configureFabFromPrefs() {
        if (!prefs.fabEnabled) {
            fabWrapper.visibility = View.GONE
            return
        }
        fabWrapper.visibility = View.VISIBLE
        fabWrapper.alpha = prefs.fabRestingOpacity / 100f

        val isBottomCenter = prefs.fabAnchor == FabAnchor.BOTTOM_CENTER
        toolboxDock.orientation = if (isBottomCenter) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val lp = fabWrapper.layoutParams as FrameLayout.LayoutParams
        lp.gravity = when (prefs.fabAnchor) {
            FabAnchor.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            FabAnchor.BOTTOM_CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            FabAnchor.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            FabAnchor.RIGHT_MID -> Gravity.CENTER_VERTICAL or Gravity.END
        }
        fabWrapper.layoutParams = lp

        // Restore dragged coordinates if user previously repositioned the FAB
        if (prefs.fabDraggable && prefs.fabCustomX >= 0f && prefs.fabCustomY >= 0f) {
            fabWrapper.post {
                fabWrapper.x = prefs.fabCustomX.coerceIn(0f, (mirrorRoot.width - fabWrapper.width).toFloat().coerceAtLeast(0f))
                fabWrapper.y = prefs.fabCustomY.coerceIn(0f, (mirrorRoot.height - fabWrapper.height).toFloat().coerceAtLeast(0f))
            }
        }

        populateToolboxButtons(isBottomCenter)
        setToolboxOpen(false, animate = false)
        scheduleFabAutoFade()
    }

    private fun populateToolboxButtons(horizontal: Boolean) {
        toolboxDock.removeAllViews()
        val activeTools = prefs.getActiveTools()
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        activeTools.forEachIndexed { idx, action ->
            // Subtle divider before Shade or Back/Screenshot groups for visual clarity
            if (idx > 0 && (action == ToolboxAction.SHADE || action == ToolboxAction.BACK)) {
                val divider = View(this).apply {
                    setBackgroundColor(0x1AFFFFFF)
                    layoutParams = if (horizontal) {
                        LinearLayout.LayoutParams(dp(1), dp(24)).apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                        }
                    } else {
                        LinearLayout.LayoutParams(dp(24), dp(1)).apply {
                            topMargin = dp(4)
                            bottomMargin = dp(4)
                        }
                    }
                }
                toolboxDock.addView(divider)
            }

            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_toolbox_item_ripple)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    if (horizontal) {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    } else {
                        topMargin = dp(2)
                        bottomMargin = dp(2)
                    }
                }
            }

            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17))
                setImageResource(action.iconRes)
                setColorFilter(0xFFD4D4D8.toInt())
            }

            val label = TextView(this).apply {
                text = action.shortLabel
                textSize = 9f
                setTextColor(0xFFA1A1AA.toInt())
                setPadding(0, dp(2), 0, 0)
            }

            btn.addView(icon)
            btn.addView(label)

            btn.setOnClickListener {
                performFabHaptic(it)
                wakeFabFromIdle()
                executeToolboxAction(action)
            }

            toolboxDock.addView(btn)
        }
    }

    private fun executeToolboxAction(action: ToolboxAction) {
        when (action) {
            ToolboxAction.POWER -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_POWER)
            ToolboxAction.VOL_UP -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_VOLUME_UP)
            ToolboxAction.VOL_DOWN -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_VOLUME_DOWN)
            ToolboxAction.SHADE -> mirrorClient?.sendExpandNotifications()
            ToolboxAction.ROTATE -> mirrorClient?.sendRotateScreen()
            ToolboxAction.BACK -> mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_BACK)
            ToolboxAction.SCREENSHOT -> captureSurfaceScreenshot()
        }
    }

    private fun captureSurfaceScreenshot() {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Screenshot unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    saveBitmapToGallery(bitmap)
                } else {
                    bitmap.recycle()
                    runOnUiThread {
                        Toast.makeText(this, "Capture failed (code $copyResult)", Toast.LENGTH_SHORT).show()
                    }
                }
            }, mainHandler)
        } catch (t: Throwable) {
            bitmap.recycle()
            Toast.makeText(this, "Capture error: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        Thread {
            try {
                val filename = "RemoteMirror_${System.currentTimeMillis()}.png"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RemoteMirror")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Saved $filename", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e("MirrorActivity", "Failed to save screenshot", t)
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                bitmap.recycle()
            }
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFabInteraction() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false

        fabToggleBtn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeFabFromIdle()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = fabWrapper.x
                    startY = fabWrapper.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (prefs.fabDraggable) {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val maxX = (mirrorRoot.width - fabWrapper.width).toFloat().coerceAtLeast(0f)
                            val maxY = (mirrorRoot.height - fabWrapper.height).toFloat().coerceAtLeast(0f)
                            fabWrapper.x = (startX + dx).coerceIn(0f, maxX)
                            fabWrapper.y = (startY + dy).coerceIn(0f, maxY)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        prefs.fabCustomX = fabWrapper.x
                        prefs.fabCustomY = fabWrapper.y
                    } else {
                        performFabHaptic(v)
                        setToolboxOpen(!isToolboxOpen, animate = true)
                    }
                    scheduleFabAutoFade()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    scheduleFabAutoFade()
                    true
                }

                else -> false
            }
        }
    }

    private fun setToolboxOpen(open: Boolean, animate: Boolean) {
        isToolboxOpen = open
        if (animate) {
            fabChevronIcon.animate().rotation(if (open) 180f else 0f).setDuration(260L).start()
            if (open) {
                toolboxDock.visibility = View.VISIBLE
                toolboxDock.alpha = 0f
                toolboxDock.translationY = 24f
                toolboxDock.scaleX = 0.92f
                toolboxDock.scaleY = 0.92f
                toolboxDock.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .start()
            } else {
                toolboxDock.animate()
                    .alpha(0f)
                    .translationY(24f)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(200L)
                    .withEndAction { toolboxDock.visibility = View.GONE }
                    .start()
            }
        } else {
            fabChevronIcon.rotation = if (open) 180f else 0f
            toolboxDock.visibility = if (open) View.VISIBLE else View.GONE
        }
        scheduleFabAutoFade()
    }

    private fun wakeFabFromIdle() {
        if (!prefs.fabEnabled) return
        mainHandler.removeCallbacks(fabFadeRunnable)
        fabWrapper.animate().alpha(prefs.fabRestingOpacity / 100f).setDuration(150L).start()
    }

    private fun scheduleFabAutoFade() {
        mainHandler.removeCallbacks(fabFadeRunnable)
        if (prefs.fabEnabled && prefs.fabAutoFade && !isToolboxOpen) {
            mainHandler.postDelayed(fabFadeRunnable, prefs.fabFadeDelaySec * 1000L)
        }
    }

    private fun performFabHaptic(view: View) {
        if (prefs.hapticOnFabTap) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("MirrorActivity", "surfaceCreated: surface=${holder.surface}")
        val decoder = videoDecoder
        if (decoder == null) {
            isConnected = false
            renderHudText()
            lastFpsSampleMs = System.currentTimeMillis()
            frameCounter.set(0)

            val newDecoder = VideoDecoder(
                surface = holder.surface,
                initialWidth = 1080,
                initialHeight = 2400,
                onVideoSizeChanged = { w, h ->
                    runOnUiThread {
                        touchController.updateVideoSize(w, h)
                    }
                },
                onFrameRendered = {
                    val count = frameCounter.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsSampleMs
                    if (elapsed >= 1000L) {
                        val measuredFps = (count * 1000L / elapsed).toInt()
                        frameCounter.set(0)
                        lastFpsSampleMs = now
                        currentFps = measuredFps
                        TelemetryState.lastFps = measuredFps
                        runOnUiThread { renderHudText() }
                    }
                }
            )
            newDecoder.start()
            videoDecoder = newDecoder

            val client = MirrorClient(
                host = host,
                port = port,
                bitrateBps = bitrateBps,
                fps = fps,
                decoder = newDecoder,
                onConnected = {
                    isConnected = true
                    runOnUiThread { renderHudText() }
                },
                onDisconnected = { reason ->
                    isConnected = false
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Log.e("MirrorActivity", "Disconnected: $reason")
                            Toast.makeText(this, "Disconnected: $reason", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                },
                onThroughputSample = { bytesPerSec ->
                    val mbps = (bytesPerSec * 8f) / 1_000_000f
                    currentMbps = mbps
                    TelemetryState.lastBitrateMbps = mbps
                    runOnUiThread { renderHudText() }
                },
                onPingMeasured = { rttMs ->
                    currentPingMs = rttMs
                    TelemetryState.lastPingMs = rttMs
                    runOnUiThread { renderHudText() }
                }
            )
            mirrorClient = client
            client.connect()
        } else {
            // Surface was recreated (e.g. during layout resize): attach new surface to decoder
            decoder.setOutputSurface(holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("MirrorActivity", "surfaceChanged: ${width}x$height")
        touchController.recomputeViewport()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("MirrorActivity", "surfaceDestroyed")
        videoDecoder?.surfaceValid = false
        // Keep mirrorClient and network connection alive while surface is being recreated
    }

    override fun onDestroy() {
        Log.d("MirrorActivity", "onDestroy")
        mainHandler.removeCallbacks(hudFadeRunnable)
        mainHandler.removeCallbacks(fabFadeRunnable)
        stopSession()
        super.onDestroy()
    }

    private fun stopSession() {
        mirrorClient?.disconnect()
        mirrorClient = null
        videoDecoder?.stop()
        videoDecoder = null
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
