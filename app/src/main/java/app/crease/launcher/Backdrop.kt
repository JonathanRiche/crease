package app.crease.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/** One thing a [BackdropDrawable] can show: a photo, solid black, or nothing at all. */
private class BackdropLayer(val bitmap: Bitmap?, val clear: Boolean = false) {
    val src = Rect()

    fun crop(bounds: Rect) {
        val b = bitmap ?: return
        val c = centreCrop(b.width, b.height, bounds.width(), bounds.height())
        src.set(c.left, c.top, c.right, c.bottom)
    }
}

private val BLACK_LAYER = BackdropLayer(null)
private val CLEAR_LAYER = BackdropLayer(null, clear = true)

/**
 * Draws behind the home screen. Holds the whole stored photo and crops when its bounds change,
 * so a fold or unfold needs only a new crop, with no decoding.
 */
private class BackdropDrawable(private val settled: () -> Unit) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var from = BLACK_LAYER
    private var to = BLACK_LAYER
    private var progress = 1f
    private var scrim = 0f
    private var fade: ValueAnimator? = null
    private var scrimFade: ValueAnimator? = null

    fun show(layer: BackdropLayer, animate: Boolean) {
        fade?.end()
        if (layer === to) return
        from = to
        to = layer
        layer.crop(bounds)
        if (!animate) return finishFade()
        progress = 0f
        // Stored before start(): with animations off, start() finishes at once.
        val animator = ValueAnimator.ofFloat(0f, 1f)
        fade = animator
        animator.apply {
            duration = FADE_MS
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishFade()
            })
            start()
        }
    }

    private fun finishFade() {
        fade = null
        progress = 1f
        from = to // Lets go of a replaced photo.
        invalidateSelf()
        settled()
    }

    fun scrimTo(target: Float, animate: Boolean) {
        scrimFade?.cancel()
        scrimFade = null
        if (!animate) {
            scrim = target
            return invalidateSelf()
        }
        if (target == scrim) return
        scrimFade = ValueAnimator.ofFloat(scrim, target).apply {
            duration = SCRIM_MS
            addUpdateListener {
                scrim = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        from.crop(bounds)
        to.crop(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (to.clear) {
            // Fade the old layer out to uncover the wallpaper.
            paintLayer(canvas, from, 1f - progress)
        } else {
            if (from !== to) paintLayer(canvas, from, 1f)
            paintLayer(canvas, to, progress)
        }
        if (scrim > 0f) canvas.drawColor((scrim * 255).toInt() shl 24)
    }

    private fun paintLayer(canvas: Canvas, layer: BackdropLayer, alpha: Float) {
        if (layer.clear) return
        val a = (alpha * 255).toInt()
        val bitmap = layer.bitmap
        if (bitmap == null) {
            canvas.drawColor(a shl 24)
        } else {
            paint.alpha = a
            canvas.drawBitmap(bitmap, layer.src, bounds, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Platform Drawable API")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val FADE_MS = 300L
        const val SCRIM_MS = 150L
    }
}

/**
 * The background setting: what is shown, the private copy of the photo, and the window flags.
 * [changed] reports a message for the edit screen (or null), and whether the colours of
 * the text must change, which happens when moving between black and anything else.
 */
class Backdrop(
    private val activity: Activity,
    private val state: State,
    private val changed: (note: String?, recolour: Boolean) -> Unit,
) {
    private val drawableImpl = BackdropDrawable(::settleWindow)
    val drawable: Drawable get() = drawableImpl

    /** The picked photo is still being copied. */
    var importing = false
        private set

    private val windowBlack = ColorDrawable(Color.BLACK)
    private val windowClear = ColorDrawable(Color.TRANSPARENT)
    // No backup: a personal photo shouldn't leave the phone, or use up the app's backup quota.
    private val dir get() = activity.noBackupFilesDir
    private val file get() = File(dir, PHOTO_FILE)
    private var photo: BackdropLayer? = null
    private var panelOpen = false
    /** Bumped by every choice, so slower work for an older choice is dropped. */
    private var generation = 0

    /** Call from onCreate. */
    fun start() {
        // Fixed from the start: changing the format later could recreate the surface and flash.
        activity.window.setFormat(PixelFormat.TRANSLUCENT)
        dir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
        if (state.background != BG_PHOTO) file.delete()
        showCurrent(animate = false)
        if (state.background == BG_PHOTO) loadStored()
    }

    fun select(background: String) {
        generation++
        importing = false
        val recolour = (background == BG_BLACK) != (state.background == BG_BLACK)
        state.background = background
        photo = null
        file.delete()
        showCurrent(animate = true)
        changed(null, recolour)
    }

    fun setDim(dim: String) {
        state.dim = dim
        updateScrim(animate = true)
        changed(null, false)
    }

    fun setPanelOpen(open: Boolean) {
        panelOpen = open
        updateScrim(animate = true)
    }

    /** False if this phone has nothing that can pick an image. */
    fun launchPicker(request: Int): Boolean {
        val intents = listOf(
            Intent(MediaStore.ACTION_PICK_IMAGES),
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
        )
        val opened = intents.any { runCatching { activity.startActivityForResult(it, request) }.isSuccess }
        if (!opened) changed("no photo picker on this phone", false)
        return opened
    }

    /** Copies a picked image in, and only then replaces the old copy: a bad pick loses nothing. */
    fun onPicked(uri: Uri?) {
        if (uri == null) return
        val run = ++generation
        importing = true
        changed(null, false)
        thread(name = "crease-photo") {
            var temp: File? = null
            val picked = runCatching {
                val bitmap = decode(ImageDecoder.createSource(activity.contentResolver, uri))
                temp = File.createTempFile(TEMP_PREFIX, null, dir)
                FileOutputStream(temp!!).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("not saved")
                }
                bitmap.prepareToDraw()
                bitmap
            }.getOrNull()
            activity.runOnUiThread { finishImport(run, picked, temp) }
        }
    }

    private fun finishImport(run: Int, bitmap: Bitmap?, temp: File?) {
        if (activity.isDestroyed || run != generation) {
            temp?.delete()
            return
        }
        importing = false
        if (bitmap == null || temp == null || !temp.renameTo(file)) {
            temp?.delete()
            // This pick may have overtaken the load of the current photo.
            if (state.background == BG_PHOTO && photo == null) loadStored()
            return changed("could not read that image", false)
        }
        val recolour = state.background == BG_BLACK
        state.background = BG_PHOTO
        photo = BackdropLayer(bitmap)
        showCurrent(animate = true)
        changed(null, recolour)
    }

    private fun loadStored() {
        val run = ++generation
        val source = file
        thread(name = "crease-photo") {
            val bitmap = runCatching {
                decode(ImageDecoder.createSource(source)).also { it.prepareToDraw() }
            }.getOrNull()
            activity.runOnUiThread {
                if (activity.isDestroyed || run != generation) return@runOnUiThread
                if (bitmap != null) {
                    photo = BackdropLayer(bitmap)
                    showCurrent(animate = true)
                } else {
                    // Missing or damaged: go back to black, for good.
                    select(BG_BLACK)
                }
            }
        }
    }

    /** ImageDecoder applies EXIF rotation and scales while decoding, which keeps memory bounded. */
    private fun decode(source: ImageDecoder.Source): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = decodeSize(info.size.width, info.size.height) ?: throw IOException("bad size")
            decoder.setTargetSize(w, h)
            // Plain memory: can be saved as JPEG, and drawn on any canvas.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /**
     * The window goes see-through before the fade, while the old layer still covers it, and goes
     * back to black after the fade. So the wallpaper is only uncovered gradually, once it's up.
     */
    private fun showCurrent(animate: Boolean) {
        if (state.background == BG_WALLPAPER) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            activity.window.setBackgroundDrawable(windowClear)
        }
        val layer = when (state.background) {
            BG_WALLPAPER -> CLEAR_LAYER
            BG_PHOTO -> photo ?: BLACK_LAYER
            else -> BLACK_LAYER
        }
        drawableImpl.show(layer, animate)
        updateScrim(animate)
    }

    private fun settleWindow() {
        if (state.background == BG_WALLPAPER) return
        activity.window.setBackgroundDrawable(windowBlack)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    }

    private fun updateScrim(animate: Boolean) =
        drawableImpl.scrimTo(scrimAlpha(state.background, state.dim, panelOpen), animate)

    private companion object {
        const val PHOTO_FILE = "background.jpg"
        const val TEMP_PREFIX = "background-import"
        const val JPEG_QUALITY = 88
    }
}
