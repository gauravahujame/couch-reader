package dev.couchreader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
    private lateinit var preferences: PreferencesRepository
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var screen = "library"
    private var selectedBook: String? = null
    private var engine: ReaderEngine? = null
    private var currentBook: Book? = null
    private var chapters = listOf<Pair<String, String>>()
    private var overlay: View? = null
    private var server: UploadServer? = null
    private var pairingGeneration = 0
    private var readerGeneration = 0
    private var onPairingTick: ((Boolean) -> Unit)? = null
    private var ready = false
    private var activeDialog: AlertDialog? = null
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
        positions = PositionRepository(db); preferences = PreferencesRepository(this)
        root = FrameLayout(this); setContentView(root)
        loading("Preparing your library…")
        io.execute {
            books.reconcile()
            main.post { if (!isDestroyed) library() }
        }
    }
    private fun loading(message: String) {
        root.removeAllViews()
        root.setBackgroundColor(Palette.paper)
        root.addView(label(message, 24f, serif = true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }
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
        val add = action("＋  Add book", primary = true) { showImport() }
        masthead(content, if (catalog.isEmpty()) null else add)
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
            add.requestFocus()
            return
        }
        val heading = row()
        heading.addView(label("Your library", 34f, serif = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label("${catalog.size} ${if (catalog.size == 1) "book" else "books"} · On this device", 15f, Palette.muted))
        content.addView(heading); content.space(10)
        val recent = catalog.firstOrNull { it.opened > 0 }
        recent?.let { book ->
            val continueButton = action("Continue reading   ·   ${book.title}   →") { open(book) }
            content.addView(continueButton, LinearLayout.LayoutParams(-1, -2)); content.space(12)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val grid = column()
        val cardWidth = ((resources.displayMetrics.widthPixels * .88 - dp(36)) / 3).toInt()
        var restore: View? = null
        catalog.chunked(3).forEachIndexed { rowIndex, group ->
            val line = row().apply { gravity = Gravity.TOP }
            group.forEachIndexed { index, book ->
                if (index > 0) line.space(14)
                val card = column().apply {
                    setPadding(dp(14), dp(14), dp(14), dp(12))
                    background = shape(Palette.white, border = Palette.line, stroke = dp(1))
                }
                val tones = listOf(0xff365347.toInt(), 0xff74513d.toInt(), 0xff515d70.toInt(), 0xff6b6743.toInt())
                val cover = column().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                    background = shape(tones[(rowIndex * 3 + index) % tones.size], 5f)
                    addView(label("—  ${book.format.uppercase()}  —", 10f, 0xffe9dfc4.toInt()).apply { letterSpacing = .15f })
                    space(12)
                    addView(label(book.title, 22f, Color.WHITE, serif = true).apply { gravity = Gravity.CENTER; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                    space(12)
                    addView(label(book.author, 12f, 0xfff1e9d8.toInt()).apply { gravity = Gravity.CENTER; maxLines = 1 })
                }
                card.addView(cover, LinearLayout.LayoutParams(-1, dp(160)))
                card.space(8)
                val read = action(book.title) { open(book) }.apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = "Read ${book.title} by ${book.author}"
                    setOnFocusChangeListener { _, focused ->
                        if (focused) selectedBook = book.id
                        background = shape(if (focused) Palette.green else Palette.white, border = if (focused) Palette.gold else Palette.line, stroke = dp(if (focused) 3 else 1))
                        setTextColor(if (focused) Color.WHITE else Palette.ink)
                    }
                }
                card.addView(read, LinearLayout.LayoutParams(-1, -2))
                card.addView(action("Book actions") { bookActions(book) }, LinearLayout.LayoutParams(-1, -2))
                if (selectedBook == book.id) restore = read
                line.addView(card, LinearLayout.LayoutParams(cardWidth, -2))
            }
            grid.addView(line); grid.space(14)
        }
        scroll.addView(grid); content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.space(8)
        content.addView(label("YOUR OWN BOOKS. YOUR OWN PACE.                                      D-PAD  Browse    ·    OK  Open", 11f, Palette.muted).apply { letterSpacing = .08f })
        (restore ?: add).requestFocus()
    }
    private fun bookActions(book: Book) {
        activeDialog = AlertDialog.Builder(this).setTitle(book.title).setItems(arrayOf("Read", "Remove from device")) { _, which ->
            if (which == 0) open(book) else confirmRemove(book)
        }.setNegativeButton("Back", null).create().also { it.show() }
    }
    private fun confirmRemove(book: Book) {
        activeDialog = AlertDialog.Builder(this).setTitle("Remove from device?")
            .setMessage("${book.title} and its saved reading position will be removed. You can import the book again later.")
            .setNegativeButton("Keep book", null).setPositiveButton("Remove") { _, _ ->
                loading("Removing book…")
                io.execute { books.remove(book); main.post { library() } }
            }.create().also { it.show(); it.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
    }
    private fun showImport() {
        closeReader(); stopPairing(); screen = "import"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val generation = ++pairingGeneration
        val content = page()
        val back = action("Back to library") { library() }
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
    private fun open(book: Book) {
        stopPairing(); closeReader(); screen = "reader"; currentBook = book; selectedBook = book.id
        val generation = readerGeneration
        ready = false; chapters = emptyList()
        loading("Opening ${book.title}…")
        io.execute {
            val position = positions.get(book.id)
            val unavailablePosition = position == null && positions.hasSavedPosition(book.id)
            books.opened(book)
            main.post {
                if (screen != "reader" || currentBook?.id != book.id || generation != readerGeneration || isDestroyed) return@post
                val eventHandler: (String, String) -> Unit = event@ { type, payload ->
                    if (screen != "reader" || currentBook?.id != book.id || generation != readerGeneration) return@event
                    when (type) {
                        "ready" -> { ready = true; root.findViewWithTag<View>("loading")?.let { root.removeView(it) }; root.requestFocus(); notice(if (unavailablePosition) "Saved position unavailable. Opened the beginning of the book." else if (book.format == "pdf") "← / →  Pages     ↑ / ↓  Scroll     OK  Controls" else "←  Previous page     →  Next page     OK  Reading controls") }
                        "chapters" -> { val list = JSONArray(payload); chapters = (0 until list.length()).map { list.getJSONObject(it).let { item -> item.getString("title") to item.getString("href") } } }
                        "location" -> {
                            val data = JSONObject(payload)
                            val locator = if (book.format == "pdf") Locator.decode(payload) ?: return@event
                                else Locator(data.getString("cfi"), data.getString("href"))
                            io.execute { positions.save(book.id, locator) }
                        }
                        "boundary" -> if (JSONObject(payload).optBoolean("end")) finishedBook() else notice("You’re at the beginning of this book.")
                        "notice" -> notice(JSONObject(payload).getString("message"))
                        "error" -> { ready = true; root.findViewWithTag<View>("loading")?.let { root.removeView(it) }; notice(JSONObject(payload).getString("message")); controls() }
                    }
                }
                val renderer: ReaderEngine = if (book.format == "pdf")
                    PdfReaderEngine(this, books, book, position, preferences.get(), eventHandler)
                else EpubReaderEngine(this, books, book, position, preferences.get(), eventHandler)
                engine = renderer
                root.removeAllViews(); root.addView(renderer.view, FrameLayout.LayoutParams(-1, -1))
                root.addView(label("Opening book…", 22f).apply { tag = "loading"; setBackgroundColor(Palette.paper); setPadding(dp(20), dp(20), dp(20), dp(20)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                root.isFocusableInTouchMode = true; root.requestFocus()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    private fun closeReader() {
        readerGeneration++
        overlay = null; currentBook = null
        engine?.let { root.removeView(it.view); it.close() }; engine = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun overlayPanel(title: String): LinearLayout {
        overlay?.let { root.removeView(it) }
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xb3202822.toInt()); isFocusable = false }
        val panel = column().apply { setPadding(dp(30), dp(22), dp(30), dp(22)); background = shape(Palette.paper); addView(label(title, 29f, serif = true)); space(14) }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(panel) }
        val margin = (resources.displayMetrics.heightPixels * .07).toInt()
        scrim.addView(scroll, FrameLayout.LayoutParams(dp(560).coerceAtMost((resources.displayMetrics.widthPixels * .86).toInt()), -2, Gravity.CENTER).apply { topMargin = margin; bottomMargin = margin })
        overlay = scrim; root.addView(scrim, FrameLayout.LayoutParams(-1, -1)); return panel
    }
    private fun hideOverlay() {
        overlay?.let { root.removeView(it) }; overlay = null
        root.requestFocus()
    }
    private fun controls() {
        if (engine == null) return
        val panel = overlayPanel("Make yourself comfortable")
        panel.addView(label(currentBook?.title.orEmpty(), 16f, Palette.muted)); panel.space(12)
        var pref = preferences.get()
        val isPdf = currentBook?.format == "pdf"
        val sizeLabel = label("Text size  ·  ${pref.size + 1} of 6", 17f)
        if (!isPdf) panel.addView(sizeLabel)
        else panel.addView(label("PDF · fixed page layout. Use ↑ / ↓ to scroll, ← / → for pages.", 17f, Palette.muted))
        val sizes = row()
        fun update(value: ReadingPreferences) { pref = value; preferences.save(pref); engine?.preferences(pref); sizeLabel.text = "Text size  ·  ${pref.size + 1} of 6" }
        sizes.addView(action("A−  Smaller") { update(pref.copy(size = (pref.size - 1).coerceAtLeast(0))) })
        sizes.space(10); sizes.addView(action("A＋  Larger") { update(pref.copy(size = (pref.size + 1).coerceAtMost(5))) })
        if (!isPdf) panel.addView(sizes)
        panel.space(10)
        val themes = row()
        themes.addView(action("Warm light") { update(pref.copy(dark = false)) }); themes.space(10)
        themes.addView(action("Dark") { update(pref.copy(dark = true)) }); panel.addView(themes); panel.space(10)
        if (!isPdf) panel.addView(action("Chapters   →") { chapterList() }, LinearLayout.LayoutParams(-1, -2))
        panel.space(10)
        val resume = action("Back to reading", true) { hideOverlay() }
        panel.addView(resume, LinearLayout.LayoutParams(-1, -2)); resume.requestFocus()
    }
    private fun chapterList() {
        val panel = overlayPanel("Chapters")
        if (chapters.isEmpty()) panel.addView(label("Chapter list is loading. Try again in a moment.", 17f))
        chapters.forEach { (title, href) -> panel.addView(action(title) { hideOverlay(); engine?.chapter(href) }, LinearLayout.LayoutParams(-1, -2)) }
        val back = action("Back to controls") { controls() }; panel.addView(back)
        (if (chapters.isEmpty()) back else panel.getChildAt(2) ?: back).requestFocus()
        panel.tag = "chapters"
    }
    private fun finishedBook() {
        val panel = overlayPanel("The end. Take a moment.")
        panel.addView(label("Your place is saved. The beginning is always there when you want it.", 18f, Palette.muted)); panel.space(16)
        val back = action("Back to library", true) { library() }; panel.addView(back)
        panel.addView(action("Read again") { hideOverlay(); engine?.chapter("") }); back.requestFocus()
    }
    private fun notice(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screen == "reader" && activeDialog?.isShowing != true && overlay == null) {
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
        if (overlay != null) { hideOverlay(); return }
        if (screen == "reader" || screen == "import") { library(); return }
        super.onBackPressed()
    }
    override fun onStop() {
        super.onStop()
        if (screen == "import") { stopPairing(); screen = "import-paused" }
    }
    override fun onStart() { super.onStart(); if (::root.isInitialized && screen == "import-paused") showImport() }
    override fun onDestroy() {
        stopPairing(); closeReader(); io.shutdown()
        super.onDestroy()
    }
}
