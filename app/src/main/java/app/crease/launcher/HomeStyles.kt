package app.crease.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Colours and font a home style draws with. */
class DialLook(
    val text: Int,
    val dim: Int,
    val faint: Int,
    val accent: Int,
    val card: Int,
    val mono: Typeface,
    val light: Typeface,
)

/**
 * A home style: a physical control for picking an app. Each one shows the same items as the home
 * list, opens an item on release or tap, and opens its menu on hold.
 */
abstract class HomeDial(context: Context, protected val look: DialLook) : View(context) {
    class Item(val label: String, val folder: Boolean, val open: () -> Unit, val hold: () -> Unit)

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            onItemsChanged()
            invalidate()
        }

    protected open fun onItemsChanged() {}

    protected val density = resources.displayMetrics.density
    protected fun dp(v: Float) = v * density
    protected fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    protected val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    protected fun tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    protected fun textPaint(size: Float, face: Typeface = look.mono) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
    }

    // Hold detection, shared by every style.
    private var holdFired = false
    private var holdTarget: (() -> Unit)? = null
    private val holdRunnable = Runnable {
        holdFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        holdTarget?.invoke()
    }

    protected fun startHold(target: () -> Unit) {
        holdFired = false
        holdTarget = target
        removeCallbacks(holdRunnable)
        postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    protected fun cancelHold() = removeCallbacks(holdRunnable)
    protected val held get() = holdFired

    /** Whether a touch at these screen coordinates belongs to this control, not to home. */
    open fun claims(rawX: Float, rawY: Float): Boolean {
        if (!isShown) return false
        val at = IntArray(2)
        getLocationOnScreen(at)
        return rawX >= at[0] && rawX <= at[0] + width && rawY >= at[1] && rawY <= at[1] + height
    }

    protected fun local(rawX: Float, rawY: Float): Pair<Float, Float> {
        val at = IntArray(2)
        getLocationOnScreen(at)
        return (rawX - at[0]) to (rawY - at[1])
    }
}

/**
 * The hinge: apps sit as tick marks down the middle of the screen, along the crease on the inner
 * screen. Drag a thumb along it and the name under it lifts out across both halves.
 */
class HingeDial(context: Context, look: DialLook) : HomeDial(context, look) {
    private var active = -1
    private var touching = false
    private var downY = 0f
    private var moved = false
    private val band get() = dp(56f)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val small = textPaint(sp(11f))
    private val big = textPaint(sp(32f), look.light)
    private val near = textPaint(sp(14f))
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.card }

    private fun step(): Float {
        val n = items.size.coerceAtLeast(1)
        return minOf(height * 0.8f / n, dp(58f))
    }

    private fun top() = height / 2f - step() * items.size / 2f
    private fun yFor(i: Int) = top() + (i + 0.5f) * step()
    private fun indexAt(y: Float) = ((y - top()) / step()).toInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))

    override fun claims(rawX: Float, rawY: Float): Boolean {
        if (!super.claims(rawX, rawY) || items.isEmpty()) return false
        val (x, y) = local(rawX, rawY)
        return abs(x - width / 2f) <= band && y >= top() - step() && y <= top() + step() * (items.size + 1)
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val spine = width / 2f
        items.forEachIndexed { i, item ->
            val y = yFor(i)
            val distance = if (touching) abs(i - active).toFloat() else 99f
            val swell = (1f - distance / 4f).coerceAtLeast(0f)
            val half = dp(5f) + swell * dp(22f)
            tickPaint.strokeWidth = dp(1.4f) + swell * dp(1f)
            tickPaint.color = if (touching && i == active) look.accent else if (item.folder) look.faint else look.dim
            canvas.drawLine(spine - half, y, spine + half, y, tickPaint)
            if (!touching) {
                // At rest, names sit small beside their ticks, alternating sides of the fold.
                small.color = look.faint
                val w = small.measureText(item.label)
                val x = if (i % 2 == 0) spine + dp(14f) else spine - dp(14f) - w
                canvas.drawText(item.label, x, y + small.textSize / 3f, small)
            }
        }
        if (touching && active in items.indices) {
            for (k in -3..3) {
                val i = active + k
                if (k == 0 || i !in items.indices) continue
                val fade = 1f - abs(k) / 4f
                near.color = look.dim
                near.alpha = (255 * fade).toInt()
                val label = items[i].label
                val w = near.measureText(label)
                val x = if (k > 0) spine + dp(40f) else spine - dp(40f) - w
                canvas.drawText(label, x, yFor(i) + near.textSize / 3f, near)
            }
            // The active name lifts out over the fold.
            val label = items[active].label
            val w = big.measureText(label)
            val y = yFor(active)
            val r = RectF(spine - w / 2 - dp(18f), y - big.textSize * 0.85f, spine + w / 2 + dp(18f), y + big.textSize * 0.55f)
            canvas.drawRoundRect(r, dp(12f), dp(12f), pill)
            big.color = if (items[active].folder) look.accent else look.text
            canvas.drawText(label, spine - w / 2, y + big.textSize / 3f, big)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (abs(e.x - width / 2f) > band) return false
                touching = true
                moved = false
                downY = e.y
                active = indexAt(e.y)
                tick()
                startHold { items.getOrNull(active)?.hold?.invoke() }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - downY) > slop) {
                    moved = true
                    cancelHold()
                }
                val next = indexAt(e.y)
                if (next != active) {
                    active = next
                    tick()
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelHold()
                if (!held) items.getOrNull(active)?.open?.invoke()
                touching = false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                touching = false
            }
        }
        invalidate()
        return true
    }
}

