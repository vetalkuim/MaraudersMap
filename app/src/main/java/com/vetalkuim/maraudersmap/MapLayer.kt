package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
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

    /** Загружает изображение слоя; долго для больших SVG, поэтому вызывается не в главном потоке. */
    fun load(context: Context, layer: MapLayer): MapImage? = when (layer) {
        MapLayer.NONE -> null
        MapLayer.HOGWARTS -> context.resources.openRawResource(R.raw.map_hogwarts).use(SvgImage::parse)
        MapLayer.CUSTOM -> customFile(context).takeIf(File::exists)?.let { loadFile(it) }
    }

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

