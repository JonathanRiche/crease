package app.crease.launcher

/** Command logic. Has no Android imports so it can be unit tested on the JVM. */

data class App(val id: String, val label: String)
data class Provider(val id: String, val label: String)
data class WidgetRef(val id: Int, val label: String, val heightDp: Int, val screen: String)

class State {
    val aliases = linkedMapOf<String, String>()
    val hidden = linkedSetOf<String>()
    val pins = mutableListOf<String>()
    val folders = linkedMapOf<String, MutableList<String>>()
    var theme = "green"
    var autoKeyboard = false
}

enum class Kind { ECHO, OUT, ERR, ITEM }
data class Action(val cmd: String, val run: Boolean = true)
data class Line(val text: String, val kind: Kind = Kind.OUT, val action: Action? = null)
data class Suggestion(val label: String, val fill: String, val run: Boolean)

interface Host {
    fun apps(): List<App>
    fun launch(app: App): Boolean
    fun web(query: String): Boolean
    fun info(app: App)
    fun uninstall(app: App)
    fun settings()
    fun providers(): List<Provider>
    fun bindWidget(providerId: String)
    /** Widgets on the current screen (cover or inner), in display order. */
    fun widgets(): List<WidgetRef>
    fun removeWidget(ref: WidgetRef)
    fun resizeWidget(ref: WidgetRef, heightDp: Int)
    fun save()
    fun themeChanged()
}

val THEMES = linkedMapOf(
    "green" to 0xFF7CFC9AL, "amber" to 0xFFFFB454L, "cyan" to 0xFF6AD7E5L,
    "pink" to 0xFFFF8FB8L, "white" to 0xFFF2F2F2L,
)

/** Lower is better; null means no match. */
fun score(query: String, name: String): Int? {
    val q = query.trim().lowercase()
    val n = name.lowercase()
    if (q.isEmpty()) return null
    if (n == q) return 0
    if (n.startsWith(q)) return 1
    if (n.split(' ', '-', '_', '.').any { it.startsWith(q) }) return 2
    if (q in n) return 3
    var i = 0
    for (c in n) if (i < q.length && c == q[i]) i++
    return if (i == q.length) 4 else null
}

class Shell(val state: State, private val host: Host) {
    val output = ArrayList<Line>()
    /** Lines ever appended; lets the UI append only what is new. */
    var total = 0L; private set
    /** Bumped by `clear`, telling the UI to drop what it shows. */
    var generation = 0; private set

    private val commands = listOf(
        "help", "ls", "open", "folder", "pin", "unpin", "alias", "unalias", "hide", "unhide",
        "widget", "web", "info", "uninstall", "settings", "theme", "keyboard", "clear",
    )
    private val appVerbs = setOf("open", "pin", "unpin", "hide", "unhide", "info", "uninstall")

    fun print(line: Line) {
        output += line
        total++
        if (output.size > MAX_LINES) output.removeAt(0)
    }
    private fun out(s: String) = print(Line(s))
    private fun err(s: String) = print(Line(s, Kind.ERR))
    private fun item(s: String, cmd: String, run: Boolean = true) =
        print(Line(s, Kind.ITEM, Action(cmd, run)))

    private fun visible() = host.apps().filter { it.id !in state.hidden }
    private fun byId(id: String) = host.apps().firstOrNull { it.id == id }
    private fun folderKey(name: String) = state.folders.keys.firstOrNull { it.equals(name, true) }

    fun run(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        // `widget bind` only ever comes from tapping a listed widget; its id is noise.
        if (!input.startsWith("widget bind ")) print(Line("$ $input", Kind.ECHO))
        val verb = input.substringBefore(' ').lowercase()
        val rest = input.substringAfter(' ', "").trim()
        when {
            verb in commands -> dispatch(verb, rest)
            else -> bare(input)
        }
    }

