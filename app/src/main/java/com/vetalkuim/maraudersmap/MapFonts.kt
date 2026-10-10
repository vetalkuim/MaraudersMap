package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * Шрифты карты: почерк Marck Script — подписи путников и рукописные части посвящения,
 * Cinzel Decorative — имена Мародёров, Harry Potter — название карты.
 */
object MapFonts {

    private const val TAG = "MapFonts"

    private val loaded = HashMap<Int, Typeface>()

    fun script(context: Context): Typeface = font(context, R.font.marck_script, "marck_script.ttf")

    fun names(context: Context): Typeface = font(context, R.font.cinzel_decorative, "cinzel_decorative.ttf")

    fun title(context: Context): Typeface = font(context, R.font.harry_potter, "harry_potter.ttf")

    /**
     * Шрифт из `res/font`. Если [android.content.res.Resources.getFont] не справился,
     * шрифт копируется из ресурса в файл и читается оттуда; курсив с засечками — только в крайнем случае.
     */
    private fun font(context: Context, res: Int, fileName: String): Typeface {
        synchronized(loaded) { loaded[res]?.let { return it } }
        val app = context.applicationContext ?: context
        val typeface = fromResources(app, res, fileName) ?: fromFile(app, res, fileName)
            ?: return Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        synchronized(loaded) { loaded[res] = typeface }
        return typeface
    }

    private fun fromResources(context: Context, res: Int, fileName: String): Typeface? = try {
        context.resources.getFont(res)
    } catch (e: Exception) {
        Log.w(TAG, "getFont($fileName) failed", e)
        null
    }

    private fun fromFile(context: Context, res: Int, fileName: String): Typeface? = try {
        val file = File(context.cacheDir, fileName)
        if (!file.exists() || file.length() == 0L) {
            context.resources.openRawResource(res).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        Typeface.createFromFile(file).takeIf { it != Typeface.DEFAULT }
    } catch (e: Exception) {
        Log.w(TAG, "$fileName from file failed", e)
        null
    }
}
