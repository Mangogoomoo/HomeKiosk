package com.home.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Settings screen. Opened by tapping the top-left corner of the dashboard 5 times. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: KioskPrefs
    private lateinit var col: LinearLayout
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPrefs(this)
        pad = (16 * resources.displayMetrics.density).toInt()

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad * 2)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        val dpm = getSystemService(DevicePolicyManager::class.java)
        val isOwner = dpm?.isDeviceOwnerApp(packageName) == true
        note(
            if (isOwner) "Full kiosk lock: ON (device owner). Navigation buttons and notifications are disabled."
            else "Full kiosk lock: OFF. Using screen pinning instead. See README to enable the full lock."
        )

        // --- Dashboard
        header("Dashboard")
        label("Website address")
        val url = field(prefs.url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        url.hint = "http://192.168.1.10:8123/"
        label("Reload page every N minutes (0 = never)")
        val reload = field(prefs.reloadMinutes.toString(), InputType.TYPE_CLASS_NUMBER)
        val ssl = switch("Accept self-signed HTTPS certificates", prefs.allowSslErrors)

        // --- Screen
        header("Screen")
        label("Dim after this many seconds without touch or movement (0 = never)")
        val idle = field(prefs.idleSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
        val useSystem = switch("Awake: use the tablet's normal brightness", prefs.awakeBrightness < 0)
        val awake = seek("Awake brightness", 5, 100, if (prefs.awakeBrightness < 0) 100 else prefs.awakeBrightness, "%")
        val dimLevel = seek("Dimmed backlight", 0, 100, prefs.dimBrightness, "%")
        val black = switch("Show a black screen when dimmed (full dark)", prefs.blackWhenIdle)

        // --- Motion
        header("Motion wake (camera)")
        val motion = switch("Wake when someone moves in front of the camera", prefs.motionEnabled)
        val sens = seek("Sensitivity", 1, 10, prefs.motionSensitivity, "")
        note("Higher = reacts to smaller movements. Lower it if the screen wakes by itself.")

        // --- Security
        header("Lock")
        val pinning = switch("Use screen pinning when full lock is off", prefs.screenPinning)
        label("Settings PIN (leave empty for none)")
        val pin = field(prefs.pin, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        // --- Buttons
        button("Save and return to dashboard") {
            prefs.url = url.text.toString()
            prefs.reloadMinutes = reload.text.toString().toIntOrNull() ?: 0
            prefs.allowSslErrors = ssl.isChecked
            prefs.idleSeconds = idle.text.toString().toIntOrNull() ?: 60
            prefs.awakeBrightness = if (useSystem.isChecked) -1 else awake.progress + 5
            prefs.dimBrightness = dimLevel.progress
            prefs.blackWhenIdle = black.isChecked
            prefs.motionEnabled = motion.isChecked
            prefs.motionSensitivity = sens.progress + 1
            prefs.screenPinning = pinning.isChecked
            prefs.pin = pin.text.toString()
            if (prefs.url.isBlank()) {
                Toast.makeText(this, "Enter the dashboard address first", Toast.LENGTH_LONG).show()
                return@button
            }
            finish()
        }

        button("Exit kiosk (open Android settings)") {
            try { stopLockTask() } catch (_: Exception) {}
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, "Press Home to return to the dashboard", Toast.LENGTH_LONG).show()
            finish()
        }

        if (isOwner && dpm != null) {
            button("Remove full kiosk lock (allows uninstall)") {
                AlertDialog.Builder(this)
                    .setTitle("Remove kiosk lock?")
                    .setMessage("The app stops being device owner. You can then uninstall it normally, or set up the lock again with adb.")
                    .setPositiveButton("Remove") { _, _ ->
                        val admin = KioskAdminReceiver.component(this)
                        try {
                            try { stopLockTask() } catch (_: Exception) {}
                            dpm.clearPackagePersistentPreferredActivities(admin, packageName)
                            dpm.setKeyguardDisabled(admin, false)
                            dpm.setLockTaskPackages(admin, arrayOf())
                            @Suppress("DEPRECATION")
                            dpm.clearDeviceOwnerApp(packageName)
                            Toast.makeText(this, "Kiosk lock removed", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        recreate()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- small UI helpers

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setPadding(0, pad * 2, 0, pad / 2)
    }.also { col.addView(it) }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, pad, 0, pad / 4)
    }.also { col.addView(it) }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        alpha = 0.7f
        setPadding(0, pad / 2, 0, 0)
    }.also { col.addView(it) }

    private fun field(value: String, type: Int) = EditText(this).apply {
        setText(value)
        inputType = type
        isSingleLine = true
    }.also { col.addView(it) }

    private fun switch(t: String, checked: Boolean) = SwitchCompat(this).apply {
        text = t
        textSize = 15f
        isChecked = checked
        setPadding(0, pad / 2, 0, pad / 2)
    }.also { col.addView(it) }

    /** Seek bar whose value runs from lo..hi; read back as progress + lo. */
    private fun seek(title: String, lo: Int, hi: Int, value: Int, suffix: String): SeekBar {
        val lbl = label("$title: $value$suffix")
        return SeekBar(this).apply {
            max = hi - lo
            progress = (value - lo).coerceIn(0, hi - lo)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    lbl.text = "$title: ${p + lo}$suffix"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }.also { col.addView(it) }
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }.also {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = pad
        col.addView(it, lp)
    }
}
