package app.crease.launcher

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// The Android-free half of the background feature: no Android imports, so JUnit can run it.

const val BG_BLACK = "black"
const val BG_WALLPAPER = "wallpaper"
const val BG_PHOTO = "photo"
const val DEFAULT_DIM = "medium"

/** Scrim strength over a photo or wallpaper at home, from 0 (none) to 1 (solid black). */
val DIM_LEVELS = linkedMapOf("low" to 0.74f, "medium" to 0.80f, "high" to 0.86f)

/** Search, edit and menu sit on a darker scrim than home, while still showing the dim level. */
const val PANEL_EXTRA_DIM = 0.06f

/** Long enough for the longest screen edge, so either screen can crop without enlarging. */
const val STORED_SHORT_SIDE = 2520
/** Keeps a panorama within GPU texture limits, and a stored photo under about 40 MB once decoded. */
const val STORED_LONG_SIDE = 4096

/** Dim and faint text over a photo or wallpaper, where the colours used on black would vanish. */
const val PHOTO_DIM_RGB = 0xC9C3B5
const val PHOTO_FAINT_RGB = 0xBDB7A9

/** Anything unknown in saved state means today's look. */
fun knownBackground(saved: String) = if (saved == BG_WALLPAPER || saved == BG_PHOTO) saved else BG_BLACK

fun knownDim(saved: String) = if (saved in DIM_LEVELS) saved else DEFAULT_DIM

fun scrimAlpha(background: String, dim: String, panelOpen: Boolean): Float {
    if (background == BG_BLACK) return 0f
    val level = DIM_LEVELS[dim] ?: DIM_LEVELS.getValue(DEFAULT_DIM)
    return if (panelOpen) level + PANEL_EXTRA_DIM else level
}

data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** The centred part of a source image with the target's shape: what "centre-crop to fill" shows. */
fun centreCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Crop {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Crop(0, 0, max(srcW, 0), max(srcH, 0))
    // Long: a 4096 px edge times a 2520 px edge is fine in Int, but a bad size shouldn't overflow.
    return if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
        val w = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
        val left = (srcW - w) / 2
        Crop(left, 0, left + w, srcH)
    } else {
        val h = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
        val top = (srcH - h) / 2
        Crop(0, top, srcW, top + h)
    }
}

/**
 * The size to decode a photo at, whichever way round it is: its short side down to
 * [STORED_SHORT_SIDE], then its long side down to [STORED_LONG_SIDE]. It never enlarges.
 * Null for a size no image can have.
 */
fun decodeSize(w: Int, h: Int): Pair<Int, Int>? {
    if (w <= 0 || h <= 0) return null
    val scale = min(
        min(1.0, STORED_SHORT_SIDE.toDouble() / min(w, h)),
        STORED_LONG_SIDE.toDouble() / max(w, h),
    )
    if (scale >= 1.0) return w to h
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
}

/** WCAG contrast ratio of opaque 0xRRGGBB text over a grey, for checking the dim levels. */
fun contrastOver(textRgb: Int, grey: Int): Double {
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(r: Int, g: Int, b: Int) = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    val text = luminance(textRgb shr 16 and 255, textRgb shr 8 and 255, textRgb and 255)
    val back = luminance(grey, grey, grey)
    return (max(text, back) + 0.05) / (min(text, back) + 0.05)
}

/** What a pure white pixel becomes under a black scrim: the worst case for light text. */
fun whiteUnderScrim(alpha: Float) = (255 * (1 - alpha)).roundToInt()
