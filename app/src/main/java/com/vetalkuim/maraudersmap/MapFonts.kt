package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

/** Почерк Marck Script — для подписей путников на карте. */
object MapFonts {

    private const val TAG = "MapFonts"

    @Volatile
    private var script: Typeface? = null

    /**
     * Marck Script из `res/font`. Если [android.content.res.Resources.getFont] не справился,
     * шрифт копируется из ресурса в файл и читается оттуда; курсив с засечками — только в крайнем случае.
     */
    fun script(context: Context): Typeface {
        script?.let { return it }
        val app = context.applicationContext ?: context
        val loaded = fromResources(app) ?: fromFile(app)
        if (loaded != null) {
            script = loaded
            return loaded
        }
        return Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }

    private fun fromResources(context: Context): Typeface? = try {
        context.resources.getFont(R.font.marck_script)
    } catch (e: Exception) {
        Log.w(TAG, "getFont(marck_script) failed", e)
        null
    }

    private fun fromFile(context: Context): Typeface? = try {
        val file = File(context.cacheDir, "marck_script.ttf")
        if (!file.exists() || file.length() == 0L) {
            context.resources.openRawResource(R.font.marck_script).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        Typeface.createFromFile(file).takeIf { it != Typeface.DEFAULT }
    } catch (e: Exception) {
        Log.w(TAG, "Marck Script from file failed", e)
        null
    }
}
