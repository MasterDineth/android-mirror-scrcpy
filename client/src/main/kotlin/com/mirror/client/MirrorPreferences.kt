package com.mirror.client

import android.content.Context
import android.content.SharedPreferences

enum class HudAlignment(val key: String) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right");

    companion object {
        fun fromKey(k: String?): HudAlignment = entries.firstOrNull { it.key == k } ?: LEFT
    }
}

enum class BitrateUnit(val key: String) {
    MBPS("Mbps"),
    MB_S("MB/s");

    companion object {
        fun fromKey(k: String?): BitrateUnit = entries.firstOrNull { it.key == k } ?: MBPS
    }
}

enum class FabAnchor(val key: String) {
    BOTTOM_RIGHT("bottom_right"),
    BOTTOM_CENTER("bottom_center"),
    BOTTOM_LEFT("bottom_left"),
    RIGHT_MID("right_mid");

    companion object {
        fun fromKey(k: String?): FabAnchor = entries.firstOrNull { it.key == k } ?: BOTTOM_RIGHT
    }
}

enum class ToolboxAction(
    val id: String,
    val title: String,
    val subtitle: String,
    val shortLabel: String,
    val iconRes: Int,
    val accentColor: Int,
    val badgeBgColor: Int,
    val defaultEnabled: Boolean
) {
    POWER(
        id = "power",
        title = "Screen Off / Sleep",
        subtitle = "Instant remote display blanking",
        shortLabel = "Power",
        iconRes = R.drawable.ic_power,
        accentColor = 0xFFFFB4AB.toInt(),
        badgeBgColor = 0x6693000A,
        defaultEnabled = true
    ),
    VOL_UP(
        id = "vol_up",
        title = "Volume Up",
        subtitle = "Increase target audio",
        shortLabel = "Vol +",
        iconRes = R.drawable.ic_volume_up,
        accentColor = 0xFFADC6FF.toInt(),
        badgeBgColor = 0x334D8EFF,
        defaultEnabled = true
    ),
    VOL_DOWN(
        id = "vol_down",
        title = "Volume Down",
        subtitle = "Decrease target audio",
        shortLabel = "Vol -",
        iconRes = R.drawable.ic_volume_down,
        accentColor = 0xFFADC6FF.toInt(),
        badgeBgColor = 0x334D8EFF,
        defaultEnabled = true
    ),
    SHADE(
        id = "shade",
        title = "Notification Shade",
        subtitle = "Pull down notification shade",
        shortLabel = "Shade",
        iconRes = R.drawable.ic_shade,
        accentColor = 0xFF7BD0FF.toInt(),
        badgeBgColor = 0x4D009BD1,
        defaultEnabled = true
    ),
    ROTATE(
        id = "rotate",
        title = "Rotate Screen",
        subtitle = "Force landscape / portrait sync",
        shortLabel = "Rotate",
        iconRes = R.drawable.ic_rotate,
        accentColor = 0xFFC0C1FF.toInt(),
        badgeBgColor = 0x4D3131C0,
        defaultEnabled = true
    ),
    BACK(
        id = "back",
        title = "System Back Nav",
        subtitle = "Simulate Android back gesture",
        shortLabel = "Back",
        iconRes = R.drawable.ic_arrow_back,
        accentColor = 0xFFE2E8F0.toInt(),
        badgeBgColor = 0xFF232A3A.toInt(),
        defaultEnabled = false
    ),
    SCREENSHOT(
        id = "screenshot",
        title = "Screenshot Capture",
        subtitle = "Save host frame in PNG",
        shortLabel = "Shot",
        iconRes = R.drawable.ic_screenshot,
        accentColor = 0xFFE2E8F0.toInt(),
        badgeBgColor = 0xFF232A3A.toInt(),
        defaultEnabled = false
    );

    companion object {
        fun defaultSet(): Set<String> = entries.filter { it.defaultEnabled }.map { it.id }.toSet()
    }
}

/**
 * In-memory snapshot of the latest live session telemetry for SettingsActivity's status card.
 */
object TelemetryState {
    @Volatile var lastPingMs: Int = -1
    @Volatile var lastFps: Int = -1
    @Volatile var lastBitrateMbps: Float = -1f
}

class MirrorPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)

    // Connection settings
    var hostIp: String
        get() = prefs.getString("host_ip", Protocol.DEFAULT_HOST) ?: Protocol.DEFAULT_HOST
        set(v) = prefs.edit().putString("host_ip", v).apply()

    var port: Int
        get() = prefs.getInt("port", Protocol.DEFAULT_PORT)
        set(v) = prefs.edit().putInt("port", v).apply()

    var bitrateIdx: Int
        get() = prefs.getInt("bitrate_idx", 1) // 12 Mbps default
        set(v) = prefs.edit().putInt("bitrate_idx", v).apply()

    var fpsIdx: Int
        get() = prefs.getInt("fps_idx", 1) // 60 FPS default
        set(v) = prefs.edit().putInt("fps_idx", v).apply()

    // Section 1: HUD & Overlay Metrics
    var hudEnabled: Boolean
        get() = prefs.getBoolean("hud_enabled", true)
        set(v) = prefs.edit().putBoolean("hud_enabled", v).apply()

    var hudAlignment: HudAlignment
        get() = HudAlignment.fromKey(prefs.getString("hud_alignment", HudAlignment.LEFT.key))
        set(v) = prefs.edit().putString("hud_alignment", v.key).apply()

    var showFps: Boolean
        get() = prefs.getBoolean("hud_show_fps", true)
        set(v) = prefs.edit().putBoolean("hud_show_fps", v).apply()

    var showBitrate: Boolean
        get() = prefs.getBoolean("hud_show_bitrate", true)
        set(v) = prefs.edit().putBoolean("hud_show_bitrate", v).apply()

    var showHostAddress: Boolean
        get() = prefs.getBoolean("hud_show_host", false)
        set(v) = prefs.edit().putBoolean("hud_show_host", v).apply()

    var showPing: Boolean
        get() = prefs.getBoolean("hud_show_ping", false)
        set(v) = prefs.edit().putBoolean("hud_show_ping", v).apply()

    var bitrateUnit: BitrateUnit
        get() = BitrateUnit.fromKey(prefs.getString("hud_bitrate_unit", BitrateUnit.MBPS.key))
        set(v) = prefs.edit().putString("hud_bitrate_unit", v.key).apply()

    var hudRestingOpacity: Int
        get() = prefs.getInt("hud_opacity", 80)
        set(v) = prefs.edit().putInt("hud_opacity", v).apply()

    var hudAutoFade: Boolean
        get() = prefs.getBoolean("hud_auto_fade", true)
        set(v) = prefs.edit().putBoolean("hud_auto_fade", v).apply()

    var hudFadeDelaySec: Int
        get() = prefs.getInt("hud_fade_delay", 3) // 0 = Never
        set(v) = prefs.edit().putInt("hud_fade_delay", v).apply()

    // Section 2: Floating Quick Toolbox (FAB)
    var fabEnabled: Boolean
        get() = prefs.getBoolean("fab_enabled", true)
        set(v) = prefs.edit().putBoolean("fab_enabled", v).apply()

    var fabDraggable: Boolean
        get() = prefs.getBoolean("fab_draggable", true)
        set(v) = prefs.edit().putBoolean("fab_draggable", v).apply()

    var fabAnchor: FabAnchor
        get() = FabAnchor.fromKey(prefs.getString("fab_anchor", FabAnchor.BOTTOM_RIGHT.key))
        set(v) {
            prefs.edit()
                .putString("fab_anchor", v.key)
                .remove("fab_custom_x")
                .remove("fab_custom_y")
                .apply()
        }

    var fabCustomX: Float
        get() = prefs.getFloat("fab_custom_x", -1f)
        set(v) = prefs.edit().putFloat("fab_custom_x", v).apply()

    var fabCustomY: Float
        get() = prefs.getFloat("fab_custom_y", -1f)
        set(v) = prefs.edit().putFloat("fab_custom_y", v).apply()

    fun isToolActive(action: ToolboxAction): Boolean {
        val saved = prefs.getStringSet("fab_active_tools", null) ?: ToolboxAction.defaultSet()
        return saved.contains(action.id)
    }

    fun setToolActive(action: ToolboxAction, active: Boolean) {
        val current = (prefs.getStringSet("fab_active_tools", null) ?: ToolboxAction.defaultSet()).toMutableSet()
        if (active) current.add(action.id) else current.remove(action.id)
        prefs.edit().putStringSet("fab_active_tools", current).apply()
    }

    fun getActiveTools(): List<ToolboxAction> {
        val saved = prefs.getStringSet("fab_active_tools", null) ?: ToolboxAction.defaultSet()
        return ToolboxAction.entries.filter { saved.contains(it.id) }
    }

    var fabRestingOpacity: Int
        get() = prefs.getInt("fab_opacity", 90)
        set(v) = prefs.edit().putInt("fab_opacity", v).apply()

    var fabAutoFade: Boolean
        get() = prefs.getBoolean("fab_auto_fade", true)
        set(v) = prefs.edit().putBoolean("fab_auto_fade", v).apply()

    var fabFadeDelaySec: Int
        get() = prefs.getInt("fab_fade_delay", 3)
        set(v) = prefs.edit().putInt("fab_fade_delay", v).apply()

    // Section 3: General Stream Preferences
    var keepScreenAwake: Boolean
        get() = prefs.getBoolean("pref_keep_awake", true)
        set(v) = prefs.edit().putBoolean("pref_keep_awake", v).apply()

    var hapticOnFabTap: Boolean
        get() = prefs.getBoolean("pref_haptic_fab", true)
        set(v) = prefs.edit().putBoolean("pref_haptic_fab", v).apply()

    fun resetToFactory() {
        val savedHost = hostIp
        val savedPort = port
        val savedBitrate = bitrateIdx
        val savedFps = fpsIdx
        prefs.edit().clear()
            .putString("host_ip", savedHost)
            .putInt("port", savedPort)
            .putInt("bitrate_idx", savedBitrate)
            .putInt("fps_idx", savedFps)
            .apply()
    }
}
