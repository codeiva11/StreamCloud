package com.lagradost.cloudstream3.ui.home

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.drawableToBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Blurred hero backdrop for the home screen (Option 2).
 *
 * Loads the current hero item's image with the project's existing Coil
 * loader, downscales it and applies a local stack blur. The blur is done
 * once per hero change and cached by the caller — never on scroll. No new
 * dependency: the blur is implemented here (works on every supported API
 * level) and no RenderEffect is used.
 */
object HomeBackdropBlur {
    /** Width the source bitmap is downscaled to before blurring. */
    private const val DOWNSCALE_WIDTH = 192

    /** Stack blur radius applied to the downscaled bitmap. */
    private const val BLUR_RADIUS = 10

    /**
     * Shared cache: the blurred bitmap of the current hero item, written by
     * HomeFragment once per hero change and read by every main screen.
     * The bitmap is never re-loaded or re-blurred per screen; screens that
     * open before Home has loaded a hero simply see no backdrop (plain
     * black) until the cache is filled.
     */
    @Volatile
    var cachedUrl: String? = null

    @Volatile
    var cachedBitmap: Bitmap? = null

    /**
     * Shows the cached blurred bitmap in the activity-level backdrop views
     * (activity_backdrop + activity_backdrop_scrim), or hides them when no
     * bitmap is cached yet. Never crashes when the views are absent.
     * Must be called on the main thread.
     */
    fun applyCachedToActivity(activity: Activity?) {
        val backdrop = activity?.findViewById<ImageView>(R.id.activity_backdrop) ?: return
        val scrim = activity.findViewById<View>(R.id.activity_backdrop_scrim) ?: return
        val bitmap = cachedBitmap
        if (bitmap != null) {
            backdrop.setImageBitmap(bitmap)
            backdrop.isVisible = true
            scrim.isVisible = true
        } else {
            backdrop.isGone = true
            scrim.isGone = true
        }
    }

    /** Hides the activity-level backdrop views. Never crashes when absent. */
    fun hideInActivity(activity: Activity?) {
        val backdrop = activity?.findViewById<ImageView>(R.id.activity_backdrop) ?: return
        val scrim = activity.findViewById<View>(R.id.activity_backdrop_scrim) ?: return
        backdrop.isGone = true
        scrim.isGone = true
    }

    /**
     * Loads [url] and returns a blurred copy, or null when the image cannot
     * be loaded. Safe to call from a worker thread; never throws.
     */
    suspend fun loadBlurredBitmap(
        context: Context,
        url: String,
        headers: Map<String, String>?,
    ): Bitmap? {
        return try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                    headerBuilder["User-Agent"] = USER_AGENT
                    headers?.forEach { (key, value) ->
                        headerBuilder[key] = value
                    }
                }.build())
                // Software bitmaps only: a hardware bitmap cannot be read back for blurring.
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            val source = when (val image = (result as? SuccessResult)?.image) {
                is BitmapImage -> image.bitmap
                is DrawableImage -> drawableToBitmap(image.drawable)
                else -> null
            } ?: return null
            blurBitmap(source)
        } catch (t: Throwable) {
            null
        }
    }

    /** Downscales [source] and applies a stack blur. Never throws. */
    fun blurBitmap(source: Bitmap): Bitmap? {
        return try {
            if (source.width <= 0 || source.height <= 0) return null
            val targetWidth = min(DOWNSCALE_WIDTH, source.width)
            val targetHeight = max(1, (source.height * targetWidth) / source.width)
            val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
            // createScaledBitmap may return the source itself when no scaling
            // is needed; the blur needs a mutable copy in that case.
            val mutable = if (scaled === source) {
                source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            } else {
                scaled
            }
            stackBlur(mutable, BLUR_RADIUS)
            mutable
        } catch (t: Throwable) {
            null
        }
    }

    /** Stack blur, in place on [bitmap]. The bitmap must be mutable. */
    private fun stackBlur(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        stackBlurPixels(pix, w, h, radius)
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }

    /**
     * Stack blur (Quasimondo) on raw pixels. Two passes (horizontal, then
     * vertical); each pixel is a weighted average of its neighbours.
     */
    private fun stackBlurPixels(pix: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1) return
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1
        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))
        val divsum = (div + 1) shr 1
        val divsum2 = divsum * divsum
        val dv = IntArray(256 * divsum2) { it / divsum2 }
        yw = 0
        yi = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius
            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi += w
                y++
            }
            x++
        }
    }
}
