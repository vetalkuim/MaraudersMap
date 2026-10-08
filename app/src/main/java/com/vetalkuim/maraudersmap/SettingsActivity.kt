package com.vetalkuim.maraudersmap

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var preview: ImageView
    private lateinit var group: RadioGroup
    private lateinit var pickButton: Button
    private val buttons = mutableMapOf<MapBackground, RadioButton>()

    /** Подавляет обработчик, когда выбор меняется программно. */
    private var ignoreChecks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preview = findViewById(R.id.preview)
        group = findViewById(R.id.background_group)
        pickButton = findViewById(R.id.pick_image)
        val paddingV = (12 * resources.displayMetrics.density).toInt()

        MapBackground.entries.forEach { bg ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                tag = bg
                setText(bg.title)
                textSize = 16f
                setPadding(paddingLeft, paddingV, paddingRight, paddingV)
            }
            group.addView(button)
            buttons[bg] = button
        }
        showSaved()

        group.setOnCheckedChangeListener { g, checkedId ->
            if (ignoreChecks) return@setOnCheckedChangeListener
            val bg = g.findViewById<View>(checkedId)?.tag as? MapBackground ?: return@setOnCheckedChangeListener
            if (bg == MapBackground.CUSTOM && !CustomBackground.exists(this)) {
                pickImage()
            } else {
                MapPrefs.setBackground(this, bg)
                showSelection(bg)
            }
        }

        pickButton.setOnClickListener { pickImage() }
        findViewById<Button>(R.id.set_wallpaper).setOnClickListener { openWallpaperPicker() }
    }

    /** Отмечает и показывает фон, сохранённый в настройках. */
    private fun showSaved() {
        val saved = MapPrefs.background(MapPrefs.get(this))
        ignoreChecks = true
        buttons.getValue(saved).isChecked = true
        ignoreChecks = false
        showSelection(saved)
    }

    private fun showSelection(bg: MapBackground) {
        val custom = if (bg == MapBackground.CUSTOM) CustomBackground.load(this, PREVIEW_MAX_SIDE) else null
        if (custom != null) preview.setImageBitmap(custom) else preview.setImageResource(bg.drawable)
        pickButton.visibility = if (bg == MapBackground.CUSTOM) View.VISIBLE else View.GONE
    }

    private fun pickImage() {
        val photoPicker = Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) MediaStore.ACTION_PICK_IMAGES
            else Intent.ACTION_OPEN_DOCUMENT,
        ).setType("image/*")
        val fallback = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        for (intent in listOf(photoPicker, fallback)) {
            try {
                startActivityForResult(intent, REQUEST_PICK_IMAGE)
                return
            } catch (e: ActivityNotFoundException) {
                // пробуем следующий способ
            }
        }
        Toast.makeText(this, R.string.no_image_picker, Toast.LENGTH_LONG).show()
        showSaved()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGE) return
        val uri = data?.data.takeIf { resultCode == RESULT_OK }
        if (uri == null) {
            showSaved() // отмена: возвращаем прежний выбор
            return
        }
        importImage(uri)
    }

    private fun importImage(uri: Uri) {
        pickButton.isEnabled = false
        Thread {
            val ok = CustomBackground.import(applicationContext, uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                pickButton.isEnabled = true
                if (ok) {
                    MapPrefs.setCustomBackground(this)
                } else {
                    Toast.makeText(this, R.string.image_import_failed, Toast.LENGTH_LONG).show()
                }
                showSaved()
            }
        }.start()
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
        const val PREVIEW_MAX_SIDE = 1280
    }
}
