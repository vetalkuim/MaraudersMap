package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Своя картинка пользователя. Выбранное изображение копируется во внутреннее хранилище
 * (уменьшенным и с учётом поворота из EXIF), поэтому не зависит от прав на исходный файл.
 */
object CustomBackground {

    private const val FILE_NAME = "custom_background.jpg"
    private const val MAX_SIDE = 2560
    private const val JPEG_QUALITY = 92

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).isFile

    /** Копирует картинку из [uri]. Выполнять не на главном потоке. Возвращает false при ошибке. */
    fun import(context: Context, uri: Uri): Boolean {
        val bitmap = try {
            decode(context, uri)
        } catch (e: Exception) {
            null
        } ?: return false

        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        return try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            bitmap.recycle()
        }
    }

    /** Загружает сохранённую картинку так, чтобы длинная сторона была не больше [maxSide]. */
    fun load(context: Context, maxSide: Int = MAX_SIDE): Bitmap? {
        val path = file(context).takeIf { it.isFile }?.path ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder сам учитывает EXIF-поворот.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = MAX_SIDE.toFloat() / max(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt(),
                        (info.size.height * scale).roundToInt(),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        return decodeLegacy(context, uri)
    }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE)
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val scale = MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) bitmap.recycle()
            bitmap = transformed
        }
        return bitmap
    }

    private fun exifRotation(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    /** Наибольшая степень двойки, при которой длинная сторона остаётся не меньше [maxSide]. */
    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
