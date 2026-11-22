package com.mirror.server

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire protocol shared between :server and :client.
 *
 * Video / telemetry stream (Server -> Client):
 *   12-byte header (big-endian):
 *     - Bytes 0..7  : Long  (presentationTimeUs; PTS_CODEC_CONFIG = -1L for SPS/PPS,
 *                            PTS_PONG = -2L for 4-byte ping echo)
 *     - Bytes 8..11 : Int   (payloadLength in bytes)
 *   Followed immediately by `payloadLength` payload bytes.
 *
 * Control stream (Client -> Server):
 *   Fixed 14-byte packet (big-endian):
 *     - Bytes 0..1  : Short (action code)
 *     - Bytes 2..5  : Int   (pointerId, keyCode, bitrateBps, or pingToken)
 *     - Bytes 6..9  : Float (normalized X in [0.0, 1.0], or target FPS for config)
 *     - Bytes 10..13: Float (normalized Y in [0.0, 1.0])
 */
object Protocol {
    const val DEFAULT_PORT = 27183
    const val VIDEO_HEADER_SIZE = 12
    const val CONTROL_PACKET_SIZE = 14

    /** Sentinel PTS indicating MediaCodec.BUFFER_FLAG_CODEC_CONFIG (SPS/PPS). */
    const val PTS_CODEC_CONFIG = -1L

    /** Sentinel PTS indicating a 4-byte ping echo payload (big-endian Int pingToken). */
    const val PTS_PONG = -2L

    // Touch actions (matching Android MotionEvent masked action values)
    const val ACTION_DOWN: Short = 0
    const val ACTION_UP: Short = 1
    const val ACTION_MOVE: Short = 2
    const val ACTION_CANCEL: Short = 3
    const val ACTION_POINTER_DOWN: Short = 5
    const val ACTION_POINTER_UP: Short = 6

    // Control actions carried in the same fixed 14-byte frame
    const val ACTION_KEY_PRESS: Short = 100
    const val ACTION_CONFIG_STREAM: Short = 101
    const val ACTION_EXPAND_NOTIFICATIONS: Short = 102
    const val ACTION_ROTATE_SCREEN: Short = 103
    const val ACTION_PING: Short = 104

    data class ControlPacket(
        val action: Short,
        val param: Int,
        val normX: Float,
        val normY: Float
    )

    fun writeVideoPacket(
        out: DataOutputStream,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ) {
        out.writeLong(ptsUs)
        out.writeInt(length)
        out.write(payload, offset, length)
        out.flush()
    }

    fun writePongPacket(out: DataOutputStream, pingToken: Int) {
        out.writeLong(PTS_PONG)
        out.writeInt(4)
        out.writeInt(pingToken)
        out.flush()
    }

    fun readControlPacket(input: DataInputStream, scratch: ByteArray): ControlPacket {
        input.readFully(scratch, 0, CONTROL_PACKET_SIZE)
        val buf = ByteBuffer.wrap(scratch, 0, CONTROL_PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        return ControlPacket(
            action = buf.short,
            param = buf.int,
            normX = buf.float,
            normY = buf.float
        )
    }
}
