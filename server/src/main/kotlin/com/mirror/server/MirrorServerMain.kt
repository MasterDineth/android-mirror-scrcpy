package com.mirror.server

import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Headless root server entry point launched via `app_process`.
 *
 * Example invocation:
 *   CLASSPATH=/data/local/tmp/mirror-server.apk /system/bin/app_process /system/bin \
 *     com.mirror.server.MirrorServerMain --port 27183 --width 1080 --height 2400 --bitrate 12000000 --fps 60
 */
object MirrorServerMain {

    private data class ServerConfig(
        val port: Int = Protocol.DEFAULT_PORT,
        val width: Int = 1080,
        val height: Int = 2400,
        val bitrateBps: Int = 12_000_000,
        val fps: Int = 60
    )

    @Volatile
    private var activeSession: ActiveSession? = null

    @JvmStatic
    fun main(args: Array<String>) {
        // Prepare a Looper on the main thread for Android framework IPC calls
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }

        VirtualDisplayBridge.bypassHiddenApis()

        val config = parseArgs(args)
        val liveDims = VirtualDisplayBridge.queryDefaultDisplaySize(config.width, config.height)
        println(
            "[MirrorServer] Starting on TCP port ${config.port} " +
                "(host display=${liveDims.width}x${liveDims.height}, " +
                "stream=${config.width}x${config.height} @ ${config.fps}fps, ${config.bitrateBps / 1_000_000} Mbps)"
        )

        val inputInjector = InputInjector(liveDims.width, liveDims.height)

        try {
            ServerSocket().use { serverSocket ->
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress("0.0.0.0", config.port), 16)
                println("[MirrorServer] Listening on 0.0.0.0:${config.port} (TCP_NODELAY enabled per client)")

                while (true) {
                    val clientSocket = serverSocket.accept()
                    // Preempt any existing active session so the incoming client connects immediately
                    activeSession?.let { old ->
                        println("[MirrorServer] Preempting previous session from ${old.socket.remoteSocketAddress}")
                        old.close()
                    }

                    val encoder = ScreenEncoder(
                        width = config.width,
                        height = config.height,
                        bitrateBps = config.bitrateBps,
                        fps = config.fps
                    )

                    val session = ActiveSession(
                        socket = clientSocket,
                        encoder = encoder,
                        config = config,
                        inputInjector = inputInjector
                    )
                    activeSession = session
                    session.start()
                }
            }
        } catch (t: Throwable) {
            System.err.println("[MirrorServer] Fatal server error on port ${config.port}: ${t.message}")
            t.printStackTrace()
        }
    }

    private class ActiveSession(
        val socket: Socket,
        val encoder: ScreenEncoder,
        val config: ServerConfig,
        val inputInjector: InputInjector
    ) {
        @Volatile
        private var controlThread: Thread? = null
        @Volatile
        private var workerThread: Thread? = null
        private val streamWriteLock = Any()

        fun start() {
            workerThread = Thread({
                runSession()
            }, "mirror-session-worker").apply {
                start()
            }
        }

        fun close() {
            encoder.stop()
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            controlThread?.interrupt()
            workerThread?.interrupt()
        }

        private fun runSession() {
            val remoteAddr = socket.remoteSocketAddress
            println("[MirrorServer] Client connected: $remoteAddr")

            socket.tcpNoDelay = true
            socket.sendBufferSize = 512 * 1024
            socket.receiveBufferSize = 64 * 1024

            // Refresh host display dimensions in case orientation changed between sessions
            val dims = VirtualDisplayBridge.queryDefaultDisplaySize(config.width, config.height)
            inputInjector.displayWidth = dims.width
            inputInjector.displayHeight = dims.height

            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 128 * 1024))

            val ct = Thread({
                val scratch = ByteArray(Protocol.CONTROL_PACKET_SIZE)
                try {
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 4096))
                    while (!socket.isClosed) {
                        val packet = Protocol.readControlPacket(input, scratch)
                        when (packet.action) {
                            Protocol.ACTION_KEY_PRESS -> {
                                inputInjector.injectKeyPress(packet.param)
                            }
                            Protocol.ACTION_CONFIG_STREAM -> {
                                encoder.requestReconfigure(
                                    newBitrateBps = packet.param,
                                    newFps = packet.normX.toInt()
                                )
                            }
                            Protocol.ACTION_EXPAND_NOTIFICATIONS -> {
                                inputInjector.expandNotificationShade()
                            }
                            Protocol.ACTION_ROTATE_SCREEN -> {
                                inputInjector.toggleScreenRotation()
                            }
                            Protocol.ACTION_PING -> {
                                synchronized(streamWriteLock) {
                                    Protocol.writePongPacket(out, packet.param)
                                }
                            }
                            else -> {
                                inputInjector.handleTouchPacket(packet)
                            }
                        }
                    }
                } catch (_: EOFException) {
                    println("[MirrorServer] Client $remoteAddr closed control stream")
                } catch (t: Throwable) {
                    if (!socket.isClosed) {
                        System.err.println("[MirrorServer] Control reader error: ${t.message}")
                    }
                } finally {
                    encoder.stop()
                    inputInjector.resetPointers()
                }
            }, "mirror-control-reader")
            ct.isDaemon = true
            ct.start()
            controlThread = ct

            try {
                encoder.streamTo(out, streamWriteLock)
            } catch (t: Throwable) {
                println("[MirrorServer] Video stream ended for $remoteAddr: ${t.message}")
            } finally {
                encoder.stop()
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
                ct.join(1000L)
                inputInjector.resetPointers()
                if (activeSession === this) {
                    activeSession = null
                }
                println("[MirrorServer] Session closed: $remoteAddr")
            }
        }
    }

    private fun parseArgs(args: Array<String>): ServerConfig {
        var port = Protocol.DEFAULT_PORT
        var width = 1080
        var height = 2400
        var bitrate = 12_000_000
        var fps = 60

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
                "--width" -> width = args.getOrNull(++i)?.toIntOrNull() ?: width
                "--height" -> height = args.getOrNull(++i)?.toIntOrNull() ?: height
                "--bitrate" -> bitrate = args.getOrNull(++i)?.toIntOrNull() ?: bitrate
                "--fps" -> fps = args.getOrNull(++i)?.toIntOrNull() ?: fps
            }
            i++
        }
        return ServerConfig(port, width, height, bitrate, fps)
    }
}
