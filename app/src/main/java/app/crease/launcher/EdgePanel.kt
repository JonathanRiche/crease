package app.crease.launcher

import kotlin.math.abs

/** Edge panel logic. Has no Android imports so it can be unit tested on the JVM. */

const val EDGE_PANEL_MAX_ROWS = 16

data class PanelRow(val app: App, val recent: Boolean)

/** The strip and panel exist only on the inner screen. */
fun isInnerScreen(smallestScreenWidthDp: Int) = smallestScreenWidthDp >= 600

/**
 * Pins in their saved order, then recents, most recent first, up to [max] rows.
 *
 * Usage stats report packages, so each recent is shown as the first non-hidden app in that
 * package, and is dropped if any app in the package is already pinned. Pins follow the home
 * screen and stay listed even when hidden, whereas a hidden app never appears as a recent.
 *
 * @param appsByPackage launchable apps in the main profile. Usage stats only cover that profile.
 */
fun panelRows(
    pins: List<App>,
    recentPackages: List<String>,
    appsByPackage: Map<String, List<App>>,
    hidden: Set<String>,
    max: Int = EDGE_PANEL_MAX_ROWS,
): List<PanelRow> {
    val pinned = pins.distinctBy { it.id }
    val pinnedIds = pinned.map { it.id }.toSet()
    val recents = recentPackages.distinct().mapNotNull { pkg ->
        val apps = appsByPackage[pkg].orEmpty()
        if (apps.any { it.id in pinnedIds }) null else apps.firstOrNull { it.id !in hidden }
    }
    return (pinned.map { PanelRow(it, false) } + recents.map { PanelRow(it, true) })
        .take(max.coerceAtLeast(0))
}

/** App ids look like "package/class#serial". */
fun packageOfAppId(id: String) = id.substringBefore('/')

/**
 * Top of the strip, in dp from the top of the screen. It is centred at [centre] of the height:
 * below Samsung's Edge panel handle, and kept clear of the bottom gesture area.
 */
fun stripTopDp(screenHeightDp: Int, stripHeightDp: Int, centre: Float = 0.62f, bottomMarginDp: Int = 96): Int {
    val top = (screenHeightDp * centre).toInt() - stripHeightDp / 2
    return top.coerceAtMost(screenHeightDp - stripHeightDp - bottomMarginDp).coerceAtLeast(0)
}

/** True once a drag has gone [minPx] along its axis, and mostly along it rather than across. */
fun isSwipe(along: Float, across: Float, minPx: Float) = along > minPx && along > 2 * abs(across)

/** How much of the panel shows while dragging: never less than none, never more than all of it. */
fun revealedClamped(dragPx: Float, widthPx: Float) = dragPx.coerceIn(0f, widthPx)

/**
 * Whether a released panel finishes opening. A quick flick decides by its direction; otherwise it
 * opens if at least [OPEN_FRACTION] of it is showing.
 */
fun settlesOpen(revealedPx: Float, widthPx: Float, inwardPxPerSec: Float, flingPxPerSec: Float) = when {
    inwardPxPerSec > flingPxPerSec -> true
    inwardPxPerSec < -flingPxPerSec -> false
    else -> revealedPx >= widthPx * OPEN_FRACTION
}

private const val OPEN_FRACTION = 0.4f
