package com.mirror.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware H.264/AVC encoder backed by MediaCodec with low-latency flags enabled.
 * Streams raw Annex-B NAL units preceded by a 12-byte header (8-byte PTS, 4-byte length).
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    @Volatile private var bitrateBps: Int = 12_000_000,
    @Volatile private var fps: Int = 60
) {
    private val running = AtomicBoolean(false)
    private val reconfigureRequested = AtomicBoolean(false)
    @Volatile private var activeCodec: MediaCodec? = null

    fun requestReconfigure(newBitrateBps: Int, newFps: Int) {
        val clampedBitrate = newBitrateBps.coerceIn(1_000_000, 50_000_000)
        val clampedFps = newFps.coerceIn(15, 120)
        val fpsChanged = clampedFps != fps
        val bitrateChanged = clampedBitrate != bitrateBps

        if (fpsChanged || bitrateChanged) {
            bitrateBps = clampedBitrate
            fps = clampedFps

            // If only bitrate changed and codec is actively running, update dynamically without destroying VirtualDisplay
            if (!fpsChanged && activeCodec != null) {
                try {
                    val params = android.os.Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clampedBitrate)
                    }
                    activeCodec?.setParameters(params)
                    println("[ScreenEncoder] Dynamically updated bitrate to ${clampedBitrate / 1_000_000} Mbps (zero flicker)")
                    return
                } catch (t: Throwable) {
                    System.err.println("[ScreenEncoder] Dynamic bitrate update unsupported (${t.message}); triggering full restart")
                }
            }

            reconfigureRequested.set(true)
            println("[ScreenEncoder] Full encoder reconfigure requested: ${clampedBitrate / 1_000_000} Mbps @ $clampedFps FPS")
        }
    }

    fun stop() {
        running.set(false)
    }

    /**
     * Runs the blocking encode-and-stream loop until [stop] is called or the socket closes.
     */
    fun streamTo(out: DataOutputStream) {
        running.set(true)

        while (running.get()) {
            reconfigureRequested.set(false)
            val displayBridge = VirtualDisplayBridge()
            var codec: MediaCodec? = null
            var inputSurface: Surface? = null

            try {
                val deviceDims = VirtualDisplayBridge.queryDefaultDisplaySize(width, height)
                val encodeWidth = alignTo16(width)
                val encodeHeight = alignTo16(height)

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val format = buildVideoFormat(encodeWidth, encodeHeight, bitrateBps, fps)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()

                displayBridge.startMirroring(
                    targetSurface = inputSurface,
                    deviceWidth = deviceDims.width,
                    deviceHeight = deviceDims.height,
                    videoWidth = encodeWidth,
                    videoHeight = encodeHeight
                )

                codec.start()
                activeCodec = codec
                println(
                    "[ScreenEncoder] Started H.264 encoder ${encodeWidth}x${encodeHeight} " +
                        "@ ${fps}fps, ${bitrateBps / 1_000_000} Mbps (KEY_LOW_LATENCY=1)"
                )

                drainEncoderLoop(codec, out)
            } finally {
                activeCodec = null
                try {
                    codec?.stop()
                } catch (_: Throwable) {
                }
                try {
                    codec?.release()
                } catch (_: Throwable) {
                }
                try {
                    inputSurface?.release()
                } catch (_: Throwable) {
                }
                displayBridge.close()
            }

            if (!reconfigureRequested.get()) {
                break
            }
        }
    }

    private fun drainEncoderLoop(codec: MediaCodec, out: DataOutputStream) {
        val bufferInfo = MediaCodec.BufferInfo()
        var scratch = ByteArray(256 * 1024)

        while (running.get() && !reconfigureRequested.get()) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 20_000L)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val size = bufferInfo.size
                        if (scratch.size < size) {
                            scratch = ByteArray(size)
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + size)
                        outputBuffer.get(scratch, 0, size)

                        val isCodecConfig =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val pts = if (isCodecConfig) {
                            Protocol.PTS_CODEC_CONFIG
                        } else {
                            bufferInfo.presentationTimeUs
                        }

                        Protocol.writeVideoPacket(out, pts, scratch, 0, size)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    println("[ScreenEncoder] Output format changed: ${codec.outputFormat}")
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Normal timeout when screen is static; loop checks running/reconfigure flags.
                }
            }
        }
    }

    private fun buildVideoFormat(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrate: Int,
        frameRate: Int
    ): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
            // Keep producing periodic frames on static screens so client decoder never stalls
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
    }

    private fun alignTo16(value: Int): Int = (value and 15.inv()).coerceAtLeast(16)
}