/**
 * The departure board: one app at a time on split flaps. Drag up or down and every letter flips
 * through the alphabet to the next name.
 */
class BoardDial(context: Context, look: DialLook) : HomeDial(context, look) {
    private var index = 0
    private var shown = ""
    private var from = ""
    private var target = ""
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private var downY = 0f
    private var startIndex = 0
    private var moved = false

    private val flap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.card }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = look.faint
    }
    private val split = Paint().apply { color = 0x99000000.toInt(); strokeWidth = dp(1.5f) }
    private val letter = textPaint(sp(26f))
    private val caption = textPaint(sp(12f))

    private fun cells(): Int {
        val longest = items.maxOfOrNull { it.label.length } ?: 6
        val fit = (width / dp(26f)).toInt().coerceAtLeast(4)
        return longest.coerceIn(4, minOf(fit, 16))
    }

    override fun onItemsChanged() {
        index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        shown = boardText(items.getOrNull(index)?.label ?: "", cells())
        from = shown
        target = shown
        progress = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = onItemsChanged()

    private fun flipTo(next: Int) {
        if (next == index) return
        index = next
        tick()
        val width = cells()
        from = boardText(currentText(), width)
        target = boardText(items[index].label, width)
        val most = from.indices.maxOfOrNull { flapSteps(from[it], target[it]) } ?: 0
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (most * 14L + from.length * 12L).coerceIn(180L, 520L)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** What each flap shows right now, part way through a flip. */
    private fun currentText(): String {
        if (progress >= 1f) return target
        val n = from.length
        return String(CharArray(n) { i ->
            val steps = flapSteps(from[i], target[i])
            // Flaps start one after another from the left, then flip at their own pace.
            val start = i.toFloat() / (n + 4)
            val local = ((progress - start) / (1f - start)).coerceIn(0f, 1f)
            flapChar(from[i], (steps * local).toInt())
        })
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val text = currentText()
        val n = text.length
        val cellW = minOf(dp(34f), width / (n + 1f))
        val cellH = cellW * 1.45f
        val gap = cellW * 0.12f
        val total = n * cellW + (n - 1) * gap
        val left = (width - total) / 2f
        val top = height / 2f - cellH / 2f
        letter.textSize = cellH * 0.62f
        val item = items[index]
        letter.color = if (item.folder) look.accent else look.text
        for (i in 0 until n) {
            val x = left + i * (cellW + gap)
            val r = RectF(x, top, x + cellW, top + cellH)
            canvas.drawRoundRect(r, dp(4f), dp(4f), flap)
            canvas.drawRoundRect(r, dp(4f), dp(4f), edge)
            val ch = text[i].toString()
            val w = letter.measureText(ch)
            canvas.drawText(ch, x + (cellW - w) / 2f, top + cellH / 2f + letter.textSize / 3f, letter)
            canvas.drawLine(x, top + cellH / 2f, x + cellW, top + cellH / 2f, split)
        }
        caption.color = look.faint
        val count = "${index + 1}/${items.size}" + if (item.folder) "  folder" else ""
        canvas.drawText(count, left, top - dp(10f), caption)
        val hint = "drag ↕"
        canvas.drawText(hint, left + total - caption.measureText(hint), top + cellH + dp(20f), caption)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                startIndex = index
                moved = false
                startHold { items.getOrNull(index)?.hold?.invoke() }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - downY) > slop) {
                    moved = true
                    cancelHold()
                }
                if (moved) flipTo(dragIndex(startIndex, downY - e.y, dp(44f), items.size))
            }
            MotionEvent.ACTION_UP -> {
                cancelHold()
                if (!moved && !held) items.getOrNull(index)?.open?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        return true
    }
}

/**
 * The tape: names written on a strip of tape running between two reels. Drag or flick it through
 * the reading head; the reels turn and wind as it moves.
 */
class TapeDial(context: Context, look: DialLook) : HomeDial(context, look) {
    private var offset = 0f
    private var centres = listOf<Float>()
    private var current = 0
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var lastX = 0f
    private var downX = 0f
    private var moved = false
    private var settling = false

