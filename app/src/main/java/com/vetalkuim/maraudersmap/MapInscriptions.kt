package com.vetalkuim.maraudersmap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/** Где стоит надпись на экране. */
enum class InscriptionPosition { TOP, CENTER, BOTTOM }

/**
 * Надписи чернилами на пергаменте — два блока, каждый вверху, в центре или внизу экрана,
 * в 1, 2 или 3 строки.
 *
 * Посвящение: «Messrs» и «are proud to present» — почерком Marck Script, имена Мародёров —
 * Cinzel Decorative. Название «The Marauder's Map» — шрифтом Harry Potter.
 * Если оба блока в одном месте, название всегда над посвящением.
 *
 * Размеры не заданы в dp: строки подгоняются под ширину экрана, но не крупнее потолка.
 * Надписи статичны — раскладка пересчитывается только при смене размера экрана или настроек.
 */
class MapInscriptions(
    private val dp: Float,
    scriptTypeface: Typeface,
    namesTypeface: Typeface,
    titleTypeface: Typeface,
) {

    /** Кусок строки одним шрифтом; [dx] — его левый край от левого края строки. */
    private class Segment(val text: String, val paint: Paint, var dx: Float = 0f)

    /** Строка по центру экрана; [baseline] — от верха блока. */
    private class Line(val segments: List<Segment>, val baseline: Float, val width: Float)

    private val messrsPaint = inkPaint(scriptTypeface)
    private val namesPaint = inkPaint(namesTypeface)
    private val presentPaint = inkPaint(scriptTypeface)
    private val titlePaint = inkPaint(titleTypeface)
    private val capBounds = Rect()

    private var width = 0f
    private var height = 0f
    private var dedicationLines = emptyList<Line>()
    private var titleLines = emptyList<Line>()
    private var dedicationHeight = 0f
    private var titleHeight = 0f
    private var dedicationTop = 0f
    private var titleTop = 0f

    var dedicationPosition = MapPrefs.DEFAULT_DEDICATION_POSITION
        set(value) {
            if (field == value) return
            field = value
            layout()
        }

    var titlePosition = MapPrefs.DEFAULT_TITLE_POSITION
        set(value) {
            if (field == value) return
            field = value
            layout()
        }

    /** Строк в посвящении: 1, 2 или 3. */
    var dedicationLineCount = MapPrefs.DEFAULT_INSCRIPTION_LINES
        set(value) {
            val count = value.coerceIn(1, MapPrefs.MAX_INSCRIPTION_LINES)
            if (field == count) return
            field = count
            build()
        }

    /** Строк в названии: 1, 2 или 3. */
    var titleLineCount = MapPrefs.DEFAULT_INSCRIPTION_LINES
        set(value) {
            val count = value.coerceIn(1, MapPrefs.MAX_INSCRIPTION_LINES)
            if (field == count) return
            field = count
            build()
        }

    /** Растёт при каждом изменении раскладки — чтобы знать, когда пересчитать, где ходить путникам. */
    var version = 0
        private set

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth.toFloat() == width && newHeight.toFloat() == height) return
        width = newWidth.toFloat()
        height = newHeight.toFloat()
        build()
    }

    private fun build() {
        if (width <= 0f || height <= 0f) return
        buildDedication()
        buildTitle()
        layout()
    }

    /**
     * В 3 строки: «Messrs» / имена / «are proud to present». Размеры имён и «are proud to present»
     * подгоняются под ширину, «Messrs» — в 1,8 раза крупнее «are proud to present».
     * В 1 и 2 строки куски разными шрифтами идут на одной базовой линии, рукописные — в 1,2 раза
     * крупнее имён; самая широкая строка вписывается в ширину, но имена не крупнее, чем в 3 строки.
     */
    private fun buildDedication() {
        val namesSize = min(
            fitSize(namesPaint, NAMES_FIRST, width * 0.86f, 22f * dp),
            fitSize(namesPaint, NAMES_SECOND, width * 0.86f, 22f * dp),
        ) * DEDICATION_SCALE
        val presentSize = fitSize(presentPaint, PRESENT, width * 0.5f, 22f * dp) * DEDICATION_SCALE

        if (dedicationLineCount == 3) {
            namesPaint.textSize = min(namesSize, fitSize(namesPaint, NAMES_ALL, width * 0.92f, namesSize))
            presentPaint.textSize = presentSize
            messrsPaint.textSize = presentSize * 1.8f
            var y = messrsPaint.textSize
            val lines = ArrayList<Line>(3)
            lines += line(y, Segment(MESSRS, messrsPaint))
            y += namesPaint.textSize * 1.7f
            lines += line(y, Segment(NAMES_ALL, namesPaint))
            y += presentPaint.textSize * 1.5f
            lines += line(y, Segment(PRESENT, presentPaint))
            dedicationLines = lines
            dedicationHeight = y + presentPaint.textSize * DESCENT
            return
        }

        // Строка — куски «рукописный / имена / рукописный»: true — имена.
        val texts = if (dedicationLineCount == 1) {
            listOf(listOf("$MESSRS " to false, NAMES_ALL to true, " $PRESENT" to false))
        } else {
            listOf(
                listOf("$MESSRS " to false, NAMES_FIRST to true),
                listOf(NAMES_SECOND to true, " $PRESENT" to false),
            )
        }
        setMixedSize(REFERENCE_SIZE)
        val widest = texts.maxOf { parts -> parts.sumOf { (text, names) -> mixedPaint(names).measureText(text).toDouble() } }
        val size = min(width * 0.92f / widest.toFloat() * REFERENCE_SIZE, namesSize)
        setMixedSize(size)
        val step = messrsPaint.textSize * 1.4f
        dedicationLines = texts.mapIndexed { i, parts ->
            val segments = parts.map { (text, names) -> Segment(text, mixedPaint(names)) }
            line(messrsPaint.textSize + step * i, *segments.toTypedArray())
        }
        dedicationHeight = dedicationLines.last().baseline + messrsPaint.textSize * DESCENT
    }

    private fun setMixedSize(namesSize: Float) {
        namesPaint.textSize = namesSize
        messrsPaint.textSize = namesSize * SCRIPT_RATIO
        presentPaint.textSize = namesSize * SCRIPT_RATIO
    }

    private fun mixedPaint(names: Boolean) = if (names) namesPaint else messrsPaint

    /**
     * В 3 строки каждое слово вписывается в ширину, и берётся наименьший размер;
     * в 2 и 1 строку название уменьшается ровно настолько, чтобы влезть, но не крупнее, чем в 3 строки.
     */
    private fun buildTitle() {
        val threeLineSize = TITLE_THREE.minOf { fitSize(titlePaint, it, width * 0.84f, 96f * dp) } * TITLE_SCALE
        val texts = when (titleLineCount) {
            1 -> TITLE_ONE
            2 -> TITLE_TWO
            else -> TITLE_THREE
        }
        titlePaint.textSize = min(texts.minOf { fitSize(titlePaint, it, width * 0.84f, 96f * dp) }, threeLineSize)

        // Верх блока — по верху заглавной «M».
        titlePaint.getTextBounds("M", 0, 1, capBounds)
        val capHeight = -capBounds.top.toFloat()
        val lineHeight = titlePaint.textSize * 1.05f
        titleLines = texts.mapIndexed { i, text -> line(capHeight + lineHeight * i, Segment(text, titlePaint)) }
        titleHeight = titleLines.last().baseline + titlePaint.textSize * DESCENT
    }

    /** Строка из кусков [segments], измеренных при текущих размерах шрифтов. */
    private fun line(baseline: Float, vararg segments: Segment): Line {
        var x = 0f
        for (segment in segments) {
            segment.dx = x
            x += segment.paint.measureText(segment.text)
        }
        return Line(segments.toList(), baseline, x)
    }

    /** Ставит блоки; два блока в одном месте — друг под другом, название сверху. */
    private fun layout() {
        if (dedicationPosition == titlePosition) {
            val gap = BLOCK_GAP_DP * dp
            titleTop = blockTop(titlePosition, titleHeight + gap + dedicationHeight)
            dedicationTop = titleTop + titleHeight + gap
        } else {
            dedicationTop = blockTop(dedicationPosition, dedicationHeight)
            titleTop = blockTop(titlePosition, titleHeight)
        }
        version++
    }

    private fun blockTop(position: InscriptionPosition, blockHeight: Float): Float = when (position) {
        // Ниже строки состояния вверху и выше панели навигации внизу.
        InscriptionPosition.TOP -> TOP_MARGIN_DP * dp
        InscriptionPosition.CENTER -> (height - blockHeight) / 2f
        InscriptionPosition.BOTTOM -> height - BOTTOM_MARGIN_DP * dp - blockHeight
    }

    /** Прямоугольники, занятые блоками: путники обходят надписи. */
    fun bounds(): List<RectF> {
        val out = ArrayList<RectF>(2)
        fun add(lines: List<Line>, top: Float, blockHeight: Float) {
            val w = lines.maxOfOrNull { it.width } ?: return
            if (w <= 0f || blockHeight <= 0f) return
            out += RectF((width - w) / 2f, top, (width + w) / 2f, top + blockHeight)
        }
        add(dedicationLines, dedicationTop, dedicationHeight)
        add(titleLines, titleTop, titleHeight)
        return out
    }

    fun draw(canvas: Canvas) {
        drawBlock(canvas, dedicationLines, dedicationTop)
        drawBlock(canvas, titleLines, titleTop)
    }

    private fun drawBlock(canvas: Canvas, lines: List<Line>, top: Float) {
        for (line in lines) {
            val left = (width - line.width) / 2f
            for (segment in line.segments) {
                canvas.drawText(segment.text, left + segment.dx, top + line.baseline, segment.paint)
            }
        }
    }

    private fun inkPaint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = Paint.Align.LEFT
        color = INK
        alpha = INK_ALPHA
    }

    /** Наибольший размер не крупнее [maxSize], при котором [text] помещается в [maxWidth]. */
    private fun fitSize(paint: Paint, text: String, maxWidth: Float, maxSize: Float): Float {
        paint.textSize = REFERENCE_SIZE
        return min(maxWidth / paint.measureText(text) * REFERENCE_SIZE, maxSize)
    }

    private companion object {
        const val MESSRS = "Messrs"
        const val NAMES_FIRST = "Moony, Wormtail,"
        const val NAMES_SECOND = "Padfoot & Prongs"
        const val NAMES_ALL = "$NAMES_FIRST $NAMES_SECOND"
        const val PRESENT = "are proud to present"
        val TITLE_THREE = listOf("The", "Marauder's", "Map")
        val TITLE_TWO = listOf("The Marauder's", "Map")
        val TITLE_ONE = listOf("The Marauder's Map")

        /** Строка меряется при этом размере, а затем размер пересчитывается пропорционально. */
        const val REFERENCE_SIZE = 100f

        /** Посвящение и название рисуются вдвое мельче «вписанного» размера. */
        const val DEDICATION_SCALE = 0.5f
        const val TITLE_SCALE = 0.5f

        /** Посвящение в 1 и 2 строки: рукописные куски относительно имён. */
        const val SCRIPT_RATIO = 1.2f

        /** Место под последней базовой линией на хвосты букв, в долях размера. */
        const val DESCENT = 0.3f

        const val BLOCK_GAP_DP = 24f
        const val TOP_MARGIN_DP = 40f
        const val BOTTOM_MARGIN_DP = 56f

        /** Чуть бледнее — будто чернила впитались в пергамент. */
        const val INK_ALPHA = 225
    }
}
