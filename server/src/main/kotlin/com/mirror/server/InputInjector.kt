package com.mirror.server

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Reconstructs multi-touch MotionEvents and navigation KeyEvents from 14-byte binary
 * control packets and injects them into the Android system via InputManagerGlobal
 * (Android 14–16) or InputManager (fallback).
 */
class InputInjector(
    @Volatile var displayWidth: Int,
    @Volatile var displayHeight: Int
) {
    companion object {
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val MAX_POINTERS = 10
    }

    private val inputManagerInstance: Any
    private val injectInputEventMethod: Method

    private val activePointers = LinkedHashMap<Int, PointF>()
    private var downTimeMs: Long = 0L

    init {
        val resolved = resolveInputManager()
        inputManagerInstance = resolved.first
        injectInputEventMethod = resolved.second
    }

    private fun resolveInputManager(): Pair<Any, Method> {
        // Android 14–17 (API 34–37) moved injectInputEvent to android.hardware.input.InputManagerGlobal
        try {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInst = globalClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
            val instance = getInst.invoke(null)
            val method = globalClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            println("[InputInjector] Using InputManagerGlobal.injectInputEvent")
            return Pair(instance!!, method)
        } catch (t: Throwable) {
            System.err.println("[InputInjector] InputManagerGlobal unavailable (${t.message}); trying InputManager")
        }

        val imClass = Class.forName("android.hardware.input.InputManager")
        val getInst = imClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
        val instance = getInst.invoke(null)
        val method = imClass.getDeclaredMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        println("[InputInjector] Using InputManager.injectInputEvent")
        return Pair(instance!!, method)
    }

    @Synchronized
    fun handleTouchPacket(packet: Protocol.ControlPacket) {
        val x = (packet.normX.coerceIn(0f, 1f)) * displayWidth
        val y = (packet.normY.coerceIn(0f, 1f)) * displayHeight
        val pointerId = packet.param
        val now = SystemClock.uptimeMillis()

        when (packet.action) {
            Protocol.ACTION_DOWN -> {
                activePointers.clear()
                downTimeMs = now
                activePointers[pointerId] = PointF(x, y)
                injectCurrentMotion(MotionEvent.ACTION_DOWN, now)
            }

            Protocol.ACTION_POINTER_DOWN -> {
                if (activePointers.isEmpty()) {
                    downTimeMs = now
                    activePointers[pointerId] = PointF(x, y)
                    injectCurrentMotion(MotionEvent.ACTION_DOWN, now)
                    return
                }
                if (activePointers.size < MAX_POINTERS) {
                    activePointers[pointerId] = PointF(x, y)
                    val index = indexOfPointer(pointerId)
                    if (index >= 0) {
                        val action = MotionEvent.ACTION_POINTER_DOWN or
                            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        injectCurrentMotion(action, now)
                    }
                }
            }

            Protocol.ACTION_MOVE -> {
                if (activePointers.isEmpty()) {
                    downTimeMs = now
                    activePointers[pointerId] = PointF(x, y)
                    injectCurrentMotion(MotionEvent.ACTION_DOWN, now)
                } else {
                    activePointers[pointerId] = PointF(x, y)
                    injectCurrentMotion(MotionEvent.ACTION_MOVE, now)
                }
            }

            Protocol.ACTION_POINTER_UP -> {
                if (activePointers.containsKey(pointerId)) {
                    activePointers[pointerId] = PointF(x, y)
                    val index = indexOfPointer(pointerId)
                    val action = if (activePointers.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_POINTER_UP or
                            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                    injectCurrentMotion(action, now)
                    activePointers.remove(pointerId)
                }
            }

            Protocol.ACTION_UP -> {
                if (activePointers.isEmpty()) return
                activePointers[pointerId] = PointF(x, y)
                if (activePointers.size > 1) {
                    val index = indexOfPointer(pointerId)
                    val action = MotionEvent.ACTION_POINTER_UP or
                        (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    injectCurrentMotion(action, now)
                    activePointers.remove(pointerId)
                } else {
                    injectCurrentMotion(MotionEvent.ACTION_UP, now)
                    activePointers.clear()
                }
            }

            Protocol.ACTION_CANCEL -> {
                if (activePointers.isNotEmpty()) {
                    injectCurrentMotion(MotionEvent.ACTION_CANCEL, now)
                    activePointers.clear()
                }
            }
        }
    }

    /**
     * Injects a full key press (ACTION_DOWN followed by ACTION_UP) for Back, Home, or Recents.
     */
    @Synchronized
    fun injectKeyPress(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        val upEvent = KeyEvent(
            now,
            now + 10L,
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        injectEvent(downEvent)
        injectEvent(upEvent)
    }

    @Synchronized
    fun resetPointers() {
        if (activePointers.isNotEmpty()) {
            injectCurrentMotion(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis())
            activePointers.clear()
        }
    }

    private fun indexOfPointer(pointerId: Int): Int {
        var i = 0
        for (id in activePointers.keys) {
            if (id == pointerId) return i
            i++
        }
        return -1
    }

    private fun injectCurrentMotion(action: Int, eventTime: Long) {
        val count = activePointers.size
        if (count == 0) return

        val props = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }

        var idx = 0
        for ((id, point) in activePointers) {
            props[idx].id = id
            props[idx].toolType = MotionEvent.TOOL_TYPE_FINGER

            coords[idx].x = point.x
            coords[idx].y = point.y
            coords[idx].pressure = 1.0f
            coords[idx].size = 1.0f
            idx++
        }

        val event = MotionEvent.obtain(
            downTimeMs,
            eventTime,
            action,
            count,
            props,
            coords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        try {
            injectEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun injectEvent(event: InputEvent) {
        try {
            injectInputEventMethod.invoke(
                inputManagerInstance,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            )
        } catch (t: Throwable) {
            System.err.println("[InputInjector] injectInputEvent failed: ${t.cause?.message ?: t.message}")
        }
    }
}
