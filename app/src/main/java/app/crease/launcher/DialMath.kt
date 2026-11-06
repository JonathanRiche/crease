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

/** The click each home style makes as an app passes its marker. */
enum class TickKind { TAPE, BOARD, HINGE }

/**
 * A short click as 16-bit mono PCM at [rate] Hz, made from scratch so no sound file is needed.
 * Tape is a dull mechanical tick, board is the double clack of a flap landing, and hinge is a
 * soft high tick. [noise] supplies values in -1..1, so tests can pass a fixed sequence.
 */
fun tickSamples(kind: TickKind, rate: Int = 44100, noise: () -> Double = { Math.random() * 2 - 1 }): ShortArray {
    val ms = when (kind) {
        TickKind.TAPE -> 28
        TickKind.BOARD -> 42
        TickKind.HINGE -> 18
    }
    val n = rate * ms / 1000
    return ShortArray(n) { i ->
        val t = i.toDouble() / rate
        val v = when (kind) {
            TickKind.TAPE ->
                noise() * 0.45 * Math.exp(-t / 0.0035) + Math.sin(2 * Math.PI * 190 * t) * 0.35 * Math.exp(-t / 0.010)
            TickKind.BOARD -> {
                // The flap hits, then settles against the one behind it.
                val second = t - 0.013
                noise() * 0.5 * Math.exp(-t / 0.003) +
                    (if (second > 0) noise() * 0.32 * Math.exp(-second / 0.004) else 0.0) +
                    Math.sin(2 * Math.PI * 140 * t) * 0.2 * Math.exp(-t / 0.012)
            }
            TickKind.HINGE -> Math.sin(2 * Math.PI * 2100 * t) * 0.32 * Math.exp(-t / 0.0028)
        }
        (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * TICK_VOLUME).toInt().toShort()
    }
}

/** Kept well below full scale: a click should be heard, not felt. */
private const val TICK_VOLUME = 0.55
