package dev.couchreader.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Reads the open book aloud, one sentence at a time, with the platform text-to-speech engine.
 * Lives only while the reader is on screen. The highlight marks the current sentence whether or
 * not the voice is running, so the reader can choose where to start before pressing play.
 * All methods run on the UI thread. The owner forwards the engine's "page" and "boundary" events.
 */
class ReadAloud(context: Context, private val engine: ReaderEngine, rate: Float, private var voiceName: String,
                private val onState: (State) -> Unit, private val onNotice: (String) -> Unit,
                private val onSpeaking: () -> Unit) {
    enum class State { PREPARING, PAUSED, PLAYING }
    private enum class Select { DEFAULT, LAST, CONTINUE }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> main.post { if (change < 0 && !closed) pause() } }.build()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var closed = false
    private var playWhenReady = false
    var state = State.PREPARING
        private set

    private var sentences = listOf<SpeechSentence>()
    private var index = -1
    // Bumped whenever the sentence list is replaced or speech is restarted; stale callbacks are dropped.
    private var pageGeneration = 0
    private var speechGeneration = 0
    private var queuedUpTo = -1
    private var loading = false

    // Why the next "page" event happens, so the right sentence is chosen when it arrives.
    private var nextSelect: Select? = null
    private var turningForPlayback = false
    // The page turned while a sentence spanning the break was still being spoken.
    private var tailUtterance: String? = null
    private var emptyPagesSkipped = 0
    private var rate = rate

    init {
        tts = TextToSpeech(context.applicationContext) { status -> main.post { initialized(status) } }
    }

    private fun initialized(status: Int) {
        if (closed) return
        val voice = tts
        android.util.Log.i(TAG, "voice init status=$status")
        if (status != TextToSpeech.SUCCESS || voice == null) {
            ttsFailed = true; playWhenReady = false
            onNotice(NO_VOICE)
            setState(State.PAUSED); return
        }
        voice.setAudioAttributes(attributes)
        voice.setSpeechRate(rate)
        applyVoice(initial = true)
        voice.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { main.post { started(id) } }
            override fun onDone(id: String) { main.post { done(id) } }
            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) { main.post { reached(id, start) } }
            @Deprecated("Deprecated in API 21") override fun onError(id: String) { main.post { failed(id) } }
            override fun onError(id: String, errorCode: Int) { main.post { failed(id) } }
            override fun onStop(id: String, interrupted: Boolean) {}
        })
        ttsReady = true
        if (state == State.PREPARING) setState(State.PAUSED)
        if (playWhenReady) { playWhenReady = false; play() }
    }

    /** Shows the highlight on the current page, then starts the voice if [play]. */
    fun begin(play: Boolean) {
        playWhenReady = play
        load(Select.DEFAULT)
    }

    fun toggle() { if (state == State.PLAYING) pause() else play() }

    fun play() {
        if (closed) return
        if (ttsFailed) { onNotice(NO_VOICE); return }
        if (!ttsReady) { playWhenReady = true; return }
        if (loading) { playWhenReady = true; return }
        if (sentences.isEmpty()) { onNotice("This page has no text to read. Press → for the next page."); return }
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onNotice("Another app is using the speakers."); return
        }
        emptyPagesSkipped = 0
        setState(State.PLAYING)
        speakFrom(index.coerceIn(0, sentences.lastIndex))
    }

    fun pause() {
        playWhenReady = false
        if (state != State.PLAYING) return
        silence()
        audio.abandonAudioFocusRequest(focusRequest)
        setState(State.PAUSED)
    }

    /** Moves the highlight by one sentence, crossing to the neighbouring page at either end. */
    fun move(delta: Int) {
        if (closed || loading) return
        val target = index + delta
        if (sentences.isNotEmpty() && target in sentences.indices) {
            index = target; engine.highlight(index)
            if (state == State.PLAYING) restartSoon()
            return
        }
        silence()
        nextSelect = if (delta > 0) Select.DEFAULT else Select.LAST
        engine.turn(delta > 0)
    }

    /** Switches voice; a blank [name] means the TV's default. A running voice restarts the sentence in the new one. */
    fun setVoice(name: String) {
        voiceName = name
        if (!ttsReady) return
        applyVoice(initial = false)
        if (state == State.PLAYING) restartSoon()
    }

    /** A saved voice that is no longer installed falls back to the TV's default instead of failing. */
    private fun applyVoice(initial: Boolean) {
        val engine = tts ?: return
        val chosen = voiceName.takeIf { it.isNotBlank() }?.let { name -> runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == name } }
            ?.takeIf { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        when {
            chosen != null -> engine.voice = chosen
            !initial -> runCatching { engine.defaultVoice }.getOrNull()?.let { engine.voice = it }
        }
    }

    fun setRate(value: Float) { rate = value; tts?.setSpeechRate(value); if (state == State.PLAYING) restartSoon() }

    /** The engine showed a new page: after a page turn, a jump, or a reflow. */
    fun onPageShown() {
        if (closed) return
        val select = when {
            tailUtterance != null -> Select.CONTINUE
            else -> nextSelect ?: Select.DEFAULT
        }
        val automatic = turningForPlayback || tailUtterance != null || nextSelect != null
        nextSelect = null; turningForPlayback = false
        // A turn the reader made (Left/Right, Go to page, a chapter) restarts the voice on the new page.
        if (!automatic) silence()
        load(select)
    }

    /** The engine refused to turn past the first or last page. */
    fun onBoundary(end: Boolean) {
        nextSelect = null; turningForPlayback = false; tailUtterance = null
        if (state != State.PLAYING) return
        // Past the last page the book is finished; before the first, the voice picks up where it was.
        if (end) { silence(); audio.abandonAudioFocusRequest(focusRequest); setState(State.PAUSED) } else speakFrom(index)
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        audio.abandonAudioFocusRequest(focusRequest)
        tts?.stop(); tts?.shutdown(); tts = null
        engine.highlight(-1)
    }

    private fun load(select: Select) {
        val generation = ++pageGeneration
        loading = true; queuedUpTo = -1
        engine.sentences { list ->
            if (closed || generation != pageGeneration) return@sentences
            loading = false
            sentences = list
            val firstNew = list.indexOfFirst { !it.continued }.takeIf { it >= 0 } ?: 0
            index = if (list.isEmpty()) -1 else when (select) {
                Select.DEFAULT -> firstNew
                Select.LAST -> list.lastIndex
                Select.CONTINUE -> 0
            }
            engine.highlight(index)
            when {
                list.isEmpty() && (state == State.PLAYING || playWhenReady) -> skipEmptyPage()
                list.isEmpty() -> onNotice("This page has no text to read.")
                select == Select.CONTINUE && state == State.PLAYING -> {
                    // The voice is still finishing the sentence that ran onto this page.
                    if (tailUtterance == null) speakFrom(index + 1) else { queuedUpTo = index; queue(index + 1) }
                }
                state == State.PLAYING -> speakFrom(index)
                playWhenReady -> { playWhenReady = false; play() }
            }
        }
    }

    private fun skipEmptyPage() {
        tailUtterance = null
        if (++emptyPagesSkipped > 5) {
            emptyPagesSkipped = 0; playWhenReady = false
            if (state == State.PLAYING) { silence(); audio.abandonAudioFocusRequest(focusRequest); setState(State.PAUSED) }
            onNotice("No readable text on the next few pages. Read-aloud is paused.")
            return
        }
        turningForPlayback = true
        engine.turn(true)
    }

    private val restart = Runnable { if (state == State.PLAYING && !loading) speakFrom(index) }
    /** Lets a held Up/Down key settle before the voice restarts. */
    private fun restartSoon() { silence(); main.postDelayed(restart, 350) }

    private fun silence() {
        main.removeCallbacks(restart)
        speechGeneration++; tailUtterance = null; queuedUpTo = -1
        tts?.stop()
    }

    private fun id(position: Int) = "$speechGeneration:$pageGeneration:$position"
    private fun parse(id: String): Int? {
        val parts = id.split(':')
        if (parts.size != 3 || parts[0].toIntOrNull() != speechGeneration || parts[1].toIntOrNull() != pageGeneration) return null
        return parts[2].toIntOrNull()
    }

    private fun speakFrom(position: Int) {
        if (position !in sentences.indices) { endOfPage(); return }
        speechGeneration++; tailUtterance = null
        val voice = tts ?: return
        voice.speak(sentences[position].text, TextToSpeech.QUEUE_FLUSH, null, id(position))
        queuedUpTo = position
        queue(position + 1)
    }

    /** Keeps one sentence queued behind the one being spoken, so there is no gap between them. */
    private fun queue(position: Int) {
        if (position <= queuedUpTo || position !in sentences.indices) return
        tts?.speak(sentences[position].text, TextToSpeech.QUEUE_ADD, null, id(position))
        queuedUpTo = position
    }

    private fun started(id: String) {
        val position = parse(id) ?: return
        if (state != State.PLAYING) return
        index = position; engine.highlight(position); onSpeaking()
        queue(position + 1)
    }

    private fun reached(id: String, start: Int) {
        val position = parse(id) ?: return
        val sentence = sentences.getOrNull(position) ?: return
        // Turn the page as soon as the voice reaches words that are not on this one.
        if (state == State.PLAYING && position == sentences.lastIndex && tailUtterance == null && nextSelect == null &&
            sentence.visibleLength < sentence.text.length && start >= sentence.visibleLength) {
            tailUtterance = id
            engine.turn(true)
        }
    }

    private fun done(id: String) {
        if (id == tailUtterance) {
            tailUtterance = null
            // The next page's sentences are not in yet; load() starts them when they arrive.
            if (!loading && state == State.PLAYING && queuedUpTo <= index) speakFrom(index + 1)
            return
        }
        val position = parse(id) ?: return
        if (state == State.PLAYING && position == sentences.lastIndex) endOfPage()
    }

    private fun endOfPage() {
        if (state != State.PLAYING) return
        turningForPlayback = true
        engine.turn(true)
    }

    private fun failed(id: String) {
        if (parse(id) == null && id != tailUtterance) return
        onNotice("The voice stopped unexpectedly. Press OK to try again.")
        pause()
    }

    // Diagnostics record state and sentence index only, never book text.
    private fun setState(value: State) { state = value; android.util.Log.i(TAG, "state=$value index=$index of ${sentences.size}"); onState(value) }

    private companion object {
        const val TAG = "CouchReadAloud"
        const val NO_VOICE = "No text-to-speech voice is available. Install or enable one in the TV’s settings."
    }
}
