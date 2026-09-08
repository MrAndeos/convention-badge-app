package com.andeos.badge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

class BadgeActivity : Activity() {

    private var tapCount = 0
    private var lastTapTime = 0L
    private var lockTaskStarted = false

    companion object {
        // 5 taps (within TAP_WINDOW_MS) opens the settings overlay (brightness + battery).
        // 10 taps (within TAP_WINDOW_MS) exits to the home screen.
        private const val SETTINGS_TAPS = 5
        private const val EXIT_TAPS = 10
        private const val TAP_WINDOW_MS = 3000L
    }

    private lateinit var root: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var image: ImageView
    private lateinit var brightnessSlider: SeekBar
    private lateinit var batteryLabel: TextView

    // When the overlay opens and WRITE_SETTINGS isn't granted, we ask the user
    // to tap once more to confirm they want to open the system permission screen.
    private var pendingPermissionRequest = false

    // Runtime-loaded badge image path. No bundled fallback by design.
    private lateinit var imagePath: File

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryLabel.text = "Battery: ${(level * 100) / scale}%"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Resolve the badge image path. The user places the file here:
        //   /sdcard/Android/data/com.andeos.badge/files/badge.png
        // No runtime permission needed for this app-private external dir.
        imagePath = File(getExternalFilesDir(null), "badge.png")

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        errorOverlay = buildErrorOverlay()
        errorOverlay.visibility = View.GONE
        root.addView(
            errorOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        overlay = buildOverlay()
        overlay.visibility = View.GONE
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)

        // Tap counting is on the root so it works regardless of which overlay
        // is visible. The slider and buttons consume their own touches, so they
        // never reach this listener -- only "empty" taps count toward exit.
        root.setOnClickListener { onUserTap() }

        loadBadgeImage()

