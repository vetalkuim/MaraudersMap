package com.vetalkuim.maraudersmap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Получатель абсолютных сегментов пути; не зависит от Android, чтобы парсер можно было проверить на JVM. */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(x1: Float, y1: Float, x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
    fun close()
}

/**
 * Разбирает атрибут `d` элемента `<path>` (все команды SVG 1.1, включая дуги)
 * и передаёт абсолютные сегменты в [PathSink]. Ошибка в данных обрывает разбор,
 * как и предписывает спецификация: уже прочитанная часть пути сохраняется.
 */
class SvgPathParser(private val data: String, private val sink: PathSink) {

    private var pos = 0
    private var x = 0f
    private var y = 0f
    private var startX = 0f
    private var startY = 0f
    private var ctrlX = 0f
    private var ctrlY = 0f
    private var prevCmd = ' '

    fun parse() {
        var cmd = ' '
        while (true) {
            skipSeparators()
            if (pos >= data.length) return
            val c = data[pos]
            if (c.isLetter()) {
                cmd = c
                pos++
            } else if (cmd == ' ' || cmd == 'Z' || cmd == 'z') {
                return
            } else if (cmd == 'M') {
                cmd = 'L'
            } else if (cmd == 'm') {
                cmd = 'l'
            }
            if (!command(cmd)) return
            prevCmd = cmd
        }
    }

    private fun command(cmd: Char): Boolean {
        val rel = cmd.isLowerCase()
        val ox = if (rel) x else 0f
        val oy = if (rel) y else 0f
        when (cmd.uppercaseChar()) {
            'M' -> {
                x = ox + (number() ?: return false)
                y = oy + (number() ?: return false)
                startX = x
                startY = y
                sink.moveTo(x, y)
            }
            'L' -> {
                x = ox + (number() ?: return false)
                y = oy + (number() ?: return false)
                sink.lineTo(x, y)
            }
            'H' -> {
                x = ox + (number() ?: return false)
                sink.lineTo(x, y)
            }
            'V' -> {
                y = oy + (number() ?: return false)
                sink.lineTo(x, y)
            }
            'C' -> {
                val x1 = ox + (number() ?: return false)
                val y1 = oy + (number() ?: return false)
                val x2 = ox + (number() ?: return false)
                val y2 = oy + (number() ?: return false)
                val ex = ox + (number() ?: return false)
                val ey = oy + (number() ?: return false)
                cubic(x1, y1, x2, y2, ex, ey)
            }
            'S' -> {
                val reflect = prevCmd.uppercaseChar() == 'C' || prevCmd.uppercaseChar() == 'S'
                val x1 = if (reflect) 2 * x - ctrlX else x
                val y1 = if (reflect) 2 * y - ctrlY else y
                val x2 = ox + (number() ?: return false)
                val y2 = oy + (number() ?: return false)
                val ex = ox + (number() ?: return false)
                val ey = oy + (number() ?: return false)
                cubic(x1, y1, x2, y2, ex, ey)
            }
            'Q' -> {
                val x1 = ox + (number() ?: return false)
                val y1 = oy + (number() ?: return false)
                val ex = ox + (number() ?: return false)
                val ey = oy + (number() ?: return false)
                quad(x1, y1, ex, ey)
            }
            'T' -> {
                val reflect = prevCmd.uppercaseChar() == 'Q' || prevCmd.uppercaseChar() == 'T'
                val x1 = if (reflect) 2 * x - ctrlX else x
                val y1 = if (reflect) 2 * y - ctrlY else y
                val ex = ox + (number() ?: return false)
                val ey = oy + (number() ?: return false)
                quad(x1, y1, ex, ey)
            }
            'A' -> {
                val rx = number() ?: return false
                val ry = number() ?: return false
                val rotation = number() ?: return false
                val largeArc = flag() ?: return false
                val sweep = flag() ?: return false
                val ex = ox + (number() ?: return false)
                val ey = oy + (number() ?: return false)
                arc(rx, ry, rotation, largeArc, sweep, ex, ey)
            }
            'Z' -> {
                sink.close()
                x = startX
                y = startY
            }
            else -> return false
        }
        return true
    }

