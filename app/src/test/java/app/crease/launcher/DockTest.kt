package app.crease.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockTest {
    @Test fun suggestsAnIconFromTheNameOrPackage() {
        assertEquals("phone", suggestIcon("Phone", "com.samsung.android.dialer"))
        assertEquals("globe", suggestIcon("Chrome", "com.android.chrome"))
        assertEquals("chat", suggestIcon("Messages", "com.samsung.android.messaging"))
        assertEquals("music", suggestIcon("YT Music", "com.google.android.apps.youtube.music"))
        assertEquals("lock", suggestIcon("Bitwarden", "com.x8bit.bitwarden"))
        assertEquals("mail", suggestIcon("Zed", "com.example.mail"))
        assertNull(suggestIcon("Zipcar", "com.zipcar.zipcar"))
    }

    @Test fun everySuggestionHasAnIcon() {
        listOf("Store", "Vault", "Podcast", "Camera", "Mail", "Maps", "Netflix", "Gallery", "Calendar", "Alarm",
            "Notes", "Kindle", "Termux", "GitHub", "Wallet", "Settings", "Weather", "Health", "Home", "Contacts",
            "Steam", "Drive", "Files").forEach { name ->
            val icon = suggestIcon(name, "x")
            assertTrue("$name -> $icon", icon in DOCK_ICONS)
        }
    }

    @Test fun togglingAddsRemovesAndRespectsTheLimit() {
        val dock = mutableListOf<DockItem>()
        assertTrue(dock.toggleApp("a", "phone"))
        assertEquals(listOf(DockItem("a", "phone")), dock)
        assertTrue(dock.toggleApp("a", "phone"))
        assertTrue(dock.isEmpty())
        repeat(DOCK_MAX) { assertTrue(dock.toggleApp("app$it", null)) }
        assertFalse(dock.toggleApp("extra", null))
        assertEquals(DOCK_MAX, dock.size)
        assertTrue(dock.toggleApp("app0", null))
        assertEquals(DOCK_MAX - 1, dock.size)
    }
}
