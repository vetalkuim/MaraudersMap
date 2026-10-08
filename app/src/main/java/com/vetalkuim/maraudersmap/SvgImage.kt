package com.vetalkuim.maraudersmap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.tan

/**
 * Минимальный SVG без сторонних библиотек: фигуры (`path`, `rect`, `circle`, `ellipse`,
 * `line`, `polyline`, `polygon`), группы, `transform`, заливка и обводка сплошным цветом.
 * Градиенты, текст, `use`, клипы и фильтры пропускаются — для рисованной карты этого хватает.
 */
class SvgImage private constructor(
    private val viewBox: RectF,
    private val shapes: List<Shape>,
) : MapImage {

    override val width: Float get() = viewBox.width()
    override val height: Float get() = viewBox.height()

    private class Shape(
        val path: Path,
        val matrix: Matrix?,
        val fill: Paint?,
        val stroke: Paint?,
    )

    override fun draw(canvas: Canvas, dst: RectF) {
        val save = canvas.save()
        canvas.translate(dst.left, dst.top)
        canvas.scale(dst.width() / viewBox.width(), dst.height() / viewBox.height())
        canvas.translate(-viewBox.left, -viewBox.top)
        for (shape in shapes) {
            val shapeSave = canvas.save()
            shape.matrix?.let(canvas::concat)
            shape.fill?.let { canvas.drawPath(shape.path, it) }
            shape.stroke?.let { canvas.drawPath(shape.path, it) }
            canvas.restoreToCount(shapeSave)
        }
        canvas.restoreToCount(save)
    }

    /** Наследуемые свойства оформления. */
    private data class Style(
        val fill: Int? = Color.BLACK,
        val stroke: Int? = null,
        val strokeWidth: Float = 1f,
        val fillOpacity: Float = 1f,
        val strokeOpacity: Float = 1f,
        val opacity: Float = 1f,
        val evenOdd: Boolean = false,
        val cap: Paint.Cap = Paint.Cap.BUTT,
        val join: Paint.Join = Paint.Join.MITER,
        val color: Int = Color.BLACK,
    )

    private class Frame(val style: Style, val matrix: Matrix?)

    companion object {
        private val SKIPPED = setOf(
            "defs", "clipPath", "mask", "pattern", "symbol", "marker", "linearGradient",
            "radialGradient", "filter", "style", "script", "text", "title", "desc", "metadata",
            "foreignObject", "switch",
        )

        /** Разбирает SVG; бросает исключение, если в потоке нет корректного `<svg>`. */
        fun parse(input: InputStream): SvgImage {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var viewBox: RectF? = null
            val shapes = mutableListOf<Shape>()
            val stack = ArrayDeque<Frame>()
            var skipDepth = 0

            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> {
                        if (skipDepth > 0) {
                            skipDepth++
                            continue
                        }
                        val name = parser.name.substringAfter(':')
                        val attrs = attributes(parser)
                        val parent = stack.lastOrNull()
                        if (parent == null && name != "svg") throw IllegalArgumentException("Not an SVG document")
                        if (name in SKIPPED || attrs["display"] == "none") {
                            skipDepth = 1
                            continue
                        }
                        if (parent == null) viewBox = rootViewBox(attrs)

                        val style = applyStyle(parent?.style ?: Style(), attrs)
                        val local = attrs["transform"]?.let(::parseTransform)
                        val matrix = when {
                            local == null -> parent?.matrix
                            parent?.matrix == null -> local
                            else -> Matrix(parent.matrix).apply { preConcat(local) }
                        }
                        stack.addLast(Frame(style, matrix))

                        if (attrs["visibility"] != "hidden") {
                            buildPath(name, attrs, style.evenOdd)?.let { path ->
                                shapes += Shape(path, matrix, fillPaint(style), strokePaint(style))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (skipDepth > 0) skipDepth-- else stack.removeLastOrNull()
                    }
                }
            }
            val box = viewBox ?: throw IllegalArgumentException("Not an SVG document")
            return SvgImage(box, shapes)
        }

        private fun attributes(parser: XmlPullParser): Map<String, String> {
            val map = HashMap<String, String>()
            for (i in 0 until parser.attributeCount) {
                map[parser.getAttributeName(i).substringAfter(':')] = parser.getAttributeValue(i).trim()
            }
            // Свойства из style="" важнее одноимённых атрибутов.
            map["style"]?.split(';')?.forEach { decl ->
                val key = decl.substringBefore(':', "").trim()
                val value = decl.substringAfter(':', "").trim()
                if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
            }
            return map
        }

        private fun rootViewBox(attrs: Map<String, String>): RectF {
            attrs["viewBox"]?.let { value ->
                val n = numbers(value)
                if (n.size == 4 && n[2] > 0f && n[3] > 0f) return RectF(n[0], n[1], n[0] + n[2], n[1] + n[3])
            }
            val w = length(attrs["width"]) ?: 0f
            val h = length(attrs["height"]) ?: 0f
            if (w > 0f && h > 0f) return RectF(0f, 0f, w, h)
            throw IllegalArgumentException("SVG has no size")
        }

        private fun applyStyle(parent: Style, a: Map<String, String>): Style {
            val color = a["color"]?.let(::parseColor) ?: parent.color
            return Style(
                fill = paint(a["fill"], parent.fill, color),
                stroke = paint(a["stroke"], parent.stroke, color),
                strokeWidth = length(a["stroke-width"]) ?: parent.strokeWidth,
                fillOpacity = a["fill-opacity"]?.toFloatOrNull() ?: parent.fillOpacity,
                strokeOpacity = a["stroke-opacity"]?.toFloatOrNull() ?: parent.strokeOpacity,
                // Прозрачность группы приближённо переносится на каждую фигуру.
                opacity = parent.opacity * (a["opacity"]?.toFloatOrNull() ?: 1f),
                evenOdd = a["fill-rule"]?.let { it == "evenodd" } ?: parent.evenOdd,
                cap = when (a["stroke-linecap"]) {
                    "round" -> Paint.Cap.ROUND
                    "square" -> Paint.Cap.SQUARE
                    "butt" -> Paint.Cap.BUTT
                    else -> parent.cap
                },
                join = when (a["stroke-linejoin"]) {
                    "round" -> Paint.Join.ROUND
                    "bevel" -> Paint.Join.BEVEL
                    "miter" -> Paint.Join.MITER
                    else -> parent.join
                },
                color = color,
            )
        }

        private fun paint(value: String?, inherited: Int?, current: Int): Int? = when {
            value == null || value == "inherit" -> inherited
            value == "none" || value.startsWith("url(") -> null
            value == "currentColor" -> current
            else -> parseColor(value) ?: inherited
        }

        private fun fillPaint(s: Style): Paint? {
            val color = s.fill ?: return null
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = withAlpha(color, s.fillOpacity * s.opacity)
            }
        }

        private fun strokePaint(s: Style): Paint? {
            val color = s.stroke ?: return null
            if (s.strokeWidth <= 0f) return null
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.color = withAlpha(color, s.strokeOpacity * s.opacity)
                strokeWidth = s.strokeWidth
                strokeCap = s.cap
                strokeJoin = s.join
            }
        }

        private fun withAlpha(color: Int, opacity: Float): Int =
            Color.argb((Color.alpha(color) * opacity.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        private fun buildPath(name: String, a: Map<String, String>, evenOdd: Boolean): Path? {
            val path = Path()
            when (name) {
                "path" -> SvgPathParser(a["d"] ?: return null, AndroidPathSink(path)).parse()
                "rect" -> {
                    val w = length(a["width"]) ?: return null
                    val h = length(a["height"]) ?: return null
                    if (w <= 0f || h <= 0f) return null
                    val x = length(a["x"]) ?: 0f
                    val y = length(a["y"]) ?: 0f
                    val rx = length(a["rx"]) ?: length(a["ry"]) ?: 0f
                    val ry = length(a["ry"]) ?: rx
                    val rect = RectF(x, y, x + w, y + h)
                    if (rx > 0f || ry > 0f) {
                        path.addRoundRect(rect, rx.coerceAtMost(w / 2), ry.coerceAtMost(h / 2), Path.Direction.CW)
                    } else {
                        path.addRect(rect, Path.Direction.CW)
                    }
                }
                "circle" -> {
                    val r = length(a["r"]) ?: return null
                    if (r <= 0f) return null
                    path.addCircle(length(a["cx"]) ?: 0f, length(a["cy"]) ?: 0f, r, Path.Direction.CW)
                }
                "ellipse" -> {
                    val rx = length(a["rx"]) ?: return null
                    val ry = length(a["ry"]) ?: return null
                    if (rx <= 0f || ry <= 0f) return null
                    val cx = length(a["cx"]) ?: 0f
                    val cy = length(a["cy"]) ?: 0f
                    path.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
                }
                "line" -> {
                    path.moveTo(length(a["x1"]) ?: 0f, length(a["y1"]) ?: 0f)
                    path.lineTo(length(a["x2"]) ?: 0f, length(a["y2"]) ?: 0f)
                }
                "polyline", "polygon" -> {
                    val n = numbers(a["points"] ?: return null)
                    if (n.size < 4) return null
                    path.moveTo(n[0], n[1])
                    var i = 2
                    while (i + 1 < n.size) {
                        path.lineTo(n[i], n[i + 1])
                        i += 2
                    }
                    if (name == "polygon") path.close()
                }
                else -> return null
            }
            path.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            return path
        }

        private fun parseTransform(value: String): Matrix {
            val result = Matrix()
            val regex = Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""")
            for (match in regex.findAll(value)) {
                val n = numbers(match.groupValues[2])
                val m = Matrix()
                when (match.groupValues[1]) {
                    "matrix" -> if (n.size == 6) m.setValues(floatArrayOf(n[0], n[2], n[4], n[1], n[3], n[5], 0f, 0f, 1f))
                    "translate" -> m.setTranslate(n.getOrElse(0) { 0f }, n.getOrElse(1) { 0f })
                    "scale" -> {
                        val sx = n.getOrElse(0) { 1f }
                        m.setScale(sx, n.getOrElse(1) { sx })
                    }
                    "rotate" -> if (n.size >= 3) m.setRotate(n[0], n[1], n[2]) else m.setRotate(n.getOrElse(0) { 0f })
                    "skewX" -> m.setSkew(tan(Math.toRadians(n.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                    "skewY" -> m.setSkew(0f, tan(Math.toRadians(n.getOrElse(0) { 0f }.toDouble())).toFloat())
                }
                result.preConcat(m)
            }
            return result
        }

        private fun numbers(value: String): FloatArray =
            Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(value)
                .mapNotNull { it.value.toFloatOrNull() }
                .toList()
                .toFloatArray()

        /** Длина в пользовательских единицах; проценты не поддерживаются. */
        private fun length(value: String?): Float? {
            if (value == null || value.endsWith("%")) return null
            val match = Regex("""^[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").find(value.trim()) ?: return null
            return match.value.toFloatOrNull()
        }

        private fun parseColor(value: String): Int? {
            val v = value.trim()
            if (v.startsWith("#") && v.length == 4) {
                val r = v[1]
                val g = v[2]
                val b = v[3]
                return parseColor("#$r$r$g$g$b$b")
            }
            if (v.startsWith("rgb(")) {
                val parts = v.removePrefix("rgb(").removeSuffix(")").split(',')
                if (parts.size != 3) return null
                val c = parts.map { part ->
                    val p = part.trim()
                    val n = (if (p.endsWith("%")) p.dropLast(1).toFloatOrNull()?.times(2.55f) else p.toFloatOrNull()) ?: return null
                    n.toInt().coerceIn(0, 255)
                }
                return Color.rgb(c[0], c[1], c[2])
            }
            return try {
                Color.parseColor(v)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

internal class AndroidPathSink(private val path: Path) : PathSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = path.quadTo(x1, y1, x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        path.cubicTo(x1, y1, x2, y2, x, y)
    override fun close() = path.close()
}
