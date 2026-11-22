package com.mirror.client

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: MirrorPreferences

    // Telemetry card
    private lateinit var telemetryPingVal: TextView
    private lateinit var telemetryFpsVal: TextView
    private lateinit var telemetryBwVal: TextView

    // HUD controls
    private lateinit var switchHudMaster: SwitchMaterial
    private lateinit var hudPreviewWrapper: LinearLayout
    private lateinit var hudPreviewBadge: TextView
    private lateinit var alignButtons: Map<HudAlignment, TextView>
    private lateinit var chipSlotFps: LinearLayout
    private lateinit var chipSlotBitrate: LinearLayout
    private lateinit var chipSlotHost: LinearLayout
    private lateinit var chipSlotPing: LinearLayout
    private lateinit var txtSlotFps: TextView
    private lateinit var txtSlotBitrate: TextView
    private lateinit var txtSlotHost: TextView
    private lateinit var txtSlotHostSub: TextView
    private lateinit var txtSlotPing: TextView
    private lateinit var iconSlotFps: ImageView
    private lateinit var iconSlotBitrate: ImageView
    private lateinit var iconSlotHost: ImageView
    private lateinit var iconSlotPing: ImageView
    private lateinit var unitButtons: Map<BitrateUnit, TextView>
    private lateinit var badgeHudOpacity: TextView
    private lateinit var hudOpacityButtons: Map<Int, TextView>
    private lateinit var switchHudAutoFade: SwitchMaterial
    private lateinit var hudDelayButtons: Map<Int, TextView>

    // FAB controls
    private lateinit var switchFabMaster: SwitchMaterial
    private lateinit var switchFabDraggable: SwitchMaterial
    private lateinit var anchorContainers: Map<FabAnchor, Pair<LinearLayout, TextView>>
    private lateinit var simulatedFabPreview: FrameLayout
    private lateinit var txtActiveToolsCount: TextView
    private lateinit var toolboxActionsContainer: LinearLayout
    private lateinit var badgeFabOpacity: TextView
    private lateinit var fabOpacityButtons: Map<Int, TextView>
    private lateinit var switchFabAutoFade: SwitchMaterial
    private lateinit var fabDelayButtons: Map<Int, TextView>
    private lateinit var txtFabFadeHint: TextView

    // General preferences
    private lateinit var switchKeepAwake: SwitchMaterial
    private lateinit var switchHapticFab: SwitchMaterial
    private lateinit var btnResetFactory: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = MirrorPreferences(this)

        bindViews()
        setupListeners()
        refreshAllUi()
    }

    private fun bindViews() {
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { finish() }

        telemetryPingVal = findViewById(R.id.telemetryPingVal)
        telemetryFpsVal = findViewById(R.id.telemetryFpsVal)
        telemetryBwVal = findViewById(R.id.telemetryBwVal)

        switchHudMaster = findViewById(R.id.switchHudMaster)
        hudPreviewWrapper = findViewById(R.id.hudPreviewWrapper)
        hudPreviewBadge = findViewById(R.id.hudPreviewBadge)
        alignButtons = mapOf(
            HudAlignment.LEFT to findViewById(R.id.btnHudAlignLeft),
            HudAlignment.CENTER to findViewById(R.id.btnHudAlignCenter),
            HudAlignment.RIGHT to findViewById(R.id.btnHudAlignRight)
        )

        chipSlotFps = findViewById(R.id.chipSlotFps)
        chipSlotBitrate = findViewById(R.id.chipSlotBitrate)
        chipSlotHost = findViewById(R.id.chipSlotHost)
        chipSlotPing = findViewById(R.id.chipSlotPing)
        txtSlotFps = findViewById(R.id.txtSlotFps)
        txtSlotBitrate = findViewById(R.id.txtSlotBitrate)
        txtSlotHost = findViewById(R.id.txtSlotHost)
        txtSlotHostSub = findViewById(R.id.txtSlotHostSub)
        txtSlotPing = findViewById(R.id.txtSlotPing)
        iconSlotFps = findViewById(R.id.iconSlotFps)
        iconSlotBitrate = findViewById(R.id.iconSlotBitrate)
        iconSlotHost = findViewById(R.id.iconSlotHost)
        iconSlotPing = findViewById(R.id.iconSlotPing)

        unitButtons = mapOf(
            BitrateUnit.MBPS to findViewById(R.id.btnUnitMbps),
            BitrateUnit.MB_S to findViewById(R.id.btnUnitMBs)
        )

        badgeHudOpacity = findViewById(R.id.badgeHudOpacity)
        hudOpacityButtons = mapOf(
            30 to findViewById(R.id.btnHudOp30),
            50 to findViewById(R.id.btnHudOp50),
            80 to findViewById(R.id.btnHudOp80),
            100 to findViewById(R.id.btnHudOp100)
        )

        switchHudAutoFade = findViewById(R.id.switchHudAutoFade)
        hudDelayButtons = mapOf(
            3 to findViewById(R.id.btnHudDelay3),
            5 to findViewById(R.id.btnHudDelay5),
            10 to findViewById(R.id.btnHudDelay10),
            0 to findViewById(R.id.btnHudDelayNever)
        )

        switchFabMaster = findViewById(R.id.switchFabMaster)
        switchFabDraggable = findViewById(R.id.switchFabDraggable)
        anchorContainers = mapOf(
            FabAnchor.BOTTOM_RIGHT to Pair(findViewById(R.id.btnAnchorBottomRight), findViewById(R.id.txtAnchorBR)),
            FabAnchor.BOTTOM_CENTER to Pair(findViewById(R.id.btnAnchorBottomCenter), findViewById(R.id.txtAnchorBC)),
            FabAnchor.BOTTOM_LEFT to Pair(findViewById(R.id.btnAnchorBottomLeft), findViewById(R.id.txtAnchorBL)),
            FabAnchor.RIGHT_MID to Pair(findViewById(R.id.btnAnchorRightMid), findViewById(R.id.txtAnchorRM))
        )
        simulatedFabPreview = findViewById(R.id.simulatedFabPreview)
        txtActiveToolsCount = findViewById(R.id.txtActiveToolsCount)
        toolboxActionsContainer = findViewById(R.id.toolboxActionsContainer)

        badgeFabOpacity = findViewById(R.id.badgeFabOpacity)
        fabOpacityButtons = mapOf(
            40 to findViewById(R.id.btnFabOp40),
            70 to findViewById(R.id.btnFabOp70),
            90 to findViewById(R.id.btnFabOp90),
            100 to findViewById(R.id.btnFabOp100)
        )

        switchFabAutoFade = findViewById(R.id.switchFabAutoFade)
        fabDelayButtons = mapOf(
            2 to findViewById(R.id.btnFabDelay2),
            3 to findViewById(R.id.btnFabDelay3),
            5 to findViewById(R.id.btnFabDelay5),
            8 to findViewById(R.id.btnFabDelay8)
        )
        txtFabFadeHint = findViewById(R.id.txtFabFadeHint)

        switchKeepAwake = findViewById(R.id.switchKeepAwake)
        switchHapticFab = findViewById(R.id.switchHapticFab)
        btnResetFactory = findViewById(R.id.btnResetFactory)
    }

    private fun setupListeners() {
        switchHudMaster.setOnCheckedChangeListener { _, checked ->
            prefs.hudEnabled = checked
            updateHudPreview()
        }

        alignButtons.forEach { (alignment, view) ->
            view.setOnClickListener {
                prefs.hudAlignment = alignment
                refreshHudSection()
            }
        }

        chipSlotFps.setOnClickListener {
            prefs.showFps = !prefs.showFps
            refreshHudSection()
        }
        chipSlotBitrate.setOnClickListener {
            prefs.showBitrate = !prefs.showBitrate
            refreshHudSection()
        }
        chipSlotHost.setOnClickListener {
            prefs.showHostAddress = !prefs.showHostAddress
            refreshHudSection()
        }
        chipSlotPing.setOnClickListener {
            prefs.showPing = !prefs.showPing
            refreshHudSection()
        }

        unitButtons.forEach { (unit, view) ->
            view.setOnClickListener {
                prefs.bitrateUnit = unit
                refreshHudSection()
                refreshTelemetryCard()
            }
        }

        hudOpacityButtons.forEach { (opacity, view) ->
            view.setOnClickListener {
                prefs.hudRestingOpacity = opacity
                refreshHudSection()
            }
        }

        switchHudAutoFade.setOnCheckedChangeListener { _, checked ->
            prefs.hudAutoFade = checked
        }

        hudDelayButtons.forEach { (delaySec, view) ->
            view.setOnClickListener {
                prefs.hudFadeDelaySec = delaySec
                refreshHudSection()
            }
        }

        switchFabMaster.setOnCheckedChangeListener { _, checked ->
            prefs.fabEnabled = checked
            simulatedFabPreview.alpha = if (checked) prefs.fabRestingOpacity / 100f else 0.15f
        }

        switchFabDraggable.setOnCheckedChangeListener { _, checked ->
            prefs.fabDraggable = checked
        }

        anchorContainers.forEach { (anchor, pair) ->
            pair.first.setOnClickListener {
                prefs.fabAnchor = anchor
                refreshFabSection()
            }
        }

        fabOpacityButtons.forEach { (opacity, view) ->
            view.setOnClickListener {
                prefs.fabRestingOpacity = opacity
                refreshFabSection()
            }
        }

        switchFabAutoFade.setOnCheckedChangeListener { _, checked ->
            prefs.fabAutoFade = checked
        }

        fabDelayButtons.forEach { (delaySec, view) ->
            view.setOnClickListener {
                prefs.fabFadeDelaySec = delaySec
                refreshFabSection()
            }
        }

        switchKeepAwake.setOnCheckedChangeListener { _, checked ->
            prefs.keepScreenAwake = checked
        }

        switchHapticFab.setOnCheckedChangeListener { _, checked ->
            prefs.hapticOnFabTap = checked
        }

        btnResetFactory.setOnClickListener {
            prefs.resetToFactory()
            refreshAllUi()
            Toast.makeText(this, "Settings restored to factory defaults", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAllUi() {
        refreshTelemetryCard()
        refreshHudSection()
        refreshFabSection()
        switchKeepAwake.isChecked = prefs.keepScreenAwake
        switchHapticFab.isChecked = prefs.hapticOnFabTap
    }

    private fun refreshTelemetryCard() {
        telemetryPingVal.text = if (TelemetryState.lastPingMs > 0) "${TelemetryState.lastPingMs} ms" else "12 ms"
        telemetryFpsVal.text = if (TelemetryState.lastFps > 0) "${TelemetryState.lastFps} FPS" else "60 FPS"
        val mbps = if (TelemetryState.lastBitrateMbps > 0f) TelemetryState.lastBitrateMbps else 14.8f
        telemetryBwVal.text = formatBandwidth(mbps, prefs.bitrateUnit)
    }

    private fun refreshHudSection() {
        switchHudMaster.isChecked = prefs.hudEnabled
        txtSlotHostSub.text = prefs.hostIp

        // Alignment segments
        alignButtons.forEach { (alignment, view) ->
            styleSegment(view, alignment == prefs.hudAlignment)
        }

        // Data slot chips
        styleMetricChip(chipSlotFps, txtSlotFps, iconSlotFps, prefs.showFps)
        styleMetricChip(chipSlotBitrate, txtSlotBitrate, iconSlotBitrate, prefs.showBitrate)
        styleMetricChip(chipSlotHost, txtSlotHost, iconSlotHost, prefs.showHostAddress)
        styleMetricChip(chipSlotPing, txtSlotPing, iconSlotPing, prefs.showPing)

        // Bitrate unit segments
        unitButtons.forEach { (unit, view) ->
            styleSegment(view, unit == prefs.bitrateUnit)
        }

        // HUD Opacity
        badgeHudOpacity.text = "${prefs.hudRestingOpacity}%"
        hudOpacityButtons.forEach { (opacity, view) ->
            styleSegment(view, opacity == prefs.hudRestingOpacity)
        }

        // HUD Auto-fade
        switchHudAutoFade.isChecked = prefs.hudAutoFade
        hudDelayButtons.forEach { (delaySec, view) ->
            styleDelayChip(view, delaySec == prefs.hudFadeDelaySec)
        }

        updateHudPreview()
    }

    private fun updateHudPreview() {
        hudPreviewWrapper.gravity = when (prefs.hudAlignment) {
            HudAlignment.LEFT -> Gravity.START
            HudAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            HudAlignment.RIGHT -> Gravity.END
        }

        val parts = mutableListOf<String>()
        val sampleFps = if (TelemetryState.lastFps > 0) TelemetryState.lastFps else 60
        val sampleMbps = if (TelemetryState.lastBitrateMbps > 0f) TelemetryState.lastBitrateMbps else 14.8f
        val samplePing = if (TelemetryState.lastPingMs > 0) TelemetryState.lastPingMs else 12

        if (prefs.showFps) parts.add("$sampleFps FPS")
        if (prefs.showBitrate) parts.add(formatBandwidth(sampleMbps, prefs.bitrateUnit))
        if (prefs.showHostAddress) parts.add(prefs.hostIp)
        if (prefs.showPing) parts.add("${samplePing}ms")

        if (parts.isEmpty()) {
            hudPreviewBadge.text = "No metrics selected"
        } else if (parts.size == 1 && prefs.showFps) {
            hudPreviewBadge.text = "$sampleFps"
        } else {
            hudPreviewBadge.text = parts.joinToString(" • ")
        }

        hudPreviewBadge.alpha = if (prefs.hudEnabled) prefs.hudRestingOpacity / 100f else 0.2f
    }

    private fun refreshFabSection() {
        switchFabMaster.isChecked = prefs.fabEnabled
        switchFabDraggable.isChecked = prefs.fabDraggable

        anchorContainers.forEach { (anchor, pair) ->
            val active = anchor == prefs.fabAnchor
            pair.first.setBackgroundResource(
                if (active) R.drawable.bg_settings_chip_active else R.drawable.bg_settings_inset
            )
            pair.second.setTextColor(if (active) 0xFFADC6FF.toInt() else 0xFFDCE2F7.toInt())
        }

        val lp = simulatedFabPreview.layoutParams as FrameLayout.LayoutParams
        lp.gravity = when (prefs.fabAnchor) {
            FabAnchor.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            FabAnchor.BOTTOM_CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            FabAnchor.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            FabAnchor.RIGHT_MID -> Gravity.CENTER_VERTICAL or Gravity.END
        }
        simulatedFabPreview.layoutParams = lp
        simulatedFabPreview.alpha = if (prefs.fabEnabled) prefs.fabRestingOpacity / 100f else 0.15f

        badgeFabOpacity.text = "${prefs.fabRestingOpacity}%"
        fabOpacityButtons.forEach { (opacity, view) ->
            styleSegment(view, opacity == prefs.fabRestingOpacity)
        }

        switchFabAutoFade.isChecked = prefs.fabAutoFade
        fabDelayButtons.forEach { (delaySec, view) ->
            styleDelayChip(view, delaySec == prefs.fabFadeDelaySec)
        }
        txtFabFadeHint.text =
            "Fades the floating toolbox button into near-transparency after ${prefs.fabFadeDelaySec} seconds of inactivity, returning to full visibility immediately on touch."

        rebuildToolboxActionsList()
    }

    private fun rebuildToolboxActionsList() {
        toolboxActionsContainer.removeAllViews()
        val activeTools = prefs.getActiveTools()
        txtActiveToolsCount.text = "${activeTools.size} of ${ToolboxAction.entries.size} active"

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        ToolboxAction.entries.forEach { action ->
            val isActive = prefs.isToolActive(action)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_settings_inset)
                alpha = if (isActive) 1.0f else 0.65f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            // Colored icon badge
            val iconBadge = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(if (isActive) action.badgeBgColor else 0xFF232A3A.toInt())
                }
            }
            val iconImg = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                setImageResource(action.iconRes)
                setColorFilter(if (isActive) action.accentColor else 0xFFC2C6D6.toInt())
            }
            iconBadge.addView(iconImg)

            // Title + subtitle
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = action.title
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (isActive) 0xFFDCE2F7.toInt() else 0xFFC2C6D6.toInt())
            })
            textCol.addView(TextView(this).apply {
                text = action.subtitle
                textSize = 11f
                setTextColor(if (isActive) 0xFFC2C6D6.toInt() else 0xFF8C909F.toInt())
            })

            // Check / Add toggle button
            val toggleBtn = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                setBackgroundResource(if (isActive) R.drawable.bg_circle_primary else R.drawable.bg_circle_inactive)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setImageResource(if (isActive) R.drawable.ic_check else R.drawable.ic_add)
            }

            row.addView(iconBadge)
            row.addView(textCol)
            row.addView(toggleBtn)

            row.setOnClickListener {
                prefs.setToolActive(action, !isActive)
                rebuildToolboxActionsList()
            }

            toolboxActionsContainer.addView(row)
        }
    }

    private fun styleSegment(view: TextView, active: Boolean) {
        if (active) {
            view.setBackgroundResource(R.drawable.bg_segment_active)
            view.setTextColor(0xFF002E6A.toInt())
        } else {
            view.background = null
            view.setTextColor(0xFFC2C6D6.toInt())
        }
    }

    private fun styleDelayChip(view: TextView, active: Boolean) {
        if (active) {
            view.setBackgroundResource(R.drawable.bg_settings_chip_active)
            view.setTextColor(0xFFADC6FF.toInt())
        } else {
            view.setBackgroundResource(R.drawable.bg_settings_inset)
            view.setTextColor(0xFFC2C6D6.toInt())
        }
    }

    private fun styleMetricChip(
        container: LinearLayout,
        title: TextView,
        icon: ImageView,
        active: Boolean
    ) {
        container.setBackgroundResource(
            if (active) R.drawable.bg_settings_chip_active else R.drawable.bg_settings_inset
        )
        title.setTextColor(if (active) 0xFFADC6FF.toInt() else 0xFFDCE2F7.toInt())
        icon.setBackgroundResource(if (active) R.drawable.bg_circle_primary else R.drawable.bg_circle_inactive)
        icon.setImageResource(if (active) R.drawable.ic_check else R.drawable.ic_add)
    }

    private fun formatBandwidth(mbps: Float, unit: BitrateUnit): String {
        return when (unit) {
            BitrateUnit.MBPS -> String.format(Locale.US, "%.1f Mbps", mbps)
            BitrateUnit.MB_S -> String.format(Locale.US, "%.2f MB/s", mbps / 8f)
        }
    }
}
