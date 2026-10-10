package com.vetalkuim.maraudersmap

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Пылинки над пергаментом (слой 0, вариант 2): медленно всплывают вверх с лёгким дрейфом вбок
 * и мерцают. Ушедшие за верхний край появляются снизу, по бокам — заворачиваются.
 * Скорости и размеры — в dp, поэтому на любом экране одинаково. Только главный поток.
 */
class DustEffect(private val dp: Float, private val count: Int = COUNT) {

    private class Mote(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        val radius: Float,
        var phase: Float,
        val speed: Float,
    )

    private val random = Random(42)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR }
    private val motes = ArrayList<Mote>(count)
    private var width = 0f
    private var height = 0f

    /**
     * Меняет размер. Впервые пылинки рассыпаются по экрану, после поворота —
     * пересчитываются пропорционально, как путники.
     */
    fun resize(newWidth: Float, newHeight: Float) {
        if (newWidth <= 0f || newHeight <= 0f || (newWidth == width && newHeight == height)) return
        if (motes.isNotEmpty() && width > 0f && height > 0f) {
            val sx = newWidth / width
            val sy = newHeight / height
            for (m in motes) {
                m.x *= sx; m.y *= sy
            }
        } else {
            repeat(count) {
                motes += Mote(
                    x = random.nextFloat() * newWidth,
                    y = random.nextFloat() * newHeight,
                    vx = (random.nextFloat() - 0.5f) * 2f * DRIFT_DP * dp,
                    vy = -(RISE_MIN_DP + random.nextFloat() * (RISE_MAX_DP - RISE_MIN_DP)) * dp,
                    radius = (RADIUS_MIN_DP + random.nextFloat() * (RADIUS_MAX_DP - RADIUS_MIN_DP)) * dp,
                    phase = random.nextFloat() * TWO_PI,
                    speed = 0.5f + random.nextFloat(),
                )
            }
        }
        width = newWidth
        height = newHeight
    }

    /** Сдвигает пылинки на [dt] секунд. */
    fun update(dt: Float) {
        val margin = MARGIN_DP * dp
        for (m in motes) {
            m.x += m.vx * dt
            m.y += m.vy * dt
            m.phase = (m.phase + m.speed * dt) % TWO_PI
            if (m.y < -margin) m.y = height + margin
            if (m.x < -margin) m.x = width + margin else if (m.x > width + margin) m.x = -margin
        }
    }

    fun draw(canvas: Canvas) {
        for (m in motes) {
            paint.alpha = (ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * (0.5f + 0.5f * sin(m.phase))).toInt()
            canvas.drawCircle(m.x, m.y, m.radius, paint)
        }
    }

    private companion object {
        const val COUNT = 40
        const val COLOR = 0xFF5A4326.toInt()
        const val RADIUS_MIN_DP = 0.8f
        const val RADIUS_MAX_DP = 2.6f

        /** Всплывают на 4–14 dp/с, дрейфуют вбок до ±4 dp/с. */
        const val RISE_MIN_DP = 4f
        const val RISE_MAX_DP = 14f
        const val DRIFT_DP = 4f

        /** Мерцание: непрозрачность от 40 до 90 из 255. */
        const val ALPHA_MIN = 40
        const val ALPHA_MAX = 90
        const val MARGIN_DP = 8f
        const val TWO_PI = (2 * PI).toFloat()
    }
}
