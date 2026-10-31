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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity(), Host {
    private lateinit var store: Store
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
    private var folderBox: LinearLayout? = null
    private var twoPane = false
    private var shownTotal = 0L
    private var shownGeneration = 0

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = appsChanged()
        override fun onPackageAdded(p: String, u: UserHandle) = appsChanged()
        override fun onPackageChanged(p: String, u: UserHandle) = appsChanged()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = appsChanged()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = appsChanged()
    }

    private val accent get() = (THEMES[state.theme] ?: THEMES.getValue("green")).toInt()
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
        reconcileWidgets()

        shell.print(Line("crease. type a name, or:"))
        shell.print(Line("help", Kind.ITEM, Action("help")))
        shell.print(Line("ls", Kind.ITEM, Action("ls")))

        window.setDecorFitsSystemWindows(false)
        launcherApps.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { resetInput() }
        }
        build()
    }

    @Deprecated("Used below API 33")
    override fun onBackPressed() = resetInput()

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
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(packageCallback)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (state.autoKeyboard) showKeyboard()
    }

    /** Pressing home while already home. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resetInput()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
    }

    // ---- layout ----

    private fun build() {
        val cfg = resources.configuration
        val draft = if (::prompt.isInitialized) prompt.text.toString() else ""
        twoPane = cfg.screenWidthDp >= 600 && cfg.screenHeightDp >= 480
        shownTotal = 0
        shownGeneration = shell.generation
        folderBox = null

        widgetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        widgetScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(widgetBox)
        }
        shortcutBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val terminal = buildTerminal(draft)

        root = LinearLayout(this)
        if (twoPane) {
            root.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(clock(32f))
            left.addView(rowScroll(shortcutBox))
            val folders = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            folderBox = folders
            left.addView(folders)
            left.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            // Equal weights put the gap on the crease, so no text sits in the fold.
            root.addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))
            root.addView(View(this), LinearLayout.LayoutParams(dp(CREASE_GAP_DP), MATCH))
            root.addView(terminal, LinearLayout.LayoutParams(0, MATCH, 1f))
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.addView(clock(22f))
            root.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, coverWidgetHeight()))
            root.addView(rowScroll(shortcutBox))
            root.addView(terminal, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val pad = dp(14)
            v.setPadding(bars.left + pad, bars.top + dp(6), bars.right + pad, maxOf(bars.bottom, ime.bottom) + dp(4))
            // The cover screen is too short for a widget and a keyboard.
            if (!twoPane) widgetScroll.visibility =
                if (ime.bottom > 0 || widgetsHere().isEmpty()) View.GONE else View.VISIBLE
            insets
        }
        setContentView(root)
        renderWidgets()
        refreshChrome()
        syncOutput()
    }

    private fun clock(size: Float) = TextClock(this).apply {
        format24Hour = "EEE d MMM  HH:mm"
        format12Hour = "EEE d MMM  h:mm a"
        typeface = Typeface.MONOSPACE
        setTextColor(accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun rowScroll(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }

    private fun buildTerminal(draft: String): LinearLayout {
        outputBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        outputScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(outputBox)
        }
        outputBox.setOnClickListener { showKeyboard() }
        suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
        }
        prompt = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT)
            setHintTextColor(DIM)
            hint = "type here"
            background = null
            isSingleLine = true
            textCursorDrawable?.setTint(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), dp(10), 0, dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, _, _ ->
                submit(text.toString())
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = renderSuggestions()
            })
        }
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "$"
                typeface = Typeface.MONOSPACE
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        prompt.setText(draft)
        prompt.setSelection(draft.length)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(outputScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(rowScroll(suggestBox), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(promptRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun lineView(line: Line) = TextView(this).apply {
        text = line.text
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(
            when (line.kind) {
                Kind.ECHO -> DIM
                Kind.OUT -> TEXT
                Kind.ERR -> ERROR
                Kind.ITEM -> accent
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

    private fun chip(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        typeface = Typeface.MONOSPACE
        setTextColor(accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(40)
        setPadding(dp(4), 0, dp(14), 0)
        setOnClickListener { onTap() }
        isFocusable = false
    }

    private fun refreshChrome() {
        renderShortcuts()
        renderFolders()
        renderSuggestions()
    }

    private fun renderShortcuts() {
        shortcutBox.removeAllViews()
        state.pins.forEach { id ->
            byIdOrNull(id)?.let { app -> shortcutBox.addView(chip(app.label) { perform(Action("open ${app.label}")) }) }
        }
        // The inner screen has a folder tree; the cover keeps folders in this row.
        if (!twoPane) state.folders.keys.forEach { name ->
            shortcutBox.addView(chip("$name/") { perform(Action("ls $name")) })
        }
    }

    private fun renderFolders() {
        val box = folderBox ?: return
        box.removeAllViews()
        state.folders.forEach { (name, ids) ->
            val isOpen = name in openFolders
            box.addView(chip("${if (isOpen) "v" else ">"} $name/") {
                if (!openFolders.add(name)) openFolders.remove(name)
                renderFolders()
            })
            if (isOpen) ids.mapNotNull(::byIdOrNull).forEach { app ->
                box.addView(chip("  ${app.label}") { perform(Action("open ${app.label}")) })
            }
        }
    }

    private fun renderSuggestions() {
        suggestBox.removeAllViews()
        shell.suggest(prompt.text.toString()).forEach { s ->
            suggestBox.addView(chip(s.label) { perform(Action(s.fill, s.run)) })
        }
    }

    private fun syncOutput() {
        if (shownGeneration != shell.generation) {
            outputBox.removeAllViews()
            shownGeneration = shell.generation
            shownTotal = shell.total
        }
        val fresh = (shell.total - shownTotal).coerceAtMost(shell.output.size.toLong()).toInt()
        val lines = if (shownTotal == 0L) shell.output else shell.output.takeLast(fresh)
        lines.forEach { outputBox.addView(lineView(it)) }
        shownTotal = shell.total
        while (outputBox.childCount > shell.output.size) outputBox.removeViewAt(0)
        // Not fullScroll(): it moves focus to the last row, and Enter would then tap it.
        outputScroll.post { outputScroll.scrollTo(0, outputBox.height) }
    }

    private fun perform(action: Action) {
        if (action.run) submit(action.cmd) else {
            prompt.setText(action.cmd)
            prompt.setSelection(action.cmd.length)
            showKeyboard()
        }
    }

    private fun submit(cmd: String) {
        prompt.setText("")
        shell.run(cmd)
        syncOutput()
        refreshChrome()
    }

    private fun resetInput() {
        prompt.setText("")
        prompt.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(prompt.windowToken, 0)
    }

    private fun showKeyboard() {
        prompt.requestFocus()
        prompt.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(prompt, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---- apps ----

    private fun appsChanged() {
        appCache = null
        refreshChrome()
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
                App(id, if (isMain) info.label.toString() else "${info.label} (work)")
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
        widgetManager.getInstalledProvidersForProfile(user).map { "${it.provider.flattenToString()}#$serial" to it }
    }

    private fun widgetLabel(info: AppWidgetProviderInfo): String {
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.provider.packageName, 0))
        }.getOrDefault(info.provider.packageName).toString()
        val name = info.loadLabel(packageManager).orEmpty()
        return if (name.equals(app, true)) app else "$app: $name"
    }

    override fun providers() = providerList().map { (id, info) -> Provider(id, widgetLabel(info)) }

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
        val wanted = widgetsHere().sumOf { it.heightDp + WIDGET_GAP_DP }
        return dp(minOf(wanted, (resources.configuration.screenHeightDp * 0.4f).toInt()))
    }

    private fun rerenderWidgets() {
        if (!twoPane) widgetScroll.layoutParams = widgetScroll.layoutParams.apply { height = coverWidgetHeight() }
        renderWidgets()
        root.requestApplyInsets()
    }

    private fun renderWidgets() {
        widgetBox.removeAllViews()
        val cfg = resources.configuration
        val widthDp = (if (twoPane) (cfg.screenWidthDp - CREASE_GAP_DP) / 2 else cfg.screenWidthDp) - PANE_MARGIN_DP
        widgetsHere().forEach { ref ->
            val info = widgetManager.getAppWidgetInfo(ref.id) ?: return@forEach
            val view = widgetHost.createView(applicationContext, ref.id, info)
            view.setAppWidget(ref.id, info)
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(Bundle(), widthDp, ref.heightDp, widthDp, ref.heightDp)
            widgetBox.addView(
                view,
                LinearLayout.LayoutParams(MATCH, dp(ref.heightDp)).apply { bottomMargin = dp(WIDGET_GAP_DP) },
            )
        }
    }

    private companion object {
        const val WIDGET_HOST_ID = 4242
        const val REQ_BIND = 1
        const val REQ_CONFIGURE = 2
        const val PENDING_WIDGET = "pendingWidget"
        const val COVER = "cover"
        const val INNER = "inner"
        const val CREASE_GAP_DP = 28
        const val PANE_MARGIN_DP = 28
        const val WIDGET_GAP_DP = 8
        // Widgets often declare a minimum smaller than they can draw in without clipping.
        const val MIN_WIDGET_DP = 80
        const val MAX_DEFAULT_WIDGET_DP = 240
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val TEXT = Color.parseColor("#D8D8D8")
        val DIM = Color.parseColor("#6E6E6E")
        val ERROR = Color.parseColor("#FF6B6B")
    }
}
