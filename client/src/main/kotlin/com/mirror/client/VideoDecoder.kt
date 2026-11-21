package com.mirror.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Zero-copy hardware H.264/AVC decoder rendering directly onto a SurfaceView's Surface.
 */
class VideoDecoder(
    private var surface: Surface,
    private val initialWidth: Int = 1080,
    private val initialHeight: Int = 2400,
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onFrameRendered: () -> Unit = {}
) {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile
    private var started = false
    @Volatile
    var surfaceValid = true

    @Synchronized
    fun start() {
        if (started) return
        Log.d("VideoDecoder", "Starting MediaCodec decoder ${initialWidth}x${initialHeight}")
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            initialWidth,
            initialHeight
        ).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        // Passing the Surface directly enables zero-copy hardware GPU output
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
        started = true
        surfaceValid = true
        Log.d("VideoDecoder", "MediaCodec decoder started successfully")
    }

    @Synchronized
    fun setOutputSurface(newSurface: Surface) {
        this.surface = newSurface
        this.surfaceValid = true
        val dec = codec ?: return
        if (started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                dec.setOutputSurface(newSurface)
                Log.d("VideoDecoder", "Attached new Surface via setOutputSurface")
            } catch (t: Throwable) {
                Log.e("VideoDecoder", "Failed to setOutputSurface", t)
            }
        }
    }

    /**
     * Feeds a single H.264 NAL unit packet into the hardware decoder and immediately
     * releases any decoded frames to the Surface for minimum latency.
     */
    @Synchronized
    fun feedNalUnit(ptsUs: Long, payload: ByteArray, length: Int) {
        val decoder = codec ?: return
        if (!started || length <= 0) return

        // Drain any already available decoded frames to keep input buffers free
        drainAvailableOutputs(decoder)

        val isConfig = ptsUs == Protocol.PTS_CODEC_CONFIG
        val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        val presentationTimeUs = if (isConfig) 0L else ptsUs

        // Queue input NAL unit
        val inputIndex = decoder.dequeueInputBuffer(10_000L)
        if (inputIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                val copyLen = length.coerceAtMost(inputBuffer.remaining())
                inputBuffer.put(payload, 0, copyLen)
                decoder.queueInputBuffer(inputIndex, 0, copyLen, presentationTimeUs, flags)
            }
        }

        // Drain output frames immediately (zero-copy render = true)
        drainAvailableOutputs(decoder)
    }

    private fun drainAvailableOutputs(decoder: MediaCodec) {
        while (started) {
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            when {
                outputIndex >= 0 -> {
                    val render = surfaceValid
                    decoder.releaseOutputBuffer(outputIndex, render)
                    if (render) {
                        onFrameRendered()
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = decoder.outputFormat
                    Log.d("VideoDecoder", "Decoder output format changed: $fmt")
                    val width = resolveCroppedWidth(fmt)
                    val height = resolveCroppedHeight(fmt)
                    if (width > 0 && height > 0) {
                        onVideoSizeChanged(width, height)
                    }
                }

                else -> break
            }
        }
    }

    @Synchronized
    fun stop() {
        started = false
        val decoder = codec
        codec = null
        if (decoder != null) {
            try {
                decoder.stop()
            } catch (_: Throwable) {
            }
            try {
                decoder.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveCroppedWidth(fmt: MediaFormat): Int {
        return if (fmt.containsKey("crop-left") && fmt.containsKey("crop-right")) {
            fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
        } else if (fmt.containsKey(MediaFormat.KEY_WIDTH)) {
            fmt.getInteger(MediaFormat.KEY_WIDTH)
        } else {
            initialWidth
        }
    }

    private fun resolveCroppedHeight(fmt: MediaFormat): Int {
        return if (fmt.containsKey("crop-top") && fmt.containsKey("crop-bottom")) {
            fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
        } else if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) {
            fmt.getInteger(MediaFormat.KEY_HEIGHT)
        } else {
            initialHeight
        }
    }
}
