package com.vetalkuim.maraudersmap

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/** Вариант пергамента (слой 0). */
enum class Background {
    /** Динамический: процедурный пергамент с пятнами, зерном и виньеткой, над ним — пылинки. */
    DYNAMIC,

    /** Статический: ровный пергамент из картинки `bg_plain.webp`. */
    STATIC,
}

/** Вариант рисунка (слой 1). */
enum class Drawing {
    /** Карта: рисунок из `map_hogwarts.svg`. */
    MAP,

    /** Надписи: название «The Marauder's Map» и посвящение. */
    INSCRIPTIONS,
}

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

    /** Вариант фона — имя из [Background]. */
    const val KEY_BACKGROUND = "background"
    val DEFAULT_BACKGROUND = Background.DYNAMIC

    /** Вариант рисунка — имя из [Drawing]. */
    const val KEY_DRAWING = "drawing"
    val DEFAULT_DRAWING = Drawing.MAP

    /** Надписи: где стоят посвящение и название — имя из [InscriptionPosition], и сколько в них строк. */
    const val KEY_DEDICATION_POSITION = "dedication_position"
    const val KEY_TITLE_POSITION = "title_position"
    const val KEY_DEDICATION_LINES = "dedication_lines"
    const val KEY_TITLE_LINES = "title_lines"
    val DEFAULT_DEDICATION_POSITION = InscriptionPosition.TOP
    val DEFAULT_TITLE_POSITION = InscriptionPosition.TOP
    const val DEFAULT_INSCRIPTION_LINES = 1
    const val MAX_INSCRIPTION_LINES = 3

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

    fun background(prefs: SharedPreferences): Background {
        val name = prefs.getString(KEY_BACKGROUND, null) ?: return DEFAULT_BACKGROUND
        return Background.entries.firstOrNull { it.name == name } ?: DEFAULT_BACKGROUND
    }

    fun setBackground(context: Context, background: Background) {
        get(context).edit().putString(KEY_BACKGROUND, background.name).apply()
    }

    fun drawing(prefs: SharedPreferences): Drawing {
        val name = prefs.getString(KEY_DRAWING, null) ?: return DEFAULT_DRAWING
        return Drawing.entries.firstOrNull { it.name == name } ?: DEFAULT_DRAWING
    }

    fun setDrawing(context: Context, drawing: Drawing) {
        get(context).edit().putString(KEY_DRAWING, drawing.name).apply()
    }

    fun dedicationPosition(prefs: SharedPreferences): InscriptionPosition =
        position(prefs, KEY_DEDICATION_POSITION, DEFAULT_DEDICATION_POSITION)

    fun titlePosition(prefs: SharedPreferences): InscriptionPosition =
        position(prefs, KEY_TITLE_POSITION, DEFAULT_TITLE_POSITION)

    private fun position(prefs: SharedPreferences, key: String, default: InscriptionPosition): InscriptionPosition {
        val name = prefs.getString(key, null) ?: return default
        return InscriptionPosition.entries.firstOrNull { it.name == name } ?: default
    }

    fun setPosition(context: Context, key: String, position: InscriptionPosition) {
        get(context).edit().putString(key, position.name).apply()
    }

    fun dedicationLines(prefs: SharedPreferences): Int = lines(prefs, KEY_DEDICATION_LINES)

    fun titleLines(prefs: SharedPreferences): Int = lines(prefs, KEY_TITLE_LINES)

    private fun lines(prefs: SharedPreferences, key: String): Int =
        prefs.getInt(key, DEFAULT_INSCRIPTION_LINES).coerceIn(1, MAX_INSCRIPTION_LINES)

    fun setLines(context: Context, key: String, lines: Int) {
        get(context).edit().putInt(key, lines).apply()
    }
}
