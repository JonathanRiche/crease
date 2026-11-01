package app.crease.launcher

/** An app in the dock. A null [icon] shows the app's name instead. */
data class DockItem(val appId: String, val icon: String?)

const val DOCK_MAX = 6

/** Names of the line icons, in display order. Each has a res/drawable/dock_<name>.xml. */
val DOCK_ICONS = listOf(
    "phone",
    "chat",
    "mail",
    "globe",
    "camera",
    "photo",
    "music",
    "headphones",
    "play",
    "pin",
    "calendar",
    "clock",
    "note",
    "book",
    "terminal",
    "code",
    "card",
    "bag",
    "gear",
    "sun",
    "heart",
    "star",
    "bolt",
    "home",
    "bell",
    "user",
    "game",
    "lock",
    "cloud",
    "folder",
)

// The first icon with a match wins, so more specific words come first.
private val ICON_WORDS = listOf(
    "bag" to listOf("play store", "galaxy store", "store", "shop", "amazon", "market"),
    "lock" to listOf("password", "bitwarden", "authenticator", "vault", "keepass"),
    "music" to listOf("music", "spotify", "tidal", "deezer", "soundcloud"),
    "headphones" to listOf("podcast", "audible", "audiobook"),
    "camera" to listOf("camera"),
    "phone" to listOf("phone", "dialer"),
    "chat" to listOf("messag", "sms", "signal", "whatsapp", "telegram", "chat", "discord", "slack"),
    "mail" to listOf("mail", "gmail", "outlook"),
    "globe" to listOf("chrome", "firefox", "browser", "internet", "brave", "opera", "vivaldi", "duckduckgo"),
    "pin" to listOf("maps", "waze", "navigation"),
    "play" to listOf("youtube", "video", "netflix", "player", "twitch", "plex"),
    "photo" to listOf("photo", "gallery"),
    "calendar" to listOf("calendar"),
    "clock" to listOf("clock", "alarm", "timer"),
    "note" to listOf("note", "keep", "obsidian", "memo"),
    "book" to listOf("book", "kindle", "reader"),
    "terminal" to listOf("termux", "terminal"),
    "code" to listOf("github", "gitlab", "code"),
    "card" to listOf("wallet", "pay", "bank"),
    "gear" to listOf("settings"),
    "sun" to listOf("weather"),
    "heart" to listOf("health", "fitness", "strava"),
    "home" to listOf("home"),
    "user" to listOf("contacts", "people"),
    "game" to listOf("game", "steam", "xbox"),
    "cloud" to listOf("drive", "cloud", "dropbox"),
    "folder" to listOf("files"),
)

/** The icon that fits an app, or null when none does. A name match beats a package match. */
fun suggestIcon(label: String, pkg: String): String? {
    val lower = label.lowercase()
    val names = lower.split(Regex("[^\\p{L}\\p{N}]+"))
    val parts = pkg.lowercase().split('.', '_')
    fun hit(word: String, tokens: List<String>) = if (' ' in word) word in lower else tokens.any { it.startsWith(word) }
    return ICON_WORDS.firstOrNull { (_, words) -> words.any { hit(it, names) } }?.first
        ?: ICON_WORDS.firstOrNull { (_, words) -> words.any { ' ' !in it && hit(it, parts) } }?.first
}

/** Adds the app, or removes it if present. Returns false only when the dock is full. */
fun MutableList<DockItem>.toggleApp(appId: String, icon: String?): Boolean {
    if (removeAll { it.appId == appId }) return true
    if (size >= DOCK_MAX) return false
    add(DockItem(appId, icon))
    return true
}
