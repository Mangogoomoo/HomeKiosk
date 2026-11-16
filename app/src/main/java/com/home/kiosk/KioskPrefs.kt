package com.home.kiosk

import android.content.Context

/** All user settings, stored on the tablet. */
class KioskPrefs(context: Context) {
    private val p = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    /** Dashboard address. */
    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v.trim()).apply()

    /** Seconds without touch or motion before dimming. 0 = never dim. */
    var idleSeconds: Int
        get() = p.getInt("idle_seconds", 60)
        set(v) = p.edit().putInt("idle_seconds", v).apply()

    /** Brightness when awake, 0..100. -1 = use the tablet's own brightness setting. */
    var awakeBrightness: Int
        get() = p.getInt("awake_brightness", -1)
        set(v) = p.edit().putInt("awake_brightness", v).apply()

    /** Backlight level when dimmed, 0..100. */
    var dimBrightness: Int
        get() = p.getInt("dim_brightness", 0)
        set(v) = p.edit().putInt("dim_brightness", v).apply()

    /** Cover the page with black when dimmed ("full dark"). */
    var blackWhenIdle: Boolean
        get() = p.getBoolean("black_when_idle", true)
        set(v) = p.edit().putBoolean("black_when_idle", v).apply()

    /** Use the camera to wake up on movement. */
    var motionEnabled: Boolean
        get() = p.getBoolean("motion_enabled", true)
        set(v) = p.edit().putBoolean("motion_enabled", v).apply()

    /** 1 (least sensitive) .. 10 (most sensitive). */
    var motionSensitivity: Int
        get() = p.getInt("motion_sensitivity", 6)
        set(v) = p.edit().putInt("motion_sensitivity", v.coerceIn(1, 10)).apply()

    /** Reload the page every N minutes. 0 = never. */
    var reloadMinutes: Int
        get() = p.getInt("reload_minutes", 0)
        set(v) = p.edit().putInt("reload_minutes", v).apply()

    /** Use Android screen pinning when the app is not device owner. */
    var screenPinning: Boolean
        get() = p.getBoolean("screen_pinning", true)
        set(v) = p.edit().putBoolean("screen_pinning", v).apply()

    /** Accept self-signed HTTPS certificates (common for local servers). */
    var allowSslErrors: Boolean
        get() = p.getBoolean("allow_ssl_errors", false)
        set(v) = p.edit().putBoolean("allow_ssl_errors", v).apply()

    /** Optional PIN required to open settings. Empty = no PIN. */
    var pin: String
        get() = p.getString("pin", "") ?: ""
        set(v) = p.edit().putString("pin", v.trim()).apply()
}
