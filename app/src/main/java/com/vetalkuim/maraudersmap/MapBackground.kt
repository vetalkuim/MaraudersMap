package com.vetalkuim.maraudersmap

import android.content.Context
import android.content.SharedPreferences

/**
 * Фоновые варианты. [CUSTOM] — картинка пользователя ([CustomBackground]), [drawable] у неё —
 * запасной фон, пока картинка не выбрана. [UNFOLD] — анимация раскрытия по кадрам пергамента.
 */
enum class MapBackground(val drawable: Int, val title: Int) {
    FOLD_WIDE(R.drawable.bg_fold_wide, R.string.bg_fold_wide),
    FOLD_THIN(R.drawable.bg_fold_thin, R.string.bg_fold_thin),
    PLAIN(R.drawable.bg_plain, R.string.bg_plain),
    CUSTOM(R.drawable.bg_plain, R.string.bg_custom),
    UNFOLD(R.drawable.bg_plain, R.string.bg_unfold);

    companion object {
        /** Кадры анимации раскрытия: широкий сгиб → тонкий сгиб → ровный лист. */
        val unfoldFrames = listOf(FOLD_WIDE, FOLD_THIN, PLAIN)

        val DEFAULT = UNFOLD
    }
}

object MapPrefs {
    private const val FILE = "map_prefs"
    const val KEY_BACKGROUND = "background"

    /** Меняется при каждой новой своей картинке, чтобы обои перечитали файл. */
    const val KEY_CUSTOM_VERSION = "custom_version"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun background(prefs: SharedPreferences): MapBackground =
        prefs.getString(KEY_BACKGROUND, null)
            ?.let { name -> MapBackground.entries.firstOrNull { it.name == name } }
            ?: MapBackground.DEFAULT

    fun setBackground(context: Context, background: MapBackground) {
        get(context).edit().putString(KEY_BACKGROUND, background.name).apply()
    }

    /** Делает фоном только что сохранённую свою картинку и просит обои перечитать её. */
    fun setCustomBackground(context: Context) {
        get(context).edit()
            .putString(KEY_BACKGROUND, MapBackground.CUSTOM.name)
            .putLong(KEY_CUSTOM_VERSION, System.currentTimeMillis())
            .apply()
    }
}
