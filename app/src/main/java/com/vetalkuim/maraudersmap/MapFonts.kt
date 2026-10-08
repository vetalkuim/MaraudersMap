package com.vetalkuim.maraudersmap

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

/** Почерк Bad Script — для подписей путников на карте и их имён в настройках. */
object MapFonts {

    private const val TAG = "MapFonts"

    @Volatile
    private var script: Typeface? = null

    /**
     * Bad Script из `res/font`. Если [android.content.res.Resources.getFont] не справился,
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
        context.resources.getFont(R.font.bad_script)
    } catch (e: Exception) {
        Log.w(TAG, "getFont(bad_script) failed", e)
        null
    }

    private fun fromFile(context: Context): Typeface? = try {
        val file = File(context.cacheDir, "bad_script.ttf")
        if (!file.exists() || file.length() == 0L) {
            context.resources.openRawResource(R.font.bad_script).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        Typeface.createFromFile(file).takeIf { it != Typeface.DEFAULT }
    } catch (e: Exception) {
        Log.w(TAG, "Bad Script from file failed", e)
        null
    }
}
