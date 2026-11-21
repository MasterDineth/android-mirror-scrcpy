package com.mirror.client

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.atomic.AtomicInteger

class MirrorActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FPS = "extra_fps"
    }

    private lateinit var host: String
    private var port: Int = Protocol.DEFAULT_PORT
    private var bitrateBps: Int = 12_000_000
    private var fps: Int = 60

    private lateinit var mirrorRoot: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusBadge: TextView
    private lateinit var touchController: TouchOverlayController

    private var videoDecoder: VideoDecoder? = null
    private var mirrorClient: MirrorClient? = null

    private val frameCounter = AtomicInteger(0)
    private var lastFpsSampleMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()
        setContentView(R.layout.activity_mirror)

        host = intent.getStringExtra(EXTRA_HOST) ?: Protocol.DEFAULT_HOST
        port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        bitrateBps = intent.getIntExtra(EXTRA_BITRATE, 12_000_000)
        fps = intent.getIntExtra(EXTRA_FPS, 60)

        mirrorRoot = findViewById(R.id.mirrorRoot)
        surfaceView = findViewById(R.id.mirrorSurfaceView)
        statusBadge = findViewById(R.id.statusBadge)

        touchController = TouchOverlayController(
            container = mirrorRoot,
            surfaceView = surfaceView
        ) { action, pointerId, normX, normY ->
            mirrorClient?.sendTouch(action, pointerId, normX, normY)
        }

        surfaceView.holder.addCallback(this)

        // Navigation bar overlay buttons (Back, Home, Recents, Exit)
        findViewById<Button>(R.id.btnNavBack).setOnClickListener {
            mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_BACK)
        }
        findViewById<Button>(R.id.btnNavHome).setOnClickListener {
            mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_HOME)
        }
        findViewById<Button>(R.id.btnNavRecents).setOnClickListener {
            mirrorClient?.sendKeyPress(KeyEvent.KEYCODE_APP_SWITCH)
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("MirrorActivity", "surfaceCreated: surface=${holder.surface}")
        val decoder = videoDecoder
        if (decoder == null) {
            statusBadge.text = "Connecting to $host:$port..."
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
                        runOnUiThread {
                            statusBadge.text = "$host:$port • ${measuredFps} FPS"
                        }
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
                    runOnUiThread {
                        statusBadge.text = "Connected • $host:$port"
                    }
                },
                onDisconnected = { reason ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Log.e("MirrorActivity", "Disconnected: $reason")
                            Toast.makeText(this, "Disconnected: $reason", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
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
