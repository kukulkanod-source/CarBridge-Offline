package com.example.carbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Shared, untinted miniature artwork for dock controls and shade shortcuts. */
internal object HuPanelIcons {
    private data class Artwork(val bitmap: Bitmap, val source: Rect)
    private var artwork: Map<String, Artwork>? = null

    @Synchronized
    fun drawable(context: Context, kind: String): Drawable {
        if(kind=="favorites") return HuFavoriteIcon()
        val icons = artwork ?: load(context).also { artwork = it }
        val icon = icons[kind] ?: return HuStatusIcon(kind)
        return IconDrawable(icon)
    }

    private fun load(context: Context): Map<String, Artwork> {
        val result = mutableMapOf<String, Artwork>()
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val atlas = BitmapFactory.decodeResource(context.resources, R.drawable.hu_realistic_atlas, options)
        if (atlas != null) {
            val names = arrayOf("back", "home", "apps", "music", "phone_off", "phone_on",
                "keyboard", "display", "wifi", "bluetooth", "cellular", "settings")
            val rows = arrayOf(128 to 416, 448 to 736, 768 to 1024)
            names.forEachIndexed { index, name ->
                val row = index / 4
                val col = index % 4
                val edges = if (row == 1) intArrayOf(0, 362, 690, 1086, 1448)
                    else intArrayOf(0, 362, 724, 1086, 1448)
                val region = Rect(edges[col] * atlas.width / 1448,
                    rows[row].first * atlas.height / 1086,
                    edges[col + 1] * atlas.width / 1448,
                    rows[row].second * atlas.height / 1086)
                result[name] = Artwork(atlas, visibleBounds(atlas, region))
            }
        }
        options.inSampleSize = 4
        BitmapFactory.decodeResource(context.resources, R.drawable.hu_realistic_location, options)?.let {
            result["location"] = Artwork(it, visibleBounds(it, Rect(0, 0, it.width, it.height)))
        }
        return result
    }

    /** Ignore faint studio shadows when sizing the glyph, without altering the source PNG. */
    private fun visibleBounds(bitmap: Bitmap, region: Rect): Rect {
        val pixels = IntArray(region.width() * region.height())
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        var left = region.right
        var top = region.bottom
        var right = region.left
        var bottom = region.top
        pixels.forEachIndexed { index, pixel ->
            if ((pixel ushr 24) >= 192) {
                val x = region.left + index % region.width()
                val y = region.top + index / region.width()
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            }
        }
        return if (right > left && bottom > top) Rect(maxOf(region.left, left - 2),
            maxOf(region.top, top - 2), minOf(region.right, right + 2), minOf(region.bottom, bottom + 2))
        else Rect(region)
    }

    private class IconDrawable(private val icon: Artwork) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val destination = RectF()
        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val scale = minOf(bounds.width().toFloat() / icon.source.width(),
                bounds.height().toFloat() / icon.source.height())
            val width = icon.source.width() * scale
            val height = icon.source.height() * scale
            val x = bounds.exactCenterX() - width / 2
            val y = bounds.exactCenterY() - height / 2
            destination.set(x, y, x + width, y + height)
            canvas.drawBitmap(icon.bitmap, icon.source, destination, paint)
        }
        override fun getIntrinsicWidth() = 96
        override fun getIntrinsicHeight() = 96
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
