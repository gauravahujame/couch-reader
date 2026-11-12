package dev.couchreader.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** An installed offline voice. [name] is the engine's stable voice name; [label] is for people. */
class VoiceChoice(val name: String, val label: String)

/**
 * Lists the TV's installed offline voices for the Settings voice picker and plays a short sample
 * of each. Samples are recorded once in the background with synthesizeToFile and kept in the app
 * cache, so changing voice plays a file at once instead of waiting for the engine to load the
 * voice. A sample that is not recorded yet is spoken live. All methods run on the UI thread.
 */
class VoiceSamples(context: Context, private val onVoices: (List<VoiceChoice>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val directory = File(context.cacheDir, "voice-samples").apply { mkdirs() }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var tts: TextToSpeech? = null
    private var installed = mapOf<String, Voice>()
    private val pending = ArrayDeque<String>()
    private var recording: String? = null
    private var player: MediaPlayer? = null
    private var closed = false
    var voices = listOf<VoiceChoice>()
        private set
    var loaded = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status -> main.post { initialized(status) } }
    }

    private fun initialized(status: Int) {
        if (closed) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) { loaded = true; onVoices(emptyList()); return }
        engine.setAudioAttributes(attributes)
        val usable = runCatching { engine.voices }.getOrNull().orEmpty().filter { voice ->
            !voice.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features &&
                // Google lists a "<locale>-language" alias for each locale's default voice; it duplicates a real voice.
                !voice.name.endsWith("-language")
        }
        installed = usable.associateBy { it.name }
        voices = label(usable)
        loaded = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { main.post { recorded(id, true) } }
            @Deprecated("Deprecated in API 21") override fun onError(id: String) { main.post { recorded(id, false) } }
            override fun onError(id: String, errorCode: Int) { main.post { recorded(id, false) } }
            override fun onStop(id: String, interrupted: Boolean) { main.post { interrupted(id) } }
        })
        onVoices(voices)
        pending.addAll(voices.map { it.name })
        recordNext()
    }

    /** The TV's own language first, then languages and voices in a stable order, numbered per language. */
    private fun label(usable: List<Voice>): List<VoiceChoice> {
        val tvLanguage = Locale.getDefault().language
        val byLocale = usable.groupBy { it.locale }.toSortedMap(compareBy<Locale>({ it.language != tvLanguage }, { it.getDisplayName(Locale.getDefault()) }))
        return byLocale.flatMap { (locale, list) ->
            val sorted = list.sortedBy { it.name }
            sorted.mapIndexed { i, voice ->
                val language = locale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
                VoiceChoice(voice.name, if (sorted.size == 1) language else "$language · Voice ${i + 1} of ${sorted.size}")
            }
        }
    }

    private fun file(name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$SAMPLE_VERSION:$name".toByteArray())
        return File(directory, digest.take(12).joinToString("") { "%02x".format(it) } + ".wav")
    }

    private fun recordNext() {
        val engine = tts ?: return
        if (closed || recording != null) return
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            val target = file(name)
            if (target.length() > 0) continue
            val voice = installed[name] ?: continue
            recording = name
            engine.voice = voice
            engine.synthesizeToFile(sampleText(voice.locale), null, File("${target.path}.part"), "sample:$name")
            return
        }
    }

    private fun recorded(id: String, success: Boolean) {
        val name = id.removePrefix("sample:").takeIf { id.startsWith("sample:") } ?: run { recordNext(); return }
        if (name != recording) return
        recording = null
        val part = File("${file(name).path}.part")
        if (success && part.length() > 0) part.renameTo(file(name)) else part.delete()
        recordNext()
    }

    /** A live sample (or tts.stop) cut a recording short; put it back at the front of the queue. */
    private fun interrupted(id: String) {
        if (!id.startsWith("sample:")) return
        val name = id.removePrefix("sample:")
        if (name == recording) { recording = null; File("${file(name).path}.part").delete(); pending.addFirst(name) }
    }

    /** Plays the sample for voice [name], or for the TV's default voice when [name] is blank. */
    fun play(name: String) {
        if (closed) return
        stopPlayback()
        val engine = tts ?: return
        val voice = installed[name] ?: runCatching { engine.defaultVoice }.getOrNull()
        val recordedFile = voice?.let { file(it.name) }?.takeIf { it.length() > 0 }
        if (recordedFile != null) {
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(recordedFile.path)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    prepare(); start()
                }
            }.getOrNull()
            if (player != null) return
        }
        // Not recorded yet: speak it now. This interrupts any recording, which goes back in the queue.
        if (voice != null) engine.voice = voice
        engine.speak(sampleText(voice?.locale ?: Locale.getDefault()), TextToSpeech.QUEUE_FLUSH, null, "live")
    }

    fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }; player = null
        // Before onInit there is nothing to stop, and stop() would wait on the engine's setup lock.
        if (loaded && recording == null) tts?.stop()
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null
        recording?.let { File("${file(it).path}.part").delete() }
        tts?.releaseOffMainThread(); tts = null
    }

    private companion object {
        /** Bump when the sample text changes so old recordings are replaced. */
        const val SAMPLE_VERSION = 1
        fun sampleText(locale: Locale) = when (locale.language) {
            "hi" -> "आपकी किताबें इसी आवाज़ में पढ़ी जाएँगी।"
            else -> "This is how I will read your books aloud."
        }
    }
}
