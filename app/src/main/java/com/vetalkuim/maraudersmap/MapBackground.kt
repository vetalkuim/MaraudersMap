package com.vetalkuim.maraudersmap

import android.content.Context
import android.content.SharedPreferences

/** Фоновые варианты пергамента. [UNFOLD] — анимация раскрытия карты по кадрам остальных вариантов. */
enum class MapBackground(val drawable: Int, val title: Int) {
    FOLD_WIDE(R.drawable.bg_fold_wide, R.string.bg_fold_wide),
    FOLD_THIN(R.drawable.bg_fold_thin, R.string.bg_fold_thin),
    PLAIN(R.drawable.bg_plain, R.string.bg_plain),
    PARCHMENT(R.drawable.bg_parchment, R.string.bg_parchment),
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

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun background(prefs: SharedPreferences): MapBackground =
        prefs.getString(KEY_BACKGROUND, null)
            ?.let { name -> MapBackground.entries.firstOrNull { it.name == name } }
            ?: MapBackground.DEFAULT

    fun setBackground(context: Context, background: MapBackground) {
        get(context).edit().putString(KEY_BACKGROUND, background.name).apply()
    }
}
