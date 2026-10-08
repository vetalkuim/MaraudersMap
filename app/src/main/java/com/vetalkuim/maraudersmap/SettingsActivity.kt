package com.vetalkuim.maraudersmap

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Разбор SVG, импорт файла и отрисовка превью — вне главного потока, строго по очереди. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // Состояние превью, доступное только из [worker].
    private lateinit var previewRenderer: MapRenderer
    private var previewLayer: MapLayer? = null
    private var previewStamp = -1L

    private var previewGeneration = 0
    private lateinit var preview: ImageView
    private lateinit var mapGroup: RadioGroup
    private lateinit var customName: TextView

    /** Подавляет обработчик, когда отметка переключается программно. */
    private var updatingMapGroup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        previewRenderer = MapRenderer(resources)

        preview = findViewById(R.id.preview)
        mapGroup = findViewById(R.id.map_group)
        customName = findViewById(R.id.map_custom_name)
        val prefs = MapPrefs.get(this)

        val backgroundGroup = findViewById<RadioGroup>(R.id.background_group)
        val currentBackground = MapPrefs.background(prefs)
        MapBackground.entries.forEach { bg ->
            backgroundGroup.addView(radioButton(bg, bg.title, bg == currentBackground))
        }
        backgroundGroup.setOnCheckedChangeListener { g, checkedId ->
            val bg = g.findViewById<View>(checkedId)?.tag as? MapBackground ?: return@setOnCheckedChangeListener
            MapPrefs.setBackground(this, bg)
            updatePreview()
        }

        val currentLayer = MapPrefs.mapLayer(prefs)
        MapLayer.entries.forEach { layer ->
            mapGroup.addView(radioButton(layer, layer.title, layer == currentLayer))
        }
        mapGroup.setOnCheckedChangeListener { g, checkedId ->
            if (updatingMapGroup) return@setOnCheckedChangeListener
            val layer = g.findViewById<View>(checkedId)?.tag as? MapLayer ?: return@setOnCheckedChangeListener
            if (layer == MapLayer.CUSTOM && MapPrefs.customMapName(MapPrefs.get(this)) == null) {
                // Своего файла ещё нет — сначала выбрать его; отметка сохранится после импорта.
                pickMapFile()
                return@setOnCheckedChangeListener
            }
            MapPrefs.setMapLayer(this, layer)
            updatePreview()
        }

        findViewById<Button>(R.id.pick_map).setOnClickListener { pickMapFile() }
        findViewById<Button>(R.id.set_wallpaper).setOnClickListener { openWallpaperPicker() }

        updateCustomName()
        updatePreview()
    }

    override fun onDestroy() {
        worker.execute { previewRenderer.release() }
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity без AndroidX получает результат выбора документа только так")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MAP_FILE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            syncMapGroup()
            return
        }
        worker.execute {
            val imported = MapLayers.importCustom(this, uri)
            mainHandler.post {
                if (isDestroyed) return@post
                if (imported) {
                    MapPrefs.setMapLayer(this, MapLayer.CUSTOM)
                    updateCustomName()
                    updatePreview()
                } else {
                    Toast.makeText(this, R.string.map_import_failed, Toast.LENGTH_LONG).show()
                }
                syncMapGroup()
            }
        }
    }

    private fun radioButton(tag: Any, title: Int, checked: Boolean): RadioButton {
        val paddingV = (12 * resources.displayMetrics.density).toInt()
        return RadioButton(this).apply {
            id = View.generateViewId()
            this.tag = tag
            setText(title)
            textSize = 16f
            setPadding(paddingLeft, paddingV, paddingRight, paddingV)
            isChecked = checked
        }
    }

    private fun pickMapFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/svg+xml", "image/png"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_MAP_FILE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.map_import_failed, Toast.LENGTH_LONG).show()
            syncMapGroup()
        }
    }

    /** Возвращает отметку на сохранённый слой (например, если выбор файла отменён). */
    private fun syncMapGroup() {
        val layer = MapPrefs.mapLayer(MapPrefs.get(this))
        for (i in 0 until mapGroup.childCount) {
            val button = mapGroup.getChildAt(i) as RadioButton
            if (button.tag == layer && !button.isChecked) {
                updatingMapGroup = true
                button.isChecked = true
                updatingMapGroup = false
            }
        }
    }

    private fun updateCustomName() {
        val name = MapPrefs.customMapName(MapPrefs.get(this))
        customName.visibility = if (name == null) View.GONE else View.VISIBLE
        if (name != null) customName.text = getString(R.string.map_custom_file, name)
    }

    /** Превью собирается тем же [MapRenderer], что и обои, — пергамент и карта вместе. */
    private fun updatePreview() {
        val prefs = MapPrefs.get(this)
        val background = MapPrefs.background(prefs)
        val layer = MapPrefs.mapLayer(prefs)
        val stamp = prefs.getLong(MapPrefs.KEY_CUSTOM_STAMP, 0L)
        val metrics = resources.displayMetrics
        val width = minOf(metrics.widthPixels, metrics.heightPixels) / PREVIEW_DOWNSCALE
        val height = maxOf(metrics.widthPixels, metrics.heightPixels) / PREVIEW_DOWNSCALE
        val generation = ++previewGeneration

        worker.execute {
            if (layer != previewLayer || stamp != previewStamp) {
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
                previewStamp = stamp
            }
            previewRenderer.background = background
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
        const val REQUEST_MAP_FILE = 1
        const val PREVIEW_DOWNSCALE = 3
        const val TAG = "MapSettings"
    }
}
