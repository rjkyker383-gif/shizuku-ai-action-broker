package com.rjkyker.shizukuai.broker.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Live talk session: SpeechRecognizer (mic) → LocalAgent (instant on-device answers) or Muse relay
 * → speak reply (Muse audio only if the relay ever returns real Muse audio; otherwise Android TTS,
 * labelled "Device voice" in the UI). After speaking, it listens again until Stop / "stop".
 *
 * Mic is used ONLY while a session is active. Never sends SMS/email, never spends, never taps.
 * All recognizer/TTS calls happen on the main thread; network on a worker.
 */
class TalkController(
    context: Context,
    private val museClient: MuseVoiceClient,
    private val factsProvider: () -> LocalAgent.Facts,
    private val ui: Listener,
) {
    interface Listener {
        fun onState(state: State, detail: String)
        fun onPartial(text: String)
        fun onTurn(who: String, text: String)
        fun onVoiceLabel(label: String)
    }

    enum class State { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TalkNet").apply { isDaemon = true } }
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    @Volatile var active = false; private set
    private val session = UUID.randomUUID().toString().take(8)
    var preferredVoiceName: String? = null

    init {
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
                applyVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { afterSpeaking() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { afterSpeaking() } }
                })
            }
            main.post { ui.onVoiceLabel(voiceLabel()) }
        }
    }

    fun recognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(app)

    /** English voices installed on the device (for the voice picker). */
    fun englishVoices(): List<Voice> = runCatching {
        tts?.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

    fun applyVoice() {
        val t = tts ?: return
        val want = preferredVoiceName ?: return
        englishVoices().firstOrNull { it.name == want }?.let { t.voice = it }
    }

    /** Honest label: Muse TTS is not available to third-party apps, so this is the device voice. */
    fun voiceLabel(): String = if (!ttsReady) "Voice: loading…" else "Device voice (Android TTS): ${tts?.voice?.name ?: "default"}"

    fun start() {
        if (active) return
        if (!recognitionAvailable()) {
            ui.onState(State.ERROR, "No speech recognizer on this device (install/enable Google speech services).")
            return
        }
        active = true
        listen()
    }

    fun stop() {
        active = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop() }
        runCatching { player?.release() }
        player = null
        ui.onState(State.IDLE, "Stopped — mic off")
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        worker.shutdownNow()
    }

    private fun listen() {
        if (!active) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it; it.setRecognitionListener(listener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        ui.onState(State.LISTENING, "Listening… speak now")
        runCatching { r.startListening(intent) }.onFailure {
            ui.onState(State.ERROR, "Mic start failed: ${it.message}"); active = false
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { ui.onState(State.THINKING, "Got it…") }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { ui.onPartial(it) }
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            handleUtterance(text)
        }
        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    ui.onState(State.LISTENING, "Didn't catch that — still listening")
                    main.postDelayed({ listen() }, 300)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    active = false; ui.onState(State.ERROR, "Microphone permission is off — tap Talk to grant it.")
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    runCatching { recognizer?.destroy() }; recognizer = null
                    main.postDelayed({ listen() }, 600)
                }
                else -> {
                    ui.onState(State.ERROR, "Speech error $error — retrying")
                    runCatching { recognizer?.destroy() }; recognizer = null
                    main.postDelayed({ listen() }, 1200)
                }
            }
        }
    }

    private fun handleUtterance(text: String) {
        if (!active) return
        if (text.isBlank()) { listen(); return }
        ui.onTurn("You", text)
        if (LocalAgent.isStopPhrase(text)) { speak("Okay, stopping.", endAfter = true); return }
        val local = runCatching { LocalAgent.answer(text, factsProvider()) }.getOrNull()
        if (local != null) { ui.onTurn("Broker", local); speak(local); return }
        ui.onState(State.THINKING, "Sending to Muse…")
        worker.execute {
            val reply = try {
                museClient.ask(text, session, cancelled = { !active })
            } catch (e: Exception) {
                Log.w(TAG, "muse relay: ${e.javaClass.simpleName}")
                main.post {
                    val msg = "I couldn't reach the Muse relay right now."
                    ui.onTurn("Broker", msg); speak(msg)
                }
                return@execute
            }
            main.post {
                if (!active) return@post
                if (reply == null) {
                    val msg = "I sent that to Muse. No reply yet — it'll show here when Muse answers."
                    ui.onTurn("Broker", msg); speak(msg)
                } else {
                    ui.onTurn(if (reply.audioUrl != null) "Muse (Muse voice)" else "Muse", reply.text)
                    if (reply.audioUrl != null) playAudio(reply.audioUrl, reply.text) else speak(reply.text)
                }
            }
        }
    }

    private var endAfterSpeech = false

    private fun speak(text: String, endAfter: Boolean = false) {
        endAfterSpeech = endAfter
        val t = tts
        if (t == null || !ttsReady) { afterSpeaking(); return }
        ui.onState(State.SPEAKING, voiceLabel())
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u-${System.nanoTime()}")
    }

    private fun playAudio(url: String, fallbackText: String) {
        ui.onState(State.SPEAKING, "Muse voice")
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
                setDataSource(url)
                setOnCompletionListener { main.post { afterSpeaking() } }
                setOnErrorListener { _, _, _ -> main.post { speak(fallbackText) }; true }
                prepareAsync()
                setOnPreparedListener { it.start() }
            }
        }.onFailure { speak(fallbackText) }
    }

    private fun afterSpeaking() {
        if (endAfterSpeech) { endAfterSpeech = false; stop(); return }
        if (active) listen() else ui.onState(State.IDLE, "Mic off")
    }

    companion object { private const val TAG = "TalkController" }
}
