package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException

/** Слой 1 — нарисованная карта поверх пергамента (слой 0). */
enum class MapLayer(val title: Int) {
    NONE(R.string.map_none),
    HOGWARTS(R.string.map_hogwarts),
    CUSTOM(R.string.map_custom);

    companion object {
        val DEFAULT = HOGWARTS
    }
}

/** Изображение слоя карты, которое умеет вписаться в заданный прямоугольник. */
interface MapImage {
    val width: Float
    val height: Float
    fun draw(canvas: Canvas, dst: RectF)
}

private class RasterImage(private val bitmap: Bitmap) : MapImage {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    override val width: Float get() = bitmap.width.toFloat()
    override val height: Float get() = bitmap.height.toFloat()
    override fun draw(canvas: Canvas, dst: RectF) = canvas.drawBitmap(bitmap, null, dst, paint)
}

object MapLayers {
    private const val CUSTOM_FILE = "map_layer_custom"
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_BITMAP_SIDE = 4096
    private const val RASTER_DIR = "map_raster"
    private const val TAG = "MapLayers"

    /** Последнее разобранное изображение: новые обои и превью не разбирают SVG заново. */
    private var parsed: Pair<String, MapImage>? = null

    /** Загружает изображение слоя; долго для больших SVG, поэтому вызывается не в главном потоке. */
    fun load(context: Context, layer: MapLayer): MapImage? = when (layer) {
        MapLayer.NONE -> null
        MapLayer.HOGWARTS -> context.resources.openRawResource(R.raw.map_hogwarts).use(SvgImage::parse)
        MapLayer.CUSTOM -> customFile(context).takeIf(File::exists)?.let { loadFile(it) }
    }

    /** Как [load], но разобранное изображение берётся из памяти, если оно уже было загружено. */
    fun image(context: Context, layer: MapLayer, stamp: Long): MapImage? {
        if (layer == MapLayer.NONE) return null
        // Метка времени меняется только у своей карты.
        val key = if (layer == MapLayer.CUSTOM) "${layer.name}_$stamp" else layer.name
        synchronized(this) { parsed?.let { (k, image) -> if (k == key) return image } }
        val image = load(context, layer) ?: return null
        synchronized(this) { parsed = key to image }
        return image
    }

    /**
     * Готовая карта под экран [width]×[height] из кэша на диске; null — ещё не растеризована.
     * Читается быстро, поэтому подходит для первого кадра.
     */
    fun cachedRaster(context: Context, layer: MapLayer, stamp: Long, width: Int, height: Int): Bitmap? {
        val file = rasterFile(context, layer, stamp, width, height) ?: return null
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.path)?.takeIf { it.width == width && it.height == height }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Готовая карта под экран: из кэша на диске, а без него — разбор и растеризация с сохранением в кэш.
     * Долго, поэтому вызывается не в главном потоке.
     */
    fun raster(context: Context, layer: MapLayer, stamp: Long, width: Int, height: Int): Bitmap? {
        cachedRaster(context, layer, stamp, width, height)?.let { return it }
        val image = image(context, layer, stamp) ?: return null
        val bitmap = MapRenderer.rasterize(image, width, height) ?: return null
        val file = rasterFile(context, layer, stamp, width, height) ?: return bitmap
        try {
            // Прежние карты и версии не нужны; эта же карта под другие размеры экрана остаётся.
            val current = file.name.removePrefix(sizePrefix(width, height))
            file.parentFile?.listFiles()?.forEach { other ->
                if (!other.name.endsWith("_$current")) other.delete()
            }
            // Свой временный файл: обои и настройки могут готовить карту одновременно.
            val tmp = File.createTempFile(file.nameWithoutExtension, ".tmp", file.parentFile)
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot cache map raster", e)
        }
        return bitmap
    }

    /**
     * Файл кэша: карта, её версия и размер экрана. Для встроенной карты версия — время установки
     * приложения, чтобы после обновления карта перерисовалась.
     */
    private fun rasterFile(context: Context, layer: MapLayer, stamp: Long, width: Int, height: Int): File? {
        if (layer == MapLayer.NONE || width <= 0 || height <= 0) return null
        val version = when (layer) {
            MapLayer.CUSTOM -> stamp
            else -> try {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            } catch (e: Exception) {
                0L
            }
        }
        val dir = File(context.cacheDir, RASTER_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return File(dir, "${sizePrefix(width, height)}${layer.name}_$version.png")
    }

    /** Кэш хранит по картинке на каждый размер экрана — например, для обеих ориентаций. */
    private fun sizePrefix(width: Int, height: Int) = "${width}x${height}_"

    /**
     * Копирует выбранный пользователем SVG или PNG во внутреннее хранилище, чтобы обои
     * не зависели от разрешений на исходный документ. Возвращает false, если файл не читается.
     */
    fun importCustom(context: Context, uri: Uri): Boolean {
        val tmp = File(context.filesDir, "$CUSTOM_FILE.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { src ->
                tmp.outputStream().use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) return false
                        dst.write(buffer, 0, n)
                    }
                }
            }
            loadFile(tmp)
            if (!tmp.renameTo(customFile(context))) return false
        } catch (e: IOException) {
            return false
        } catch (e: RuntimeException) {
            // Битый SVG/XML или нехватка памяти на декодирование — файл не подходит.
            return false
        } catch (e: OutOfMemoryError) {
            return false
        } finally {
            tmp.delete()
        }
        MapPrefs.setCustomMap(context, displayName(context, uri) ?: uri.lastPathSegment.orEmpty())
        return true
    }

    private fun customFile(context: Context) = File(context.filesDir, CUSTOM_FILE)

    /** Растровые форматы (PNG и всё, что понимает BitmapFactory) или SVG — по содержимому. */
    private fun loadFile(file: File): MapImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return file.inputStream().use(SvgImage::parse)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_BITMAP_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode ${file.name}")
        return RasterImage(bitmap)
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}

