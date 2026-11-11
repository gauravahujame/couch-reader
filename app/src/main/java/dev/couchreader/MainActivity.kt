package dev.couchreader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dev.couchreader.data.*
import dev.couchreader.importer.*
import dev.couchreader.reader.*
import dev.couchreader.ui.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var db: LibraryDatabase
    private lateinit var books: BookRepository
    private lateinit var positions: PositionRepository
    private lateinit var progress: ProgressRepository
    private var readerProgress = ReadingProgress(null, "Loading chapter…")
    private val liveProgress = mutableMapOf<String, ReadingProgress>()
    private val indexQueue = java.util.ArrayDeque<Book>()
    private val indexing = mutableSetOf<String>()
    private val failedIndexes = mutableSetOf<String>()
    private var indexEngine: EpubReaderEngine? = null
    private lateinit var preferences: PreferencesRepository
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    internal var screen = "library"
        private set
    private var selectedBook: String? = null
    internal var engine: ReaderEngine? = null
        private set
    private var currentBook: Book? = null
    private var chapters = listOf<Pair<String, String>>()
    private var overlay: View? = null
    private var server: UploadServer? = null
    private var pairingGeneration = 0
    private var readerGeneration = 0
    private var onPairingTick: ((Boolean) -> Unit)? = null
    internal var ready = false
        private set
    private var settingsView: View? = null
    private var settingsFromReader = false
    private var pendingReaderError: String? = null
    private var restoreSettingsFocus = false
    internal val sleepTimer = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun resetSleepTimer() {
        main.removeCallbacks(sleepTimer)
        if (screen != "reader" && !(screen == "settings" && settingsFromReader)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val minutes = preferences.get().sleepMinutes
        if (minutes > 0) main.postDelayed(sleepTimer, minutes * 60_000L)
    }
    // Pages under the open overlay, with the focusability to restore when it closes.
    private var overlayBlocked: List<Pair<ViewGroup, Int>> = emptyList()
    private var overlayReturnFocus: View? = null
    private val timer = object : Runnable {
        override fun run() {
            val upload = server ?: return
            val refreshed = upload.refreshIfExpired()
            onPairingTick?.invoke(refreshed)
            main.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        db = LibraryDatabase(this); books = BookRepository(this, db)
        positions = PositionRepository(db); progress = ProgressRepository(db); preferences = PreferencesRepository(this)
        root = FrameLayout(this); setContentView(root)
        loading("Couch Reader", "Preparing your library")
        io.execute {
            books.reconcile()
            books.refreshMetadata()
            main.post { if (!isDestroyed) library() }
        }
    }
    /** Replaces the screen with a [LoadingScreen]; tagged "loading" so reader events can dismiss it. */
    private fun loading(eyebrow: String, message: String, hero: View? = null, detail: String? = null): LoadingScreen {
        root.removeAllViews()
        root.setBackgroundColor(Palette.paper)
        return LoadingScreen(this, eyebrow, message, hero, detail).also {
            it.tag = "loading"
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
    }
    private fun dismissLoading() { (root.findViewWithTag<View>("loading") as? LoadingScreen)?.dismiss() }
    private fun page(): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(Palette.paper)
        return column().apply {
            val horizontal = (resources.displayMetrics.widthPixels * .06).toInt()
            val vertical = (resources.displayMetrics.heightPixels * .055).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
    }
    private fun masthead(parent: LinearLayout, right: View? = null) {
        val row = row()
        row.addView(label("▤  COUCH READER", 15f).apply { letterSpacing = .13f; typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        right?.let { row.addView(it) }
        parent.addView(row)
        parent.space(20)
    }
    private fun library() {
        stopPairing(); closeReader(); screen = "library"
        val content = page()
        val catalog = books.books()
        val progressByBook = progress.all() + liveProgress
        // An empty library keeps a labelled call to action in the body; otherwise "Add book" is a header icon.
        val add = if (catalog.isEmpty()) action("＋  Add book", primary = true) { showImport() }
            else iconAction(R.drawable.ic_add, "Add book", primary = true) { showImport() }
        val headerActions = row()
        val settingsButton = iconAction(R.drawable.ic_settings, "Settings") { settings() }
        headerActions.addView(settingsButton)
        if (catalog.isNotEmpty()) { headerActions.space(10); headerActions.addView(add) }
        masthead(content, headerActions)
        if (catalog.isEmpty()) {
            val center = row().apply { gravity = Gravity.CENTER }
            val illustration = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(30), dp(24), dp(30), dp(24))
                background = shape(Color.rgb(226, 231, 214), 100f)
                addView(label("A place for\nyour next\nchapter.", 33f, serif = true).apply { gravity = Gravity.CENTER })
                space(20)
                addView(BookUnderline(this@MainActivity), LinearLayout.LayoutParams(dp(170), dp(18)))
            }
            center.addView(illustration, LinearLayout.LayoutParams(dp(300), dp(280)))
            center.space(36)
            val intro = column()
            intro.addView(label("Make yourself\ncomfortable.", 42f, serif = true))
            intro.space(12)
            intro.addView(label("Your books. A bigger screen.\nA little time to get lost in a story.", 19f, Palette.muted))
            intro.space(22)
            intro.addView(add, LinearLayout.LayoutParams(-2, -2))
            center.addView(intro, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
            content.addView(label("01   ADD A BOOK     /     02   SETTLE IN     /     03   READ AT YOUR OWN PACE", 12f, Palette.muted).apply { letterSpacing = .1f; gravity = Gravity.CENTER })
            (if (restoreSettingsFocus) settingsButton else add).requestFocus(); restoreSettingsFocus = false
            return
        }
        // One compact header row: title and count on the left, "Continue reading" on the right.
        // Keeping the shortcut out of its own full-width row leaves the vertical space to the cards.
        val heading = row()
        val titleGroup = row().apply { gravity = Gravity.BOTTOM }
        titleGroup.addView(label("Your library", 34f, serif = true)); titleGroup.space(14)
        titleGroup.addView(label("${catalog.size} ${if (catalog.size == 1) "book" else "books"} · On this device", 15f, Palette.muted).apply { maxLines = 1 })
        heading.addView(titleGroup, LinearLayout.LayoutParams(0, -2, 1f))
        val recent = catalog.filter { it.opened > 0 }.maxByOrNull { it.opened }
        recent?.let { book ->
            heading.space(16)
            heading.addView(action("▶   Continue  ·  ${book.title}") { open(book) }.apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * .38).toInt()
                contentDescription = "Continue reading ${book.title}"
            })
        }
        content.addView(heading); content.space(14)
        // The parent column draws children unclipped (for focus shadows), so the scroller clips itself.
        val scroll = ScrollView(this).apply {
            isFillViewport = true; clipToPadding = false
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS; clipToOutline = true
        }
        val grid = column()
        // Column count follows the usable width so cards keep a readable size on any TV density.
        val gap = dp(14)
        val usableWidth = resources.displayMetrics.widthPixels * .88 - dp(8)
        val columns = (usableWidth / dp(250)).toInt().coerceIn(2, 5)
        val cardWidth = ((usableWidth - gap * (columns - 1)) / columns).toInt()
        val idealCover = (cardWidth * .62).toInt().coerceIn(dp(120), dp(190))
        val covers = mutableListOf<View>()
        val coverTitles = mutableListOf<TextView>()
        var restore: View? = null
        var recentRead: View? = null
        var firstRead: View? = null
        catalog.chunked(columns).forEach { group ->
            val line = row().apply { gravity = Gravity.TOP }
            group.forEachIndexed { index, book ->
                if (index > 0) line.space(14)
                val card = column().apply {
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = shape(Palette.white, border = Palette.line, stroke = dp(1))
                }
                val cover = BookCover(this, book.title, book.author, book.format, CoverTones.forKey(book.id))
                covers += cover; coverTitles += cover.title
                card.addView(cover, LinearLayout.LayoutParams(-1, idealCover))
                card.space(10)
                val saved = progressByBook[book.id]
                val indicator = row().apply { tag = "progress-${book.id}"; minimumHeight = dp(18) }
                fillProgress(indicator, saved ?: ReadingProgress(if (book.opened == 0L) 0.0 else null, ""), if (book.format == "pdf") "" else "Calculating…")
                card.addView(indicator, LinearLayout.LayoutParams(-1, -2))
                card.space(10)
                // Primary action and a compact overflow share one line, so every card shows both without scrolling.
                val buttons = row()
                val read = action(if (book.opened > 0) "Continue" else "Start reading") { open(book) }.apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = if (book.author.isBlank()) "Read ${book.title}" else "Read ${book.title} by ${book.author}"
                    setOnFocusChangeListener { _, focused ->
                        if (focused) selectedBook = book.id
                        background = shape(if (focused) Palette.green else Palette.white, border = if (focused) Palette.gold else Palette.line, stroke = dp(if (focused) 3 else 1))
                        setTextColor(if (focused) Color.WHITE else Palette.ink)
                        elevation = if (focused) dp(6).toFloat() else 0f
                    }
                }
                buttons.addView(read, LinearLayout.LayoutParams(0, -2, 1f))
                buttons.space(8)
                buttons.addView(iconAction(R.drawable.ic_more, "Book actions for ${book.title}") { bookActions(book) })
                card.addView(buttons, LinearLayout.LayoutParams(-1, -2))
                if (selectedBook == book.id) restore = read
                if (book.id == recent?.id) recentRead = read
                if (firstRead == null) firstRead = read
                line.addView(card, LinearLayout.LayoutParams(cardWidth, -2))
            }
            grid.addView(line); grid.space(14)
        }
        // Shrink covers (never the controls) until a full row of cards fits the visible area.
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val firstRow = grid.getChildAt(0) ?: return@addOnLayoutChangeListener
            val current = covers.firstOrNull()?.layoutParams?.height ?: return@addOnLayoutChangeListener
            if (firstRow.height == 0) return@addOnLayoutChangeListener
            val target = (bottom - top - (firstRow.height - current) - dp(4)).coerceIn(dp(96), idealCover)
            if (target == current) return@addOnLayoutChangeListener
            scroll.post {
                covers.forEach { it.layoutParams = it.layoutParams.apply { height = target } }
                coverTitles.forEach { it.maxLines = if (target < dp(140)) 2 else 3 }
            }
        }
        scroll.addView(grid); content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.space(8)
        content.addView(label("YOUR OWN BOOKS. YOUR OWN PACE.                                      D-PAD  Browse    ·    OK  Open", 11f, Palette.muted).apply { letterSpacing = .08f })
        // Default focus lands on the most recently read book, so OK resumes it straight away.
        (if (restoreSettingsFocus) settingsButton else restore ?: recentRead ?: firstRead ?: add).requestFocus(); restoreSettingsFocus = false
        catalog.filter { it.format == "epub" && it.opened > 0 && progressByBook[it.id]?.fraction == null && it.id !in failedIndexes }.forEach(::queueIndex)
    }
    private fun progressText(value: ReadingProgress): String {
        val percent = value.fraction?.let { "${kotlin.math.round(it * 100).toInt()}% read" } ?: if (currentBook?.id in failedIndexes) "Progress unavailable" else "Calculating progress…"
        return if (value.chapter.isBlank()) percent else "${value.chapter} · $percent"
    }
    /** The opening screen's line for a returning reader: where they left off and how far in they are. */
    private fun openingDetail(value: ReadingProgress): String {
        val percent = value.fraction?.let { "${kotlin.math.round(it * 100).toInt()}% read" }
        return listOfNotNull(value.chapter.takeIf { it.isNotBlank() }, percent).joinToString("  ·  ").ifEmpty { "Picking up where you left off" }
    }
    /** Renders a card's progress as one line: the bar, then the percentage (or a short status) to its right. */
    private fun fillProgress(parent: LinearLayout, value: ReadingProgress, pendingMessage: String = "Calculating…") {
        parent.removeAllViews()
        parent.orientation = LinearLayout.HORIZONTAL; parent.gravity = Gravity.CENTER_VERTICAL
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = ((value.fraction ?: 0.0) * 1000).toInt()
            isIndeterminate = false; isFocusable = false
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.green)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.line)
            contentDescription = value.fraction?.let { "${kotlin.math.round(it * 100).toInt()} percent read" } ?: "Progress not calculated yet"
        }
        parent.addView(bar, LinearLayout.LayoutParams(0, dp(6), 1f))
        val unknown = if (parent.tag.toString().removePrefix("progress-") in failedIndexes) "Unavailable" else pendingMessage
        val text = value.fraction?.let { "${kotlin.math.round(it * 100).toInt()}%" } ?: unknown
        if (text.isNotEmpty()) {
            parent.space(10)
            parent.addView(label(text, 13f, Palette.muted).apply { maxLines = 1; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        }
    }
    private fun queueIndex(book: Book) {
        if (!indexing.add(book.id)) return
        failedIndexes.remove(book.id)
        indexQueue.addLast(book)
        startNextIndex()
    }
    private fun startNextIndex() {
        if (indexEngine != null || indexQueue.isEmpty() || isDestroyed) return
        val book = indexQueue.removeFirst()
        fun finish() {
            indexing.remove(book.id)
            indexEngine?.close(); indexEngine = null
            startNextIndex()
        }
        indexEngine = EpubReaderEngine(this, books, book, null, preferences.get(), indexOnly = true) { type, payload ->
            when (type) {
                "indexed" -> {
                    if (currentBook?.id == book.id && engine is EpubReaderEngine) {
                        (engine as EpubReaderEngine).refreshProgress(); finish()
                    } else io.execute {
                        val locator = positions.get(book.id)
                        main.post { if (!isDestroyed) indexEngine?.progressAt(locator) }
                    }
                }
                "readingInfo" -> {
                    if (currentBook?.id == book.id && engine is EpubReaderEngine) {
                        (engine as EpubReaderEngine).refreshProgress(); finish()
                        return@EpubReaderEngine
                    }
                    val data = JSONObject(payload)
                    val value = ReadingProgress(if (data.isNull("fraction")) null else data.getDouble("fraction"), data.optString("chapter"))
                    liveProgress[book.id] = value
                    io.execute {
                        if (books.books().any { it.id == book.id }) progress.save(book.id, value)
                    }
                    root.findViewWithTag<LinearLayout>("progress-${book.id}")?.let { fillProgress(it, value) }
                    finish()
                }
                "indexError", "error" -> {
                    failedIndexes.add(book.id); indexing.remove(book.id)
                    if (currentBook?.id == book.id) root.findViewWithTag<TextView>("reader-progress")?.text = progressText(readerProgress)
                    root.findViewWithTag<LinearLayout>("progress-${book.id}")?.let { fillProgress(it, ReadingProgress(null, "")) }
                    finish()
                }
            }
        }
    }
    private fun bookActions(book: Book) {
        overlayReturnFocus = currentFocus
        val panel = overlayPanel(book.title)
        if (book.author.isNotBlank()) {
            panel.addView(label(book.author, 17f, Palette.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            panel.space(16)
        } else panel.space(4)
        val read = action(if (book.opened > 0) "Continue reading" else "Start reading", true) { open(book) }
        panel.addView(read, LinearLayout.LayoutParams(-1, -2)); panel.space(8)
        panel.addView(action("Remove from device") { confirmRemove(book) }, LinearLayout.LayoutParams(-1, -2)); panel.space(8)
        panel.addView(action("Back to library") { hideOverlay() }, LinearLayout.LayoutParams(-1, -2))
        read.requestFocus()
    }
    private fun confirmRemove(book: Book) {
        val panel = overlayPanel("Remove from device?")
        panel.addView(label("“${BookMetadata.ellipsize(book.title, 90)}” and its saved reading position will be removed. You can import the book again later.", 18f, Palette.muted)); panel.space(16)
        val buttons = row()
        val keep = action("Keep book", true) { hideOverlay() }
        buttons.addView(keep); buttons.space(10)
        buttons.addView(action("Remove") {
            loading("Tidying the shelf", "Removing ${book.title}")
            io.execute { books.remove(book); main.post { library() } }
        })
        panel.addView(buttons); keep.requestFocus()
    }
    private fun showImport() {
        closeReader(); stopPairing(); screen = "import"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val generation = ++pairingGeneration
        val content = page()
        val back = iconAction(R.drawable.ic_back, "Back to library") { library() }
        masthead(content, back)
        val body = row()
        val instructions = column()
        instructions.addView(label("A new story\nis a scan away.", 40f, serif = true)); instructions.space(16)
        instructions.addView(label("1   Connect your phone or computer to the same Wi-Fi.\n2   Scan the code or open the address in a browser.\n3   Choose one EPUB or unlocked PDF, up to 50 MiB.", 18f, Palette.muted))
        instructions.space(16)
        val status = label("Finding your local network…", 17f)
        instructions.addView(status)
        val actions = row(); instructions.space(14); instructions.addView(actions)
        val instructionScroll = ScrollView(this).apply { addView(instructions) }
        body.addView(instructionScroll, LinearLayout.LayoutParams(0, -1, 1f)); body.space(30)
        val codePanel = column().apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)); background = shape(Palette.white) }
        val qr = SquareQrView(this).apply { contentDescription = "Scan to add a book from your phone" }
        codePanel.addView(qr, LinearLayout.LayoutParams(dp(184), dp(184)))
        val expiry = label("Private, local transfer", 13f, Palette.muted)
        codePanel.addView(expiry)
        body.addView(codePanel, LinearLayout.LayoutParams(dp(230), -2))
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val addressLabel = label("", 16f).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setTextIsSelectable(false) }
        content.addView(addressLabel)
        content.space(6)
        content.addView(label("Keep this screen open. Trusted home networks only; transfers use local HTTP.\nIf devices cannot connect, check Wi-Fi settings. Guest networks may block device-to-device traffic.", 12f, Palette.muted))
        fun retry() { showImport() }
        val address = UploadServer.localAddress()
        if (address == null) {
            status.text = "No local network found. Connect your TV to Wi-Fi or Ethernet, then retry."
            qr.visibility = View.GONE
            actions.addView(action("Retry network", true, ::retry)); actions.getChildAt(0).requestFocus(); return
        }
        try {
            val upload = UploadServer(books, ImportBook(books)) { message, result -> main.post {
                if (screen != "import" || pairingGeneration != generation) return@post
                status.text = message
                if (result != null) {
                    actions.removeAllViews()
                    actions.addView(action("Read now", true) { open(result.book) })
                    actions.space(10); actions.addView(action("Back to library") { library() })
                    actions.getChildAt(0).requestFocus()
                }
            } }
            upload.start(30000, true); server = upload
            fun renderCode() {
                val url = "http://$address:${upload.listeningPort}/?token=${upload.token}"
                val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 400, 400)
                val pixels = IntArray(400 * 400) { i -> if (matrix[i % 400, i / 400]) Color.BLACK else Color.WHITE }
                qr.setImageBitmap(Bitmap.createBitmap(pixels, 400, 400, Bitmap.Config.ARGB_8888))
                addressLabel.text = url
            }
            onPairingTick = { refreshed ->
                if (refreshed) renderCode()
                val seconds = upload.remainingSeconds()
                expiry.text = "Refreshes in %d:%02d".format(seconds / 60, seconds % 60)
            }
            renderCode(); onPairingTick?.invoke(false)
            status.text = "Ready for your book. The code refreshes automatically every 10 minutes."
            actions.addView(action("Generate new code") {
                if (upload.refreshPairing()) {
                    renderCode(); onPairingTick?.invoke(false)
                    status.text = "New code ready. Scan it to connect your phone."
                } else notice("Please wait for the current upload to finish.")
            })
            main.postDelayed(timer, 1000)
            back.requestFocus()
        } catch (_: Exception) {
            stopPairing(); status.text = "Could not start local transfer. Check your network and retry."
            actions.addView(action("Retry", true, ::retry)); actions.getChildAt(0).requestFocus()
        }
    }
    private fun stopPairing() { main.removeCallbacks(timer); onPairingTick = null; server?.stop(); server = null; pairingGeneration++ }
    internal fun open(book: Book) {
        stopPairing(); closeReader(); screen = "reader"; currentBook = book; selectedBook = book.id
        val generation = readerGeneration
        ready = false; chapters = emptyList(); readerProgress = ReadingProgress(null, "Loading chapter…")
        // The opening screen shows the same cover as the library card, so the book appears to lift off the shelf.
        val cover = BookCover(this, book.title, book.author, book.format, CoverTones.forKey(book.id), titleSize = 20f).apply {
            elevation = dp(14).toFloat(); title.maxLines = 4
        }
        val returning = book.opened > 0
        val opening = loading(if (returning) "Welcome back" else "Now opening", book.title,
            hero = FrameLayout(this).apply { clipChildren = false; addView(cover, FrameLayout.LayoutParams(dp(150), dp(214))) },
            detail = book.author.takeIf { it.isNotBlank() }?.let { "by $it" })
        val live = liveProgress[book.id]
        io.execute {
            val position = positions.get(book.id)
            val unavailablePosition = position == null && positions.hasSavedPosition(book.id)
            val saved = if (returning) live ?: progress.all()[book.id] else null
            books.opened(book)
            main.post {
                if (screen != "reader" || currentBook?.id != book.id || generation != readerGeneration || isDestroyed) return@post
                saved?.let { opening.setDetail(openingDetail(it)) }
                val eventHandler: (String, String) -> Unit = event@ { type, payload ->
                    if ((screen != "reader" && !(screen == "settings" && settingsFromReader)) || currentBook?.id != book.id || generation != readerGeneration) return@event
                    when (type) {
                        "ready" -> { if (book.format == "epub" && !JSONObject(payload).optBoolean("indexed")) queueIndex(book); ready = true; dismissLoading(); if (screen == "reader") root.requestFocus(); notice(if (unavailablePosition) "Saved position unavailable. Opened the beginning of the book." else if (book.format == "pdf") "← / →  Pages     ↑ / ↓  Scroll     OK  Controls" else "←  Previous page     →  Next page     OK  Reading controls") }
                        "chapters" -> { val list = JSONArray(payload); chapters = (0 until list.length()).map { list.getJSONObject(it).let { item -> item.getString("title") to item.getString("href") } } }
                        "readingInfo" -> {
                            val data = JSONObject(payload)
                            val value = ReadingProgress(if (data.isNull("fraction")) null else data.getDouble("fraction"), data.optString("chapter", "Chapter"))
                            readerProgress = value
                            liveProgress[book.id] = value
                            root.findViewWithTag<TextView>("reader-progress")?.text = progressText(value)
                            io.execute { progress.save(book.id, value) }
                        }
                        "location" -> {
                            val data = JSONObject(payload)
                            val locator = if (book.format == "pdf") Locator.decode(payload) ?: return@event
                                else Locator(data.getString("cfi"), data.getString("href"))
                            io.execute { positions.save(book.id, locator) }
                        }
                        "boundary" -> if (screen == "reader") { if (JSONObject(payload).optBoolean("end")) finishedBook() else notice("You’re at the beginning of this book.") }
                        "notice" -> notice(JSONObject(payload).getString("message"))
                        "error" -> { ready = true; dismissLoading(); val message = JSONObject(payload).getString("message")
                            if (screen == "settings") pendingReaderError = message
                            else { notice(message); controls() } }
                    }
                }
                val renderer: ReaderEngine = if (book.format == "pdf")
                    PdfReaderEngine(this, books, book, position, preferences.get(), eventHandler)
                else EpubReaderEngine(this, books, book, position, preferences.get(), onEvent = eventHandler)
                engine = renderer
                // The renderer prepares underneath the opening screen, which stays put and keeps animating.
                for (i in root.childCount - 1 downTo 0) if (root.getChildAt(i) !== opening) root.removeViewAt(i)
                root.addView(renderer.view, 0, FrameLayout.LayoutParams(-1, -1))
                if (opening.parent == null) root.addView(opening, FrameLayout.LayoutParams(-1, -1))
                root.isFocusableInTouchMode = true; root.requestFocus()
                resetSleepTimer()
            }
        }
    }
    private fun closeReader() {
        main.removeCallbacks(sleepTimer)
        pendingReaderError = null
        readerGeneration++
        overlay = null; overlayBlocked = emptyList(); overlayReturnFocus = null; currentBook = null
        engine?.let { root.removeView(it.view); it.close() }; engine = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun overlayPanel(title: String): LinearLayout {
        overlay?.let { root.removeView(it) }
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xb3202822.toInt()); isFocusable = false }
        // Book titles can run to 300 characters; three lines keep the actions in view.
        val heading = label(title, 29f, serif = true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        val panel = column().apply { setPadding(dp(30), dp(22), dp(30), dp(22)); background = shape(Palette.paper, dp(18).toFloat()); addView(heading); space(14) }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(panel) }
        val margin = (resources.displayMetrics.heightPixels * .07).toInt()
        scrim.addView(scroll, FrameLayout.LayoutParams(dp(560).coerceAtMost((resources.displayMetrics.widthPixels * .86).toInt()), -2, Gravity.CENTER).apply { topMargin = margin; bottomMargin = margin })
        // D-pad focus search ignores z-order, so block the page underneath or focus escapes the panel.
        if (overlayBlocked.isEmpty()) overlayBlocked = (0 until root.childCount).map { root.getChildAt(it) }
            .filterIsInstance<ViewGroup>().filter { it.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS }
            .map { it to it.descendantFocusability }
            .onEach { (view, _) -> view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        // The scrim fades in only when an overlay first opens; switching panels just lifts the new one in.
        if (overlay == null) { scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(180).start() }
        scroll.alpha = 0f; scroll.translationY = dp(14).toFloat()
        scroll.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        overlay = scrim; root.addView(scrim, FrameLayout.LayoutParams(-1, -1)); return panel
    }
    private fun hideOverlay() {
        overlay?.let { root.removeView(it) }; overlay = null
        overlayBlocked.forEach { (view, original) -> view.descendantFocusability = original }; overlayBlocked = emptyList()
        val target = overlayReturnFocus; overlayReturnFocus = null
        if (target?.isAttachedToWindow == true) target.requestFocus() else root.requestFocus()
    }
    private fun controls() {
        if (engine == null) return
        val panel = overlayPanel("Make yourself comfortable")
        panel.addView(label(currentBook?.title.orEmpty(), 16f, Palette.muted)); panel.space(8)
        panel.addView(label(progressText(readerProgress), 17f, Palette.muted).apply { tag = "reader-progress" })
        panel.space(12)
        var pref = preferences.get()
        val isPdf = currentBook?.format == "pdf"
        val sizeLabel = label("Text size  ·  ${ReadingPreferences.sizes[pref.size]} px", 17f)
        if (!isPdf) panel.addView(sizeLabel)
        else panel.addView(label("PDF · fixed page layout. Use ↑ / ↓ to scroll, ← / → for pages.", 17f, Palette.muted))
        val sizes = row()
        fun update(value: ReadingPreferences) { pref = value; preferences.save(pref); engine?.preferences(pref); sizeLabel.text = "Text size  ·  ${ReadingPreferences.sizes[pref.size]} px" }
        sizes.addView(action("A−  Smaller") { update(pref.copy(size = (pref.size - 1).coerceAtLeast(0))) })
        sizes.space(10); sizes.addView(action("A＋  Larger") { update(pref.copy(size = (pref.size + 1).coerceAtMost(5))) })
        if (!isPdf) panel.addView(sizes)
        panel.space(10)
        val themes = row()
        lateinit var light: ChoiceButton; lateinit var dark: ChoiceButton
        fun theme(isDark: Boolean) { update(pref.copy(dark = isDark)); light.refresh(); dark.refresh() }
        light = ChoiceButton(this, "Warm light", { !pref.dark }) { theme(false) }
        dark = ChoiceButton(this, "Dark", { pref.dark }) { theme(true) }
        themes.addView(light); themes.space(10); themes.addView(dark); panel.addView(themes); panel.space(10)
        if (!isPdf) panel.addView(action("Chapters   →") { chapterList() }, LinearLayout.LayoutParams(-1, -2))
        else panel.addView(action("Go to page   →") { pageList() }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(action("All settings →") { settings() }, LinearLayout.LayoutParams(-1, -2))
        panel.space(10)
        val resume = action("Back to reading", true) { hideOverlay() }
        panel.addView(resume, LinearLayout.LayoutParams(-1, -2)); resume.requestFocus()
    }
    private fun leaveSettings() {
        settingsView?.let { root.removeView(it) }; settingsView = null
        if (settingsFromReader) {
            screen = "reader"; root.requestFocus()
            pendingReaderError?.let { pendingReaderError = null; notice(it); controls() }
        } else { restoreSettingsFocus = true; library() }
    }
    internal fun settings() {
        settingsFromReader = screen == "reader"
        hideOverlay(); screen = "settings"
        if (!settingsFromReader) root.removeAllViews()
        // Keep the renderer measured and alive underneath the opaque settings page.
        val content = column().apply {
            setBackgroundColor(Palette.paper)
            val x = (resources.displayMetrics.widthPixels * .05).toInt()
            val y = (resources.displayMetrics.heightPixels * .05).toInt()
            setPadding(x, y, x, y)
            isFocusableInTouchMode = false
        }
        settingsView = content; root.addView(content, FrameLayout.LayoutParams(-1, -1))
        masthead(content, iconAction(R.drawable.ic_back, if (settingsFromReader) "Back to reading" else "Back to library") { leaveSettings() })
        content.addView(label("Settings", 32f, serif = true)); content.space(10)
        val description = label("", 15f, Palette.muted).apply { minHeight = dp(42) }
        val rows = column()
        // The parent column draws children unclipped (for focus shadows), so the scroller clips itself;
        // otherwise scrolled rows paint over the title above and the description below.
        val scroll = ScrollView(this).apply {
            addView(rows)
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS; clipToOutline = true
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.space(8); content.addView(description)
        var pref = preferences.get()
        fun save(value: ReadingPreferences) {
            val renderChanged = value.size != pref.size || value.spread != pref.spread || value.dark != pref.dark
            pref = value; preferences.save(value)
            if (renderChanged) engine?.preferences(value)
            resetSleepTimer()
        }
        fun heading(text: String) { rows.addView(label(text, 19f, serif = true)); rows.space(5) }
        fun option(title: String, explanation: String, value: () -> String, change: (Int) -> Unit): OptionRow {
            val row = OptionRow(this, title, value, change) { description.text = explanation }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2)); rows.space(8)
            return row
        }
        heading("Text")
        val first = option("Text size · Applies to EPUB", "Changes EPUB text across all books. PDF pages keep their printed layout.",
            { "${ReadingPreferences.sizes[pref.size]} px" }) { save(pref.copy(size = (pref.size + it).coerceIn(0, 5))) }
        heading("Page")
        option("Two-page spread · Applies to EPUB", "Shows two pages on wide screens, with space between them.",
            { if (pref.spread) "On" else "Off" }) { save(pref.copy(spread = !pref.spread)) }
        heading("Screen")
        option("Let the TV sleep after no key press", "Releases the screen after this idle time. Your TV controls when its screensaver or sleep starts.",
            { if (pref.sleepMinutes == 0) "Never" else "${pref.sleepMinutes} min" }) {
            val options = ReadingPreferences.sleepOptions
            save(pref.copy(sleepMinutes = options[(options.indexOf(pref.sleepMinutes) + it).coerceIn(0, options.lastIndex)]))
        }
        heading("About")
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName?.takeIf { it.isNotBlank() } ?: "Unknown"
        val about = column().apply {
            isFocusable = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = shape(Palette.white, border = Palette.line, stroke = dp(1))
            setOnFocusChangeListener { _, focused ->
                background = shape(Palette.white, border = if (focused) Palette.gold else Palette.line, stroke = dp(if (focused) 3 else 1))
                if (focused) description.text = "App version and books stored on this TV. Storage includes all private book files."
            }
        }
        rows.addView(about, LinearLayout.LayoutParams(-1, -2))
        about.addView(label("Version  $version", 16f)); about.space(6)
        val totals = label("Calculating…", 16f, Palette.muted)
        about.addView(totals); rows.space(10)
        io.execute {
            val result = runCatching {
                val count = books.books().size
                val bytes = listOf(books.root, books.locationCaches).sumOf { directory -> directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                "$count ${if (count == 1) "book" else "books"} stored · ${android.text.format.Formatter.formatFileSize(this, bytes)} used"
            }.getOrDefault("Storage information unavailable")
            main.post { if (settingsView === content) totals.text = result }
        }
        first.requestFocus()
    }
    private fun chapterList() {
        val panel = overlayPanel("Chapters")
        if (chapters.isEmpty()) panel.addView(label("Chapter list is loading. Try again in a moment.", 17f))
        chapters.forEach { (title, href) -> panel.addView(action(title) { hideOverlay(); engine?.chapter(href) }, LinearLayout.LayoutParams(-1, -2)) }
        val back = action("Back to controls") { controls() }; panel.addView(back)
        (if (chapters.isEmpty()) back else panel.getChildAt(2) ?: back).requestFocus()
        panel.tag = "chapters"
    }
    /**
     * PDF page picker. Ten pages per row, so Up/Down moves ten pages and Left/Right one. PDFs longer than
     * 100 pages first show a row of 100-page blocks; picking a block redraws the grid with its pages.
     */
    private fun pageList(block: Int? = null) {
        val pdf = engine as? PdfReaderEngine ?: return
        val blockSize = 100
        val panel = overlayPanel("Go to page"); panel.tag = "pages"
        val count = pdf.pageCount; val current = pdf.currentPage
        val back = action("Back to controls") { controls() }
        if (count == 0) {
            panel.addView(label("Pages are still loading. Try again in a moment.", 17f)); panel.space(16)
            panel.addView(back); back.requestFocus(); return
        }
        panel.addView(label("You’re on page ${current + 1} of $count.", 17f, Palette.muted)); panel.space(14)
        fun grid(cells: List<View>, columns: Int) {
            cells.chunked(columns).forEachIndexed { index, line ->
                if (index > 0) panel.space(8)
                val row = row()
                for (column in 0 until columns) {
                    if (column > 0) row.space(8)
                    // Empty fillers keep a short last row aligned with the columns above it.
                    row.addView(line.getOrNull(column) ?: View(this), LinearLayout.LayoutParams(0, -2, 1f))
                }
                panel.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        fun compact(button: ChoiceButton, side: Int) = button.apply { minWidth = 0; minimumWidth = 0; setPadding(dp(side), dp(10), dp(side), dp(10)) }
        val start = block ?: (current / blockSize * blockSize)
        var focus: View? = null
        if (count > blockSize) {
            grid((0 until count step blockSize).map { first ->
                compact(ChoiceButton(this, "${first + 1}–${minOf(first + blockSize, count)}", { first == start }) { pageList(first) }, 8)
                    .also { if (block != null && first == start) focus = it }
            }, 4)
            panel.space(18)
        }
        val pages = (start until minOf(start + blockSize, count)).map { index ->
            compact(ChoiceButton(this, "${index + 1}", { index == current }, mark = false) { hideOverlay(); engine?.chapter("page:$index") }, 0)
                .also { if (focus == null && index == current) focus = it }
        }
        grid(pages, 10)
        panel.space(16); panel.addView(back)
        (focus ?: pages.firstOrNull() ?: back).requestFocus()
    }
    private fun finishedBook() {
        val panel = overlayPanel("The end. Take a moment.")
        panel.addView(label("Your place is saved. The beginning is always there when you want it.", 18f, Palette.muted)); panel.space(16)
        val back = action("Back to library", true) { library() }; panel.addView(back)
        panel.addView(action("Read again") { hideOverlay(); engine?.chapter("") }); back.requestFocus()
    }
    private fun notice(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) resetSleepTimer()
        if (screen == "reader" && overlay == null) {
            if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && ready) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> engine?.turn(false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> engine?.turn(true)
                    KeyEvent.KEYCODE_DPAD_UP -> engine?.moveVertical(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> engine?.moveVertical(1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> controls()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("TV back navigation")
    override fun onBackPressed() {
        if (screen == "settings") { leaveSettings(); return }
        if (overlay != null) { hideOverlay(); return }
        if (screen == "reader" || screen == "import") { library(); return }
        super.onBackPressed()
    }
    override fun onStop() {
        super.onStop()
        main.removeCallbacks(sleepTimer)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (screen == "import") { stopPairing(); screen = "import-paused" }
    }
    override fun onStart() { super.onStart(); if (::preferences.isInitialized) resetSleepTimer(); if (::root.isInitialized && screen == "import-paused") showImport() }
    override fun onDestroy() {
        stopPairing(); closeReader(); indexQueue.clear(); indexEngine?.close(); indexEngine = null; io.shutdown()
        super.onDestroy()
    }
}