    private fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, ex: Float, ey: Float) {
        sink.cubicTo(x1, y1, x2, y2, ex, ey)
        ctrlX = x2
        ctrlY = y2
        x = ex
        y = ey
    }

    private fun quad(x1: Float, y1: Float, ex: Float, ey: Float) {
        sink.quadTo(x1, y1, ex, ey)
        ctrlX = x1
        ctrlY = y1
        x = ex
        y = ey
    }

    /** Дуга в параметрах конечных точек → кривые Безье не длиннее 90° (SVG 1.1, приложение F.6). */
    private fun arc(rxIn: Float, ryIn: Float, angleDeg: Float, largeArc: Boolean, sweep: Boolean, ex: Float, ey: Float) {
        val x0 = x.toDouble()
        val y0 = y.toDouble()
        x = ex
        y = ey
        if (x0 == ex.toDouble() && y0 == ey.toDouble()) return
        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) {
            sink.lineTo(ex, ey)
            return
        }
        val phi = angleDeg * PI / 180
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx2 = (x0 - ex) / 2
        val dy2 = (y0 - ey) / 2
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt((num / den).coerceAtLeast(0.0))
        if (largeArc == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + ex) / 2
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + ey) / 2

        val theta1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var delta = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - theta1
        if (sweep && delta < 0) delta += 2 * PI
        if (!sweep && delta > 0) delta -= 2 * PI

        val segments = ceil(abs(delta) / (PI / 2) - 1e-7).toInt().coerceAtLeast(1)
        val step = delta / segments
        val k = 4.0 / 3.0 * tan(step / 4)
        var t = theta1
        for (i in 0 until segments) {
            val cos1 = cos(t)
            val sin1 = sin(t)
            val cos2 = cos(t + step)
            val sin2 = sin(t + step)
            // Точки на единичной окружности, затем масштаб, поворот и перенос в центр.
            val p1x = cos1 - k * sin1
            val p1y = sin1 + k * cos1
            val p2x = cos2 + k * sin2
            val p2y = sin2 - k * cos2
            val last = i == segments - 1
            sink.cubicTo(
                mapX(p1x, p1y, rx, ry, cosPhi, sinPhi, cx), mapY(p1x, p1y, rx, ry, cosPhi, sinPhi, cy),
                mapX(p2x, p2y, rx, ry, cosPhi, sinPhi, cx), mapY(p2x, p2y, rx, ry, cosPhi, sinPhi, cy),
                if (last) ex else mapX(cos2, sin2, rx, ry, cosPhi, sinPhi, cx),
                if (last) ey else mapY(cos2, sin2, rx, ry, cosPhi, sinPhi, cy),
            )
            t += step
        }
    }

    private fun skipSeparators() {
        while (pos < data.length) {
            val c = data[pos]
            if (c == ',' || c.isWhitespace()) pos++ else return
        }
    }

    private fun flag(): Boolean? {
        skipSeparators()
        if (pos >= data.length) return null
        return when (data[pos++]) {
            '0' -> false
            '1' -> true
            else -> null
        }
    }

    /** Читает число SVG: «-1.5e-3», «.5.5» (два числа), «1-2» (два числа). */
    private fun number(): Float? {
        skipSeparators()
        val start = pos
        if (pos < data.length && (data[pos] == '+' || data[pos] == '-')) pos++
        var digits = 0
        while (pos < data.length && data[pos].isDigit()) { pos++; digits++ }
        if (pos < data.length && data[pos] == '.') {
            pos++
            while (pos < data.length && data[pos].isDigit()) { pos++; digits++ }
        }
        if (digits == 0) {
            pos = start
            return null
        }
        if (pos < data.length && (data[pos] == 'e' || data[pos] == 'E')) {
            val expStart = pos
            pos++
            if (pos < data.length && (data[pos] == '+' || data[pos] == '-')) pos++
            if (pos < data.length && data[pos].isDigit()) {
                while (pos < data.length && data[pos].isDigit()) pos++
            } else {
                pos = expStart
            }
        }
        return data.substring(start, pos).toFloatOrNull()
    }

    private companion object {
        fun mapX(ux: Double, uy: Double, rx: Double, ry: Double, cosPhi: Double, sinPhi: Double, cx: Double): Float =
            (cosPhi * rx * ux - sinPhi * ry * uy + cx).toFloat()

        fun mapY(ux: Double, uy: Double, rx: Double, ry: Double, cosPhi: Double, sinPhi: Double, cy: Double): Float =
            (sinPhi * rx * ux + cosPhi * ry * uy + cy).toFloat()
    }
}
