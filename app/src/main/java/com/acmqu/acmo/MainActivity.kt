package com.acmqu.acmo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.acmqu.acmo.databinding.ActivityMainBinding
import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.gemini.GeminiClient
import com.acmqu.acmo.settings.Settings
import com.acmqu.acmo.settings.SettingsPanel
import com.acmqu.acmo.voice.ModelInstaller
import com.acmqu.acmo.voice.Speaker
import kotlinx.coroutines.launch

/**
 * The one screen. It shows the face, full-screen and always on, and wires it
 * to the [Brain]. Five taps in the top-left corner open the settings card; a
 * tap anywhere else previews the next expression.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private lateinit var panel: SettingsPanel

    private val cornerTaps = ArrayDeque<Long>()

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) boot() else say(R.string.speech_no_mic)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        speaker = Speaker(this).apply {
            onStart = { binding.face.setSpeaking(true) }
            onFinish = { binding.face.setSpeaking(false) }
        }
        brain = Brain(
            face = binding.face,
            speaker = speaker,
            gemini = GeminiClient(BuildConfig.GEMINI_API_KEY),
            scope = lifecycleScope,
            apology = getString(R.string.speech_apology),
        )
        panel = SettingsPanel(binding.settings, this, settings) { applySettings() }
        applySettings()

        setUpKiosk()
        setUpTouch()

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            Log.w(TAG, "GEMINI_API_KEY is empty -- add it to local.properties and rebuild")
            say(R.string.speech_no_key)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            boot()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Unpacks the wake-word model (a few seconds the first time) and starts listening. */
    private fun boot() {
        lifecycleScope.launch {
            try {
                val model = ModelInstaller.load(this@MainActivity)
                brain.start(model)
            } catch (e: Exception) {
                Log.e(TAG, "could not load the wake-word model", e)
                binding.face.setExpression(Expression.SAD)
                say(R.string.speech_no_ears)
            }
        }
    }

    private fun say(resId: Int) {
        lifecycleScope.launch {
            try { speaker.speak(getString(resId), "en") } catch (e: Exception) { Log.w(TAG, "could not speak", e) }
        }
    }

    // ---- settings ----

    private fun applySettings() {
        val theme = settings.theme()
        binding.face.setTheme(theme)
        window.decorView.setBackgroundColor(theme.bg)
        panel.applyBrightness()
        panel.refresh()
    }

    private fun showSettings(show: Boolean) {
        if (show) panel.refresh()
        binding.settingsScrim.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---- touch ----

    private fun setUpTouch() {
        binding.face.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performClick()
                onFaceTap(event.x / v.width, event.y / v.height)
            }
            true
        }
        binding.settingsScrim.setOnClickListener { showSettings(false) }
        binding.settings.btnClose.setOnClickListener { showSettings(false) }
        // Taps on the card itself must not fall through to the scrim.
        binding.settings.settingsCard.setOnClickListener { }
    }

    /** Coordinates are fractions of the face. The top-left 20 % is the secret corner. */
    private fun onFaceTap(fx: Float, fy: Float) {
        if (fx < CORNER && fy < CORNER) {
            val now = System.currentTimeMillis()
            cornerTaps.addLast(now)
            while (cornerTaps.isNotEmpty() && now - cornerTaps.first() > TAP_WINDOW_MS) cornerTaps.removeFirst()
            if (cornerTaps.size >= TAPS_TO_OPEN) {
                cornerTaps.clear()
                showSettings(true)
            }
            return
        }
        cornerTaps.clear()
        brain.previewNext()
    }

    // ---- kiosk ----

    private fun setUpKiosk() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---- lifecycle ----

    override fun onStart() {
        super.onStart()
        brain.setForeground(true)
    }

    override fun onStop() {
        brain.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        brain.stop()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val CORNER = 0.2f
        private const val TAPS_TO_OPEN = 5
        private const val TAP_WINDOW_MS = 2500L
    }
}
