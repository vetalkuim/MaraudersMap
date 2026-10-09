package com.vetalkuim.maraudersmap

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * Фоновые варианты.
 * [includesMap] — картинка уже с нарисованной картой: она заменяет слои 0 и 1, отдельная карта не рисуется.
 */
enum class MapBackground(val drawable: Int, val title: Int, val includesMap: Boolean = false) {
    PLAIN(R.drawable.bg_plain, R.string.bg_plain),
    POSTER(R.drawable.bg_poster, R.string.bg_poster, includesMap = true);

    companion object {
        /** По умолчанию — ровный пергамент; сохранённый прежний режим раскрытия тоже становится им. */
        val DEFAULT = PLAIN
    }
}

object MapPrefs {
    private const val FILE = "map_prefs"
    const val KEY_BACKGROUND = "background"
    const val KEY_MAP_LAYER = "map_layer"
    const val KEY_DEMENTORS = "dementor_count"

    /** JSON-массив имён путников, пустые строки тоже хранятся — это незаполненные строки в настройках. */
    const val KEY_TRAVELER_NAMES = "traveler_names"

    /** Прежнее число путников: из него берутся имена по умолчанию, пока список не сохранён. */
    private const val KEY_TRAVELER_COUNT = "traveler_count"

    /** Интенсивность цвета слоя 1 (карты) в процентах. */
    const val KEY_MAP_INTENSITY = "map_intensity"
    const val DEFAULT_MAP_INTENSITY = 100
    const val MAX_MAP_INTENSITY = 200

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

    /** Слой 1, который действительно рисуется: при фоне с картой — никакого, выбор при этом сохраняется. */
    fun visibleMapLayer(prefs: SharedPreferences): MapLayer =
        if (background(prefs).includesMap) MapLayer.NONE else mapLayer(prefs)

    fun setMapLayer(context: Context, layer: MapLayer) {
        get(context).edit().putString(KEY_MAP_LAYER, layer.name).apply()
    }

    fun travelerNames(prefs: SharedPreferences): List<String> {
        val json = prefs.getString(KEY_TRAVELER_NAMES, null)
        if (json != null) {
            try {
                val array = JSONArray(json)
                return List(array.length()) { array.optString(it) }
            } catch (e: JSONException) {
                // испорченное значение — как будто списка нет
            }
        }
        val count = prefs.getInt(KEY_TRAVELER_COUNT, MapCreatures.DEFAULT_TRAVELERS)
        return MapCreatures.NAMES.take(count.coerceIn(0, MapCreatures.NAMES.size))
    }

    fun setTravelerNames(context: Context, names: List<String>) {
        get(context).edit().putString(KEY_TRAVELER_NAMES, JSONArray(names).toString()).apply()
    }

    fun dementorCount(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DEMENTORS, MapCreatures.DEFAULT_DEMENTORS).coerceIn(0, MapCreatures.MAX_COUNT)

    fun setDementorCount(context: Context, dementors: Int) {
        get(context).edit().putInt(KEY_DEMENTORS, dementors).apply()
    }

    fun mapIntensity(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_MAP_INTENSITY, DEFAULT_MAP_INTENSITY).coerceIn(0, MAX_MAP_INTENSITY)

    fun setMapIntensity(context: Context, percent: Int) {
        get(context).edit().putInt(KEY_MAP_INTENSITY, percent).apply()
    }
}
