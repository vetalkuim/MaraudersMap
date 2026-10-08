package com.vetalkuim.maraudersmap

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Разбор SVG, импорт файлов и отрисовка превью — вне главного потока, строго по очереди. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // Состояние превью, доступное только из [worker].
    private lateinit var previewRenderer: MapRenderer
    private var previewLayer: MapLayer? = null
    private var previewMapStamp = -1L
    private var previewCustomVersion = -1L

    private var previewGeneration = 0
    private lateinit var preview: ImageView
    private lateinit var backgroundGroup: RadioGroup
    private lateinit var pickImageButton: Button
    private lateinit var mapGroup: RadioGroup
    private lateinit var customMapName: TextView
    private lateinit var mapIntensity: SeekBar
    private lateinit var mapIntensityLabel: TextView
    private lateinit var dementorCount: SeekBar
    private lateinit var dementorLabel: TextView
    private lateinit var travelerList: LinearLayout
    private lateinit var addTraveler: Button

    /** Имена путников в порядке строк [travelerList], включая пустые. */
    private val travelerNames = mutableListOf<String>()
    private val previewRunnable = Runnable { updatePreview() }

    /** Подавляет обработчики, когда отметка переключается программно. */
    private var ignoreChecks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        previewRenderer = MapRenderer(applicationContext)

        preview = findViewById(R.id.preview)
        backgroundGroup = findViewById(R.id.background_group)
        pickImageButton = findViewById(R.id.pick_image)
        mapGroup = findViewById(R.id.map_group)
        customMapName = findViewById(R.id.map_custom_name)
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

        MapBackground.entries.forEach { bg -> backgroundGroup.addView(radioButton(bg, bg.title)) }
        MapLayer.entries.forEach { layer -> mapGroup.addView(radioButton(layer, layer.title)) }
        showSaved()

        backgroundGroup.setOnCheckedChangeListener { g, checkedId ->
            if (ignoreChecks) return@setOnCheckedChangeListener
            val bg = g.findViewById<View>(checkedId)?.tag as? MapBackground ?: return@setOnCheckedChangeListener
            if (bg == MapBackground.CUSTOM && !CustomBackground.exists(this)) {
                // Своей картинки ещё нет — сначала выбрать её; отметка сохранится после импорта.
                pickImage()
            } else {
                MapPrefs.setBackground(this, bg)
                showSaved()
            }
        }
        mapGroup.setOnCheckedChangeListener { g, checkedId ->
            if (ignoreChecks) return@setOnCheckedChangeListener
            val layer = g.findViewById<View>(checkedId)?.tag as? MapLayer ?: return@setOnCheckedChangeListener
            if (layer == MapLayer.CUSTOM && MapPrefs.customMapName(MapPrefs.get(this)) == null) {
                pickMapFile()
            } else {
                MapPrefs.setMapLayer(this, layer)
                showSaved()
            }
        }

        val sliders = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showSliders(mapIntensity.progress, dementorCount.progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            /** Сохраняется, когда палец отпущен, — превью не пересобирается на каждом делении. */
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

        pickImageButton.setOnClickListener { pickImage() }
        findViewById<Button>(R.id.pick_map).setOnClickListener { pickMapFile() }
        findViewById<Button>(R.id.set_wallpaper).setOnClickListener { openWallpaperPicker() }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(previewRunnable)
        worker.execute { previewRenderer.release() }
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity без AndroidX получает результат выбора только так")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data.takeIf { resultCode == RESULT_OK }
        when {
            requestCode != REQUEST_PICK_IMAGE && requestCode != REQUEST_MAP_FILE -> return
            uri == null -> showSaved() // отмена: возвращаем прежний выбор
            requestCode == REQUEST_PICK_IMAGE -> importImage(uri)
            else -> importMap(uri)
        }
    }

    private fun radioButton(tag: Any, title: Int): RadioButton {
        val paddingV = (12 * resources.displayMetrics.density).toInt()
        return RadioButton(this).apply {
            id = View.generateViewId()
            this.tag = tag
            setText(title)
            textSize = 16f
            setPadding(paddingLeft, paddingV, paddingRight, paddingV)
        }
    }

    /** Отмечает сохранённые в настройках фон и карту и обновляет превью. */
    private fun showSaved() {
        val prefs = MapPrefs.get(this)
        val background = MapPrefs.background(prefs)
        val layer = MapPrefs.mapLayer(prefs)
        ignoreChecks = true
        check(backgroundGroup, background)
        check(mapGroup, layer)
        ignoreChecks = false

        val intensity = MapPrefs.mapIntensity(prefs)
        val dementors = MapPrefs.dementorCount(prefs)
        mapIntensity.progress = intensity
        dementorCount.progress = dementors
        showSliders(intensity, dementors)

        pickImageButton.visibility = if (background == MapBackground.CUSTOM) View.VISIBLE else View.GONE
        val name = MapPrefs.customMapName(prefs)
        customMapName.visibility = if (name == null) View.GONE else View.VISIBLE
        if (name != null) customMapName.text = getString(R.string.map_custom_file, name)
        updatePreview()
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
            // Тем же почерком, что и подписи путников на карте.
            typeface = MapFonts.script(this@SettingsActivity)
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

    /** Обои переименовывают путников сразу, а превью пересобирается, когда ввод затихнет. */
    private fun saveTravelers() {
        MapPrefs.setTravelerNames(this, travelerNames)
        updateAddTraveler()
        mainHandler.removeCallbacks(previewRunnable)
        mainHandler.postDelayed(previewRunnable, PREVIEW_DEBOUNCE_MS)
    }

    private fun updateAddTraveler() {
        addTraveler.isEnabled = travelerNames.size < MapCreatures.MAX_TRAVELERS
    }

    private fun check(group: RadioGroup, tag: Any) {
        for (i in 0 until group.childCount) {
            val button = group.getChildAt(i) as RadioButton
            if (button.tag == tag && !button.isChecked) button.isChecked = true
        }
    }

    private fun pickImage() {
        val photoPicker = Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) MediaStore.ACTION_PICK_IMAGES
            else Intent.ACTION_OPEN_DOCUMENT,
        ).setType("image/*")
        val fallback = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        startPicker(listOf(photoPicker, fallback), REQUEST_PICK_IMAGE, R.string.no_image_picker)
    }

    private fun pickMapFile() {
        val document = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/svg+xml", "image/png"))
        startPicker(listOf(document), REQUEST_MAP_FILE, R.string.map_import_failed)
    }

    private fun startPicker(intents: List<Intent>, requestCode: Int, notFound: Int) {
        for (intent in intents) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, requestCode)
                return
            } catch (e: ActivityNotFoundException) {
                // пробуем следующий способ
            }
        }
        Toast.makeText(this, notFound, Toast.LENGTH_LONG).show()
        showSaved()
    }

    private fun importImage(uri: Uri) {
        pickImageButton.isEnabled = false
        worker.execute {
            val ok = CustomBackground.import(applicationContext, uri)
            mainHandler.post {
                if (isDestroyed) return@post
                pickImageButton.isEnabled = true
                if (ok) {
                    MapPrefs.setCustomBackground(this)
                } else {
                    Toast.makeText(this, R.string.image_import_failed, Toast.LENGTH_LONG).show()
                }
                showSaved()
            }
        }
    }

    private fun importMap(uri: Uri) {
        worker.execute {
            val ok = MapLayers.importCustom(applicationContext, uri)
            mainHandler.post {
                if (isDestroyed) return@post
                if (ok) {
                    MapPrefs.setMapLayer(this, MapLayer.CUSTOM)
                } else {
                    Toast.makeText(this, R.string.map_import_failed, Toast.LENGTH_LONG).show()
                }
                showSaved()
            }
        }
    }

    /** Превью собирается тем же [MapRenderer], что и обои, — фон и карта вместе. */
    private fun updatePreview() {
        val prefs = MapPrefs.get(this)
        val background = MapPrefs.background(prefs)
        val layer = MapPrefs.mapLayer(prefs)
        val mapStamp = prefs.getLong(MapPrefs.KEY_CUSTOM_STAMP, 0L)
        val customVersion = prefs.getLong(MapPrefs.KEY_CUSTOM_VERSION, 0L)
        val travelers = MapPrefs.travelerNames(prefs)
        val dementors = MapPrefs.dementorCount(prefs)
        val intensity = MapPrefs.mapIntensity(prefs)
        val metrics = resources.displayMetrics
        val width = minOf(metrics.widthPixels, metrics.heightPixels) / PREVIEW_DOWNSCALE
        val height = maxOf(metrics.widthPixels, metrics.heightPixels) / PREVIEW_DOWNSCALE
        val generation = ++previewGeneration

        worker.execute {
            if (layer != previewLayer || mapStamp != previewMapStamp) {
                previewRenderer.mapImage = try {
                    MapLayers.load(this, layer)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot load map layer $layer", e)
                    null
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Map layer $layer is too large", e)
                    null
                }
                previewLayer = layer
                previewMapStamp = mapStamp
            }
            if (customVersion != previewCustomVersion) {
                previewRenderer.invalidateCustom()
                previewCustomVersion = customVersion
            }
            previewRenderer.background = background
            previewRenderer.mapIntensity = intensity
            // Превью в PREVIEW_DOWNSCALE раз меньше экрана — путники уменьшены так же.
            previewRenderer.creatures = MapCreatures(metrics.density / PREVIEW_DOWNSCALE).apply {
                travelerNames = travelers
                dementorCount = dementors
                resize(width.toFloat(), height.toFloat())
                warmUp()
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Время старта анимации не задано, поэтому рисуется её последний кадр.
            previewRenderer.draw(Canvas(bitmap))
            mainHandler.post {
                if (generation == previewGeneration && !isDestroyed) preview.setImageBitmap(bitmap)
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
        const val REQUEST_PICK_IMAGE = 1
        const val REQUEST_MAP_FILE = 2
        const val PREVIEW_DOWNSCALE = 3
        const val PREVIEW_DEBOUNCE_MS = 500L
        const val TAG = "MapSettings"
    }
}