        // Pin from launch to match the pre-file-load behavior. If the user
        // opens settings before granting WRITE_SETTINGS, requestWriteSettingsPermission()
        // briefly calls stopLockTask() so the system permission screen can show.
        tryStartLockTask()
    }

    private fun loadBadgeImage() {
        if (!imagePath.exists()) {
            image.visibility = View.GONE
            errorOverlay.visibility = View.VISIBLE
            return
        }
        val bitmap = BitmapFactory.decodeFile(imagePath.absolutePath)
        if (bitmap == null) {
            image.visibility = View.GONE
            errorOverlay.visibility = View.VISIBLE
            return
        }
        image.setImageBitmap(bitmap)
        image.visibility = View.VISIBLE
        errorOverlay.visibility = View.GONE
    }

    private fun buildErrorOverlay(): LinearLayout {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(Color.BLACK)
        container.gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)

        val header = TextView(this)
        header.text = "Badge image not found"
        header.setTextColor(Color.WHITE)
        header.textSize = 22f
        header.gravity = Gravity.CENTER
        container.addView(header)

        val instructions = TextView(this)
        val dir = getExternalFilesDir(null)?.absolutePath ?: "/sdcard/Android/data/com.andeos.badge/files"
        instructions.text = "Place a file named badge.png at:\n\n$dir\n\n10 taps anywhere to exit."
        instructions.setTextColor(Color.WHITE)
        instructions.textSize = 16f
        instructions.gravity = Gravity.CENTER
        val ip = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        ip.topMargin = (24 * resources.displayMetrics.density).toInt()
        container.addView(instructions, ip)

        return container
    }

    private fun tryStartLockTask() {
        if (lockTaskStarted) return
        try {
            startLockTask()
            lockTaskStarted = true
        } catch (e: Exception) {
            // screen pinning not available/enabled -- fine, app still works
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun exitToHome() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            // wasn't pinned -- fine
        }
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(home)
        finish()
    }

    private fun buildOverlay(): LinearLayout {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(Color.argb(220, 0, 0, 0))
        container.gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)
        container.isClickable = true
        // Taps on the overlay's empty space count toward the 10-tap exit. The
        // SeekBar and Close button consume their own clicks, so they don't.
        container.setOnClickListener { onUserTap() }

        val title = TextView(this)
        title.text = "Settings (10 taps anywhere exits)"
        title.setTextColor(Color.WHITE)
        title.textSize = 18f
        title.gravity = Gravity.CENTER
        container.addView(title)

        val brightnessLabel = TextView(this)
        brightnessLabel.text = "Brightness"
        brightnessLabel.setTextColor(Color.WHITE)
        brightnessLabel.textSize = 16f
        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        labelParams.topMargin = (24 * resources.displayMetrics.density).toInt()
        container.addView(brightnessLabel, labelParams)

        brightnessSlider = SeekBar(this)
        brightnessSlider.max = 255
        brightnessSlider.progress = getCurrentBrightness()
        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setBrightness(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(
            brightnessSlider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        batteryLabel = TextView(this)
        batteryLabel.text = "Battery: --%"
        batteryLabel.setTextColor(Color.WHITE)
        batteryLabel.textSize = 16f
        batteryLabel.gravity = Gravity.CENTER
        val batteryParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        batteryParams.topMargin = (24 * resources.displayMetrics.density).toInt()
        container.addView(batteryLabel, batteryParams)

        val closeButton = Button(this)
        closeButton.text = "Close"
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        buttonParams.topMargin = (32 * resources.displayMetrics.density).toInt()
        container.addView(closeButton, buttonParams)
        // Just dismisses the popup. The tap counter resets so the next 5 taps
        // open the popup again instead of carrying over toward the exit threshold.
        closeButton.setOnClickListener { closeOverlay() }

        return container
    }

    private fun closeOverlay() {
        overlay.visibility = View.GONE
        tapCount = 0
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        errorOverlay.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.System.canWrite(this)
        ) {
            Toast.makeText(
                this,
                "Tap again to open system permission",
                Toast.LENGTH_LONG
            ).show()
            pendingPermissionRequest = true
        } else {
            brightnessSlider.progress = getCurrentBrightness()
            batteryLabel.text = "Battery: ${readBatteryPercent()}%"
            tryStartLockTask()
        }
    }

    private fun requestWriteSettingsPermission() {
        // Launching a system activity from inside the tap handler is unreliable
        // on some devices (focus/immersive interactions); post it so it runs
        // after the current event drain finishes.
        Handler(Looper.getMainLooper()).post {
            // Pinned tasks block activities from other packages, so the system
            // permission screen can't launch over us. Briefly unpin, relaunch
            // the system Settings, and re-pin from onResume when we return.
            try {
                stopLockTask()
                lockTaskStarted = false
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (e: Exception) {
                lockTaskStarted = false
                Toast.makeText(
                    this,
                    "Could not open permission settings: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onUserTap() {
        if (pendingPermissionRequest) {
            pendingPermissionRequest = false
            requestWriteSettingsPermission()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTapTime > TAP_WINDOW_MS) tapCount = 0
        lastTapTime = now
        tapCount++
        when {
            tapCount >= EXIT_TAPS -> {
                tapCount = 0
                exitToHome()
            }
            overlay.visibility != View.VISIBLE && tapCount >= SETTINGS_TAPS -> {
                showOverlay()
            }
        }
    }

    private fun getCurrentBrightness(): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                .coerceIn(0, 255)
        } catch (e: Settings.SettingNotFoundException) {
            128
        }
    }

    private fun setBrightness(value: Int) {
        val clamped = value.coerceIn(0, 255)
        val canWrite = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            true
        }
        if (canWrite) {
            try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    clamped
                )
            } catch (e: SecurityException) {
                // lost the permission mid-use -- fall back silently
            }
        }
    }

    private fun readBatteryPercent(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    override fun onResume() {
        super.onResume()
        // Re-pin if we were unpinned to show the WRITE_SETTINGS permission screen.
        tryStartLockTask()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(batteryReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // wasn't registered -- fine
        }
    }
}
