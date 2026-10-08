package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.roundToInt

/**
 * Картинки дементоров. В памяти держатся только те, чьи дементоры сейчас на экране:
 * [retain] освобождает остальные. Картинка декодируется сразу уменьшенной под размер на экране.
 */
class DementorSprites(private val context: Context) {

    /** Картинка и маска лица — серого овала в капюшоне, который перекрашивается под фон. */
    class Sprite(val bitmap: Bitmap, val face: Bitmap, val faceLeft: Int, val faceTop: Int)

    private val cache = HashMap<DementorVariant, Sprite>()

    fun get(variant: DementorVariant, heightPx: Float): Sprite? = cache[variant] ?: load(variant, heightPx)?.also {
        cache[variant] = it
    }

    /** Освобождает картинки вариантов, которых больше нет на экране. */
    fun retain(variants: Set<DementorVariant>) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in variants) {
                entry.value.recycle()
                iterator.remove()
            }
        }
    }

    fun release() = retain(emptySet())

    private fun Sprite.recycle() {
        bitmap.recycle()
        face.recycle()
    }

    private fun load(variant: DementorVariant, heightPx: Float): Sprite? {
        val res = drawableFor(variant)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, res, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= heightPx) sample *= 2
        val bitmap = BitmapFactory.decodeResource(context.resources, res, BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sample
        }) ?: return null
        return faceMask(variant, bitmap)
    }

    /**
     * Лицо на рисунке — тёмно-серое (около 38 из 255) пятно внутри чёрного капюшона.
     * Маска берёт его пиксели в чуть расширенном овале лица, с мягким краем.
     */
    private fun faceMask(variant: DementorVariant, bitmap: Bitmap): Sprite {
        val w = bitmap.width
        val h = bitmap.height
        val rx = variant.faceRx * w * FACE_MARGIN
        val ry = variant.faceRy * h * FACE_MARGIN
        val left = (variant.faceX * w - rx).roundToInt().coerceIn(0, w - 1)
        val top = (variant.faceY * h - ry).roundToInt().coerceIn(0, h - 1)
        val right = (variant.faceX * w + rx).roundToInt().coerceIn(left + 1, w)
        val bottom = (variant.faceY * h + ry).roundToInt().coerceIn(top + 1, h)
        val mw = right - left
        val mh = bottom - top
        val pixels = IntArray(mw * mh)
        bitmap.getPixels(pixels, 0, mw, left, top, mw, mh)
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24
            val gray = p and 0xFF
            val weight = when {
                alpha < 250 -> 0f
                gray < FACE_GRAY_MIN -> ((gray - FACE_GRAY_MIN + FACE_SOFT) / FACE_SOFT).coerceIn(0f, 1f)
                gray > FACE_GRAY_MAX -> ((FACE_GRAY_MAX + FACE_SOFT - gray) / FACE_SOFT).coerceIn(0f, 1f)
                else -> 1f
            }
            pixels[i] = (weight * 255).roundToInt() shl 24
        }
        val face = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        face.setPixels(pixels, 0, mw, 0, 0, mw, mh)
        return Sprite(bitmap, face, left, top)
    }

    private companion object {
        const val FACE_MARGIN = 1.35f
        const val FACE_GRAY_MIN = 28
        const val FACE_GRAY_MAX = 60
        const val FACE_SOFT = 14f

        fun drawableFor(variant: DementorVariant): Int = when (variant.number) {
            1 -> R.drawable.dementor_1
            2 -> R.drawable.dementor_2
            3 -> R.drawable.dementor_3
            4 -> R.drawable.dementor_4
            5 -> R.drawable.dementor_5
            6 -> R.drawable.dementor_6
            else -> R.drawable.dementor_7
        }
    }
}