    private fun bare(input: String) {
        state.aliases.entries.firstOrNull { it.key.equals(input, true) }?.let { a ->
            byId(a.value)?.let { launch(it); return }
        }
        folderKey(input)?.let { listFolder(it); return }
        withApp("open", input) { launch(it) }
    }

    private fun dispatch(verb: String, rest: String) {
        when (verb) {
            "help" -> help()
            "ls" -> if (rest.isEmpty()) listAll() else
                folderKey(rest)?.let { listFolder(it) } ?: err("no folder: $rest")
            "open" -> needs(rest, "open <app>") { withApp("open", rest) { launch(it) } }
            "folder" -> folder(rest)
            "pin" -> needs(rest, "pin <app>") { withApp("pin", rest) { pin(it) } }
            "unpin" -> needs(rest, "unpin <app>") {
                withApp("unpin", rest) {
                    state.pins.remove(it.id); host.save(); out("unpinned ${it.label}")
                }
            }
            "alias" -> alias(rest)
            "unalias" -> needs(rest, "unalias <name>") {
                val key = state.aliases.keys.firstOrNull { it.equals(rest, true) }
                if (key == null) err("no alias: $rest") else {
                    state.aliases.remove(key); host.save(); out("removed $key")
                }
            }
            "hide" -> needs(rest, "hide <app>") {
                withApp("hide", rest) {
                    state.hidden += it.id; host.save(); out("hid ${it.label}")
                }
            }
            "unhide" -> unhide(rest)
            "widget" -> widget(rest)
            "web" -> needs(rest, "web <query>") { if (!host.web(rest)) err("no browser found") }
            "info" -> needs(rest, "info <app>") { withApp("info", rest) { host.info(it) } }
            "uninstall" -> needs(rest, "uninstall <app>") {
                withApp("uninstall", rest) { host.uninstall(it) }
            }
            "settings" -> host.settings()
            "theme" -> theme(rest)
            "keyboard" -> keyboard(rest)
            "clear" -> { output.clear(); generation++ }
        }
    }

    private inline fun needs(rest: String, usage: String, block: () -> Unit) {
        if (rest.isEmpty()) err("usage: $usage") else block()
    }

    private fun launch(app: App) {
        if (!host.launch(app)) err("could not open ${app.label}")
    }

    /** Resolves an app; on several matches prints them as tappable lines. */
    private inline fun withApp(
        verb: String, query: String, includeHidden: Boolean = false, action: (App) -> Unit,
    ) {
        val pool = if (includeHidden) host.apps() else visible()
        val alias = state.aliases.entries.firstOrNull { it.key.equals(query, true) }
        val hit = alias?.let { a -> pool.firstOrNull { it.id == a.value } }
        if (hit != null) return action(hit)
        val scored = pool.mapNotNull { a -> score(query, a.label)?.let { a to it } }
        val best = scored.minOfOrNull { it.second }
        val matches = scored.filter { it.second == best }.map { it.first }
        when {
            matches.isEmpty() -> {
                err("not found: $query")
                if (verb == "open") item("web $query", "web $query")
            }
            matches.size == 1 || best == 0 -> action(matches[0])
            else -> matches.sortedBy { it.label.lowercase() }
                .forEach { item(it.label, "$verb ${it.label}") }
        }
    }

    private fun listAll() {
        state.folders.forEach { (name, ids) ->
            item("$name/  (${ids.count { byId(it) != null }})", "ls $name")
        }
        val filed = state.folders.values.flatten().toSet()
        visible().filter { it.id !in filed }.sortedBy { it.label.lowercase() }
            .forEach { item(it.label, "open ${it.label}") }
    }

    private fun listFolder(name: String) {
        val apps = state.folders[name].orEmpty().mapNotNull(::byId)
        if (apps.isEmpty()) out("$name/ is empty. folder add $name <app>")
        apps.forEach { item(it.label, "open ${it.label}") }
    }

    private fun pin(app: App) {
        if (app.id !in state.pins) state.pins += app.id
        host.save()
        out("pinned ${app.label}")
    }

