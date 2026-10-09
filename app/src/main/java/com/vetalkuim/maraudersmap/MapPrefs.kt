package com.vetalkuim.maraudersmap

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

object MapPrefs {
    private const val FILE = "map_prefs"
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
