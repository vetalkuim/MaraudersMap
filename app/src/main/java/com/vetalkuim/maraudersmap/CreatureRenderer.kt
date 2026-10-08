package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Рисует слой 2 — путников с их следами и подписями — поверх карты. */
class CreatureRenderer(context: Context) {

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

    fun draw(canvas: Canvas, creatures: MapCreatures) {
        drawFootprints(canvas, creatures)
        drawLabels(canvas, creatures)
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
