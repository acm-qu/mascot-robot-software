package com.acmqu.acmo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.acmqu.acmo.databinding.ActivityMainBinding
import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.RemoteServer
import com.acmqu.acmo.remote.RobotLink
import com.acmqu.acmo.settings.Settings
import com.acmqu.acmo.settings.SettingsPanel
import com.acmqu.acmo.voice.ElevenLabs
import com.acmqu.acmo.voice.ModelInstaller
import com.acmqu.acmo.voice.Speaker
import kotlinx.coroutines.launch

/**
 * The one screen. It shows the face, full-screen and always on, and wires it
 * to the [Brain]. Five taps in the top-left corner open the settings card.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private lateinit var panel: SettingsPanel
    private lateinit var remote: RemoteServer
    private lateinit var robot: RobotLink

    private val cornerTaps = ArrayDeque<Long>()
    private var devBarBasePadding = 0

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
        val eleven = if (BuildConfig.ELEVENLABS_API_KEY.isBlank()) null else ElevenLabs(
            apiKey = BuildConfig.ELEVENLABS_API_KEY,
            voiceId = BuildConfig.ELEVENLABS_VOICE_ID.ifBlank { ElevenLabs.DEFAULT_VOICE_ID },
        )
        brain = Brain(
            face = binding.face,
            speaker = speaker,
            apiKey = BuildConfig.GEMINI_API_KEY,
            scope = lifecycleScope,
            apology = getString(R.string.speech_apology),
            eleven = eleven,
        )
        // ACMO's face drives the robot's body language: every expression change
        // is written to the wheel-control board over the USB cable.
        robot = RobotLink(applicationContext)
        robot.start()
        binding.face.onExpression = { robot.face(it) }

        remote = RemoteServer(RemoteServer.PORT, brain, onMain = { block -> runOnUiThread { block() } }, hasKey = eleven != null)
        panel = SettingsPanel(binding.settings, this, settings) { applySettings() }
        panel.remoteStatus = ::remoteStatus
        applySettings()
        if (eleven == null) {
            Log.w(TAG, "ELEVENLABS_API_KEY is empty -- the remote console gets 503; add it to local.properties and rebuild")
        }

        setUpKiosk()
        setUpTouch()
        setUpDevBar()

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            Log.w(TAG, "GEMINI_API_KEY is empty -- add it to local.properties and rebuild")
            say(R.string.speech_no_key)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            boot()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        takePrompt(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Plugging the board in delivers USB_DEVICE_ATTACHED here (singleTask);
        // connect right away rather than waiting for the next face change.
        if (::robot.isInitialized) robot.connect()
        takePrompt(intent)
    }

    /**
     * A prompt handed in by intent goes to the model like a typed one -- the way
     * to make ACMO talk from a shell:
     * `adb shell am start -n com.acmqu.acmo/.MainActivity --es prompt "say hi"`.
     */
    private fun takePrompt(intent: Intent?) {
        val prompt = intent?.getStringExtra(EXTRA_PROMPT) ?: return
        intent.removeExtra(EXTRA_PROMPT)
        brain.submitText(prompt)
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
        brain.setConversation(settings.conversation)
        if (settings.remote) remote.startListening() else remote.stopListening()
        panel.refresh()
        styleDevBar(theme)
    }

    /** The line under the Remote pills on the settings card: where the console should point. */
    private fun remoteStatus(): String = when {
        !settings.remote -> getString(R.string.remote_off)
        !remote.isAlive -> getString(R.string.remote_failed, RemoteServer.PORT)
        else -> RemoteServer.localAddress()?.let { "http://$it:${RemoteServer.PORT}" }
            ?: getString(R.string.remote_no_wifi, RemoteServer.PORT)
    }

    private fun showSettings(show: Boolean) {
        if (show) panel.refresh()
        binding.settingsScrim.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---- dev mode: a text box and a mic button along the bottom ----

    private fun setUpDevBar() {
        devBarBasePadding = binding.devBar.paddingBottom
        binding.devInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendTyped()
                true
            } else false
        }
        binding.devMic.setOnClickListener { brain.toggleListening() }
        brain.onStateChanged = { styleMic(it) }

        // The bar sits on the bottom edge; lift it over the keyboard and the nav bar when they show.
        ViewCompat.setOnApplyWindowInsetsListener(binding.devBar) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = devBarBasePadding + maxOf(ime, bars))
            insets
        }
    }

    private fun sendTyped() {
        val text = binding.devInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.devInput.text?.clear()
        WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        brain.submitText(text)
    }

    private fun styleDevBar(theme: com.acmqu.acmo.face.FaceTheme) {
        binding.devBar.visibility = if (settings.devMode) View.VISIBLE else View.GONE
        binding.devInput.setTextColor(theme.ink)
        binding.devInput.setHintTextColor(0xFF706D70.toInt())
        // Typed prompts go to Gemini, so they are off with the conversation.
        binding.devInput.isEnabled = settings.conversation
        binding.devInput.alpha = if (settings.conversation) 1f else 0.4f
        binding.devInput.background = GradientDrawable().apply {
            cornerRadius = 9999f
            setColor(0x00000000)
            setStroke((2 * resources.displayMetrics.density).toInt(), theme.accent)
        }
        styleMic(brain.state)
    }

    /** Filled while listening, a ring otherwise, dimmed while ACMO is busy. */
    private fun styleMic(state: Brain.State) {
        val theme = settings.theme()
        val listening = state == Brain.State.LISTENING
        panel.stylePill(binding.devMic, selected = listening, theme)
        binding.devMic.iconTint = ColorStateList.valueOf(if (listening) 0xFF010000.toInt() else theme.ink)
        val busy = state == Brain.State.THINKING || state == Brain.State.SPEAKING || state == Brain.State.BOOTING ||
            !settings.conversation
        binding.devMic.isEnabled = !busy
        binding.devMic.alpha = if (busy) 0.4f else 1f
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
        remote.stopListening()
        robot.stop()
        brain.stop()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_PROMPT = "prompt"
        private const val CORNER = 0.2f
        private const val TAPS_TO_OPEN = 5
        private const val TAP_WINDOW_MS = 2500L
    }
}
