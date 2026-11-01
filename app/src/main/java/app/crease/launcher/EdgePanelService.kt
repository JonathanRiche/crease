package app.crease.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.VelocityTracker
import java.util.concurrent.Executors

/**
 * A thin strip on the right edge of the inner screen. Swiping in from it opens a panel of pinned
 * and recent apps over whatever is running.
 *
 * This is an accessibility service only to get TYPE_ACCESSIBILITY_OVERLAY. It subscribes to no
 * events and cannot read window content (see res/xml/edge_panel_service.xml).
 */
class EdgePanelService : AccessibilityService() {
    private class PanelData(
        val rows: List<PanelRow>,
        val targets: Map<String, Pair<ComponentName, UserHandle>>,
        val accent: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val mono by lazy { resources.getFont(R.font.jetbrains_mono_regular) }

    private var strip: View? = null
    private var panel: PanelRoot? = null
    private var receiverRegistered = false

    private val windows get() = getSystemService(WindowManager::class.java)
    private val launcherApps get() = getSystemService(LauncherApps::class.java)

    private val deviceEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) closePanel() else sync()
        }
    }

    // ---- lifecycle ----

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            // Home and recents leave the panel up otherwise, since it holds no window focus.
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(deviceEvents, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(deviceEvents, filter)
            }
            receiverRegistered = true
        }
        sync()
    }

    /** Folding and unfolding arrive here, as does rotation. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removePanelNow()
        sync()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(deviceEvents)
        receiverRegistered = false
        removePanelNow()
        removeStrip()
        loader.shutdownNow()
        super.onDestroy()
    }

    // Required overrides. No event types are requested, so neither should ever do any work.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ---- strip ----

    /**
     * Accessibility overlays also draw over the lock screen, so everything is removed while
     * locked: the panel must not list or open apps there.
     */
    private fun allowed() = isInnerScreen(resources.configuration.smallestScreenWidthDp) &&
        getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Makes the windows match the current screen and lock state. */
    private fun sync() {
        if (!allowed()) {
            removePanelNow()
            removeStrip()
            return
        }
        val current = strip
        if (current == null) {
            val view = buildStrip()
            if (runCatching { windows.addView(view, stripParams()) }.isSuccess) strip = view
        } else {
            runCatching { windows.updateViewLayout(current, stripParams()) }
        }
    }

    private fun removeStrip() {
        strip?.let { view -> runCatching { windows.removeView(view) } }
        strip = null
    }

    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Screen coordinates, whatever the system bars are doing.
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun stripParams() = overlayParams(dp(STRIP_TOUCH_DP), dp(STRIP_HEIGHT_DP), 0).apply {
        // RIGHT rather than END: the edge shouldn't flip with the language.
        gravity = Gravity.RIGHT or Gravity.TOP
        y = dp(stripTopDp(resources.configuration.screenHeightDp, STRIP_HEIGHT_DP))
    }

    /** The touch area is wider than the faint line that is drawn. */
    private fun buildStrip(): View = FrameLayout(this).apply {
        addView(
            View(context).apply {
                setBackgroundColor(FAINT)
                alpha = STRIP_ALPHA
            },
            FrameLayout.LayoutParams(dp(STRIP_LINE_DP), MATCH, Gravity.RIGHT),
        )
        // Asks the system not to treat this stretch of edge as a back swipe. Limited to 200dp per edge.
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
        }
        var startX = 0f
        var startY = 0f
        var dragging = false
        var velocity: VelocityTracker? = null
        // The whole gesture stays with this strip, even once the panel's window is on top.
        setOnTouchListener { _, e ->
            velocity?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    dragging = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && isSwipe(startX - e.rawX, e.rawY - startY, dp(DRAG_START_DP).toFloat())) {
                        // Measure from here, so the panel doesn't jump when the drag begins.
                        startX = e.rawX
                        dragging = true
                        openPanel()
                    }
                    if (dragging) panel?.followFinger(startX - e.rawX)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        // Inward is towards smaller x on this right-hand strip.
                        panel?.settle(-(velocity?.xVelocity ?: 0f))
                    }
                    dragging = false
                    velocity?.recycle()
                    velocity = null
                }
            }
            true
        }
    }

    // ---- panel ----

    private fun openPanel() {
        if (panel != null || !allowed()) return
        val root = PanelRoot()
        val params = overlayParams(MATCH, MATCH, 0).apply { gravity = Gravity.LEFT or Gravity.TOP }
        if (runCatching { windows.addView(root, params) }.isFailure) return
        panel = root
        root.followFinger(0f)
        // Listing apps and reading usage events can be slow, so the rows arrive just after the slide starts.
        loader.execute {
            val data = load()
            main.post { if (panel === root) root.show(data) }
        }
    }

    private fun closePanel() {
        val root = panel ?: return
        panel = null
        root.slideOut { removeWindow(root) }
    }

    private fun removePanelNow() {
        val root = panel ?: return
        panel = null
        removeWindow(root)
    }

    private fun removeWindow(view: View) {
        runCatching { windows.removeView(view) }
    }

    private fun open(app: App, data: PanelData) {
        val target = data.targets[app.id]
        if (target != null) {
            runCatching { launcherApps.startMainActivity(target.first, target.second, null, null) }
        }
        closePanel()
    }

    /** Full-screen and transparent: a touch that misses the sheet lands here and closes it. */
    private inner class PanelRoot : FrameLayout(this@EdgePanelService) {
        private val widthPx = dp(PANEL_WIDTH_DP)
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var velocity: VelocityTracker? = null
        private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    isFocusable = false
                    addView(rows)
                },
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
        }
        private val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            // Touches on the sheet's empty areas must not count as tapping outside.
            isClickable = true
            addView(View(context).apply { setBackgroundColor(FAINT) }, LinearLayout.LayoutParams(dp(1), MATCH))
            addView(column, LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        init {
            addView(sheet, LayoutParams(widthPx, MATCH, Gravity.RIGHT))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val edge = dp(PANEL_PADDING_DP)
                column.setPadding(edge, maxOf(bars.top, dp(32)) + edge, bars.right + edge, bars.bottom + dp(8))
                insets
            }
        }

        /** Puts the sheet under the finger, [revealedPx] of it showing. */
        fun followFinger(revealedPx: Float) {
            sheet.animate().cancel()
            sheet.translationX = widthPx - revealedClamped(revealedPx, widthPx.toFloat())
        }

        /** On release: finish opening or closing, from wherever the finger left it. */
        fun settle(inwardPxPerSec: Float) {
            val revealed = widthPx - sheet.translationX
            if (settlesOpen(revealed, widthPx.toFloat(), inwardPxPerSec, dp(FLING_DP).toFloat())) {
                sheet.animate().translationX(0f).setDuration(SLIDE_MS)
            } else {
                closePanel()
            }
        }

        fun slideOut(done: () -> Unit) {
            sheet.animate().translationX(widthPx.toFloat()).setDuration(SLIDE_MS).withEndAction(done)
        }

        fun show(data: PanelData) {
            rows.removeAllViews()
            rows.addView(
                View(context).apply { setBackgroundColor(data.accent) },
                LinearLayout.LayoutParams(dp(7), dp(14)).apply { bottomMargin = dp(12) },
            )
            if (data.rows.isEmpty()) rows.addView(label("nothing pinned yet", DIM))
            var inRecents = false
            data.rows.forEach { row ->
                if (row.recent && !inRecents) {
                    inRecents = true
                    rows.addView(label("recent", FAINT).apply { setPadding(0, dp(18), 0, dp(4)) })
                }
                rows.addView(
                    label(row.app.label.lowercase(), TEXT).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setOnClickListener { open(row.app, data) }
                    },
                )
            }
        }

        private fun label(value: String, color: Int) = TextView(context).apply {
            text = value
            typeface = mono
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = false
            // Setting single line clears the minimum height, so this comes after it.
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
        }

        /** Sees touches before the rows and the scroll view, to catch a swipe back to the edge. */
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    dragging = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    if (isSwipe(e.rawX - startX, e.rawY - startY, dp(DRAG_START_DP).toFloat())) {
                        startX = e.rawX
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            velocity?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) followFinger(widthPx - (e.rawX - startX))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        settle(-(velocity?.xVelocity ?: 0f))
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        closePanel()
                    }
                    dragging = false
                    velocity?.recycle()
                    velocity = null
                }
            }
            return true
        }
    }

    // ---- data, off the main thread ----

    private fun load(): PanelData {
        val state = State()
        val targets = HashMap<String, Pair<ComponentName, UserHandle>>()
        val rows = runCatching {
            // Read fresh each time: the launcher may have changed pins since the last open.
            Store(this).load(state, mutableListOf())
            val users = getSystemService(UserManager::class.java)
            val me = Process.myUserHandle()
            val mine = ArrayList<App>()
            val apps = launcherApps.profiles.flatMap { user ->
                val serial = users.getSerialNumberForUser(user)
                launcherApps.getActivityList(null, user)
                    .filter { it.applicationInfo.packageName != packageName }
                    .map { info ->
                        // Must match the ids and labels in MainActivity.apps().
                        val id = "${info.componentName.flattenToString()}#$serial"
                        targets[id] = info.componentName to user
                        val name = state.names[id] ?: info.label.toString()
                        App(id, if (user == me) name else "$name (work)").also { if (user == me) mine += it }
                    }
            }.associateBy { it.id }
            panelRows(
                pins = state.panel.mapNotNull(apps::get),
                recentPackages = recentPackages(),
                appsByPackage = mine.sortedBy { it.label.lowercase() }.groupBy { packageOfAppId(it.id) },
                hidden = state.hidden,
            )
        }.getOrDefault(emptyList())
        val accent = (THEMES[state.theme] ?: THEMES.getValue(DEFAULT_THEME)).toInt()
        return PanelData(rows, targets, accent)
    }

    /** Most recent first. Empty without usage access, or if the system refuses. */
    private fun recentPackages(): List<String> {
        if (!EdgePanelAccess.hasUsageAccess(this)) return emptyList()
        return runCatching {
            val now = System.currentTimeMillis()
            val found = getSystemService(UsageStatsManager::class.java).queryEvents(now - RECENT_WINDOW_MS, now)
            val lastUsed = HashMap<String, Long>()
            val event = UsageEvents.Event()
            while (found != null && found.getNextEvent(event)) {
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastUsed[event.packageName] = event.timeStamp
            }
            lastUsed.entries.sortedByDescending { it.value }.map { it.key }
        }.getOrDefault(emptyList())
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private companion object {
        const val STRIP_HEIGHT_DP = 160
        const val STRIP_TOUCH_DP = 20
        const val STRIP_LINE_DP = 2
        const val STRIP_ALPHA = 0.6f
        const val DRAG_START_DP = 8
        const val FLING_DP = 600
        const val PANEL_WIDTH_DP = 240
        const val PANEL_PADDING_DP = 20
        const val SLIDE_MS = 160L
        const val RECENT_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        // Same values as MainActivity, whose colours are private.
        val TEXT = Color.parseColor("#E6DFCF")
        val DIM = Color.parseColor("#8A8478")
        val FAINT = Color.parseColor("#55514A")
    }
}

/** What the launcher needs to show whether the panel is on and link to the settings for it. */
object EdgePanelAccess {
    fun isEnabled(context: Context): Boolean =
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == context.packageName && info.name == EdgePanelService::class.java.name
            }

    fun hasUsageAccess(context: Context): Boolean {
        val mode = context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
