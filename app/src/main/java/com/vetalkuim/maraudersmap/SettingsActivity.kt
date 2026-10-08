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
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
    private lateinit var travelerCount: SeekBar
    private lateinit var dementorCount: SeekBar
    private lateinit var travelerLabel: TextView
    private lateinit var dementorLabel: TextView

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
        travelerCount = findViewById(R.id.traveler_count)
        dementorCount = findViewById(R.id.dementor_count)
        travelerLabel = findViewById(R.id.traveler_count_label)
        dementorLabel = findViewById(R.id.dementor_count_label)
        travelerCount.max = MapCreatures.MAX_COUNT
        dementorCount.max = MapCreatures.MAX_COUNT

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

        val counts = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showCounts(travelerCount.progress, dementorCount.progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            /** Сохраняется, когда палец отпущен, — превью не пересобирается на каждом делении. */
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                MapPrefs.setCounts(this@SettingsActivity, travelerCount.progress, dementorCount.progress)
                showSaved()
            }
        }
        travelerCount.setOnSeekBarChangeListener(counts)
        dementorCount.setOnSeekBarChangeListener(counts)

        pickImageButton.setOnClickListener { pickImage() }
        findViewById<Button>(R.id.pick_map).setOnClickListener { pickMapFile() }
        findViewById<Button>(R.id.set_wallpaper).setOnClickListener { openWallpaperPicker() }
    }

    override fun onDestroy() {
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

        val travelers = MapPrefs.travelerCount(prefs)
        val dementors = MapPrefs.dementorCount(prefs)
        travelerCount.progress = travelers
        dementorCount.progress = dementors
        showCounts(travelers, dementors)

        pickImageButton.visibility = if (background == MapBackground.CUSTOM) View.VISIBLE else View.GONE
        val name = MapPrefs.customMapName(prefs)
        customMapName.visibility = if (name == null) View.GONE else View.VISIBLE
        if (name != null) customMapName.text = getString(R.string.map_custom_file, name)
        updatePreview()
    }

    private fun showCounts(travelers: Int, dementors: Int) {
        travelerLabel.text = getString(R.string.traveler_count, travelers)
        dementorLabel.text = getString(R.string.dementor_count, dementors)
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
        val travelers = MapPrefs.travelerCount(prefs)
        val dementors = MapPrefs.dementorCount(prefs)
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
            // Превью в PREVIEW_DOWNSCALE раз меньше экрана — путники уменьшены так же.
            previewRenderer.creatures = MapCreatures(metrics.density / PREVIEW_DOWNSCALE).apply {
                travelerCount = travelers
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
        const val TAG = "MapSettings"
    }
}
