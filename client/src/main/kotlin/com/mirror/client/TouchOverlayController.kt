package com.mirror.client

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Handles aspect-ratio scaling and letterbox offset calculation on the SurfaceView,
 * and translates single/multi-touch MotionEvents into normalized [0.0..1.0] coordinates.
 */
class TouchOverlayController(
    private val container: FrameLayout,
    private val surfaceView: SurfaceView,
    private val onTouchPacket: (action: Short, pointerId: Int, normX: Float, normY: Float) -> Unit
) : View.OnTouchListener {

    private var videoWidth: Int = 1080
    private var videoHeight: Int = 2400

    private var renderedWidth: Float = 1f
    private var renderedHeight: Float = 1f
    private var letterboxOffsetX: Float = 0f
    private var letterboxOffsetY: Float = 0f

    init {
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            recomputeViewport()
        }
        container.setOnTouchListener(this)
    }

    fun updateVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width != videoWidth || height != videoHeight) {
            videoWidth = width
            videoHeight = height
            recomputeViewport()
        }
    }

    /**
     * Computes exact fit-center aspect-ratio dimensions and letterbox offsets,
     * and resizes the centered SurfaceView to eliminate aspect distortion.
     */
    fun recomputeViewport() {
        val containerW = container.width.toFloat()
        val containerH = container.height.toFloat()
        if (containerW <= 0f || containerH <= 0f) return

        val scale = min(containerW / videoWidth.toFloat(), containerH / videoHeight.toFloat())
        renderedWidth = (videoWidth * scale).coerceAtLeast(1f)
        renderedHeight = (videoHeight * scale).coerceAtLeast(1f)
        letterboxOffsetX = (containerW - renderedWidth) * 0.5f
        letterboxOffsetY = (containerH - renderedHeight) * 0.5f

        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(renderedWidth.toInt(), renderedHeight.toInt(), Gravity.CENTER)

        val targetW = renderedWidth.toInt().coerceAtLeast(1)
        val targetH = renderedHeight.toInt().coerceAtLeast(1)
        if (params.width != targetW || params.height != targetH) {
            params.width = targetW
            params.height = targetH
            params.gravity = Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                // Ignore taps that start strictly in the black letterbox margins
                if (!isInsideVideoBounds(x, y)) return false
                emitPointer(
                    action = Protocol.ACTION_DOWN,
                    pointerId = event.getPointerId(index),
                    rawX = x,
                    rawY = y
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                // If secondary pointer lands outside the video frame, skip emitting it but retain active touch session
                if (isInsideVideoBounds(x, y)) {
                    emitPointer(
                        action = Protocol.ACTION_POINTER_DOWN,
                        pointerId = event.getPointerId(index),
                        rawX = x,
                        rawY = y
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerCount = event.pointerCount
                for (i in 0 until pointerCount) {
                    emitPointer(
                        action = Protocol.ACTION_MOVE,
                        pointerId = event.getPointerId(i),
                        rawX = event.getX(i),
                        rawY = event.getY(i)
                    )
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                emitPointer(
                    action = Protocol.ACTION_POINTER_UP,
                    pointerId = event.getPointerId(index),
                    rawX = event.getX(index),
                    rawY = event.getY(index)
                )
            }

            MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                emitPointer(
                    action = Protocol.ACTION_UP,
                    pointerId = event.getPointerId(index),
                    rawX = event.getX(index),
                    rawY = event.getY(index)
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                emitPointer(
                    action = Protocol.ACTION_CANCEL,
                    pointerId = event.getPointerId(index),
                    rawX = event.getX(index),
                    rawY = event.getY(index)
                )
            }
        }
        return true
    }

    private fun isInsideVideoBounds(x: Float, y: Float): Boolean {
        return x >= letterboxOffsetX &&
            x <= letterboxOffsetX + renderedWidth &&
            y >= letterboxOffsetY &&
            y <= letterboxOffsetY + renderedHeight
    }

    private fun emitPointer(action: Short, pointerId: Int, rawX: Float, rawY: Float) {
        val normX = ((rawX - letterboxOffsetX) / renderedWidth).coerceIn(0f, 1f)
        val normY = ((rawY - letterboxOffsetY) / renderedHeight).coerceIn(0f, 1f)
        onTouchPacket(action, pointerId, normX, normY)
    }
}
