package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build

/**
 * Рисует обои по слоям:
 * 0 — пергамент ([drawBackground]), растянутый по принципу center-crop без искажений:
 *     статический ([Background.STATIC]) или динамический — с пятнами и пылинками ([Background.DYNAMIC]);
 * 1 — рисунок: карта ([drawMap]), вписанная в экран целиком ([Drawing.MAP]),
 *     или надписи ([inscriptions], [Drawing.INSCRIPTIONS]);
 * 2 — путники со следами и подписями ([drawCreatures]).
 */
class MapRenderer(private val context: Context) {

    /** Слой 0, вариант 1 — статический пергамент; загружается при первой отрисовке. */
    private var parchment: Bitmap? = null

    /** Вариант фона; ненужная картинка другого варианта освобождается. */
    var background: Background = MapPrefs.DEFAULT_BACKGROUND
        set(value) {
            if (field == value) return
            field = value
            if (value == Background.DYNAMIC) {
                parchment?.recycle()
                parchment = null
            } else {
                dustParchment?.recycle()
                dustParchment = null
            }
        }

    /**
     * Слой 0, вариант 2 — процедурный пергамент под размер экрана ([ParchmentGenerator]).
     * Генерируется в фоне; пока его нет — заливка базовым цветом бумаги.
     */
    var dustParchment: Bitmap? = null

    /** Пылинки над пергаментом варианта 2. */
    private val dust by lazy { DustEffect(context.resources.displayMetrics.density) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    /**
     * Чернила впитываются в пергамент: белый фон PNG-рисунка не перекрывает бумагу (API 29+).
     * Карта рисуется той же сепией, что и следы путников.
     */
    private val mapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.MULTIPLY
        colorFilter = ColorMatrixColorFilter(inkTint(INK))
    }
    private var mapCache: Bitmap? = null

    /** Вариант рисунка: карта или надписи — рисуется что-то одно. */
    var drawing: Drawing = MapPrefs.DEFAULT_DRAWING
        set(value) {
            if (field == value) return
            field = value
            if (value != Drawing.MAP) {
                mapRaster = null
                mapCache?.recycle()
                mapCache = null
            }
        }

    /**
     * По чему посчитана сетка путников: картинка карты и раскладка надписей;
     * пересчёт — только когда что-то из этого изменилось.
     */
    private var walkAreaSource: Bitmap? = null
    private var walkAreaInscriptions = -1
    private var walkAreaResult: WalkArea? = null

    /** Посвящение и название карты; где они стоят и сколько в них строк — задаёт движок. */
    val inscriptions by lazy {
        MapInscriptions(
            context.resources.displayMetrics.density,
            MapFonts.script(context),
            MapFonts.names(context),
            MapFonts.title(context),
        )
    }

    /**
     * Изображение слоя карты; null — слой выключен или ещё загружается.
     * Растеризуется при первой отрисовке — так делает превью в настройках.
     */
    var mapImage: MapImage? = null
        set(value) {
            field = value
            mapCache?.recycle()
            mapCache = null
        }

    /**
     * Готовая карта под размер экрана ([MapLayers.raster]): обои получают её заранее, из кэша
     * на диске или из фонового потока, чтобы не растеризовать сложный вектор во время кадра.
     * Если задана — главнее [mapImage].
     */
    var mapRaster: Bitmap? = null

    /**
     * Интенсивность цвета рисунка (карты или надписей) в процентах: до 100 — чернила бледнее,
     * выше 100 — рисунок накладывается второй раз и чернила гуще.
     */
    var mapIntensity: Int = MapPrefs.DEFAULT_MAP_INTENSITY

    /** Фон анимирован (пылинки) — кадры нужны, даже когда на карте никого нет. */
    val isAnimated: Boolean
        get() = background == Background.DYNAMIC

    /** Слой 2; null — не рисуется. */
    var creatures: MapCreatures? = null

    private val creatureRenderer by lazy { CreatureRenderer(context) }

    /** Сдвигает анимацию фона на [dtSeconds] секунд. */
    fun update(dtSeconds: Float) {
        if (background == Background.DYNAMIC) dust.update(dtSeconds.coerceIn(0f, MAX_DUST_STEP_S))
    }

