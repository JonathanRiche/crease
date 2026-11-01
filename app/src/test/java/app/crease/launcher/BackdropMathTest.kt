package app.crease.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BackdropMathTest {
    private val cover = 1080 to 2520
    private val inner = 2256 to 2504

    private fun assertShape(crop: Crop, screen: Pair<Int, Int>) {
        val wanted = screen.first.toDouble() / screen.second
        val got = crop.width.toDouble() / crop.height
        assertTrue("$crop for $screen", abs(wanted - got) < 0.002)
    }

    @Test fun cropIsCentredAndFillsBothScreens() {
        val photo = 3360 to 2520
        listOf(cover, inner).forEach { screen ->
            val c = centreCrop(photo.first, photo.second, screen.first, screen.second)
            assertShape(c, screen)
            assertEquals(0, c.top)
            assertEquals(2520, c.bottom)
            assertTrue(abs(c.left - (photo.first - c.right)) <= 1)
        }
        assertEquals(Crop(1140, 0, 2220, 2520), centreCrop(3360, 2520, 1080, 2520))
    }

    @Test fun samePhotoCropsDifferentlyPerScreen() {
        val onCover = centreCrop(2520, 3360, cover.first, cover.second)
        val onInner = centreCrop(2520, 3360, inner.first, inner.second)
        assertEquals(Crop(540, 0, 1980, 3360), onCover)
        assertEquals(2520, onInner.width)
        assertShape(onInner, inner)
    }

    @Test fun cropWithSameShapeIsWholeImage() {
        assertEquals(Crop(0, 0, 1080, 2520), centreCrop(1080, 2520, 1080, 2520))
    }

    @Test fun cropHandlesExtremesAndBadSizes() {
        val thin = centreCrop(100000, 1, 1080, 2520)
        assertTrue(thin.width >= 1 && thin.height == 1)
        assertEquals(Crop(0, 0, 0, 0), centreCrop(0, 0, 1080, 2520))
        assertEquals(Crop(0, 0, 40, 30), centreCrop(40, 30, 0, 0))
    }

    @Test fun decodeNeverEnlargesAndRejectsBadSizes() {
        assertEquals(800 to 600, decodeSize(800, 600))
        assertEquals(STORED_SHORT_SIDE to STORED_SHORT_SIDE, decodeSize(STORED_SHORT_SIDE, STORED_SHORT_SIDE))
        assertNull(decodeSize(0, 100))
        assertNull(decodeSize(100, -1))
    }

    @Test fun decodeShrinksBigPhotosToCoverEitherScreen() {
        assertEquals(3360 to 2520, decodeSize(8160, 6120))
        assertEquals(2520 to 3360, decodeSize(6120, 8160))
    }

    @Test fun decodeIsBoundedForAnySize() {
        listOf(30000 to 30000, 100000 to 1, 1 to 100000, 20000 to 3000, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (w, h) ->
            val (dw, dh) = decodeSize(w, h)!!
            assertTrue("$dw x $dh", max(dw, dh) <= STORED_LONG_SIDE)
            assertTrue("$dw x $dh", min(dw, dh) in 1..STORED_SHORT_SIDE)
            // Under the 100 MB bitmap draw limit, even at 8 bytes a pixel for an HDR photo.
            assertTrue(dw.toLong() * dh * 8 < 100_000_000L)
        }
    }

    @Test fun savedValuesFallBack() {
        assertEquals(BG_BLACK, knownBackground(""))
        assertEquals(BG_BLACK, knownBackground("nonsense"))
        assertEquals(BG_PHOTO, knownBackground(BG_PHOTO))
        assertEquals(DEFAULT_DIM, knownDim("nonsense"))
    }

    @Test fun blackHasNoScrim() {
        DIM_LEVELS.keys.forEach { dim ->
            assertEquals(0f, scrimAlpha(BG_BLACK, dim, false))
            assertEquals(0f, scrimAlpha(BG_BLACK, dim, true))
        }
    }

    @Test fun dimLevelsStayDistinctInPanels() {
        val home = DIM_LEVELS.keys.map { scrimAlpha(BG_PHOTO, it, false) }
        val panel = DIM_LEVELS.keys.map { scrimAlpha(BG_PHOTO, it, true) }
        assertEquals(home.sorted(), home)
        assertEquals(3, panel.toSet().size)
        home.zip(panel).forEach { (h, p) -> assertTrue(p in (h + 0.001f)..1f) }
    }

    // The worst case: every text colour over a pure white photo, at the lowest dim.
    private fun assertAA(rgb: Int, panel: Boolean) {
        val grey = whiteUnderScrim(scrimAlpha(BG_PHOTO, "low", panel))
        val ratio = contrastOver(rgb, grey)
        assertTrue("%06x over grey %d is %.2f".format(rgb, grey, ratio), ratio >= 4.5)
    }

    @Test fun textStaysReadableOnWhiteAtLowestDim() {
        listOf(0xE6DFCF, PHOTO_DIM_RGB, PHOTO_FAINT_RGB).forEach {
            assertAA(it, panel = false)
            assertAA(it, panel = true)
        }
        THEMES.values.forEach {
            assertAA((it and 0xFFFFFF).toInt(), panel = false)
            assertAA((it and 0xFFFFFF).toInt(), panel = true)
        }
        // Errors only appear on panels.
        assertAA(0xD98A7A, panel = true)
    }

    @Test fun contrastMatchesKnownValues() {
        assertEquals(21.0, contrastOver(0xFFFFFF, 0), 0.01)
        assertEquals(1.0, contrastOver(0x808080, 128), 0.01)
    }
}
