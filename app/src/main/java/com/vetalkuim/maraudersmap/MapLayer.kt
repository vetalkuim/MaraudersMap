package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.io.IOException

/** Изображение слоя карты, которое умеет вписаться в заданный прямоугольник. */
interface MapImage {
    val width: Float
    val height: Float
    fun draw(canvas: Canvas, dst: RectF)
}

object MapLayers {
    private const val RASTER_DIR = "map_raster"
    private const val MAP_NAME = "map"
    private const val TAG = "MapLayers"

    /** Разобранная карта: новые обои и превью не разбирают SVG заново. */
    private var parsed: MapImage? = null

    /** Загружает карту; долго для большого SVG, поэтому вызывается не в главном потоке. */
    fun load(context: Context): MapImage =
        context.resources.openRawResource(R.raw.map_hogwarts).use(SvgImage::parse)

    /** Как [load], но разобранная карта берётся из памяти, если она уже была загружена. */
    fun image(context: Context): MapImage {
        synchronized(this) { parsed?.let { return it } }
        val image = load(context)
        synchronized(this) { parsed = image }
        return image
    }

    /**
     * Готовая карта под экран [width]×[height] из кэша на диске; null — ещё не растеризована.
     * Читается быстро, поэтому подходит для первого кадра.
     */
    fun cachedRaster(context: Context, width: Int, height: Int): Bitmap? {
        val file = rasterFile(context, width, height) ?: return null
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
    fun raster(context: Context, width: Int, height: Int): Bitmap? {
        cachedRaster(context, width, height)?.let { return it }
        val image = image(context)
        val bitmap = MapRenderer.rasterize(image, width, height) ?: return null
        val file = rasterFile(context, width, height) ?: return bitmap
        try {
            // Прежние версии карты не нужны; эта же версия под другие размеры экрана остаётся.
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
     * Файл кэша: версия карты и размер экрана. Версия — время установки приложения,
     * чтобы после обновления карта перерисовалась.
     */
    private fun rasterFile(context: Context, width: Int, height: Int): File? {
        if (width <= 0 || height <= 0) return null
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        val dir = File(context.cacheDir, RASTER_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return File(dir, "${sizePrefix(width, height)}${MAP_NAME}_$version.png")
    }

    /** Кэш хранит по картинке на каждый размер экрана — например, для обеих ориентаций. */
    private fun sizePrefix(width: Int, height: Int) = "${width}x${height}_"
}
