package com.codinglibs.imageserver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        toggle.setOnClickListener { if (isRunning()) stop() else start() }
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        attachServerListeners(ServerService.server)
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            1 -> ServerService.server?.confirmDelete(resultCode == RESULT_OK)
            3 -> ServerService.server?.grantAccess(Environment.isExternalStorageManager())
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 3) {
            ServerService.server?.grantAccess(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun attachServerListeners(s: ImageServer?) {
        s?.onDeletePending = { pd -> startIntentSenderForResult(pd.sender, 1, null, 0, 0, 0) }
        s?.onAuthRequested = { showAuthDialog() }
        if (s?.isAuthPending() == true) showAuthDialog()
    }

    private fun showAuthDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 48)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("New connection")
            .setMessage("A browser wants to access your media. Choose access level:")
            .setView(layout)
            .create()
        fun addButton(label: String, action: () -> Unit) {
            layout.addView(
                Button(this).apply {
                    text = label
                    isAllCaps = false
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xFF3D3D3D.toInt())
                    setOnClickListener {
                        action()
                        dialog.dismiss()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 20
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
        }
        addButton("View only") { ServerService.server?.grantAccess(false) }
        addButton("View and delete") { requestWriteAccess() }
        addButton("Cancel") { ServerService.server?.denyAccess() }
        dialog.show()
    }

    private fun requestWriteAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                ServerService.server?.grantAccess(true)
            } else {
                try {
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        ), 3
                    )
                } catch (e: Exception) {
                    ServerService.server?.grantAccess(false)
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 3)
        }
    }

    private fun isRunning() = ServerService.server?.running == true

    private fun start() {
        if (!hasPermissions()) {
            requestPermissionsIfNeeded()
            return
        }
        requestBatteryExemption()
        startForegroundService(Intent(this, ServerService::class.java))
        toggle.isEnabled = false
        toggle.text = "Starting..."
        pollStatus(0)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun pollStatus(n: Int) {
        val s = ServerService.server
        if (s?.running == true) {
            attachServerListeners(s)
            toggle.isEnabled = true
            refresh()
        } else if (n < 20) {
            toggle.postDelayed({ pollStatus(n + 1) }, 200)
        } else {
            toggle.isEnabled = true
            status.text = "Failed to start server"
            toggle.text = "Start server"
            toggle.setBackgroundResource(R.drawable.btn_start)
        }
    }

    private fun stop() {
        stopService(Intent(this, ServerService::class.java))
        toggle.postDelayed({ refresh() }, 200)
    }

    private fun refresh() {
        val s = ServerService.server
        if (s?.running == true) {
            toggle.text = "Stop server"
            toggle.setBackgroundResource(R.drawable.btn_stop)
            status.text = "Running at:\nhttp://${s.localIp()}:${s.port}/image/one\n\n" +
                "Open this address in your PC browser (same Wi-Fi network)"
        } else {
            toggle.text = "Start server"
            toggle.setBackgroundResource(R.drawable.btn_start)
            status.text = "Tap start, then open the address shown here in your PC browser"
        }
    }

    private fun neededPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT >= 33 ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT >= 29 ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasPermissions(): Boolean {
        // Android 14+ partial access ("Select photos and videos") grants only VISUAL_USER_SELECTED
        if (Build.VERSION.SDK_INT >= 34 &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissionsIfNeeded() {
        if (!hasPermissions()) requestPermissions(neededPerms(), 2)
    }
}
