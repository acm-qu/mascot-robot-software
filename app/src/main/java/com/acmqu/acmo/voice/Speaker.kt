package com.acmqu.acmo.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's text-to-speech, wrapped so one sentence is one suspending call.
 * [onStart] and [onFinish] fire on the main thread around every utterance --
 * that is what drives the mouth.
 */
class Speaker(context: Context) {
    var onStart: (() -> Unit)? = null
    var onFinish: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var currentTag: String? = null

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                // Only takes effect once the engine is connected -- earlier calls are silently ignored.
                tts.setPitch(1.1f)
                tts.setSpeechRate(1.0f)
            }
            ready.complete(ok)
        }
        tts.setOnUtteranceProgressListener(@Suppress("OVERRIDE_DEPRECATION") object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { onStart?.invoke() }
            }

            override fun onDone(utteranceId: String?) {
                main.post { onFinish?.invoke() }
                utteranceId?.let { pending.remove(it) }?.complete(Unit)
            }

            override fun onError(utteranceId: String?) {
                main.post { onFinish?.invoke() }
                utteranceId?.let { pending.remove(it) }?.completeExceptionally(IllegalStateException("TTS error"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { onFinish?.invoke() }
                utteranceId?.let { pending.remove(it) }?.completeExceptionally(IllegalStateException("TTS error $errorCode"))
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                main.post { onFinish?.invoke() }
                // An ordinary failure, not a cancellation: the caller's error path must still run.
                utteranceId?.let { pending.remove(it) }?.completeExceptionally(IllegalStateException("speech was stopped"))
            }
        })
    }

    /** Says [text] and returns once it has been heard. [languageTag] is BCP-47, e.g. "en" or "ar". */
    suspend fun speak(text: String, languageTag: String = "en") {
        if (!ready.await()) throw IllegalStateException("text-to-speech is not available")
        setLanguage(languageTag)

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw IllegalStateException("text-to-speech refused the sentence")
        }
        try {
            withTimeout(SENTENCE_TIMEOUT_MS) { done.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun setLanguage(tag: String) {
        if (tag == currentTag) return
        val result = tts.setLanguage(Locale.forLanguageTag(tag))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "no voice for $tag, falling back to the default")
            tts.language = Locale.getDefault()
        }
        currentTag = tag
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "Speaker"
        /** One segment is a sentence or two; anything longer than this is stuck. */
        private const val SENTENCE_TIMEOUT_MS = 45_000L
    }
}
