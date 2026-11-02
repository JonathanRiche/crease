package app.crease.launcher

/** Characters a departure-board flap cycles through, in order. */
const val FLAP_CHARS = " abcdefghijklmnopqrstuvwxyz0123456789+-./&'"

/** How many flips a flap needs to go from [from] to [to], always turning forwards. */
fun flapSteps(from: Char, to: Char, chars: String = FLAP_CHARS): Int {
    val a = chars.indexOf(from).let { if (it < 0) 0 else it }
    val b = chars.indexOf(to).let { if (it < 0) 0 else it }
    return (b - a + chars.length) % chars.length
}

/** The character a flap shows after [steps] flips from [from]. */
fun flapChar(from: Char, steps: Int, chars: String = FLAP_CHARS): Char {
    val a = chars.indexOf(from).let { if (it < 0) 0 else it }
    return chars[(a + steps) % chars.length]
}

/** Pads or cuts [text] to exactly [width] characters, so every flap has a letter. */
fun boardText(text: String, width: Int): String =
    if (text.length > width) text.take((width - 1).coerceAtLeast(0)) + "." else text.padEnd(width)

/** Which item a vertical drag lands on: one item per [stepPx], up for the next. */
fun dragIndex(start: Int, dragUpPx: Float, stepPx: Float, count: Int): Int {
    if (count <= 0) return 0
    return (start + (dragUpPx / stepPx).toInt()).coerceIn(0, count - 1)
}

/** The index of the centre in [centres] nearest to [position]. */
fun nearestIndex(position: Float, centres: List<Float>): Int =
    centres.indices.minByOrNull { kotlin.math.abs(centres[it] - position) } ?: 0
