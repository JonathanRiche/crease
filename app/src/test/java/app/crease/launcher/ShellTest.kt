package app.crease.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShellTest {
    private class FakeHost : Host {
        val launched = mutableListOf<String>()
        val searches = mutableListOf<String>()
        val bound = mutableListOf<String>()
        val refs = mutableListOf(WidgetRef(7, "Clock", 100, "inner"))
        var saves = 0
        override fun apps() = listOf(
            App("cam", "Camera"), App("camsc", "CamScanner"), App("chrome", "Chrome"),
            App("maps", "Google Maps"), App("msgs", "Messages"), App("slack", "Slack"),
        )
        override fun launch(app: App): Boolean { launched += app.label; return true }
        override fun web(query: String): Boolean { searches += query; return true }
        override fun info(app: App) = Unit
        override fun uninstall(app: App) = Unit
        override fun settings() = Unit
        override fun providers() = listOf(Provider("w1", "Clock: Analog"), Provider("w2", "Weather"))
        override fun bindWidget(providerId: String) { bound += providerId }
        override fun widgets() = refs.toList()
        override fun removeWidget(ref: WidgetRef) { refs.remove(ref) }
        override fun resizeWidget(ref: WidgetRef, heightDp: Int) {
            refs[refs.indexOf(ref)] = ref.copy(heightDp = heightDp)
        }
        override fun save() { saves++ }
        override fun themeChanged() = Unit
    }

    private lateinit var host: FakeHost
    private lateinit var state: State
    private lateinit var shell: Shell

    @Before fun setUp() {
        host = FakeHost(); state = State(); shell = Shell(state, host)
    }

    private fun last() = shell.output.last()
    private fun items() = shell.output.filter { it.kind == Kind.ITEM }.map { it.text }

    @Test fun scoresPreferTighterMatches() {
        assertEquals(0, score("slack", "Slack"))
        assertEquals(1, score("sl", "Slack"))
        assertEquals(2, score("maps", "Google Maps"))
        assertEquals(3, score("oogle", "Google Maps"))
        assertEquals(4, score("gm", "Google Maps"))
        assertNull(score("xyz", "Slack"))
        assertNull(score(" ", "Slack"))
    }

    @Test fun bareNameOpensUniqueMatch() {
        shell.run("chro")
        assertEquals(listOf("Chrome"), host.launched)
    }

    @Test fun exactNameWinsOverLongerPrefixMatch() {
        shell.run("camera")
        assertEquals(listOf("Camera"), host.launched)
    }

    @Test fun ambiguousNameListsChoicesWithoutOpening() {
        shell.run("cam")
        assertTrue(host.launched.isEmpty())
        assertEquals(listOf("Camera", "CamScanner"), items())
        assertEquals("open Camera", shell.output.first { it.kind == Kind.ITEM }.action?.cmd)
    }

    @Test fun unknownNameOffersWebSearch() {
        shell.run("zzzq")
        assertEquals(Kind.ERR, shell.output[1].kind)
        assertEquals("web zzzq", last().action?.cmd)
    }

    @Test fun folderLifecycle() {
        shell.run("folder new work")
        shell.run("folder add work slack")
        shell.run("folder add work google maps")
        assertEquals(listOf("slack", "maps"), state.folders["work"])

        shell.run("work")
        assertEquals(listOf("Slack", "Google Maps"), items())
        assertTrue(host.launched.isEmpty())

        shell.run("folder del WORK slack")
        assertEquals(listOf("maps"), state.folders["work"])
        shell.run("folder rm work")
        assertTrue(state.folders.isEmpty())
    }

    @Test fun lsShowsFoldersAndOnlyUnfiledApps() {
        shell.run("folder new work")
        shell.run("folder add work slack")
        shell.run("ls")
        val listed = items()
        assertEquals("work/  (1)", listed.first())
        assertFalse("Slack" in listed)
        assertTrue("Chrome" in listed)
    }

    @Test fun folderRejectsBadInput() {
        shell.run("folder new")
        assertEquals(Kind.ERR, last().kind)
        shell.run("folder new two words")
        assertTrue(state.folders.isEmpty())
        shell.run("folder add nope slack")
        assertEquals("no folder: nope", last().text)
    }

    @Test fun ambiguousFolderAddKeepsFolderInChoices() {
        shell.run("folder new tools")
        shell.run("folder add tools cam")
        assertEquals("folder add tools Camera", last().let { shell.output.first { l -> l.text == "Camera" } }.action?.cmd)
    }

    @Test fun aliasOpensApp() {
        shell.run("alias b chrome")
        shell.run("b")
        assertEquals(listOf("Chrome"), host.launched)
        shell.run("alias ls chrome")
        assertEquals(Kind.ERR, last().kind)
    }

    @Test fun hiddenAppsDropOutOfListsButCanReturn() {
        shell.run("hide slack")
        shell.run("slack")
        assertTrue(host.launched.isEmpty())
        shell.run("unhide slack")
        shell.run("slack")
        assertEquals(listOf("Slack"), host.launched)
    }

    @Test fun pinIsNotDuplicated() {
        shell.run("pin slack")
        shell.run("pin slack")
        assertEquals(listOf("slack"), state.pins)
    }

    @Test fun widgetCommands() {
        shell.run("widget add wea")
        assertEquals("widget bind w2", last().action?.cmd)
        shell.run("widget bind w2")
        assertEquals(listOf("w2"), host.bound)
        assertFalse(shell.output.any { it.text.contains("widget bind") })
        shell.run("widget size 1 300")
        assertEquals(300, host.refs[0].heightDp)
        shell.run("widget size 1 5")
        assertEquals(Kind.ERR, last().kind)
        shell.run("widget rm 9")
        assertEquals(Kind.ERR, last().kind)
        shell.run("widget rm 1")
        assertTrue(host.refs.isEmpty())
    }

    @Test fun clearEmptiesOutputAndBumpsGeneration() {
        shell.run("help")
        val generation = shell.generation
        shell.run("clear")
        assertTrue(shell.output.isEmpty())
        assertEquals(generation + 1, shell.generation)
    }

    @Test fun suggestionsCoverAppsCommandsAndArguments() {
        assertEquals("Slack", shell.suggest("sl").first().label)
        assertTrue(shell.suggest("wid").any { it.fill == "widget " && !it.run })
        assertEquals("pin Slack", shell.suggest("pin sl").first().fill)
        shell.run("folder new work")
        assertEquals("folder add work Slack", shell.suggest("folder add work sl").first().fill)
        assertEquals("folder add work ", shell.suggest("folder add wo").first().fill)
        assertEquals("open Google Maps", shell.suggest("google ma").first().fill)
        assertTrue(shell.suggest("").isEmpty())
    }

    @Test fun outputIsCapped() {
        repeat(800) { shell.run("help") }
        assertTrue(shell.output.size <= 600)
        assertTrue(shell.total > 600)
    }
}
