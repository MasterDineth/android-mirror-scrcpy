package com.mirror.server

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Mirrors Display.DEFAULT_DISPLAY onto a MediaCodec input Surface using HiddenApiBypass
 * and hidden Android framework APIs.
 *
 * On Android 14–16 (API 34–36), DisplayManager.createVirtualDisplay(String, int, int, int, Surface)
 * is the primary mirroring path (SurfaceControl.createDisplay was removed in AOSP 14+).
 * A SurfaceControl reflection path is also retained as a fallback for custom ROMs or older APIs.
 */
class VirtualDisplayBridge : AutoCloseable {

    data class DisplayDimensions(val width: Int, val height: Int, val rotation: Int)

    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceControlToken: IBinder? = null

    companion object {
        private const val DISPLAY_NAME = "mirror-scrcpy"

        fun bypassHiddenApis() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    HiddenApiBypass.addHiddenApiExemptions("L")
                } catch (t: Throwable) {
                    System.err.println("[VirtualDisplayBridge] HiddenApiBypass warning: ${t.message}")
                }
            }
        }

        /**
         * Queries the real logical size of Display.DEFAULT_DISPLAY via DisplayManagerGlobal,
         * falling back to the configured default (1080x2400) if unavailable.
         */
        fun queryDefaultDisplaySize(fallbackWidth: Int, fallbackHeight: Int): DisplayDimensions {
            return try {
                val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val dmg = dmgClass.getMethod("getInstance").invoke(null)
                val displayInfo = dmgClass
                    .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                    .invoke(dmg, Display.DEFAULT_DISPLAY)
                    ?: return DisplayDimensions(fallbackWidth, fallbackHeight, 0)

                val infoClass = displayInfo.javaClass
                val w = infoClass.getField("logicalWidth").getInt(displayInfo)
                val h = infoClass.getField("logicalHeight").getInt(displayInfo)
                val rot = infoClass.getField("rotation").getInt(displayInfo)
                if (w > 0 && h > 0) {
                    DisplayDimensions(w, h, rot)
                } else {
                    DisplayDimensions(fallbackWidth, fallbackHeight, 0)
                }
            } catch (t: Throwable) {
                System.err.println(
                    "[VirtualDisplayBridge] Could not query DisplayInfo (${t.message}); " +
                        "using ${fallbackWidth}x${fallbackHeight}"
                )
                DisplayDimensions(fallbackWidth, fallbackHeight, 0)
            }
        }
    }

    /**
     * Starts mirroring Display.DEFAULT_DISPLAY into [targetSurface].
     */
    fun startMirroring(
        targetSurface: Surface,
        deviceWidth: Int,
        deviceHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ) {
        close()

        // Primary path for Android 14–16 (API 34–36): hidden static DisplayManager.createVirtualDisplay
        if (tryCreateDisplayManagerMirror(targetSurface, videoWidth, videoHeight)) {
            println("[VirtualDisplayBridge] Mirroring active via DisplayManager.createVirtualDisplay")
            return
        }

        // Fallback path: SurfaceControl.createDisplay + transaction reflection
        if (tryCreateSurfaceControlMirror(targetSurface, deviceWidth, deviceHeight, videoWidth, videoHeight)) {
            println("[VirtualDisplayBridge] Mirroring active via SurfaceControl reflection")
            return
        }

        throw IllegalStateException(
            "Failed to create virtual display mirror on API ${Build.VERSION.SDK_INT} " +
                "via both DisplayManager and SurfaceControl."
        )
    }

    private fun tryCreateDisplayManagerMirror(
        surface: Surface,
        width: Int,
        height: Int
    ): Boolean {
        // Attempt 1: Hidden static DisplayManager.createVirtualDisplay(String, int, int, int, Surface)
        try {
            val method = DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java
            )
            method.isAccessible = true
            val vd = method.invoke(
                null,
                DISPLAY_NAME,
                width,
                height,
                Display.DEFAULT_DISPLAY,
                surface
            ) as? VirtualDisplay
            if (vd != null) {
                virtualDisplay = vd
                return true
            }
        } catch (t: Throwable) {
            System.err.println("[VirtualDisplayBridge] Static DisplayManager.createVirtualDisplay unavailable: ${t.cause?.message ?: t.message}")
        }

        // Attempt 2: Search for any static createVirtualDisplay overload (e.g. including flags)
        try {
            for (m in DisplayManager::class.java.methods) {
                if (m.name == "createVirtualDisplay" && java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    val pts = m.parameterTypes
                    if (pts.isNotEmpty() && pts[0] == String::class.java) {
                        m.isAccessible = true
                        val args = when (pts.size) {
                            5 -> arrayOf(DISPLAY_NAME, width, height, Display.DEFAULT_DISPLAY, surface)
                            6 -> arrayOf(DISPLAY_NAME, width, height, Display.DEFAULT_DISPLAY, surface, 0)
                            else -> null
                        }
                        if (args != null) {
                            val vd = m.invoke(null, *args) as? VirtualDisplay
                            if (vd != null) {
                                virtualDisplay = vd
                                return true
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            System.err.println("[VirtualDisplayBridge] Static method search fallback failed: ${t.cause?.message ?: t.message}")
        }

        return false
    }

    private fun tryCreateSurfaceControlMirror(
        surface: Surface,
        deviceWidth: Int,
        deviceHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Boolean {
        return try {
            val scClass = Class.forName("android.view.SurfaceControl")
            val createDisplay = scClass.getMethod(
                "createDisplay",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            // secure = true allows capturing DRM/secure surfaces when running as root
            val token = createDisplay.invoke(null, DISPLAY_NAME, true) as IBinder

            val openTx = scClass.getMethod("openTransaction")
            val closeTx = scClass.getMethod("closeTransaction")
            val setSurface = scClass.getMethod(
                "setDisplaySurface",
                IBinder::class.java,
                Surface::class.java
            )
            val setProjection = scClass.getMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            )
            val setLayerStack = scClass.getMethod(
                "setDisplayLayerStack",
                IBinder::class.java,
                Int::class.javaPrimitiveType
            )

            val layerStackRect = Rect(0, 0, deviceWidth, deviceHeight)
            val displayRect = Rect(0, 0, videoWidth, videoHeight)

            openTx.invoke(null)
            try {
                setSurface.invoke(null, token, surface)
                setProjection.invoke(null, token, 0, layerStackRect, displayRect)
                setLayerStack.invoke(null, token, 0)
            } finally {
                closeTx.invoke(null)
            }

            surfaceControlToken = token
            true
        } catch (t: Throwable) {
            System.err.println("[VirtualDisplayBridge] SurfaceControl fallback failed: ${t.cause?.message ?: t.message}")
            false
        }
    }

    override fun close() {
        virtualDisplay?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
            virtualDisplay = null
        }

        surfaceControlToken?.let { token ->
            try {
                val scClass = Class.forName("android.view.SurfaceControl")
                scClass.getMethod("destroyDisplay", IBinder::class.java).invoke(null, token)
            } catch (_: Throwable) {
            }
            surfaceControlToken = null
        }
    }
}
