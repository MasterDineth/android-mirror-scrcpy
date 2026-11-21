package com.mirror.client

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coroutines-based TCP client with TCP_NODELAY enabled.
 * Reads 12-byte-header H.264 NAL packets and writes 14-byte binary touch/control packets concurrently.
 */
class MirrorClient(
    private val host: String,
    private val port: Int,
    private val bitrateBps: Int,
    private val fps: Int,
    private val decoder: VideoDecoder,
    private val onConnected: () -> Unit,
    private val onDisconnected: (reason: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoingQueue = Channel<ByteArray>(capacity = 256)
    private val explicitlyDisconnected = AtomicBoolean(false)

    @Volatile
    private var socket: Socket? = null
    private var sessionJob: Job? = null

    fun connect() {
        sessionJob = scope.launch {
            var tcpSocket: Socket? = null
            try {
                var lastErr: Throwable? = null
                val targetHost = this@MirrorClient.host
                val targetPort = this@MirrorClient.port
                val endpoint = InetSocketAddress(targetHost, targetPort)

                for (attempt in 1..3) {
                    try {
                        Log.d("MirrorClient", "Connecting to $targetHost:$targetPort (resolved=${endpoint.address}) (attempt $attempt/3)...")
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.receiveBufferSize = 512 * 1024
                        s.sendBufferSize = 64 * 1024
                        s.connect(endpoint, 5_000)
                        tcpSocket = s
                        lastErr = null
                        break
                    } catch (t: Throwable) {
                        lastErr = t
                        try { tcpSocket?.close() } catch (_: Throwable) {}
                        tcpSocket = null
                        if (attempt < 3 && isActive && !explicitlyDisconnected.get()) {
                            Log.w("MirrorClient", "Attempt $attempt failed (${t.message}), retrying in 300ms...")
                            kotlinx.coroutines.delay(300)
                        }
                    }
                }
                if (lastErr != null) {
                    throw lastErr
                }

                val activeSocket = tcpSocket ?: throw IllegalStateException("Socket not connected")
                socket = activeSocket
                Log.d("MirrorClient", "Successfully connected to $host:$port")
                onConnected()

                // Send requested bitrate/FPS configuration immediately after connecting
                outgoingQueue.trySend(
                    Protocol.encodeControlPacket(
                        action = Protocol.ACTION_CONFIG_STREAM,
                        param = bitrateBps,
                        normX = fps.toFloat(),
                        normY = 0f
                    )
                )

                val writerJob = launch {
                    val out = activeSocket.getOutputStream()
                    for (packet in outgoingQueue) {
                        out.write(packet)
                        out.flush()
                    }
                }

                try {
                    readVideoStreamLoop(activeSocket)
                } finally {
                    writerJob.cancel()
                }
                if (!explicitlyDisconnected.get()) {
                    Log.d("MirrorClient", "Stream closed by host")
                    onDisconnected("Stream closed by host")
                }
            } catch (_: EOFException) {
                if (!explicitlyDisconnected.get()) {
                    Log.d("MirrorClient", "Host closed stream (EOF)")
                    onDisconnected("Host disconnected")
                }
            } catch (t: Throwable) {
                if (isActive && !explicitlyDisconnected.get()) {
                    Log.e("MirrorClient", "Connection to $host:$port failed: ${t.message}", t)
                    onDisconnected(t.message ?: t.javaClass.simpleName)
                }
            } finally {
                try {
                    tcpSocket?.close()
                } catch (_: Throwable) {
                }
                socket = null
            }
        }
    }

    private fun readVideoStreamLoop(tcpSocket: Socket) {
        val input = DataInputStream(BufferedInputStream(tcpSocket.getInputStream(), 256 * 1024))
        var payloadBuffer = ByteArray(256 * 1024)

        while (scope.isActive && !tcpSocket.isClosed) {
            val ptsUs = input.readLong()
            val length = input.readInt()
            if (length <= 0 || length > 16 * 1024 * 1024) {
                throw IllegalStateException("Invalid NAL payload length: $length")
            }
            if (payloadBuffer.size < length) {
                payloadBuffer = ByteArray(length)
            }
            input.readFully(payloadBuffer, 0, length)
            decoder.feedNalUnit(ptsUs, payloadBuffer, length)
        }
    }

    fun sendTouch(action: Short, pointerId: Int, normX: Float, normY: Float) {
        val packet = Protocol.encodeControlPacket(action, pointerId, normX, normY)
        outgoingQueue.trySend(packet)
    }

    fun sendKeyPress(keyCode: Int) {
        val packet = Protocol.encodeControlPacket(
            action = Protocol.ACTION_KEY_PRESS,
            param = keyCode,
            normX = 0f,
            normY = 0f
        )
        outgoingQueue.trySend(packet)
    }

    fun disconnect() {
        explicitlyDisconnected.set(true)
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        outgoingQueue.close()
        sessionJob?.cancel()
        scope.cancel()
    }
}
