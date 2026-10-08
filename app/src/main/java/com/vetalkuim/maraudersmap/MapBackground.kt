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
    const val KEY_MAP_LAYER = "map_layer"
    const val KEY_CUSTOM_NAME = "map_custom_name"
    const val KEY_CUSTOM_STAMP = "map_custom_stamp"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun background(prefs: SharedPreferences): MapBackground =
        prefs.getString(KEY_BACKGROUND, null)
            ?.let { name -> MapBackground.entries.firstOrNull { it.name == name } }
            ?: MapBackground.DEFAULT

    fun setBackground(context: Context, background: MapBackground) {
        get(context).edit().putString(KEY_BACKGROUND, background.name).apply()
    }

    fun mapLayer(prefs: SharedPreferences): MapLayer =
        prefs.getString(KEY_MAP_LAYER, null)
            ?.let { name -> MapLayer.entries.firstOrNull { it.name == name } }
            ?: MapLayer.DEFAULT

    fun setMapLayer(context: Context, layer: MapLayer) {
        get(context).edit().putString(KEY_MAP_LAYER, layer.name).apply()
    }

    fun customMapName(prefs: SharedPreferences): String? = prefs.getString(KEY_CUSTOM_NAME, null)

    /** Новая метка времени заставляет обои перечитать файл, даже если имя совпадает с прежним. */
    fun setCustomMap(context: Context, name: String) {
        get(context).edit()
            .putString(KEY_CUSTOM_NAME, name)
            .putLong(KEY_CUSTOM_STAMP, System.currentTimeMillis())
            .apply()
    }
}
