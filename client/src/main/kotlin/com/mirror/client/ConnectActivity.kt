package com.mirror.client

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ConnectActivity : AppCompatActivity() {

    private val bitrateOptions = listOf(
        "4 Mbps" to 4_000_000,
        "8 Mbps" to 8_000_000,
        "12 Mbps (Default)" to 12_000_000,
        "16 Mbps" to 16_000_000,
        "24 Mbps" to 24_000_000
    )

    private val fpsOptions = listOf(
        "30 FPS" to 30,
        "60 FPS (Default)" to 60,
        "90 FPS" to 90,
        "120 FPS" to 120
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        requestLocalNetworkPermissionIfNeeded()

        val prefs = getSharedPreferences("mirror_prefs", MODE_PRIVATE)
        val editHostIp = findViewById<EditText>(R.id.editHostIp)
        val editPort = findViewById<EditText>(R.id.editPort)
        val spinnerBitrate = findViewById<Spinner>(R.id.spinnerBitrate)
        val spinnerFps = findViewById<Spinner>(R.id.spinnerFps)
        val btnConnect = findViewById<Button>(R.id.btnConnect)

        editHostIp.setText(prefs.getString("host_ip", Protocol.DEFAULT_HOST))
        editPort.setText(prefs.getInt("port", Protocol.DEFAULT_PORT).toString())

        spinnerBitrate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            bitrateOptions.map { it.first }
        )
        spinnerBitrate.setSelection(prefs.getInt("bitrate_idx", 2)) // 12 Mbps default

        spinnerFps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            fpsOptions.map { it.first }
        )
        spinnerFps.setSelection(prefs.getInt("fps_idx", 1)) // 60 FPS default

        btnConnect.setOnClickListener {
            val host = editHostIp.text.toString().trim()
            val port = editPort.text.toString().trim().toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) {
                Toast.makeText(this, "Enter a valid host IP and TCP port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val bitrateIdx = spinnerBitrate.selectedItemPosition.coerceIn(bitrateOptions.indices)
            val fpsIdx = spinnerFps.selectedItemPosition.coerceIn(fpsOptions.indices)

            prefs.edit()
                .putString("host_ip", host)
                .putInt("port", port)
                .putInt("bitrate_idx", bitrateIdx)
                .putInt("fps_idx", fpsIdx)
                .apply()

            val intent = Intent(this, MirrorActivity::class.java).apply {
                putExtra(MirrorActivity.EXTRA_HOST, host)
                putExtra(MirrorActivity.EXTRA_PORT, port)
                putExtra(MirrorActivity.EXTRA_BITRATE, bitrateOptions[bitrateIdx].second)
                putExtra(MirrorActivity.EXTRA_FPS, fpsOptions[fpsIdx].second)
            }
            startActivity(intent)
        }
    }

    private fun requestLocalNetworkPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 34) {
            val perm = "android.permission.ACCESS_LOCAL_NETWORK"
            try {
                if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(perm), 1001)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
