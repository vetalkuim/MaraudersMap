package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Рисует слой 2 — путников с их следами и подписями и дементоров над ними — поверх карты. */
class CreatureRenderer(context: Context) {

    private val sprites = DementorSprites(context)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        alpha = DEMENTOR_ALPHA
    }
    private val facePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val onScreen = HashSet<DementorVariant>()

    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textAlign = Paint.Align.CENTER
        typeface = try {
            context.resources.getFont(R.font.bad_script)
        } catch (e: Exception) {
            Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
    }

    /** Правая ступня из `footprint_right.svg`: носок вверху, центр — в начале координат. */
    private val rightFoot = Path().apply {
        SvgPathParser(FOOT_SOLE, AndroidPathSink(this)).parse()
        SvgPathParser(FOOT_HEEL, AndroidPathSink(this)).parse()
        offset(-FOOT_CENTER_X, -FOOT_CENTER_Y)
    }

    /** [backgroundAt] — цвет фона в точке экрана: лицо дементора — прорезь, сквозь которую виден фон. */
    fun draw(canvas: Canvas, creatures: MapCreatures, backgroundAt: (Float, Float) -> Int) {
        drawFootprints(canvas, creatures)
        drawLabels(canvas, creatures)
        onScreen.clear()
        for (d in creatures.dementors) {
            onScreen += d.variant
            drawDementor(canvas, creatures, d, backgroundAt)
        }
        sprites.retain(onScreen)
    }

    fun release() = sprites.release()

    /**
     * Картинка рисуется горизонтальными полосами: низ балахона колышется волной, капюшон
     * иногда чуть сдвигается вбок — голова поворачивается; вся фигура слегка покачивается.
     */
    private fun drawDementor(canvas: Canvas, creatures: MapCreatures, d: Dementor, backgroundAt: (Float, Float) -> Int) {
        val h = creatures.dementorHeight(d.scale)
        val w = h * d.variant.aspect
        val sprite = sprites.get(d.variant, creatures.dementorHeight(1f)) ?: return
        val bitmap = sprite.bitmap
        val t = creatures.time
        val head = headTurn(t, d.seed) * HEAD_SHIFT * w

        val save = canvas.save()
        canvas.translate(d.x, d.y)
        canvas.rotate(SWAY_DEGREES * sin(t * 0.9f + d.seed), 0f, -h / 2)
        for (i in 0 until STRIPS) {
            val f0 = i.toFloat() / STRIPS
            val f1 = (i + 1).toFloat() / STRIPS
            val shift = stripShift((f0 + f1) / 2, t, d.seed) * w + head * headWeight((f0 + f1) / 2)
            src.set(0, (f0 * bitmap.height).toInt(), bitmap.width, (f1 * bitmap.height).toInt())
            // Полосы чуть перекрываются, чтобы между ними не просвечивали швы.
            dst.set(-w / 2 + shift, -h / 2 + f0 * h, w / 2 + shift, -h / 2 + f1 * h + 1f)
            canvas.drawBitmap(bitmap, src, dst, spritePaint)
        }

        val sx = w / bitmap.width
        val sy = h / bitmap.height
        val faceLeft = -w / 2 + sprite.faceLeft * sx + head
        val faceTop = -h / 2 + sprite.faceTop * sy
        dst.set(faceLeft, faceTop, faceLeft + sprite.face.width * sx, faceTop + sprite.face.height * sy)
        facePaint.color = shade(backgroundAt(d.x + dst.centerX(), d.y + dst.centerY()))
        canvas.drawBitmap(sprite.face, null, dst, facePaint)
        canvas.restoreToCount(save)
    }

    private fun drawFootprints(canvas: Canvas, creatures: MapCreatures) {
        val scale = FOOT_LENGTH_DP * creatures.dp / FOOT_SVG_LENGTH
        for (foot in creatures.footprints) {
            val opacity = MapCreatures.footprintOpacity(creatures.time - foot.born)
            if (opacity <= 0f) continue
            footPaint.alpha = (MapCreatures.FOOT_MAX_ALPHA * opacity).toInt()
            val save = canvas.save()
            canvas.translate(foot.x, foot.y)
            // Носок в SVG смотрит вверх (−y), поэтому к направлению движения добавляется 90°.
            canvas.rotate(Math.toDegrees(foot.angle.toDouble()).toFloat() + 90f)
            // Левая ступня — зеркальная копия правой.
            canvas.scale(if (foot.left) -scale else scale, scale)
            canvas.drawPath(rightFoot, footPaint)
            canvas.restoreToCount(save)
        }
    }

    private fun drawLabels(canvas: Canvas, creatures: MapCreatures) {
        labelPaint.textSize = LABEL_SIZE_DP * creatures.dp
        for (t in creatures.travelers) {
            val name = t.name?.takeIf { it.isNotBlank() } ?: continue
            if (t.labelX.isNaN()) continue
            canvas.drawText(name, t.labelX, t.labelY, labelPaint)
        }
    }

    private companion object {
        const val DEMENTOR_ALPHA = 240
        const val STRIPS = 24
        const val SWAY_DEGREES = 2.5f

        /** Колышется нижняя часть балахона — от этой доли высоты и ниже. */
        const val HEM_START = 0.4f
        const val HEM_AMPLITUDE = 0.045f

        /** Капюшон с головой — верхняя доля картинки. */
        const val HEAD_END = 0.24f
        const val HEAD_SHIFT = 0.035f

        /** Лицо — затемнённый на 30% фон под ним. */
        const val FACE_SHADE = 0.7f

        /** Сдвиг полосы на высоте [f] (0 — верх, 1 — низ) в долях ширины. */
        fun stripShift(f: Float, t: Float, seed: Float): Float {
            if (f <= HEM_START) return 0f
            val depth = ((f - HEM_START) / (1f - HEM_START)).pow(1.5f)
            return HEM_AMPLITUDE * depth * sin(t * 1.7f - f * 7f + seed)
        }

        /** Вес сдвига головы: капюшон целиком, ниже плеч плавно сходит на нет. */
        fun headWeight(f: Float): Float = when {
            f <= HEAD_END - 0.08f -> 1f
            f >= HEAD_END -> 0f
            else -> (HEAD_END - f) / 0.08f
        }

        /** Поворот головы −1..1: почти всё время прямо, иногда плавно в сторону и обратно. */
        fun headTurn(t: Float, seed: Float): Float {
            val wave = sin(t * 0.31f + seed * 5f)
            val turn = ((abs(wave) - 0.6f) / 0.4f).coerceIn(0f, 1f)
            return if (wave < 0f) -turn else turn
        }

        fun shade(color: Int): Int = Color.rgb(
            (Color.red(color) * FACE_SHADE).toInt(),
            (Color.green(color) * FACE_SHADE).toInt(),
            (Color.blue(color) * FACE_SHADE).toInt(),
        )

        /** Сепия, общая для всех путников. */
        const val INK = 0xFF3B2614.toInt()
        const val LABEL_SIZE_DP = 20f

        /** Длина следа на экране и длина ступни в единицах SVG (от носка до пятки). */
        const val FOOT_LENGTH_DP = 18.5f
        const val FOOT_SVG_LENGTH = 194f
        const val FOOT_CENTER_X = 50f
        const val FOOT_CENTER_Y = 120f

        const val FOOT_SOLE = "M42 25C22 27 10 53 12 85C14 117 23 135 27 151C30 163 61 163 64 151" +
            "C68 135 88 119 88 85C88 49 68 23 42 25Z"
        const val FOOT_HEEL = "M64 177C64 163 27 163 27 177C27 187 26 199 32 207" +
            "C38 217 54 217 60 207C66 199 64 187 64 177Z"
    }
}