    fun draw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
        when (drawing) {
            Drawing.MAP -> drawMap(canvas)
            Drawing.INSCRIPTIONS -> {
                inscriptions.resize(canvas.width, canvas.height)
                inscriptions.draw(canvas, mapIntensity)
            }
        }
        updateWalkArea(canvas.width, canvas.height)
        drawCreatures(canvas)
    }

    /**
     * Путники ходят только по чистой бумаге — там, где нет рисунка карты или надписей.
     * С невидимым рисунком (интенсивность 0 %) — по всему экрану.
     */
    fun updateWalkArea(width: Int, height: Int) {
        val world = creatures ?: return
        if (width <= 0 || height <= 0) return
        val visible = mapIntensity > 0
        val mapVisible = visible && drawing == Drawing.MAP && (mapRaster != null || mapImage != null)
        val withInscriptions = visible && drawing == Drawing.INSCRIPTIONS
        if (!mapVisible && !withInscriptions) {
            if (world.walkArea != null) world.walkArea = null
            walkAreaSource = null
            walkAreaInscriptions = NO_INSCRIPTIONS
            walkAreaResult = null
            return
        }
        val bitmap = if (mapVisible) (mapBitmap(width, height) ?: return) else null
        val inscriptionsKey = if (withInscriptions) {
            inscriptions.resize(width, height)
            inscriptions.version
        } else {
            NO_INSCRIPTIONS
        }
        if (bitmap === walkAreaSource && inscriptionsKey == walkAreaInscriptions &&
            world.walkArea === walkAreaResult
        ) return
        world.walkArea = walkAreaOf(bitmap, withInscriptions, width, height, world.dp)
        walkAreaSource = bitmap
        walkAreaInscriptions = inscriptionsKey
        walkAreaResult = world.walkArea
    }

    /**
     * Сетка чернил: клетка занята, если в ней есть хоть одна тёмная точка рисунка [map]
     * или она лежит под надписью (если [withInscriptions]).
     */
    private fun walkAreaOf(map: Bitmap?, withInscriptions: Boolean, width: Int, height: Int, dp: Float): WalkArea {
        val cell = WalkArea.CELL_DP * dp
        val (cols, rows) = WalkArea.gridSize(width.toFloat(), height.toFloat(), cell)
        val ink = BooleanArray(cols * rows)
        if (map != null) {
            val line = IntArray(width)
            val colOf = IntArray(width) { minOf((it / cell).toInt(), cols - 1) }
            for (y in 0 until height) {
                map.getPixels(line, 0, width, 0, y, width, 1)
                val rowStart = minOf((y / cell).toInt(), rows - 1) * cols
                for (x in 0 until width) {
                    if (isInk(line[x])) ink[rowStart + colOf[x]] = true
                }
            }
        }
        if (withInscriptions) for (r in inscriptions.bounds()) {
            val left = (r.left / cell).toInt().coerceIn(0, cols - 1)
            val right = (r.right / cell).toInt().coerceIn(0, cols - 1)
            val top = (r.top / cell).toInt().coerceIn(0, rows - 1)
            val bottom = (r.bottom / cell).toInt().coerceIn(0, rows - 1)
            for (row in top..bottom) {
                for (col in left..right) ink[row * cols + col] = true
            }
        }
        return WalkArea.fromInk(width.toFloat(), height.toFloat(), cell, dp, ink)
    }

    private fun drawMap(canvas: Canvas) {
        val strength = 255 * mapIntensity / 100
        if (strength <= 0) return
        val bitmap = mapBitmap(canvas.width, canvas.height) ?: return
        mapPaint.alpha = strength.coerceAtMost(255)
        canvas.drawBitmap(bitmap, 0f, 0f, mapPaint)
        if (strength > 255) {
            mapPaint.alpha = (strength - 255).coerceAtMost(255)
            canvas.drawBitmap(bitmap, 0f, 0f, mapPaint)
        }
    }

    private fun drawCreatures(canvas: Canvas) {
        val world = creatures ?: return
        creatureRenderer.draw(canvas, world)
    }

    /**
     * Карта под размер экрана: готовая [mapRaster] или растеризованная один раз из [mapImage] —
     * перерисовывать сложный вектор каждый кадр анимации слишком дорого.
     * Готовая карта другого размера (после поворота) не рисуется, пока не придёт новая.
     */
    private fun mapBitmap(width: Int, height: Int): Bitmap? {
        mapRaster?.let { return if (it.width == width && it.height == height) it else null }
        val image = mapImage ?: return null
        mapCache?.let { if (it.width == width && it.height == height) return it }
        mapCache?.recycle()
        mapCache = rasterize(image, width, height)
        return mapCache
    }

    private fun drawBackground(canvas: Canvas) {
        when (background) {
            Background.STATIC -> {
                val bitmap = parchment ?: BitmapFactory.decodeResource(
                    context.resources, R.drawable.bg_plain, BitmapFactory.Options().apply { inScaled = false },
                ).also { parchment = it }
                drawCropped(canvas, bitmap)
            }
            Background.DYNAMIC -> {
                // После поворота, пока новый лист не готов, растягивается прежний.
                dustParchment?.let { drawCropped(canvas, it) } ?: canvas.drawColor(ParchmentGenerator.BASE_COLOR)
                dust.resize(canvas.width.toFloat(), canvas.height.toFloat())
                dust.draw(canvas)
            }
        }
    }

    private fun drawCropped(canvas: Canvas, bitmap: Bitmap) {
        centerCrop(bitmap.width, bitmap.height, canvas.width, canvas.height, srcRect)
        dstRect.set(0, 0, canvas.width, canvas.height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    fun release() {
        parchment?.recycle()
        parchment = null
        dustParchment?.recycle()
        dustParchment = null
        mapImage = null
        mapRaster = null
        creatureRenderer.release()
    }

    companion object {
        /**
         * Вписывает карту в экран целиком с полями [MAP_MARGIN].
         * Долго для больших SVG — обои вызывают это не в главном потоке.
         */
        fun rasterize(image: MapImage, width: Int, height: Int): Bitmap? {
            if (width <= 0 || height <= 0 || image.width <= 0f || image.height <= 0f) return null
            val margin = minOf(width, height) * MAP_MARGIN
            val scale = minOf((width - 2 * margin) / image.width, (height - 2 * margin) / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = (width - w) / 2
            val top = (height - h) / 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            image.draw(Canvas(bitmap), RectF(left, top, left + w, top + h))
            return bitmap
        }


        /** Ключ раскладки надписей, когда надписи не рисуются. */
        private const val NO_INSCRIPTIONS = -1

        /** Пылинки не прыгают после долгого кадра: шаг не больше 0,1 с. */
        private const val MAX_DUST_STEP_S = 0.1f

        /** Поля вокруг карты — доля меньшей стороны экрана. */
        private const val MAP_MARGIN = 0.04f

        /** Порог «чернил»: насколько точка темнее бумаги с учётом прозрачности, из 255. */
        private const val INK_THRESHOLD = 64

        /** Тёмная непрозрачная точка; белый фон PNG-рисунка чернилами не считается. */
        private fun isInk(pixel: Int): Boolean {
            val alpha = pixel ushr 24
            if (alpha < INK_THRESHOLD) return false
            val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            return (255 - luma) * alpha / 255 >= INK_THRESHOLD
        }

        /**
         * Перекрашивает рисунок в [ink] по яркости: чёрное становится [ink], белое остаётся белым,
         * промежуточное — между ними; прозрачность не меняется.
         */
        private fun inkTint(ink: Int): ColorMatrix {
            fun row(channel: Int): FloatArray {
                val k = (255 - channel) / 255f
                return floatArrayOf(k * 0.299f, k * 0.587f, k * 0.114f, 0f, channel.toFloat())
            }
            return ColorMatrix(
                row(Color.red(ink)) + row(Color.green(ink)) + row(Color.blue(ink)) +
                    floatArrayOf(0f, 0f, 0f, 1f, 0f),
            )
        }

        /** Вычисляет область исходника, которая заполнит экран без искажений. */
        private fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int, out: Rect) {
            if (dstW <= 0 || dstH <= 0) {
                out.set(0, 0, srcW, srcH)
                return
            }
            if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
                val cropW = (srcH.toLong() * dstW / dstH).toInt()
                val left = (srcW - cropW) / 2
                out.set(left, 0, left + cropW, srcH)
            } else {
                val cropH = (srcW.toLong() * dstH / dstW).toInt()
                val top = (srcH - cropH) / 2
                out.set(0, top, srcW, top + cropH)
            }
        }
    }
}
