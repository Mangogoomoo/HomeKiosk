package com.home.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.http.SslError
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: KioskPrefs
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var blackout: View

    private val handler = Handler(Looper.getMainLooper())
    private var dimmed = false
    private var swallowGesture = false
    private var ignoreMotionUntil = 0L
    private var loadedUrl: String? = null
    private var motionDetector: MotionDetector? = null
    private val cornerTaps = ArrayDeque<Long>()

    // Swipe-in menu
    private lateinit var drawer: ScrollView
    private lateinit var scrim: View
    private lateinit var motionSwitch: SwitchCompat
    private lateinit var blackSwitch: SwitchCompat
    private lateinit var dimLabel: TextView
    private lateinit var dimSeek: SeekBar
    private var drawerOpen = false
    private var edgeTracking = false
    private var edgeStartX = 0f
    private var edgeStartY = 0f
    private val dimSteps = intArrayOf(0, 15, 30, 60, 120, 300, 600, 1800)

    private val dimRunnable = Runnable { dim() }
    private val retryRunnable = Runnable { loadedUrl?.let { webView.loadUrl(it) } }
    private val reloadRunnable = object : Runnable {
        override fun run() {
            webView.reload()
            scheduleReload()
        }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMotion()
            else Toast.makeText(this, "Camera denied – motion wake is off", Toast.LENGTH_LONG).show()
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPrefs(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this)
        blackout = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        buildDrawer()
        root.addView(webView, FrameLayout.LayoutParams(match, match))
        root.addView(scrim, FrameLayout.LayoutParams(match, match))
        root.addView(drawer, FrameLayout.LayoutParams(dp(DRAWER_DP), match, Gravity.START))
        root.addView(blackout, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        // With gesture navigation, ask Android to leave the middle of the left edge to us,
        // so the swipe opens our menu instead of being taken as a system "back" swipe.
        if (Build.VERSION.SDK_INT >= 29) {
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val mid = v.height / 2
                val half = dp(100) // Android allows at most 200dp per edge
                v.systemGestureExclusionRects = listOf(Rect(0, mid - half, dp(EDGE_DP), mid + half))
            }
        }

        setupWebView()

        // Back button only closes the menu; otherwise does nothing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerOpen) closeDrawer()
            }
        })

        setupDeviceOwnerPolicies()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()

        val url = prefs.url
        if (url.isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (url != loadedUrl) {
            loadedUrl = url
            webView.loadUrl(url)
        }

        wake()
        scheduleReload()

        applyMotionSetting()
        enterLockTask()
    }

    private fun applyMotionSetting() {
        if (prefs.motionEnabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) startMotion()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            motionDetector?.stop()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(reloadRunnable)
        CookieManager.getInstance().flush()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        motionDetector?.stop()
        try { webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(Color.BLACK)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isHapticFeedbackEnabled = false

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            // Keep every link inside the kiosk.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError,
            ) {
                // Dashboard unreachable (Wi-Fi drop, server restart): try again shortly.
                if (request.isForMainFrame) {
                    handler.removeCallbacks(retryRunnable)
                    handler.postDelayed(retryRunnable, 15_000)
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                if (prefs.allowSslErrors) sslHandler.proceed() else sslHandler.cancel()
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The web engine crashed: rebuild the screen instead of crashing the app.
                root.removeView(view)
                view.destroy()
                recreate()
                return true
            }
        }
    }

    private fun scheduleReload() {
        handler.removeCallbacks(reloadRunnable)
        val m = prefs.reloadMinutes
        if (m > 0) handler.postDelayed(reloadRunnable, m * 60_000L)
    }

    // ---------------------------------------------------------------- touch, dim, wake

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dimmed) {
                    // First touch on a dark screen only wakes it; it doesn't press anything.
                    swallowGesture = true
                    wake()
                    return true
                }
                swallowGesture = false
                edgeTracking = !drawerOpen && ev.x <= dp(EDGE_DP)
                edgeStartX = ev.x
                edgeStartY = ev.y
                checkSecretCorner(ev)
            }
            MotionEvent.ACTION_MOVE -> if (edgeTracking && !swallowGesture) {
                val dx = ev.x - edgeStartX
                val dy = abs(ev.y - edgeStartY)
                if (dx > dp(60) && dx > dy * 2) {
                    // Swipe in from the left edge: take the touch away from the page, open the menu.
                    edgeTracking = false
                    swallowGesture = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    requestDrawer()
                    return true
                } else if (dy > dp(80)) {
                    edgeTracking = false
                }
            }
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowGesture = false
            }
            return true
        }
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(dimRunnable)
        val s = prefs.idleSeconds
        if (s > 0) handler.postDelayed(dimRunnable, s * 1000L)
    }

    private fun wake() {
        val wasDimmed = dimmed
        dimmed = false
        setBrightness(prefs.awakeBrightness)
        blackout.animate().cancel()
        if (blackout.visibility == View.VISIBLE) {
            blackout.animate().alpha(0f).setDuration(300)
                .withEndAction { blackout.visibility = View.GONE }.start()
        }
        if (wasDimmed) ignoreMotionFor(2_500)
        resetIdleTimer()
    }

    private fun dim() {
        if (dimmed) return
        dimmed = true
        closeDrawer()
        setBrightness(prefs.dimBrightness)
        if (prefs.blackWhenIdle) {
            blackout.animate().cancel()
            blackout.visibility = View.VISIBLE
            blackout.animate().alpha(1f).setDuration(1_000).start()
        }
        // The screen going dark changes the light in the room; don't treat that as someone walking by.
        ignoreMotionFor(2_500)
    }

    /** percent: 0..100, or -1 to follow the tablet's own brightness setting. */
    private fun setBrightness(percent: Int) {
        val lp = window.attributes
        lp.screenBrightness =
            if (percent < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else (percent / 100f).coerceIn(0f, 1f)
        window.attributes = lp
    }

    private fun ignoreMotionFor(ms: Long) {
        ignoreMotionUntil = SystemClock.elapsedRealtime() + ms
        motionDetector?.resetBaseline()
    }

    private fun startMotion() {
        val detector = motionDetector ?: MotionDetector(this, this) {
            runOnUiThread { onMotion() }
        }.also { motionDetector = it }
        detector.sensitivity = prefs.motionSensitivity
        detector.start()
    }

    private fun onMotion() {
        if (SystemClock.elapsedRealtime() < ignoreMotionUntil) return
        if (dimmed) wake() else resetIdleTimer() // someone still standing there keeps it awake
    }

    // ---------------------------------------------------------------- swipe-in menu

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildDrawer() {
        scrim = View(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        drawer = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(28, 32, 40))
            visibility = View.GONE
            isClickable = true
            addView(content)
        }

        fun addText(t: String, size: Float, dim: Boolean = false) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(if (dim) Color.argb(170, 255, 255, 255) else Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
        }.also { content.addView(it) }

        fun addSwitch(t: String, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
            text = t
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(14))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }.also { content.addView(it) }

        fun addButton(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }.also {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(10)
            content.addView(it, lp)
        }

        addText("Kiosk menu", 24f)

        motionSwitch = addSwitch("Wake on movement (camera)") { on ->
            if (prefs.motionEnabled != on) {
                prefs.motionEnabled = on
                applyMotionSetting()
            }
        }
        blackSwitch = addSwitch("Black screen when dimmed") { on -> prefs.blackWhenIdle = on }

        dimLabel = addText("", 17f)
        dimSeek = SeekBar(this).apply {
            max = dimSteps.size - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    dimLabel.text = "Dim after: ${formatSeconds(dimSteps[p])}"
                    if (fromUser) prefs.idleSeconds = dimSteps[p]
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        content.addView(dimSeek)

        addButton("Dim now") { closeDrawer(); handler.post { dim() } }
        addButton("Reload page") { closeDrawer(); webView.reload() }
        addButton("All settings…") {
            closeDrawer()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        addButton("Exit kiosk") { exitKiosk() }
        addButton("Close") { closeDrawer() }
        addText("Swipe in from the left edge to open this menu.", 13f, dim = true)
    }

    private fun formatSeconds(s: Int) = when {
        s <= 0 -> "never"
        s < 60 -> "$s seconds"
        s < 3600 -> if (s / 60 == 1) "1 minute" else "${s / 60} minutes"
        else -> "${s / 3600} h"
    }

    /** Opens the menu, asking for the PIN first if one is set. */
    private fun requestDrawer() {
        val pin = prefs.pin
        if (pin.isEmpty()) {
            openDrawer()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == pin) openDrawer()
                else Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { hideSystemUi() }
            .show()
    }

    private fun openDrawer() {
        // Show current values
        motionSwitch.isChecked = prefs.motionEnabled
        blackSwitch.isChecked = prefs.blackWhenIdle
        val idle = prefs.idleSeconds
        dimSeek.progress = dimSteps.indices.minByOrNull { abs(dimSteps[it] - idle) } ?: 3
        dimLabel.text = "Dim after: ${formatSeconds(dimSteps[dimSeek.progress])}"

        drawerOpen = true
        val w = dp(DRAWER_DP).toFloat()
        scrim.animate().cancel()
        drawer.animate().cancel()
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(200).start()
        drawer.translationX = -w
        drawer.visibility = View.VISIBLE
        drawer.animate().translationX(0f).setDuration(220).start()
        resetIdleTimer()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        val w = dp(DRAWER_DP).toFloat()
        scrim.animate().cancel()
        drawer.animate().cancel()
        scrim.animate().alpha(0f).setDuration(200)
            .withEndAction { scrim.visibility = View.GONE }.start()
        drawer.animate().translationX(-w).setDuration(200)
            .withEndAction { drawer.visibility = View.GONE }.start()
        hideSystemUi()
    }

    private fun exitKiosk() {
        closeDrawer()
        try { stopLockTask() } catch (_: Exception) {}
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(this, "Press Home to return to the dashboard", Toast.LENGTH_LONG).show()
    }

    /** Backup way in: tap the top-left corner 5 times within 3 seconds. */
    private fun checkSecretCorner(ev: MotionEvent) {
        val zone = 80 * resources.displayMetrics.density
        if (ev.x > zone || ev.y > zone) {
            cornerTaps.clear()
            return
        }
        val now = SystemClock.elapsedRealtime()
        cornerTaps.addLast(now)
        while (cornerTaps.isNotEmpty() && cornerTaps.first() < now - 3_000) cornerTaps.removeFirst()
        if (cornerTaps.size >= 5) {
            cornerTaps.clear()
            requestDrawer()
        }
    }

    // ---------------------------------------------------------------- kiosk lock

    private fun hideSystemUi() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * If the app was made device owner (see README), lock the tablet down completely:
     * no home/recents/back bar, no notifications, app is the home screen, no lock screen.
     */
    private fun setupDeviceOwnerPolicies() {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = KioskAdminReceiver.component(this)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= 28) {
                // Only the power menu stays available (so the tablet can still be restarted).
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            }
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(this, MainActivity::class.java))
            dpm.setKeyguardDisabled(admin, true)
            dpm.setPermissionGrantState(
                admin, packageName, Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            val plugged = BatteryManager.BATTERY_PLUGGED_AC or
                BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enterLockTask() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val permitted = dpm?.isLockTaskPermitted(packageName) == true
        if (permitted || prefs.screenPinning) {
            try {
                startLockTask() // device owner: silent full lock. Otherwise: Android's "pin app" prompt.
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        /** Width of the left-edge strip where a swipe starts. */
        private const val EDGE_DP = 32
        /** Width of the slide-in menu. */
        private const val DRAWER_DP = 360
    }
}