    private fun folder(rest: String) {
        val parts = rest.split(Regex("\\s+"), limit = 3).filter { it.isNotEmpty() }
        val usage = "usage: folder new|rm <name>  or  folder add|del <name> <app>"
        val sub = parts.getOrNull(0)?.lowercase()
        val name = parts.getOrNull(1)
        val q = parts.getOrNull(2)
        if (sub == null || name == null) return err(usage)
        when (sub) {
            "new" -> when {
                q != null || !name.matches(NAME) -> err("folder names are one word")
                folderKey(name) != null -> err("already exists: $name")
                else -> { state.folders[name] = mutableListOf(); host.save(); out("created $name/") }
            }
            "rm" -> {
                val key = folderKey(name) ?: return err("no folder: $name")
                state.folders.remove(key); host.save(); out("removed $key/ (apps stay installed)")
            }
            "add", "del" -> {
                val key = folderKey(name) ?: return err("no folder: $name")
                if (q == null) return err(usage)
                val verb = "folder $sub $key"
                withApp(verb, q) { app ->
                    val ids = state.folders.getValue(key)
                    if (sub == "add") { if (app.id !in ids) ids += app.id } else ids.remove(app.id)
                    host.save()
                    out(if (sub == "add") "${app.label} -> $key/" else "${app.label} removed from $key/")
                }
            }
            else -> err(usage)
        }
    }

