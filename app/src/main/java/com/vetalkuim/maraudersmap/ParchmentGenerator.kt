package com.vetalkuim.maraudersmap

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Динамический пергамент (слой 0, вариант 2): база `#E6D5B0`, мягкие пятна по шуму,
 * мелкое зерно бумаги и виньетка к краям. Долго (~100–300 мс) — вызывается не в главном потоке.
 * Всё детерминировано ([SEED]) — лист всегда одинаковый.
 */
object ParchmentGenerator {

    const val BASE_COLOR = 0xFFE6D5B0.toInt()

    private const val BASE_R = 0xE6
    private const val BASE_G = 0xD5
    private const val BASE_B = 0xB0

    private const val STAIN_R = 150
    private const val STAIN_G = 110
    private const val STAIN_B = 60

    private const val LIGHT_R = 250
    private const val LIGHT_G = 240
    private const val LIGHT_B = 215

    /** Пятна плавные, поэтому шум считается в 1/[DOWNSCALE] разрешения и растягивается с фильтрацией. */
    private const val DOWNSCALE = 4
    private const val SEED = 1337

    /** Чернила зерна и виньетки. */
    private const val INK_RGB = 0x3B2A14
    private const val GRAIN_LIGHT_RGB = 0xFFF8E8
    private const val GRAIN_SIZE = 128
    private const val GRAIN_ALPHA = 22f

    fun generate(width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val stains = stainBitmap(width, height)
        canvas.drawBitmap(stains, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        stains.recycle()

        drawGrain(canvas)
        drawVignette(canvas, width, height)
        return result
    }

    /**
     * Крупные пятна (`blotch`): где шум выше 0,55 — бумага темнеет к пятну до 35 %,
     * где ниже 0,4 — светлеет; мелкая рябь (`mottle`) даёт ±6 % неровности тона.
     */
    private fun stainBitmap(width: Int, height: Int): Bitmap {
        val w = (width + DOWNSCALE - 1) / DOWNSCALE
        val h = (height + DOWNSCALE - 1) / DOWNSCALE
        // Координаты — в долях короткой стороны, чтобы узор был одного масштаба на любом экране.
        val unit = min(w, h).toFloat()
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val v = y / unit
            for (x in 0 until w) {
                val u = x / unit
                val blotch = fbm(u * 2.5f, v * 2.5f, SEED, 5)
                val mottle = fbm(u * 9f, v * 9f, SEED + 7, 3)

                val dark = (smoothstep(0.55f, 0.8f, blotch) * 0.35f + (mottle - 0.5f) * 0.12f)
                    .coerceIn(0f, 1f)
                val light = smoothstep(0.4f, 0.2f, blotch) * 0.35f

                var r = lerp(BASE_R.toFloat(), STAIN_R.toFloat(), dark)
                var g = lerp(BASE_G.toFloat(), STAIN_G.toFloat(), dark)
                var b = lerp(BASE_B.toFloat(), STAIN_B.toFloat(), dark)
                r = lerp(r, LIGHT_R.toFloat(), light)
                g = lerp(g, LIGHT_G.toFloat(), light)
                b = lerp(b, LIGHT_B.toFloat(), light)

                pixels[y * w + x] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Зерно бумаги: тайл случайных тёмных и светлых точек, замощённый по всему листу. */
    private fun drawGrain(canvas: Canvas) {
        val random = Random(SEED)
        val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) {
            val n = random.nextFloat() - 0.5f
            val alpha = (abs(n) * 2f * GRAIN_ALPHA).toInt()
            val rgb = if (n < 0) INK_RGB else GRAIN_LIGHT_RGB
            (alpha shl 24) or rgb
        }
        val tile = Bitmap.createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawPaint(paint)
        tile.recycle()
    }

    /** Эллиптическая виньетка: прозрачно до 45 % радиуса, 25 % к 78 %, 60 % в углах. */
    private fun drawVignette(canvas: Canvas, width: Int, height: Int) {
        val gradient = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(INK_RGB, INK_RGB, (0x40 shl 24) or INK_RGB, (0x99 shl 24) or INK_RGB),
            floatArrayOf(0f, 0.45f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        val sqrt2 = sqrt(2f)
        gradient.setLocalMatrix(Matrix().apply {
            setScale(width / 2f * sqrt2, height / 2f * sqrt2)
            postTranslate(width / 2f, height / 2f)
        })
        canvas.drawPaint(Paint().apply { shader = gradient })
    }

    private fun fbm(x: Float, y: Float, seed: Int, octaves: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var norm = 0f
        var freq = 1f
        for (i in 0 until octaves) {
            sum += valueNoise(x * freq, y * freq, seed + i * 101) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2f
        }
        return sum / norm
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val fx = floor(x)
        val fy = floor(y)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val tx = fade(x - fx)
        val ty = fade(y - fy)
        val a = hash(x0, y0, seed)
        val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed)
        val d = hash(x0 + 1, y0 + 1, seed)
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun fade(t: Float) = t * t * (3f - 2f * t)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float =
        fade(((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f))
}
