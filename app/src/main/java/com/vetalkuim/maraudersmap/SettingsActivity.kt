package com.vetalkuim.maraudersmap

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val preview = findViewById<ImageView>(R.id.preview)
        val group = findViewById<RadioGroup>(R.id.background_group)
        val current = MapPrefs.background(MapPrefs.get(this))
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
            if (bg == current) button.isChecked = true
        }
        preview.setImageResource(current.drawable)

        group.setOnCheckedChangeListener { g, checkedId ->
            val bg = g.findViewById<View>(checkedId)?.tag as? MapBackground ?: return@setOnCheckedChangeListener
            MapPrefs.setBackground(this, bg)
            preview.setImageResource(bg.drawable)
        }

        findViewById<Button>(R.id.set_wallpaper).setOnClickListener { openWallpaperPicker() }
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
}
