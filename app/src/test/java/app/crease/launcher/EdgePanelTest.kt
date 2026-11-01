package app.crease.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgePanelTest {
    private fun app(pkg: String, cls: String = "Main", serial: Int = 0) =
        App("$pkg/$pkg.$cls#$serial", pkg)

    private val mail = app("mail")
    private val maps = app("maps")
    private val chat = app("chat")
    private val installed = listOf(mail, maps, chat).groupBy { packageOfAppId(it.id) }

    private fun labels(rows: List<PanelRow>) = rows.map { it.app.label to it.recent }

    @Test fun `pins come first then recents in order`() {
        val rows = panelRows(listOf(chat), listOf("maps", "mail"), installed, emptySet())
        assertEquals(listOf("chat" to false, "maps" to true, "mail" to true), labels(rows))
    }

    @Test fun `no usage access means pins only`() {
        assertEquals(listOf("mail" to false), labels(panelRows(listOf(mail), emptyList(), installed, emptySet())))
    }

    @Test fun `an empty panel has no rows`() {
        assertTrue(panelRows(emptyList(), emptyList(), installed, emptySet()).isEmpty())
    }

    @Test fun `a pinned app is not repeated as a recent`() {
        val rows = panelRows(listOf(mail), listOf("mail", "maps"), installed, emptySet())
        assertEquals(listOf("mail" to false, "maps" to true), labels(rows))
    }

    @Test fun `duplicates are dropped from both lists`() {
        val rows = panelRows(listOf(mail, mail), listOf("maps", "maps"), installed, emptySet())
        assertEquals(listOf("mail" to false, "maps" to true), labels(rows))
    }

    @Test fun `a package with one app pinned adds no recent for its other apps`() {
        val phone = App("dialer/dialer.Phone#0", "phone")
        val contacts = App("dialer/dialer.Contacts#0", "contacts")
        val rows = panelRows(listOf(contacts), listOf("dialer"), mapOf("dialer" to listOf(phone, contacts)), emptySet())
        assertEquals(listOf("contacts" to false), labels(rows))
    }

    @Test fun `a work pin does not hide the personal app from recents`() {
        val workMail = app("mail", serial = 10)
        val rows = panelRows(listOf(workMail), listOf("mail"), installed, emptySet())
        assertEquals(listOf(workMail.id, mail.id), rows.map { it.app.id })
    }

    @Test fun `hidden apps are not recents but stay as pins`() {
        val rows = panelRows(listOf(chat), listOf("maps", "mail"), installed, setOf(chat.id, maps.id))
        assertEquals(listOf("chat" to false, "mail" to true), labels(rows))
    }

    @Test fun `a hidden app gives way to the next app in its package`() {
        val phone = App("dialer/dialer.Phone#0", "phone")
        val contacts = App("dialer/dialer.Contacts#0", "contacts")
        val rows = panelRows(emptyList(), listOf("dialer"), mapOf("dialer" to listOf(phone, contacts)), setOf(phone.id))
        assertEquals(listOf("contacts" to true), labels(rows))
    }

    @Test fun `recents with no launchable app are skipped`() {
        assertTrue(panelRows(emptyList(), listOf("com.android.systemui"), installed, emptySet()).isEmpty())
    }

    @Test fun `the cap keeps pins ahead of recents`() {
        val rows = panelRows(listOf(mail, maps), listOf("chat"), installed, emptySet(), max = 2)
        assertEquals(listOf("mail" to false, "maps" to false), labels(rows))
    }

    @Test fun `a negative cap gives no rows rather than throwing`() {
        assertTrue(panelRows(listOf(mail), emptyList(), installed, emptySet(), max = -1).isEmpty())
    }

    @Test fun `package comes from the app id`() {
        assertEquals("com.a.b", packageOfAppId("com.a.b/com.a.b.Main#0"))
    }

    @Test fun `inner screen starts at 600dp`() {
        assertFalse(isInnerScreen(0))
        assertFalse(isInnerScreen(599))
        assertTrue(isInnerScreen(600))
    }

    @Test fun `strip sits below the middle of a tall screen`() {
        assertEquals(440, stripTopDp(screenHeightDp = 840, stripHeightDp = 160))
    }

    @Test fun `strip stays clear of the bottom and on screen`() {
        assertEquals(144, stripTopDp(screenHeightDp = 400, stripHeightDp = 160))
        assertEquals(0, stripTopDp(screenHeightDp = 100, stripHeightDp = 160))
    }

    @Test fun `a swipe must be long enough and mostly along its axis`() {
        assertTrue(isSwipe(along = 40f, across = 10f, minPx = 30f))
        assertFalse(isSwipe(along = 20f, across = 0f, minPx = 30f))
        assertFalse(isSwipe(along = 40f, across = 30f, minPx = 30f))
        assertFalse(isSwipe(along = -40f, across = 0f, minPx = 30f))
    }

    @Test fun dragIsClampedToThePanel() {
        assertEquals(0f, revealedClamped(-50f, 200f), 0f)
        assertEquals(120f, revealedClamped(120f, 200f), 0f)
        assertEquals(200f, revealedClamped(900f, 200f), 0f)
    }

    @Test fun releaseFollowsPositionUnlessFlicked() {
        assertEquals(true, settlesOpen(100f, 200f, 0f, 1000f))
        assertEquals(false, settlesOpen(60f, 200f, 0f, 1000f))
        assertEquals(true, settlesOpen(10f, 200f, 2000f, 1000f))
        assertEquals(false, settlesOpen(190f, 200f, -2000f, 1000f))
    }
}
