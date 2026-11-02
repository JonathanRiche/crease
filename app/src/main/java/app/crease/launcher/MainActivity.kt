package app.crease.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity(), Host {
    private lateinit var store: Store
    private lateinit var backdrop: Backdrop
    private var backdropNote: String? = null
    private var pickingPhoto = false
    private lateinit var shell: Shell
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgetHost: AppWidgetHost

    private val state = State()
    private val widgetRefs = mutableListOf<WidgetRef>()
    private val appTargets = HashMap<String, Pair<ComponentName, UserHandle>>()
    private var appCache: List<App>? = null
    private var pendingWidget = AppWidgetManager.INVALID_APPWIDGET_ID
    private val openFolders = hashSetOf<String>()

    private lateinit var root: LinearLayout
    private lateinit var prompt: EditText
    private lateinit var outputScroll: ScrollView
    private lateinit var outputBox: LinearLayout
    private lateinit var suggestBox: LinearLayout
    private lateinit var shortcutBox: LinearLayout
    private lateinit var widgetScroll: ScrollView
    private lateinit var widgetBox: LinearLayout
    private lateinit var folderBox: LinearLayout
    private lateinit var suggestRow: HorizontalScrollView
    private lateinit var homeScroll: ScrollView
    private lateinit var homeStage: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var headerRow: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var clockView: View
    private lateinit var hintView: TextView
    private enum class Mode { HOME, SEARCH, EDIT, MENU, PICK, NAME, ICON }
    private var mode = Mode.HOME
    /** Screens to return to on back. */
    private val trail = ArrayDeque<Mode>()
    private val widgetCells = HashMap<Int, View>()
    private var resizingId: Int? = null
    private var resizeControls: View? = null
    private var pickDock = false
    private var pickPanel = false
    private var iconApp: App? = null
    private lateinit var dockBar: LinearLayout
    private var menuApp: App? = null
    private var pickFolder: String? = null
    private var namePrompt: ((String) -> Unit)? = null
    private var twoPane = false
    private var idleShown = false
    private var shownTotal = 0L
    private var shownGeneration = 0

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = appsChanged()
        override fun onPackageAdded(p: String, u: UserHandle) = appsChanged()
        override fun onPackageChanged(p: String, u: UserHandle) = appsChanged()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = appsChanged()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = appsChanged()
    }

    private val accent get() = (THEMES[state.theme] ?: THEMES.getValue(DEFAULT_THEME)).toInt()
    private val DIM get() = if (state.background == BG_BLACK) BLACK_DIM else PHOTO_DIM
    private val FAINT get() = if (state.background == BG_BLACK) BLACK_FAINT else PHOTO_FAINT
    private val screen get() = if (resources.configuration.smallestScreenWidthDp >= 600) INNER else COVER
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        pendingWidget = saved?.getInt(PENDING_WIDGET, pendingWidget) ?: pendingWidget
        launcherApps = getSystemService(LauncherApps::class.java)
        userManager = getSystemService(UserManager::class.java)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        store = Store(this)
        store.load(state, widgetRefs)
        shell = Shell(state, this)
        backdrop = Backdrop(this, state, ::backdropChanged)
        backdrop.start()
        reconcileWidgets()

        window.setDecorFitsSystemWindows(false)
        launcherApps.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { back() }
        }
        build()
    }

    @Deprecated("Used below API 33")
    override fun onBackPressed() = back()

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(PENDING_WIDGET, pendingWidget)
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgetHost.stopListening() }
        // Come back to a clean screen after using an app, but not mid widget setup.
        if (pendingWidget == AppWidgetManager.INVALID_APPWIDGET_ID && !pickingPhoto) resetInput()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(packageCallback)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (state.autoKeyboard && mode == Mode.HOME) openSearch()
    }

    /** Pressing home while already home. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pickingPhoto = false
        resetInput()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizingId = null
        build()
    }

    // ---- layout ----

    private val mono by lazy { resources.getFont(R.font.jetbrains_mono_regular) }
    private val monoLight by lazy { resources.getFont(R.font.jetbrains_mono_light) }

    private fun build() {
        val cfg = resources.configuration
        val draft = if (::prompt.isInitialized) prompt.text.toString() else ""
        twoPane = cfg.screenWidthDp >= 600 && cfg.screenHeightDp >= 480

        widgetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        widgetScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(widgetBox)
        }
        shortcutBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        folderBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFillViewport = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // Centred both ways on the cover and when centred on the inner screen.
                gravity = if (twoPane && innerAlign != ALIGN_CENTER) Gravity.TOP else Gravity.CENTER
                addView(shortcutBox)
                addView(folderBox)
            })
        }
        hintView = TextView(this).apply {
            typeface = mono
            setTextColor(FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openSearch() }
            isFocusable = false
        }
        dockBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        clockView = clock(if (twoPane) 72f else 56f)
        homeStage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = buildPanel()
        val stage = FrameLayout(this).apply {
            addView(homeStage, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        root = GestureRoot()
        root.orientation = LinearLayout.VERTICAL
        root.background = backdrop.drawable
        if (twoPane) {
            val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(clockView)
            when (innerAlign) {
                ALIGN_LEFT -> {
                    left.addView(homeScroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(28) })
                    homeStage.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
                }
                ALIGN_RIGHT -> {
                    // Shares the right half with widgets: they go first, so neither covers the other.
                    homeStage.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, coverWidgetHeight()))
                    homeStage.addView(homeScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
                }
                else -> homeStage.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            homeStage.addView(hintView)
            // Equal weights put the gap on the crease, so no text sits in the fold.
            panes.addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))
            panes.addView(View(this), LinearLayout.LayoutParams(dp(CREASE_GAP_DP), MATCH))
            panes.addView(stage, LinearLayout.LayoutParams(0, MATCH, 1f))
            val body = FrameLayout(this)
            body.addView(panes, FrameLayout.LayoutParams(MATCH, MATCH))
            // Only as wide as its text, so touches beside it still reach any widgets.
            if (innerAlign == ALIGN_CENTER) {
                body.addView(homeScroll, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.CENTER_HORIZONTAL))
            }
            root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            // Spans both halves, so the dock is centred on the whole screen.
            root.addView(dockBar, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            root.addView(clockView)
            homeStage.setPadding(0, dp(20), 0, 0)
            homeStage.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, coverWidgetHeight()))
            homeStage.addView(homeScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            homeStage.addView(hintView)
            homeStage.addView(dockBar)
            root.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        val edge = dp(if (twoPane) 32 else 24)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left + edge, bars.top + dp(28), bars.right + edge, maxOf(bars.bottom, ime.bottom) + dp(8))
            insets
        }
        setContentView(root)
        prompt.setText(draft)
        prompt.setSelection(draft.length)
        renderWidgets()
        renderHome()
        applyMode()
    }

    /** Large thin time, with a small lowercase date and one accent block beneath it. */
    private fun clock(size: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val lower = object : android.text.method.ReplacementTransformationMethod() {
            override fun getOriginal() = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
            override fun getReplacement() = "abcdefghijklmnopqrstuvwxyz".toCharArray()
        }
        addView(TextClock(context).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            typeface = monoLight
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            isSingleLine = true
            includeFontPadding = false
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(TextClock(context).apply {
                format24Hour = "EEE d MMM"
                format12Hour = "EEE d MMM"
                typeface = mono
                setTextColor(DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                isSingleLine = true
                transformationMethod = lower
            })
            addView(View(context).apply { setBackgroundColor(accent) },
                LinearLayout.LayoutParams(dp(7), dp(14)).apply { marginStart = dp(8) })
        })
    }

    /** The swipe-up panel: search and commands, plus the edit and menu screens. */
    private fun buildPanel(): LinearLayout {
        headerTitle = TextView(this).apply {
            typeface = mono
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(headerTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chip("done", accent) { back() }.apply { setPadding(dp(18), 0, 0, 0) })
        }
        outputBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outputScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(outputBox)
        }
        suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(suggestBox)
        }
        prompt = EditText(this).apply {
            typeface = mono
            setTextColor(TEXT)
            setHintTextColor(FAINT)
            hint = "search"
            background = null
            isSingleLine = true
            textCursorDrawable?.setTint(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(12), 0, dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            // A hardware Enter reports both key down and key up; act on one of them.
            setOnEditorActionListener { _, _, event ->
                if (event == null || event.action == android.view.KeyEvent.ACTION_DOWN) submit(text.toString())
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = queryChanged()
            })
        }
        promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = ">"
                typeface = mono
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(outputScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(suggestRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(promptRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun lineView(line: Line) = TextView(this).apply {
        text = if (line.kind == Kind.ITEM) line.text.lowercase() else line.text
        typeface = mono
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (line.action != null) 15f else 13f)
        setTextColor(
            when (line.kind) {
                Kind.ECHO -> FAINT
                Kind.OUT -> DIM
                Kind.ERR -> ERROR
                Kind.ITEM -> TEXT
            },
        )
        val action = line.action
        if (action != null) {
            // Finger-sized rows: anything tappable is at least 40dp tall.
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { perform(action) }
            isFocusable = false
        } else {
            setPadding(0, dp(2), 0, dp(2))
        }
    }

    private fun chip(label: String, color: Int = TEXT, onTap: () -> Unit) = TextView(this).apply {
        text = label.lowercase()
        typeface = mono
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(40)
        setPadding(0, 0, dp(18), 0)
        setOnClickListener { onTap() }
        isFocusable = false
    }

    /** A control at the end of a row, such as an arrow or a remove mark. */
    private class Control(val label: String, val color: Int?, val onTap: (TextView) -> Unit)

    private fun row(
        label: String,
        color: Int = TEXT,
        onHold: (() -> Unit)? = null,
        controls: List<Control> = emptyList(),
        onTap: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(chip(label, color, onTap).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            // Setting single line clears the minimum height, so restore the finger-sized row.
            minHeight = dp(44)
            if (onHold != null) setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onHold()
                true
            }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        controls.forEach { control ->
            addView(TextView(context).apply {
                text = control.label
                typeface = mono
                setTextColor(control.color ?: DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                minHeight = dp(40)
                minWidth = dp(44)
                setOnClickListener { control.onTap(this) }
                isFocusable = false
            })
        }
    }

    // ---- home ----

    private fun renderHome() {
        shortcutBox.removeAllViews()
        shortcutBox.gravity = homeGravity
        state.pins.mapNotNull(::byIdOrNull).forEach { app ->
            shortcutBox.addView(row(app.label, onHold = { openMenu(app) }) { launch(app) })
            alignHomeRow(shortcutBox.getChildAt(shortcutBox.childCount - 1))
        }
        renderFolders()
        renderDock()
        hintView.text = if (state.pins.isEmpty() && state.folders.isEmpty()) {
            "swipe up for apps · hold to edit"
        } else {
            "apps"
        }
        // Once there's a dock, the bottom of the screen is busy enough.
        hintView.visibility = if (dockBar.childCount > 0) View.GONE else View.VISIBLE
    }

    private fun renderDock() {
        dockBar.removeAllViews()
        state.dock.forEach { item ->
            val app = byIdOrNull(item.appId) ?: return@forEach
            val icon = item.icon?.takeIf { it in DOCK_ICONS }
            val content: View = if (icon != null) IconView(this, icon, TEXT) else TextView(this).apply {
                text = app.label.lowercase()
                typeface = mono
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            dockBar.addView(FrameLayout(this).apply {
                val size = if (icon != null) dp(DOCK_ICON_DP) else WRAP
                addView(content, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                contentDescription = app.label
                isFocusable = false
                setOnClickListener { launch(app) }
                setOnLongClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    openMenu(app)
                    true
                }
            }, if (twoPane) {
                // Stretched across this wide screen the buttons would drift far apart.
                LinearLayout.LayoutParams(dp(DOCK_SLOT_DP), dp(DOCK_HEIGHT_DP))
            } else {
                LinearLayout.LayoutParams(0, dp(DOCK_HEIGHT_DP), 1f)
            })
        }
    }

    private val innerAlign get() = state.homeAlign
    private val homeGravity get() = when {
        !twoPane || innerAlign == ALIGN_CENTER -> Gravity.CENTER_HORIZONTAL
        innerAlign == ALIGN_RIGHT -> Gravity.END
        else -> Gravity.START
    }

    /**
     * Shrinks a home row to its text and removes the padding that spaces chips apart, which would
     * push it off-centre. A full-width row would also take taps and holds on the empty space beside it.
     */
    private fun alignHomeRow(view: View) {
        if (homeGravity == Gravity.START) return
        view.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        val text = (view as? ViewGroup)?.getChildAt(0) as? TextView ?: view as? TextView ?: return
        text.setPadding(0, 0, 0, 0)
    }

    private fun renderFolders() {
        folderBox.removeAllViews()
        folderBox.gravity = homeGravity
        state.folders.forEach { (name, ids) ->
            val isOpen = name in openFolders
            folderBox.addView(chip("$name/", if (isOpen) accent else DIM) {
                if (!openFolders.add(name)) openFolders.remove(name)
                renderFolders()
            })
            alignHomeRow(folderBox.getChildAt(folderBox.childCount - 1))
            if (isOpen) ids.mapNotNull(::byIdOrNull).forEach { app ->
                folderBox.addView(
                    row(if (homeGravity == Gravity.START) "  ${app.label}" else app.label, onHold = { openMenu(app) }) { launch(app) },
                )
                alignHomeRow(folderBox.getChildAt(folderBox.childCount - 1))
            }
        }
    }

    // ---- modes ----

    private fun applyMode() {
        val home = mode == Mode.HOME
        backdrop.setPanelOpen(!home)
        homeStage.visibility = if (home) View.VISIBLE else View.GONE
        dockBar.visibility = if (home) View.VISIBLE else View.GONE
        // Centred apps sit over both halves, so they'd collide with search and edit.
        if (twoPane && innerAlign == ALIGN_CENTER) homeScroll.visibility = if (home) View.VISIBLE else View.GONE
        panel.visibility = if (home) View.GONE else View.VISIBLE
        // The cover needs the room; the inner screen keeps its clock on the other half.
        clockView.visibility = if (home || twoPane) View.VISIBLE else View.GONE
        val typing = mode == Mode.SEARCH || mode == Mode.NAME
        promptRow.visibility = if (typing) View.VISIBLE else View.GONE
        headerRow.visibility = if (mode == Mode.SEARCH || home) View.GONE else View.VISIBLE
        if (typing) showKeyboard() else hideKeyboard()
        shell.clearOutput()
        when (mode) {
            Mode.HOME -> outputBox.removeAllViews()
            Mode.SEARCH, Mode.PICK -> {
                outputBox.removeAllViews()
                idleShown = false
                shownGeneration = shell.generation
                shownTotal = shell.total
                headerTitle.text = if (pickDock) "in dock" else if (pickPanel) "in edge panel" else pickFolder?.let { "in $it/" } ?: "on home"
                syncOutput()
            }
            Mode.NAME -> {
                outputBox.removeAllViews()
                suggestRow.visibility = View.GONE
            }
            Mode.EDIT -> renderEdit()
            Mode.MENU -> renderMenu()
            Mode.ICON -> renderIcons()
        }
        if (home) renderHome()
    }

    private fun setMode(next: Mode) {
        resizingId = null
        // Home is the root: nothing should be waiting to reopen once you're back there.
        if (next == Mode.HOME) trail.clear()
        prompt.setText("")
        mode = next
        applyMode()
    }

    private fun openSearch() {
        trail.clear()
        namePrompt = null
        setMode(Mode.SEARCH)
    }

    private fun openEdit() {
        trail.clear()
        backdropNote = null
        setMode(Mode.EDIT)
        // Always start at the top, not wherever the list was left last time.
        outputScroll.post { outputScroll.scrollTo(0, 0) }
    }

    private fun push(next: Mode) {
        trail.addLast(mode)
        setMode(next)
    }

    private fun openMenu(app: App) {
        menuApp = app
        push(Mode.MENU)
    }

    private fun openPicker(folder: String?, dock: Boolean = false, edgePanel: Boolean = false) {
        pickFolder = folder
        pickDock = dock
        pickPanel = edgePanel
        push(Mode.PICK)
    }

    private fun openIcons(app: App) {
        iconApp = app
        push(Mode.ICON)
    }

    private fun askName(title: String, draft: String, onName: (String) -> Unit) {
        namePrompt = onName
        headerTitle.text = title
        push(Mode.NAME)
        prompt.hint = title
        prompt.setText(draft)
        prompt.setSelection(draft.length)
    }

    private fun goHome() {
        namePrompt = null
        pickFolder = null
        pickDock = false
        pickPanel = false
        trail.clear()
        prompt.hint = "search"
        if (::prompt.isInitialized) setMode(Mode.HOME)
    }

    private fun back() {
        if (resizingId != null) return stopResizing()
        // Back on home does nothing, as on any launcher.
        if (mode == Mode.HOME) return goHome()
        namePrompt = null
        pickFolder = null
        pickDock = false
        pickPanel = false
        prompt.hint = "search"
        val previous = trail.removeLastOrNull() ?: return goHome()
        setMode(previous)
    }

    private fun hideKeyboard() {
        prompt.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(prompt.windowToken, 0)
    }

    private fun showKeyboard() {
        prompt.requestFocus()
        prompt.post { prompt.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }

    // ---- search ----

    private fun queryChanged() {
        if (mode != Mode.SEARCH) return
        if (shell.output.isNotEmpty() && prompt.text.isNotEmpty()) shell.clearOutput()
        syncOutput()
        renderSuggestions()
    }

    /** Only commands and arguments here: apps already fill the list above. */
    private fun renderSuggestions() {
        suggestBox.removeAllViews()
        val found = shell.suggest(prompt.text.toString()).filterNot { it.fill.startsWith("open ") }
        found.forEachIndexed { i, s ->
            suggestBox.addView(chip(s.label, if (i == 0) accent else DIM) { perform(Action(s.fill, s.run)) })
        }
        suggestRow.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Apps matching the query, each with a mark that puts it on home or in a folder. */
    private fun renderIdle() {
        outputBox.removeAllViews()
        idleShown = true
        outputBox.gravity = Gravity.TOP
        val query = if (mode == Mode.SEARCH) prompt.text.toString().trim() else ""
        val folder = pickFolder
        val members = when {
            folder != null -> state.folders[folder] ?: return
            pickPanel -> state.panel
            else -> state.pins
        }
        val dock = pickDock
        fun has(id: String) = if (dock) state.dock.any { it.appId == id } else id in members
        val all = shell.visibleApps()
        val apps = if (query.isEmpty()) all else all
            .mapNotNull { a -> score(query, a.label)?.let { a to it } }
            .sortedWith(compareBy({ it.second }, { it.first.label.lowercase() }))
            .map { it.first }
        apps.forEach { app ->
            fun mark() = if (has(app.id)) "✓" else "+"
            fun toggle(view: TextView?) {
                if (dock) {
                    if (!state.dock.toggleApp(app.id, iconFor(app))) headerTitle.text = "dock is full ($DOCK_MAX)"
                } else if (!members.remove(app.id)) {
                    members += app.id
                }
                save()
                view?.text = mark()
                view?.setTextColor(if (has(app.id)) accent else DIM)
                renderHome()
            }
            val control = Control(mark(), if (has(app.id)) accent else DIM) { toggle(it) }
            outputBox.addView(
                row(app.label, onHold = { openMenu(app) }, controls = listOf(control)) {
                    if (mode == Mode.PICK) {
                        toggle(null)
                        renderIdle()
                    } else {
                        launch(app)
                    }
                },
            )
        }
        outputScroll.post { outputScroll.scrollTo(0, 0) }
    }

    private fun iconFor(app: App) = suggestIcon(app.label, app.id.substringBefore('/'))

    private fun syncOutput() {
        if (shownGeneration != shell.generation) {
            outputBox.removeAllViews()
            idleShown = false
            shownGeneration = shell.generation
            shownTotal = shell.total
        }
        if (shell.output.isEmpty()) {
            if (mode == Mode.SEARCH || mode == Mode.PICK) renderIdle()
            return
        }
        if (mode != Mode.SEARCH) return
        if (idleShown) {
            outputBox.removeAllViews()
            idleShown = false
        }
        outputBox.gravity = Gravity.BOTTOM
        val fresh = (shell.total - shownTotal).coerceAtMost(shell.output.size.toLong()).toInt()
        val lines = shell.output.takeLast(fresh)
        val firstNew = lines.indexOfLast { it.kind == Kind.ECHO }.coerceAtLeast(0)
        val anchorIndex = outputBox.childCount + firstNew
        lines.forEach { outputBox.addView(lineView(it)) }
        shownTotal = shell.total
        while (outputBox.childCount > shell.output.size) outputBox.removeViewAt(0)
        if (lines.isNotEmpty()) scrollToChildAfterLayout(anchorIndex)
    }

    /**
     * Shows the latest result from its first line, so a long `ls` starts at "a", not "z".
     * Not fullScroll(): it moves focus to the last row, and Enter would then tap it.
     */
    private fun scrollToChildAfterLayout(index: Int) {
        val observer = outputBox.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                outputBox.viewTreeObserver.removeOnPreDrawListener(this)
                val child = outputBox.getChildAt(index.coerceAtMost(outputBox.childCount - 1))
                if (child != null) outputScroll.scrollTo(0, child.top)
                return true
            }
        })
        outputBox.requestLayout()
    }

    private fun perform(action: Action) {
        if (action.run) submit(action.cmd) else {
            prompt.setText(action.cmd)
            prompt.setSelection(action.cmd.length)
            showKeyboard()
        }
    }

    private fun submit(cmd: String) {
        namePrompt?.let { onName ->
            namePrompt = null
            onName(cmd.trim())
            return
        }
        if (cmd.isBlank()) return
        prompt.setText("")
        shell.run(cmd)
        if (mode == Mode.SEARCH) {
            syncOutput()
            renderSuggestions()
        }
        renderHome()
    }

    /** Clean home screen. Also used by the shell when an app opens. */
    private fun resetInput() = goHome()

    // ---- edit ----

    private fun section(title: String) = TextView(this).apply {
        text = title
        typeface = mono
        setTextColor(FAINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun renderEdit() {
        outputBox.removeAllViews()
        outputBox.gravity = Gravity.TOP
        headerTitle.text = "edit home"
        val edit = {
            save()
            renderHome()
            renderEdit()
        }

        outputBox.addView(section("apps"))
        val pins = state.pins.mapNotNull(::byIdOrNull)
        pins.forEach { app ->
            val i = state.pins.indexOf(app.id)
            outputBox.addView(
                row(
                    app.label,
                    controls = listOf(
                        Control("↑", null) { state.pins.moveBy(i, -1); edit() },
                        Control("↓", null) { state.pins.moveBy(i, 1); edit() },
                        Control("×", null) { state.pins.removeAt(i); edit() },
                    ),
                ) { openMenu(app) },
            )
        }
        outputBox.addView(chip("+ choose apps", accent) { openPicker(null) })
        if (twoPane) outputBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("position:", FAINT) {})
            HOME_ALIGNS.forEach { align ->
                addView(chip(align, if (align == state.homeAlign) accent else DIM) {
                    state.homeAlign = align
                    save()
                    build()
                })
            }
        })

        outputBox.addView(section("dock"))
        state.dock.toList().forEachIndexed { i, item ->
            val app = byIdOrNull(item.appId) ?: return@forEachIndexed
            val line = row(
                app.label,
                controls = listOf(
                    Control("↑", null) { state.dock.moveBy(i, -1); edit() },
                    Control("↓", null) { state.dock.moveBy(i, 1); edit() },
                    Control("×", null) { state.dock.removeAt(i); edit() },
                ),
            ) { openIcons(app) }
            item.icon?.takeIf { it in DOCK_ICONS }?.let { icon ->
                line.addView(
                    IconView(this, icon, DIM),
                    0,
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) },
                )
            }
            outputBox.addView(line)
        }
        outputBox.addView(chip("+ choose dock apps", accent) { openPicker(null, dock = true) })

        outputBox.addView(section("folders"))
        state.folders.keys.toList().forEach { name ->
            val count = state.folders[name].orEmpty().count { byIdOrNull(it) != null }
            outputBox.addView(
                row(
                    "$name/  $count",
                    controls = listOf(Control("×", null) { state.folders.remove(name); openFolders.remove(name); edit() }),
                ) { openPicker(name) },
            )
        }
        outputBox.addView(chip("+ new folder", accent) {
            askName("folder name", "") { typed ->
                val name = typed.replace(Regex("\\s+"), "-")
                if (name.isNotEmpty() && state.folders.keys.none { it.equals(name, true) }) {
                    state.folders[name] = mutableListOf()
                    save()
                }
                back()
            }
        })

        outputBox.addView(section("widgets on this screen"))
        widgetsHere().forEach { ref ->
            // Two lines: widget names are long, and the cover screen is narrow.
            outputBox.addView(chip(ref.label) {})
            outputBox.addView(
                row(
                    "${ref.heightDp}dp",
                    DIM,
                    controls = listOf(
                        Control("↑", null) { moveWidget(ref, -1); edit() },
                        Control("↓", null) { moveWidget(ref, 1); edit() },
                        Control("−", null) { resizeWidget(ref, (ref.heightDp - WIDGET_STEP_DP).coerceAtLeast(MIN_WIDGET_DP)); edit() },
                        Control("+", null) { resizeWidget(ref, ref.heightDp + WIDGET_STEP_DP); edit() },
                        Control("×", null) { removeWidget(ref); edit() },
                    ),
                ) {},
            )
        }
        outputBox.addView(chip("+ add widget", accent) {
            openSearch()
            prompt.post { submit("widget add") }
        })

        outputBox.addView(section("edge panel"))
        state.panel.mapNotNull(::byIdOrNull).forEach { app ->
            val i = state.panel.indexOf(app.id)
            outputBox.addView(
                row(
                    app.label,
                    controls = listOf(
                        Control("↑", null) { state.panel.moveBy(i, -1); edit() },
                        Control("↓", null) { state.panel.moveBy(i, 1); edit() },
                        Control("×", null) { state.panel.removeAt(i); edit() },
                    ),
                ) { openMenu(app) },
            )
        }
        outputBox.addView(chip("+ choose panel apps", accent) { openPicker(null, edgePanel = true) })
        outputBox.addView(row(if (EdgePanelAccess.isEnabled(this)) "panel on" else "panel off") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        outputBox.addView(row(if (EdgePanelAccess.hasUsageAccess(this)) "recents on" else "recents off") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        outputBox.addView(section("colour"))
        outputBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            THEMES.keys.forEach { name ->
                addView(chip(name, if (name == state.theme) accent else DIM) {
                    state.theme = name
                    save()
                    build()
                })
            }
        }.let { strip ->
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            }
        })

        outputBox.addView(section("background"))
        fun marked(kind: String) = if (state.background == kind) accent else DIM
        outputBox.addView(chip("black", marked(BG_BLACK)) { backdrop.select(BG_BLACK) })
        outputBox.addView(chip("system wallpaper", marked(BG_WALLPAPER)) { backdrop.select(BG_WALLPAPER) })
        outputBox.addView(chip(if (backdrop.importing) "loading photo..." else "choose photo", marked(BG_PHOTO)) {
            pickingPhoto = true
            if (!backdrop.launchPicker(REQ_PHOTO)) pickingPhoto = false
        })
        backdropNote?.let { outputBox.addView(lineView(Line(it, Kind.ERR))) }
        if (state.background != BG_BLACK) {
            outputBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chip("dim:", FAINT) {})
                DIM_LEVELS.keys.forEach { level ->
                    addView(chip(level, if (level == state.dim) accent else DIM) { backdrop.setDim(level) })
                }
            })
        }
    }

    private fun backdropChanged(note: String?, recolour: Boolean) {
        backdropNote = note
        save()
        if (recolour) {
            val y = outputScroll.scrollY
            build()
            outputScroll.post { outputScroll.scrollTo(0, y) }
        } else if (mode == Mode.EDIT) {
            renderEdit()
        }
    }

    /** Pick what a dock button shows: any icon, or the app's name. */
    private fun renderIcons() {
        val app = iconApp ?: return back()
        outputBox.removeAllViews()
        outputBox.gravity = Gravity.TOP
        headerTitle.text = "icon for ${app.label.lowercase()}"
        val current = state.dock.firstOrNull { it.appId == app.id }?.icon
        fun choose(icon: String?) {
            val i = state.dock.indexOfFirst { it.appId == app.id }
            if (i >= 0) state.dock[i] = state.dock[i].copy(icon = icon)
            save()
            renderHome()
            back()
        }
        outputBox.addView(chip("text: ${app.label}", if (current == null) accent else TEXT) { choose(null) })
        DOCK_ICONS.chunked(ICON_COLUMNS).forEach { names ->
            outputBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                names.forEach { name ->
                    addView(FrameLayout(context).apply {
                        contentDescription = name
                        isFocusable = false
                        setOnClickListener { choose(name) }
                        addView(
                            IconView(context, name, if (name == current) accent else TEXT),
                            FrameLayout.LayoutParams(dp(DOCK_ICON_DP), dp(DOCK_ICON_DP), Gravity.CENTER),
                        )
                    }, LinearLayout.LayoutParams(0, dp(DOCK_HEIGHT_DP), 1f))
                }
                // Keep a short last row on the same columns.
                repeat(ICON_COLUMNS - names.size) {
                    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                }
            })
        }
    }

    private fun moveWidget(ref: WidgetRef, delta: Int) {
        val here = widgetsHere()
        val neighbour = here.getOrNull(here.indexOf(ref) + delta) ?: return
        val a = widgetRefs.indexOf(ref)
        val b = widgetRefs.indexOf(neighbour)
        widgetRefs[a] = neighbour
        widgetRefs[b] = ref
        save()
        rerenderWidgets()
    }

    // ---- app menu ----

    private fun renderMenu() {
        val app = menuApp ?: return back()
        outputBox.removeAllViews()
        outputBox.gravity = Gravity.TOP
        headerTitle.text = app.label.lowercase()
        val (component, user) = appTargets[app.id] ?: return back()
        if (launcherApps.hasShortcutHostPermission()) {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(component.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            runCatching { launcherApps.getShortcuts(query, user) }.getOrNull().orEmpty().take(MAX_SHORTCUTS).forEach { shortcut ->
                outputBox.addView(row(shortcut.shortLabel?.toString() ?: shortcut.id) {
                    runCatching { launcherApps.startShortcut(shortcut, null, null) }
                    goHome()
                })
            }
        }
        outputBox.addView(section("edit"))
        val onHome = app.id in state.pins
        outputBox.addView(row(if (onHome) "remove from home" else "add to home") {
            if (onHome) state.pins.remove(app.id) else state.pins += app.id
            save()
            renderHome()
            renderMenu()
        })
        val inPanel = app.id in state.panel
        outputBox.addView(row(if (inPanel) "remove from edge panel" else "add to edge panel") {
            if (inPanel) state.panel.remove(app.id) else state.panel += app.id
            save()
            renderMenu()
        })
        val dockItem = state.dock.firstOrNull { it.appId == app.id }
        outputBox.addView(row(if (dockItem != null) "remove from dock" else "add to dock") {
            if (!state.dock.toggleApp(app.id, iconFor(app))) headerTitle.text = "dock is full ($DOCK_MAX)"
            save()
            renderHome()
            if (headerTitle.text.toString().startsWith("dock is full").not()) renderMenu()
        })
        if (dockItem != null) outputBox.addView(row("change dock icon") { openIcons(app) })
        outputBox.addView(row("rename") {
            askName("new name, blank to reset", state.names[app.id].orEmpty()) { typed ->
                if (typed.isEmpty()) state.names.remove(app.id) else state.names[app.id] = typed
                save()
                appCache = null
                menuApp = byIdOrNull(app.id)
                renderHome()
                back()
            }
        })
        outputBox.addView(row("hide from search") {
            state.hidden += app.id
            state.pins.remove(app.id)
            state.dock.removeAll { it.appId == app.id }
            state.panel.remove(app.id)
            save()
            renderHome()
            back()
        })
        outputBox.addView(row("app info") { info(app) })
        outputBox.addView(row("uninstall") { uninstall(app) })
    }

    /** Sees touches before its children, for swipes and for holding an empty area. */
    private inner class GestureRoot : LinearLayout(this@MainActivity) {
        private var startX = 0f
        private var startY = 0f
        private var canSwipe = false
        private var consumed = false
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var heldWidget: Int? = null
        private val holdWidget = Runnable {
            val id = heldWidget ?: return@Runnable
            if (mode != Mode.HOME) return@Runnable
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // Takes the gesture back from the widget, so letting go doesn't also tap it.
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            startResizing(id)
        }

        // What the finger is doing in resize mode.
        private var stretchRight = false
        private var stretchBottom = false
        private var passToControls = false
        private var startWidth = 0
        private var startHeight = 0

        private fun handleResize(id: Int, e: android.view.MotionEvent): Boolean {
            val cell = widgetCells[id] ?: run {
                resizingId = null
                return true
            }
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    stretchRight = false
                    stretchBottom = false
                    passToControls = inside(resizeControls, e.rawX, e.rawY)
                    if (passToControls) return super.dispatchTouchEvent(e)
                    val r = android.graphics.Rect()
                    cell.getGlobalVisibleRect(r)
                    val grip = dp(RESIZE_GRIP_DP)
                    val withinY = e.rawY >= r.top - grip && e.rawY <= r.bottom + grip
                    val withinX = e.rawX >= r.left - grip && e.rawX <= r.right + grip
                    stretchRight = kotlin.math.abs(e.rawX - r.right) <= grip && withinY
                    stretchBottom = kotlin.math.abs(e.rawY - r.bottom) <= grip && withinX
                    startWidth = cell.width
                    startHeight = cell.height
                    // A touch away from the widget and its grips finishes.
                    if (!stretchRight && !stretchBottom && !inside(cell, e.rawX, e.rawY)) stopResizing()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (passToControls) return super.dispatchTouchEvent(e)
                    if (stretchRight || stretchBottom) {
                        val width = if (stretchRight) startWidth + (e.rawX - startX).toInt() else startWidth
                        val height = if (stretchBottom) startHeight + (e.rawY - startY).toInt() else startHeight
                        val params = cell.layoutParams
                        params.width = width.coerceIn(dp(MIN_WIDGET_WIDTH_DP), dp(fullWidgetWidthDp))
                        params.height = height.coerceIn(dp(MIN_WIDGET_DP), dp(maxWidgetHeightDp))
                        cell.layoutParams = params
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (passToControls) {
                        passToControls = false
                        return super.dispatchTouchEvent(e)
                    }
                    if (stretchRight || stretchBottom) {
                        val density = resources.displayMetrics.density
                        val (w, h) = stretchedSize(
                            (cell.layoutParams.width / density).toInt(),
                            (cell.layoutParams.height / density).toInt(),
                            fullWidgetWidthDp,
                            maxWidgetHeightDp,
                            MIN_WIDGET_WIDTH_DP,
                            MIN_WIDGET_DP,
                        )
                        stretchRight = false
                        stretchBottom = false
                        setWidgetSize(id, w, h)
                    }
                }
            }
            return true
        }

        private val hold = Runnable {
            if (mode == Mode.HOME) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                openEdit()
            }
        }

        init {
            // Gives clearFocus() somewhere to land, so the cursor doesn't stay in the prompt.
            isFocusableInTouchMode = true
        }

        private fun inside(view: View?, x: Float, y: Float): Boolean {
            if (view == null || view.visibility != View.VISIBLE) return false
            val r = android.graphics.Rect()
            return view.getGlobalVisibleRect(r) && r.contains(x.toInt(), y.toInt())
        }

        private fun hitsControl(view: View, x: Float, y: Float): Boolean {
            if (!inside(view, x, y)) return false
            if (view.isClickable) return true
            return view is ViewGroup && (0 until view.childCount).any { hitsControl(view.getChildAt(it), x, y) }
        }

        private fun allowSwipe(x: Float, y: Float) = when (mode) {
            // Widgets keep their own scrolling, and so does a home list that overflows.
            Mode.HOME -> !inside(widgetBox, x, y) && !(inside(homeScroll, x, y) && homeScroll.canScrollVertically(1))
            else -> inside(outputScroll, x, y) && !outputScroll.canScrollVertically(-1)
        }

        override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
            resizingId?.let { return handleResize(it, e) }
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    consumed = false
                    startX = e.rawX
                    startY = e.rawY
                    canSwipe = allowSwipe(e.rawX, e.rawY)
                    heldWidget = if (mode == Mode.HOME) {
                        widgetCells.entries.firstOrNull { inside(it.value, e.rawX, e.rawY) }?.key
                    } else {
                        null
                    }
                    if (heldWidget != null) {
                        postDelayed(holdWidget, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    // Touches that start on a side edge or the bottom bar are usually Android's back or
                    // home gesture. Android takes those over without telling us the touch ended, so a
                    // pending hold would fire half a second later and open edit mode.
                    val insets = rootWindowInsets
                    val edges = insets?.getInsets(WindowInsets.Type.systemGestures())
                    val bottomZone = maxOf(
                        edges?.bottom ?: 0,
                        insets?.getInsets(WindowInsets.Type.mandatorySystemGestures())?.bottom ?: 0,
                        insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0,
                        dp(BOTTOM_GESTURE_DP),
                    )
                    val onEdge = (edges != null && (e.rawX < edges.left || e.rawX > width - edges.right)) ||
                        e.rawY > height - bottomZone
                    if (onEdge) heldWidget = null
                    if (mode == Mode.HOME && !onEdge && !inside(widgetBox, e.rawX, e.rawY) && !hitsControl(this, e.rawX, e.rawY)) {
                        postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (consumed) return true
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (kotlin.math.hypot(dx, dy) > slop) {
                        removeCallbacks(hold)
                        removeCallbacks(holdWidget)
                    }
                    if (canSwipe && kotlin.math.abs(dy) > dp(SWIPE_DP) && kotlin.math.abs(dy) > 2 * kotlin.math.abs(dx)) {
                        val up = dy < 0
                        if (mode == Mode.HOME && up) openSearch()
                        else if (mode != Mode.HOME && !up) back()
                        else return super.dispatchTouchEvent(e)
                        val cancel = android.view.MotionEvent.obtain(e)
                        cancel.action = android.view.MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        consumed = true
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold)
                    removeCallbacks(holdWidget)
                    if (consumed) {
                        consumed = false
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(e)
        }
    }

    // ---- apps ----

    private fun appsChanged() {
        appCache = null
        renderHome()
        if (mode == Mode.SEARCH || mode == Mode.PICK) syncOutput()
    }

    private fun byIdOrNull(id: String) = apps().firstOrNull { it.id == id }

    override fun apps(): List<App> = appCache ?: launcherApps.profiles.flatMap { user ->
        val serial = userManager.getSerialNumberForUser(user)
        val isMain = user == android.os.Process.myUserHandle()
        launcherApps.getActivityList(null, user)
            .filter { it.applicationInfo.packageName != packageName }
            .map { info ->
                val id = "${info.componentName.flattenToString()}#$serial"
                appTargets[id] = info.componentName to user
                val name = state.names[id] ?: info.label.toString()
                App(id, if (isMain) name else "$name (work)")
            }
    }.sortedBy { it.label.lowercase() }.also { appCache = it }

    override fun launch(app: App): Boolean {
        val (component, user) = appTargets[app.id] ?: return false
        return runCatching {
            launcherApps.startMainActivity(component, user, null, null)
            resetInput()
        }.isSuccess
    }

    override fun web(query: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query))))
        resetInput()
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override fun info(app: App) {
        val (component, user) = appTargets[app.id] ?: return
        launcherApps.startAppDetailsActivity(component, user, null, null)
    }

    override fun uninstall(app: App) {
        val (component, user) = appTargets[app.id] ?: return
        startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${component.packageName}"))
                .putExtra(Intent.EXTRA_USER, user),
        )
    }

    override fun settings() = startActivity(Intent(Settings.ACTION_SETTINGS))

    override fun save() = store.save(state, widgetRefs)

    override fun themeChanged() = build()

    // ---- widgets ----

    private fun providerList() = launcherApps.profiles.flatMap { user ->
        val serial = userManager.getSerialNumberForUser(user)
        // Samsung lists extra same-named variants in its own categories; these aren't home screen widgets.
        widgetManager.getInstalledProvidersForProfile(user)
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .map { "${it.provider.flattenToString()}#$serial" to it }
    }

    private fun widgetLabel(info: AppWidgetProviderInfo): String {
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.provider.packageName, 0))
        }.getOrDefault(info.provider.packageName).toString()
        val name = info.loadLabel(packageManager).orEmpty()
        return if (name.equals(app, true)) app else "$app: $name"
    }

    // Samsung ships some widgets twice under one name; listing both just looks broken.
    override fun providers() = providerList()
        .map { (id, info) -> Provider(id, widgetLabel(info)) }
        .distinctBy { it.label }

    override fun bindWidget(providerId: String) {
        val info = providerList().firstOrNull { it.first == providerId }?.second
            ?: return shell.print(Line("widget not available", Kind.ERR))
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrFinish(id)
        } else {
            // First bind per provider: Android shows its own permission dialog.
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
                REQ_BIND,
            )
        }
    }

    private fun configureOrFinish(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id) ?: return cancelWidget(id, "widget failed to bind")
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
            } catch (e: ActivityNotFoundException) {
                cancelWidget(id, "widget setup screen missing")
            } catch (e: SecurityException) {
                cancelWidget(id, "widget setup screen refused to open")
            }
        } else {
            finishWidget(id, info)
        }
    }

    private fun finishWidget(id: Int, info: AppWidgetProviderInfo) {
        pendingWidget = AppWidgetManager.INVALID_APPWIDGET_ID
        val density = resources.displayMetrics.density
        val height = (info.minHeight / density).toInt().coerceIn(MIN_WIDGET_DP, MAX_DEFAULT_WIDGET_DP)
        val label = widgetLabel(info)
        widgetRefs += WidgetRef(id, label, height, screen)
        save()
        shell.print(Line("added $label"))
        rerenderWidgets()
        syncOutput()
    }

    private fun cancelWidget(id: Int, message: String?) {
        pendingWidget = AppWidgetManager.INVALID_APPWIDGET_ID
        widgetHost.deleteAppWidgetId(id)
        if (message != null) shell.print(Line(message, Kind.ERR))
        syncOutput()
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(request, result, data)
        if (request == REQ_PHOTO) {
            pickingPhoto = false
            if (result == RESULT_OK) backdrop.onPicked(data?.data)
            return
        }
        val id = pendingWidget
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (result != RESULT_OK) return cancelWidget(id, "widget cancelled")
        when (request) {
            REQ_BIND -> configureOrFinish(id)
            REQ_CONFIGURE -> widgetManager.getAppWidgetInfo(id)?.let { finishWidget(id, it) }
                ?: cancelWidget(id, "widget failed to bind")
        }
    }

    /** Drops saved widgets whose app is gone, and ids Android holds that we don't use. */
    private fun reconcileWidgets() {
        val before = widgetRefs.size
        widgetRefs.removeAll { widgetManager.getAppWidgetInfo(it.id) == null }
        val known = widgetRefs.map { it.id }.toSet()
        widgetHost.appWidgetIds.filter { it !in known && it != pendingWidget }
            .forEach { widgetHost.deleteAppWidgetId(it) }
        if (widgetRefs.size != before) save()
    }

    private fun widgetsHere() = widgetRefs.filter { it.screen == screen }

    override fun widgets() = widgetsHere()

    override fun removeWidget(ref: WidgetRef) {
        widgetRefs.remove(ref)
        widgetHost.deleteAppWidgetId(ref.id)
        save()
        rerenderWidgets()
    }

    override fun resizeWidget(ref: WidgetRef, heightDp: Int) {
        widgetRefs[widgetRefs.indexOf(ref)] = ref.copy(heightDp = heightDp)
        save()
        rerenderWidgets()
    }

    /** The cover keeps at least 60% of its height for the terminal. */
    private fun coverWidgetHeight(): Int {
        val controls = if (resizingId != null && widgetRefById(resizingId!!)?.screen == screen) RESIZE_CONTROLS_DP else 0
        val wanted = widgetsHere().sumOf { it.heightDp + WIDGET_GAP_DP } + controls
        return dp(minOf(wanted, (resources.configuration.screenHeightDp * 0.4f).toInt()))
    }

    private fun rerenderWidgets() {
        if (!twoPane || innerAlign == ALIGN_RIGHT) {
            widgetScroll.layoutParams = widgetScroll.layoutParams.apply { height = coverWidgetHeight() }
        }
        renderWidgets()
        root.requestApplyInsets()
    }

    private val fullWidgetWidthDp: Int
        get() {
            val cfg = resources.configuration
            return (if (twoPane) (cfg.screenWidthDp - CREASE_GAP_DP) / 2 else cfg.screenWidthDp) - PANE_MARGIN_DP
        }

    /** Where widgets share a column with apps they stop at 40%, which is also all the cover shows. */
    private val maxWidgetHeightDp: Int
        get() {
            val share = if (!twoPane || innerAlign == ALIGN_RIGHT) 0.4f else 0.8f
            return (resources.configuration.screenHeightDp * share).toInt() - WIDGET_GAP_DP
        }

    private fun renderWidgets() {
        widgetBox.removeAllViews()
        widgetCells.clear()
        resizeControls = null
        val full = fullWidgetWidthDp
        widgetsHere().forEach { ref ->
            val info = widgetManager.getAppWidgetInfo(ref.id) ?: return@forEach
            val view = widgetHost.createView(applicationContext, ref.id, info)
            view.setAppWidget(ref.id, info)
            val widthDp = if (ref.widthDp > 0) minOf(ref.widthDp, full) else full
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(Bundle(), widthDp, ref.heightDp, widthDp, ref.heightDp)
            val cell = FrameLayout(this).apply {
                addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                if (ref.id == resizingId) addView(ResizeFrame(context, accent), FrameLayout.LayoutParams(MATCH, MATCH))
            }
            widgetCells[ref.id] = cell
            widgetBox.addView(
                cell,
                LinearLayout.LayoutParams(dp(widthDp), dp(ref.heightDp)).apply { bottomMargin = dp(WIDGET_GAP_DP) },
            )
            if (ref.id == resizingId) {
                val controls = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(chip("full width", DIM) { setWidgetSize(ref.id, 0, widgetRefById(ref.id)?.heightDp ?: ref.heightDp) })
                    addView(chip("remove", DIM) {
                        widgetRefById(ref.id)?.let(::removeWidget)
                        stopResizing()
                    })
                    addView(chip("done", accent) { stopResizing() })
                }
                resizeControls = controls
                widgetBox.addView(controls)
            }
        }
    }

    private fun widgetRefById(id: Int) = widgetRefs.firstOrNull { it.id == id }

    private fun setWidgetSize(id: Int, widthDp: Int, heightDp: Int) {
        val i = widgetRefs.indexOfFirst { it.id == id }
        if (i < 0) return
        widgetRefs[i] = widgetRefs[i].copy(widthDp = widthDp, heightDp = heightDp)
        save()
        rerenderWidgets()
    }

    private fun startResizing(id: Int) {
        resizingId = id
        rerenderWidgets()
    }

    private fun stopResizing() {
        if (resizingId == null) return
        resizingId = null
        rerenderWidgets()
    }

    /** The outline and grips shown on a widget being stretched. */
    private class ResizeFrame(context: android.content.Context, color: Int) : View(context) {
        private val density = resources.displayMetrics.density
        private val line = android.graphics.Paint().apply {
            this.color = color
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = density
        }
        private val grip = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 3 * density
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val half = line.strokeWidth / 2
            canvas.drawRect(half, half, w - half, h - half, line)
            val length = minOf(12 * density, w / 4, h / 4)
            val inset = grip.strokeWidth / 2
            canvas.drawLine(w - inset, h / 2 - length, w - inset, h / 2 + length, grip)
            canvas.drawLine(w / 2 - length, h - inset, w / 2 + length, h - inset, grip)
            canvas.drawLine(w - inset - length, h - inset, w - inset, h - inset, grip)
            canvas.drawLine(w - inset, h - inset - length, w - inset, h - inset, grip)
        }
    }

    private companion object {
        const val WIDGET_HOST_ID = 4242
        const val REQ_BIND = 1
        const val REQ_CONFIGURE = 2
        const val PENDING_WIDGET = "pendingWidget"
        const val COVER = "cover"
        const val INNER = "inner"
        const val CREASE_GAP_DP = 40
        const val SWIPE_DP = 48
        const val BOTTOM_GESTURE_DP = 32
        const val DOCK_HEIGHT_DP = 56
        const val DOCK_ICON_DP = 26
        const val DOCK_SLOT_DP = 88
        const val ICON_COLUMNS = 6
        const val WIDGET_STEP_DP = 40
        const val MAX_SHORTCUTS = 6
        const val PANE_MARGIN_DP = 64
        const val WIDGET_GAP_DP = 8
        // Widgets often declare a minimum smaller than they can draw in without clipping.
        const val MIN_WIDGET_DP = 80
        const val MIN_WIDGET_WIDTH_DP = 80
        const val RESIZE_GRIP_DP = 32
        const val RESIZE_CONTROLS_DP = 48
        const val MAX_DEFAULT_WIDGET_DP = 240
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val TEXT = Color.parseColor("#E6DFCF")
        val BLACK_DIM = Color.parseColor("#8A8478")
        val BLACK_FAINT = Color.parseColor("#55514A")
        const val REQ_PHOTO = 3
        val PHOTO_DIM = PHOTO_DIM_RGB or (0xFF shl 24)
        val PHOTO_FAINT = PHOTO_FAINT_RGB or (0xFF shl 24)
        val ERROR = Color.parseColor("#D98A7A")
    }
}
