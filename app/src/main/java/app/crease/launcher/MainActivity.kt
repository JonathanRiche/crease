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
    private lateinit var suggestRow: HorizontalScrollView
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
        // Come back to a clean screen after using an app.
        resetInput()
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

    private val mono by lazy { resources.getFont(R.font.jetbrains_mono_regular) }
    private val monoLight by lazy { resources.getFont(R.font.jetbrains_mono_light) }

    private fun build() {
        val cfg = resources.configuration
        val draft = if (::prompt.isInitialized) prompt.text.toString() else ""
        twoPane = cfg.screenWidthDp >= 600 && cfg.screenHeightDp >= 480
        shownTotal = 0
        shownGeneration = shell.generation
        idleShown = false
        folderBox = null

        widgetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        widgetScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
        }
        val terminal = buildTerminal()

        // Gives clearFocus() somewhere to land, so the cursor doesn't stay in the prompt.
        root = LinearLayout(this).apply { isFocusableInTouchMode = true }
        if (twoPane) {
            root.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(clock(72f))
            shortcutBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(28), 0, 0)
            }
            val folders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            folderBox = folders
            widgetBox.setPadding(0, dp(28), 0, 0)
            widgetScroll.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(shortcutBox)
                addView(folders)
                addView(widgetBox)
            })
            left.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            // Equal weights put the gap on the crease, so no text sits in the fold.
            root.addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))
            root.addView(View(this), LinearLayout.LayoutParams(dp(CREASE_GAP_DP), MATCH))
            root.addView(terminal, LinearLayout.LayoutParams(0, MATCH, 1f))
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.addView(clock(56f))
            widgetBox.setPadding(0, dp(20), 0, 0)
            widgetScroll.addView(widgetBox)
            root.addView(widgetScroll, LinearLayout.LayoutParams(MATCH, coverWidgetHeight()))
            root.addView(terminal, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        val edge = dp(if (twoPane) 32 else 24)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left + edge, bars.top + dp(28), bars.right + edge, maxOf(bars.bottom, ime.bottom) + dp(8))
            // The cover screen is too short for a widget and a keyboard.
            if (!twoPane) widgetScroll.visibility =
                if (ime.bottom > 0 || widgetsHere().isEmpty()) View.GONE else View.VISIBLE
            insets
        }
        setContentView(root)
        prompt.setText(draft)
        prompt.setSelection(draft.length)
        renderWidgets()
        refreshChrome()
        syncOutput()
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

    private fun buildTerminal(): LinearLayout {
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
                override fun afterTextChanged(s: Editable?) = renderSuggestions()
            })
        }
        val promptRow = LinearLayout(this).apply {
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

    private fun refreshChrome() {
        renderShortcuts()
        renderFolders()
        renderSuggestions()
        if (idleShown) renderIdle()
    }

    private fun renderShortcuts() {
        if (!twoPane) return
        shortcutBox.removeAllViews()
        state.pins.forEach { id ->
            byIdOrNull(id)?.let { app -> shortcutBox.addView(chip(app.label) { perform(Action("open ${app.label}")) }) }
        }
    }

    private fun renderFolders() {
        val box = folderBox ?: return
        box.removeAllViews()
        state.folders.forEach { (name, ids) ->
            val isOpen = name in openFolders
            box.addView(chip("$name/", if (isOpen) accent else DIM) {
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
        val found = shell.suggest(prompt.text.toString())
        found.forEachIndexed { i, s ->
            suggestBox.addView(chip(s.label, if (i == 0) accent else DIM) { perform(Action(s.fill, s.run)) })
        }
        suggestRow.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * What sits in the output area when nothing has been run. Inner screen: every app.
     * Cover screen: pins and folders, near the thumb.
     */
    private fun renderIdle() {
        outputBox.removeAllViews()
        idleShown = true
        if (twoPane) {
            outputBox.gravity = Gravity.TOP
            shell.visibleApps().forEach { app ->
                outputBox.addView(lineView(Line(app.label, Kind.ITEM, Action("open ${app.label}"))))
            }
        } else {
            outputBox.gravity = Gravity.BOTTOM
            state.pins.mapNotNull(::byIdOrNull).forEach { app ->
                outputBox.addView(lineView(Line(app.label, Kind.ITEM, Action("open ${app.label}"))))
            }
            state.folders.keys.forEach { name ->
                outputBox.addView(chip("$name/", DIM) { perform(Action("ls $name")) })
            }
        }
        outputScroll.post { outputScroll.scrollTo(0, if (twoPane) 0 else outputBox.height) }
    }

    private fun syncOutput() {
        if (shownGeneration != shell.generation) {
            outputBox.removeAllViews()
            idleShown = false
            shownGeneration = shell.generation
            shownTotal = shell.total
        }
        if (shell.output.isEmpty()) {
            if (!idleShown) renderIdle()
            return
        }
        if (idleShown) {
            outputBox.removeAllViews()
            idleShown = false
        }
        outputBox.gravity = Gravity.BOTTOM
        val fresh = (shell.total - shownTotal).coerceAtMost(shell.output.size.toLong()).toInt()
        val lines = if (shownTotal == 0L) shell.output else shell.output.takeLast(fresh)
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
        if (cmd.isBlank()) return
        prompt.setText("")
        shell.run(cmd)
        syncOutput()
        refreshChrome()
    }

    /** Back to the clean home screen: no draft, no output, no keyboard. */
    private fun resetInput() {
        prompt.setText("")
        prompt.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(prompt.windowToken, 0)
        shell.clearOutput()
        syncOutput()
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
        const val CREASE_GAP_DP = 40
        const val PANE_MARGIN_DP = 64
        const val WIDGET_GAP_DP = 8
        // Widgets often declare a minimum smaller than they can draw in without clipping.
        const val MIN_WIDGET_DP = 80
        const val MAX_DEFAULT_WIDGET_DP = 240
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val TEXT = Color.parseColor("#E6DFCF")
        val DIM = Color.parseColor("#8A8478")
        val FAINT = Color.parseColor("#55514A")
        val ERROR = Color.parseColor("#D98A7A")
    }
}