    private val label = textPaint(sp(14f))
    private val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.card }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f); color = look.faint }
    private val reel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = look.dim
    }
    private val wound = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.card }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.accent; strokeWidth = dp(1.5f) }

    override fun onItemsChanged() {
        var x = 0f
        val gap = dp(30f)
        centres = items.map { item ->
            val w = label.measureText(item.label)
            val c = x + w / 2f
            x += w + gap
            c
        }
        offset = offset.coerceIn(centres.firstOrNull() ?: 0f, centres.lastOrNull() ?: 0f)
        if (items.isNotEmpty() && current !in items.indices) current = 0
        if (centres.isNotEmpty() && offset == 0f) offset = centres[0]
    }

    private val minOffset get() = centres.firstOrNull() ?: 0f
    private val maxOffset get() = centres.lastOrNull() ?: 0f

    private fun moveTo(value: Float) {
        offset = value.coerceIn(minOffset, maxOffset)
        val nearest = nearestIndex(offset, centres)
        if (nearest != current) {
            current = nearest
            tick()
        }
        invalidate()
    }

    private fun snap() {
        if (centres.isEmpty()) return
        settling = true
        val target = centres[nearestIndex(offset, centres)]
        scroller.startScroll(offset.toInt(), 0, (target - offset).toInt(), 0, 260)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveTo(scroller.currX.toFloat())
            postInvalidateOnAnimation()
        } else {
            settling = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val mid = width / 2f
        val bandY = height * 0.36f
        val bandH = dp(44f)
        val reelY = height * 0.74f
        val reelX1 = width * 0.2f
        val reelX2 = width * 0.8f
        val span = (maxOffset - minOffset).coerceAtLeast(1f)
        val played = (offset - minOffset) / span
        val rMin = dp(16f)
        val rMax = minOf(dp(40f), height * 0.22f)
        val left = rMin + (rMax - rMin) * played
        val right = rMax - (rMax - rMin) * played

        // Tape running from each reel up to the band.
        canvas.drawLine(reelX1 - left, reelY, 0f + dp(8f), bandY + bandH / 2f, line)
        canvas.drawLine(reelX2 + right, reelY, width - dp(8f), bandY + bandH / 2f, line)
        canvas.drawRect(0f, bandY - bandH / 2f, width.toFloat(), bandY + bandH / 2f, tape)
        canvas.drawLine(0f, bandY - bandH / 2f, width.toFloat(), bandY - bandH / 2f, line)
        canvas.drawLine(0f, bandY + bandH / 2f, width.toFloat(), bandY + bandH / 2f, line)

        items.forEachIndexed { i, item ->
            val x = mid + (centres[i] - offset)
            val distance = abs(x - mid)
            if (distance > width) return@forEachIndexed
            val focus = (1f - distance / (width * 0.45f)).coerceIn(0f, 1f)
            label.textSize = sp(13f) + focus * sp(5f)
            label.color = if (i == current) look.accent else if (item.folder) look.dim else look.text
            label.alpha = (90 + 165 * focus).toInt()
            val w = label.measureText(item.label)
            canvas.drawText(item.label, x - w / 2f, bandY + label.textSize / 3f, label)
        }

        // The reading head.
        canvas.drawLine(mid, bandY - bandH / 2f - dp(14f), mid, bandY - bandH / 2f - dp(4f), head)
        canvas.drawLine(mid, bandY + bandH / 2f + dp(4f), mid, bandY + bandH / 2f + dp(14f), head)

        // Reels: tape wound on as wide circles, spokes turning with the tape.
        val turn = offset / dp(18f)
        drawReel(canvas, reelX1, reelY, left, turn)
        drawReel(canvas, reelX2, reelY, right, turn)
    }

    private fun drawReel(canvas: Canvas, cx: Float, cy: Float, radius: Float, turn: Float) {
        canvas.drawCircle(cx, cy, radius, wound)
        canvas.drawCircle(cx, cy, radius, reel)
        canvas.drawCircle(cx, cy, dp(7f), reel)
        for (k in 0 until 3) {
            val a = turn + k * 2f * PI.toFloat() / 3f
            canvas.drawLine(
                cx + cos(a) * dp(7f), cy + sin(a) * dp(7f),
                cx + cos(a) * (dp(7f) + dp(7f)), cy + sin(a) * (dp(7f) + dp(7f)),
                reel,
            )
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                settling = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                lastX = e.x
                downX = e.x
                moved = false
                startHold { items.getOrNull(current)?.hold?.invoke() }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop) {
                    moved = true
                    cancelHold()
                }
                if (moved) moveTo(offset - (e.x - lastX))
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                cancelHold()
                if (!moved && !held) {
                    // A tap opens the name under the finger, or the one at the head.
                    val mid = width / 2f
                    val hit = items.indices.firstOrNull { i ->
                        val w = label.measureText(items[i].label)
                        abs(mid + (centres[i] - offset) - e.x) <= w / 2f + dp(6f)
                    } ?: current
                    items.getOrNull(hit)?.open?.invoke()
                } else if (moved) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    if (abs(vx) > dp(300f)) {
                        scroller.fling(offset.toInt(), 0, -vx.toInt(), 0, minOffset.toInt(), maxOffset.toInt(), 0, 0)
                        postOnAnimationDelayed({ snapWhenStopped() }, 16)
                        postInvalidateOnAnimation()
                    } else {
                        snap()
                    }
                }
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        return true
    }

    private fun snapWhenStopped() {
        if (!scroller.isFinished) {
            postOnAnimationDelayed({ snapWhenStopped() }, 16)
        } else {
            snap()
        }
    }
}