    private fun alias(rest: String) {
        val parts = rest.split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) {
            if (state.aliases.isEmpty()) out("no aliases. alias <name> <app>")
            state.aliases.forEach { (k, id) -> out("$k -> ${byId(id)?.label ?: "(not installed)"}") }
            return
        }
        val (name, q) = parts
        if (!name.matches(NAME) || name.lowercase() in commands) return err("can't use alias: $name")
        withApp("alias $name", q) { app ->
            state.aliases[name] = app.id; host.save(); out("$name -> ${app.label}")
        }
    }

    private fun unhide(rest: String) {
        if (rest.isEmpty()) {
            val hidden = state.hidden.mapNotNull(::byId)
            if (hidden.isEmpty()) out("nothing hidden")
            hidden.forEach { item(it.label, "unhide ${it.label}") }
            return
        }
        withApp("unhide", rest, includeHidden = true) {
            state.hidden.remove(it.id); host.save(); out("unhid ${it.label}")
        }
    }

    private fun widget(rest: String) {
        val parts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotEmpty() }
        val arg = parts.getOrNull(1).orEmpty()
        when (parts.getOrNull(0)?.lowercase()) {
            null, "ls" -> {
                val list = host.widgets()
                if (list.isEmpty()) out("no widgets on this screen. widget add [search]")
                list.forEachIndexed { i, w ->
                    item("${i + 1}  ${w.label}  ${w.heightDp}dp", "widget rm ${i + 1}", run = false)
                }
            }
            "add" -> {
                val found = host.providers().filter { arg.isEmpty() || score(arg, it.label) != null }
                    .sortedBy { it.label.lowercase() }
                if (found.isEmpty()) err("no widgets found")
                found.forEach { item(it.label, "widget bind ${it.id}") }
            }
            "bind" -> host.bindWidget(arg)
            "rm" -> widgetAt(arg.toIntOrNull()) { host.removeWidget(it); out("removed ${it.label}") }
            "size" -> {
                val nums = arg.split(Regex("\\s+"))
                val h = nums.getOrNull(1)?.toIntOrNull()
                if (h == null || h !in 40..1000) return err("usage: widget size <n> <40-1000 dp>")
                widgetAt(nums[0].toIntOrNull()) { host.resizeWidget(it, h); out("${it.label}: ${h}dp") }
            }
            else -> err("usage: widget ls | add [search] | rm <n> | size <n> <dp>")
        }
    }

    private inline fun widgetAt(n: Int?, block: (WidgetRef) -> Unit) {
        val ref = n?.let { host.widgets().getOrNull(it - 1) }
        if (ref == null) err("no such widget. see: widget ls") else block(ref)
    }

    private fun theme(rest: String) {
        val t = rest.lowercase()
        if (t in THEMES) {
            state.theme = t; host.save(); host.themeChanged(); out("theme $t")
        } else THEMES.keys.forEach { item(it, "theme $it") }
    }

    private fun keyboard(rest: String) {
        when (rest.lowercase()) {
            "on" -> state.autoKeyboard = true
            "off" -> state.autoKeyboard = false
            else -> return err("usage: keyboard on|off (open it automatically on home)")
        }
        host.save()
        out("keyboard ${rest.lowercase()}")
    }

    private fun help() {
        listOf(
            "<app>             open best match",
            "ls [folder]       list apps and folders",
            "folder new|rm <name>",
            "folder add|del <name> <app>",
            "pin|unpin <app>   shortcut row",
            "alias <name> <app>, unalias <name>",
            "hide <app>, unhide [app]",
            "widget ls",
            "widget add [search]",
            "widget rm <n>, widget size <n> <dp>",
            "web <query>",
            "info|uninstall <app>",
            "settings, clear",
            "theme [name], keyboard on|off",
        ).forEach(::out)
    }

    /** Live completions for the row above the prompt. */
    fun suggest(input: String): List<Suggestion> {
        fun apps(q: String, cmd: (App) -> String) = visible()
            .mapNotNull { a -> score(q, a.label)?.let { a to it } }
            .sortedWith(compareBy({ it.second }, { it.first.label.lowercase() }))
            .take(MAX_SUGGESTIONS).map { (a, _) -> Suggestion(a.label, cmd(a), true) }

        val text = input.trimStart()
        if (text.isEmpty()) return emptyList()
        val verb = text.substringBefore(' ').lowercase()
        if (' ' in text) {
            val rest = text.substringAfter(' ').trimStart()
            val words = rest.split(Regex("\\s+"))
            return when {
                verb in appVerbs -> if (rest.isEmpty()) emptyList() else apps(rest) { "$verb ${it.label}" }
                verb == "ls" -> state.folders.keys.filter { it.startsWith(rest, true) }
                    .map { Suggestion("$it/", "ls $it", true) }
                verb == "theme" -> THEMES.keys.filter { it.startsWith(rest, true) }
                    .map { Suggestion(it, "theme $it", true) }
                verb == "alias" && words.size >= 2 ->
                    apps(words.drop(1).joinToString(" ")) { "alias ${words[0]} ${it.label}" }
                verb == "folder" && words.size >= 3 && words[0].lowercase() in setOf("add", "del") ->
                    apps(words.drop(2).joinToString(" ")) { "folder ${words[0]} ${words[1]} ${it.label}" }
                verb == "folder" && words.size == 2 && words[0].lowercase() != "new" ->
                    state.folders.keys.filter { it.startsWith(words[1], true) }
                        .map { Suggestion("$it/", "folder ${words[0]} $it ", false) }
                verb !in commands -> apps(text) { "open ${it.label}" }
                else -> emptyList()
            }
        }
        val folders = state.folders.keys.filter { it.startsWith(text, true) }
            .map { Suggestion("$it/", "ls $it", true) }
        val cmds = commands.filter { it.startsWith(verb) }
            .map { Suggestion(it, if (it in NO_ARGS) it else "$it ", it in NO_ARGS) }
        return (apps(text) { "open ${it.label}" } + folders + cmds).take(MAX_SUGGESTIONS)
    }

    private companion object {
        const val MAX_LINES = 600
        const val MAX_SUGGESTIONS = 8
        val NAME = Regex("[\\p{L}\\p{N}_-]+")
        val NO_ARGS = setOf("help", "ls", "settings", "clear", "unhide")
    }
}
