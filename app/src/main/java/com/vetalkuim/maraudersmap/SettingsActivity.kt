package com.vetalkuim.maraudersmap

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    /** Подготовка карты — вне главного потока, строго по очереди. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var mapIntensity: SeekBar
    private lateinit var mapIntensityLabel: TextView
    private lateinit var dementorCount: SeekBar
    private lateinit var dementorLabel: TextView
    private lateinit var travelerList: LinearLayout
    private lateinit var addTraveler: Button

    /** Имена путников в порядке строк [travelerList], включая пустые. */
    private val travelerNames = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        mapIntensity = findViewById(R.id.map_intensity)
        mapIntensityLabel = findViewById(R.id.map_intensity_label)
        dementorCount = findViewById(R.id.dementor_count)
        dementorLabel = findViewById(R.id.dementor_count_label)
        travelerList = findViewById(R.id.traveler_list)
        addTraveler = findViewById(R.id.add_traveler)
        mapIntensity.max = MapPrefs.MAX_MAP_INTENSITY
        dementorCount.max = MapCreatures.MAX_COUNT

        travelerNames += MapPrefs.travelerNames(MapPrefs.get(this))
        travelerNames.forEach { addTravelerRow(it, travelerList.childCount) }
        updateAddTraveler()
        addTraveler.setOnClickListener {
            travelerNames.add(0, "")
            saveTravelers()
            val field = addTravelerRow("", 0)
            field.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }

        showSaved()
        warmMapCache()

        val sliders = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showSliders(mapIntensity.progress, dementorCount.progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            /** Сохраняется, когда палец отпущен, — обои не перестраиваются на каждом делении. */
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (seekBar == mapIntensity) {
                    MapPrefs.setMapIntensity(this@SettingsActivity, seekBar.progress)
                } else {
                    MapPrefs.setDementorCount(this@SettingsActivity, seekBar.progress)
                }
                showSaved()
            }
        }
        mapIntensity.setOnSeekBarChangeListener(sliders)
        dementorCount.setOnSeekBarChangeListener(sliders)

        findViewById<Button>(R.id.set_wallpaper).setOnClickListener { openWallpaperPicker() }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Показывает сохранённые значения ползунков. */
    private fun showSaved() {
        val prefs = MapPrefs.get(this)
        val intensity = MapPrefs.mapIntensity(prefs)
        val dementors = MapPrefs.dementorCount(prefs)
        mapIntensity.progress = intensity
        dementorCount.progress = dementors
        showSliders(intensity, dementors)
    }

    private fun showSliders(intensity: Int, dementors: Int) {
        mapIntensityLabel.text = getString(R.string.map_intensity, intensity)
        dementorLabel.text = getString(R.string.dementor_count, dementors)
    }

    /** Строка «имя + Удалить». Позиция строки в [travelerList] совпадает с индексом в [travelerNames]. */
    private fun addTravelerRow(name: String, index: Int): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(this).apply {
            setText(name)
            setHint(R.string.traveler_name_hint)
            setTextColor(getColor(R.color.parchment))
            setHintTextColor(getColor(R.color.parchment_dark))
            backgroundTintList = getColorStateList(R.color.parchment_dark)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            // Состояние строк восстанавливается из настроек, а не из сохранённых View.
            isSaveEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val i = travelerList.indexOfChild(row)
                    if (i < 0) return
                    travelerNames[i] = s?.toString().orEmpty()
                    saveTravelers()
                }
            })
        }
        val remove = Button(this).apply {
            setText(R.string.remove_traveler)
            setOnClickListener {
                val i = travelerList.indexOfChild(row)
                if (i < 0) return@setOnClickListener
                travelerNames.removeAt(i)
                travelerList.removeView(row)
                saveTravelers()
            }
        }
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        travelerList.addView(row, index)
        updateAddTraveler()
        return field
    }

    /** Обои переименовывают путников сразу. */
    private fun saveTravelers() {
        MapPrefs.setTravelerNames(this, travelerNames)
        updateAddTraveler()
    }

    private fun updateAddTraveler() {
        addTraveler.isEnabled = travelerNames.size < MapCreatures.MAX_TRAVELERS
    }

    /**
     * Заранее готовит карту под полный экран в кэше на диске, чтобы обои показали её
     * с первого кадра, а не после разбора SVG. Готовит в фоне.
     */
    private fun warmMapCache() {
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(real)
        val width = minOf(real.widthPixels, real.heightPixels)
        val height = maxOf(real.widthPixels, real.heightPixels)
        worker.execute {
            try {
                MapLayers.raster(this, width, height)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot prepare map", e)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Map is too large", e)
            }
        }
    }

    private fun openWallpaperPicker() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, MapWallpaperService::class.java),
        )
        try {
            startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_wallpaper_picker, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "MapSettings"
    }
}
