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
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Live talk session: SpeechRecognizer (mic) → LocalAgent (instant on-device answers) or Muse relay
 * → speak reply. Muse replies carry an MP3 synthesized with Muse's own TTS (default male voice Static,
 * avocado_v2:vdc_3944, or the Muse voice RJ picked); it is downloaded with the broker token and played with MediaPlayer, labelled
 * "Muse voice — <name>". Android TTS (preferring a male en-US voice) is used only when no Muse audio
 * arrives within the timeout or the download/playback fails. After speaking, it listens again until Stop.
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
    /** Muse voice RJ picked (sent with every utterance); default Static. */
    var museVoice: String = MuseVoiceClient.DEFAULT_VOICE
    /** One-shot test turn (debug intent): play the reply, then go idle instead of reopening the mic. */
    @Volatile private var oneShot = false
    @Volatile var lastPlayedLabel: String = ""; private set

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
        val want = preferredVoiceName
        val chosen = (if (want != null) englishVoices().firstOrNull { it.name == want } else null)
            ?: pickMaleUsVoice(englishVoices())
        chosen?.let { runCatching { t.voice = it } }
    }

    /** Fallback-only label (Muse replies are labelled "Muse voice — <name>" while they play). */
    fun voiceLabel(): String = if (!ttsReady) "Voice: loading…"
        else "Muse voice ($museVoice) · fallback: ${tts?.voice?.name ?: "device default"}"

    fun start() {
        if (active) return
        if (!recognitionAvailable()) {
            ui.onState(State.ERROR, "No speech recognizer on this device (install/enable Google speech services).")
            return
        }
        active = true
        listen()
    }

    /**
     * Debug-build test hook: run one Muse round trip for [text] as if it had been spoken (no mic), play the
     * reply, then return to idle. Used by `am start … --es talk_test_text "…"` to verify playback end to end.
     */
    fun injectUtterance(text: String) {
        if (active) return
        active = true; oneShot = true
        Log.i(TAG, "talk test turn injected (${text.length} chars)")
        handleUtterance(text)
    }

    fun stop() {
        active = false; oneShot = false
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
                museClient.ask(text, session, museVoice, cancelled = { !active })?.let { r ->
                    // Download Muse's MP3 here on the worker so playback starts instantly on main.
                    if (r.audioUrl == null) return@let r to null
                    val f = File(app.cacheDir, "muse_voice/${r.id.ifBlank { "reply" }}.mp3")
                    val bytes = runCatching { museClient.downloadAudio(r.audioUrl, f) }
                        .onFailure { Log.w(TAG, "muse audio download failed: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrNull()
                    if (bytes != null) Log.i(TAG, "muse audio downloaded id=${r.id} bytes=$bytes voice=${r.voiceLabel}")
                    r to (if (bytes != null) f else null)
                }
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
                    Log.i(TAG, "no Muse reply within timeout -> device TTS notice")
                    val msg = "I sent that to Muse. No reply yet — it'll show here when Muse answers."
                    ui.onTurn("Broker", msg); speak(msg)
                } else {
                    val (r, file) = reply
                    if (file != null) {
                        ui.onTurn("Muse (${r.voiceLabel})", r.text)
                        playAudio(file, r.voiceLabel, r.text)
                    } else {
                        Log.i(TAG, "Muse reply without playable audio -> Android TTS fallback")
                        ui.onTurn("Muse (device voice fallback)", r.text); speak(r.text)
                    }
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

    private fun playAudio(file: File, label: String, fallbackText: String) {
        endAfterSpeech = false
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val vol = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
        if (vol == 0) Log.w(TAG, "media volume is 0 — Muse audio will be silent")
        ui.onState(State.SPEAKING, if (vol == 0) "Speaking: $label — media volume is 0, turn it up to hear it" else "Speaking: $label")
        ui.onVoiceLabel(label)
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.path)
                setOnPreparedListener {
                    Log.i(TAG, "playing $label durationMs=${it.duration}")
                    it.start()
                }
                setOnCompletionListener {
                    Log.i(TAG, "playback complete: $label")
                    lastPlayedLabel = label
                    main.post { afterSpeaking(label) }
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra -> Android TTS fallback")
                    main.post { speak(fallbackText) }; true
                }
                prepareAsync()
            }
        }.onFailure {
            Log.w(TAG, "MediaPlayer setup failed: ${it.javaClass.simpleName} -> Android TTS fallback")
            speak(fallbackText)
        }
    }

    private fun afterSpeaking(played: String? = null) {
        if (oneShot) {
            oneShot = false; active = false
            ui.onState(State.IDLE, if (played != null) "Played: $played ✓" else "Test turn done")
            return
        }
        if (endAfterSpeech) { endAfterSpeech = false; stop(); return }
        if (active) listen() else ui.onState(State.IDLE, "Mic off")
    }

    companion object {
        private const val TAG = "TalkController"
        /** Google TTS en-US male voice ids (offline first). */
        private val MALE_HINTS = listOf("en-us-x-iom", "en-us-x-iol", "en-us-x-tpd", "male")
        private val FEMALE_HINTS = listOf("female", "en-us-x-sfg", "en-us-x-tpf", "en-us-x-tpc", "en-us-x-iob")

        /** Prefer a male en-US voice for the fallback; null = keep the engine default. */
        fun pickMaleUsVoice(voices: List<Voice>): Voice? {
            val us = voices.filter { it.locale.country.equals("US", true) }
            fun score(v: Voice): Int {
                val n = v.name.lowercase()
                val f = v.features.orEmpty().joinToString(",").lowercase()
                if (FEMALE_HINTS.any { n.contains(it) || f.contains("gender=female") }) return -1
                val i = MALE_HINTS.indexOfFirst { n.contains(it) || f.contains("gender=male") }
                return if (i < 0) -1 else 100 - i * 10 + (if (n.endsWith("local")) 5 else 0)
            }
            return us.map { it to score(it) }.filter { it.second >= 0 }.maxByOrNull { it.second }?.first
        }
    }
}
