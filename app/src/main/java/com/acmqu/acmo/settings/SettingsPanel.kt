package com.acmqu.acmo.settings

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.acmqu.acmo.R
import com.acmqu.acmo.databinding.ViewSettingsBinding
import com.acmqu.acmo.face.FaceTheme
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/**
 * The hidden settings card: light/dark, dev mode, the remote console, media
 * volume, screen brightness and the face's primary colour. Theme, dev mode,
 * remote and colour persist through [Settings]; volume is the device's own;
 * brightness is applied to this window.
 */
class SettingsPanel(
    private val b: ViewSettingsBinding,
    private val activity: Activity,
    private val settings: Settings,
    private val onChanged: () -> Unit,
) {
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val swatchViews = mutableListOf<View>()

    /** What the line under the Remote pills says -- the address, or why there is none. The activity knows. */
    var remoteStatus: () -> String = { "" }

    init {
        b.btnLight.setOnClickListener { settings.dark = false; changed() }
        b.btnDark.setOnClickListener { settings.dark = true; changed() }
        b.btnDevOff.setOnClickListener { settings.devMode = false; changed() }
        b.btnDevOn.setOnClickListener { settings.devMode = true; changed() }
        b.btnRemoteOff.setOnClickListener { settings.remote = false; changed() }
        b.btnRemoteOn.setOnClickListener { settings.remote = true; changed() }

        b.sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setVolume(value / 100f)
        }
        b.sliderBrightness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.brightness = value / 100f
                applyBrightness()
            }
        }

        val size = dp(R.dimen.swatch_size)
        val gap = dp(10f)
        Settings.SWATCHES.forEachIndexed { i, _ ->
            val v = View(activity)
            val lp = LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = gap }
            v.layoutParams = lp
            v.setOnClickListener { settings.swatch = i; changed() }
            b.swatches.addView(v)
            swatchViews += v
        }
    }

    private fun changed() {
        onChanged()
        refresh()
    }

    /** Redraws the card for the current theme and values. Call after any settings change. */
    fun refresh() {
        val theme = settings.theme()
        val muted = 0xFF706D70.toInt()
        val border = 0xFF373637.toInt()

        b.settingsCard.background = GradientDrawable().apply {
            setColor(theme.bg)
            setStroke(dp(2f), border)
        }
        b.settingsShadow.setBackgroundColor(theme.accent)

        for (label in listOf(b.titleText, b.labelTheme, b.labelDev, b.labelRemote, b.labelVolume, b.labelBrightness, b.labelColour)) {
            label.setTextColor(muted)
        }
        b.hintText.setTextColor(muted)
        b.remoteAddress.setTextColor(muted)
        b.remoteAddress.text = remoteStatus()

        stylePill(b.btnLight, selected = !settings.dark, theme)
        stylePill(b.btnDark, selected = settings.dark, theme)
        stylePill(b.btnDevOff, selected = !settings.devMode, theme)
        stylePill(b.btnDevOn, selected = settings.devMode, theme)
        stylePill(b.btnRemoteOff, selected = !settings.remote, theme)
        stylePill(b.btnRemoteOn, selected = settings.remote, theme)
        stylePill(b.btnClose, selected = false, theme)

        b.sliderVolume.value = (currentVolume() * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
        val brightness = settings.brightness
        b.sliderBrightness.value = if (brightness < 0f) 100f else (brightness * 100f).roundToInt().toFloat().coerceIn(5f, 100f)

        swatchViews.forEachIndexed { i, v ->
            val color = Settings.SWATCHES[i].color ?: if (settings.dark) FaceTheme.TEAL_ON_DARK else FaceTheme.TEAL_ON_LIGHT
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (i == settings.swatch) setStroke(dp(3f), theme.ink)
            }
            v.contentDescription = Settings.SWATCHES[i].name
        }
    }

    /** Filled teal with dark text when selected; a ring in the ink colour otherwise. */
    fun stylePill(button: MaterialButton, selected: Boolean, theme: FaceTheme) {
        if (selected) {
            button.backgroundTintList = ColorStateList.valueOf(theme.accent)
            button.strokeColor = ColorStateList.valueOf(theme.accent)
            button.setTextColor(0xFF010000.toInt())
        } else {
            button.backgroundTintList = ColorStateList.valueOf(0x00000000)
            button.strokeColor = ColorStateList.valueOf(theme.accent)
            button.setTextColor(theme.ink)
        }
    }

    // ---- volume & brightness ----

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt().coerceIn(0, max), 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "volume is locked by the system (do not disturb?)", e)
        }
    }

    fun applyBrightness() {
        val v = settings.brightness
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (v < 0f) v else v.coerceIn(0.05f, 1f)
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics).roundToInt()

    private fun dp(resId: Int): Int = activity.resources.getDimensionPixelSize(resId)

    companion object {
        private const val TAG = "SettingsPanel"
    }
}
