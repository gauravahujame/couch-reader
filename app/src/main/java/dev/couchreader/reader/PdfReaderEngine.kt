package dev.couchreader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import dev.couchreader.data.Book
import dev.couchreader.data.BookRepository
import dev.couchreader.data.Locator
import dev.couchreader.data.ReadingPreferences
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Fixed PDF pages are rendered off the UI thread. Up/Down scrolls within the current page. */
class PdfReaderEngine(context: Context, books: BookRepository, book: Book, locator: Locator?,
                      prefs: ReadingPreferences, private val onEvent: (String, String) -> Unit) : ReaderEngine {
    private val worker = Executors.newSingleThreadExecutor()
    private val frame = FrameLayout(context)
    private val scroll = ScrollView(context)
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; isFocusable = false }
    override val view: View get() = frame
    override val reflowable = false
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var page = locator?.takeIf { it.format == "pdf" }?.page ?: 0
    /** Zero until the document opens. Read on the UI thread; written there via frame.post ordering. */
    var pageCount = 0
        private set
    /** Zero-based page currently shown. */
    val currentPage: Int get() = page
    private val pendingTurns = java.util.ArrayDeque<Boolean>()
    // A jump requested while a page is still rendering; it replaces any queued turns.
    private var pendingJump: Int? = null
    private var bitmap: Bitmap? = null
    private var loading = true
    @Volatile private var closed = false
    private var changingPage = false
    private var initialized = false
    init {
        scroll.isFocusable = false; scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(image, FrameLayout.LayoutParams(-1, -2))
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (!loading && !changingPage && !closed) save() }
        preferences(prefs)
        frame.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (scroll.width <= 0 || closed) return
                frame.removeOnLayoutChangeListener(this)
                worker.execute {
                try {
                    descriptor = ParcelFileDescriptor.open(File(books.directory(book), book.contentPath), ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(descriptor!!); pageCount = renderer!!.pageCount
                    val invalid = page !in 0 until pageCount
                    page = page.coerceIn(0, pageCount - 1)
                    frame.post {
                        if (closed) return@post
                        onEvent("chapters", "[]")
                        if (invalid) onEvent("notice", JSONObject().put("message", "Saved page unavailable. Opened the nearest page.").toString())
                        render(if (invalid) 0f else locator?.offset ?: 0f)
                    }
                } catch (_: Exception) { frame.post { if (!closed) onEvent("error", JSONObject().put("message", "Could not open this PDF. Return to the library and try another copy.").toString()) } }
                }
            }
        })
    }
    private fun render(offset: Float = 0f) {
        if (closed) return
        loading = true
        val index = page
        val targetWidth = scroll.width.coerceAtLeast(1).coerceAtMost(1920)
        worker.execute {
            try {
                val rendered = renderer!!.openPage(index).use { pdfPage ->
                    val ratio = pdfPage.height.toDouble() / pdfPage.width
                    // Bound allocation to 12M pixels even for extremely tall/wide PDF pages.
                    val width = minOf(targetWidth.toDouble(), kotlin.math.sqrt(12_000_000.0 / ratio), 12000.0 / ratio).toInt().coerceAtLeast(1)
                    val height = (width * ratio).toInt().coerceIn(1, 12000)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        Canvas(this).drawColor(Color.WHITE)
                        pdfPage.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                frame.post {
                    if (closed) { rendered.recycle(); return@post }
                    changingPage = true
                    image.layoutParams = FrameLayout.LayoutParams(-1, (scroll.width.toDouble() * rendered.height / rendered.width).toInt().coerceIn(1, 1_000_000))
                    image.setImageBitmap(rendered)
                    val previous = bitmap; bitmap = rendered; previous?.recycle()
                    image.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            image.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            if (closed) return
                            scroll.scrollTo(0, (maxScroll() * offset.coerceIn(0f, 1f)).toInt())
                            changingPage = false; loading = false
                            save()
                            if (!initialized) { initialized = true; onEvent("ready", "{}") }
                            val jump = pendingJump; pendingJump = null
                            if (jump != null) chapter("page:$jump") else if (pendingTurns.isNotEmpty()) turn(pendingTurns.removeFirst())
                        }
                    })
                    image.requestLayout()
                }
            } catch (_: Exception) {
                frame.post { if (!closed) { loading = false; onEvent("error", JSONObject().put("message", "This PDF page could not be displayed. Try another page or return to the library.").toString()) } }
            }
        }
    }
    private fun maxScroll() = (image.height - scroll.height).coerceAtLeast(0)
    private fun save() {
        val offset = if (maxScroll() == 0) 0f else scroll.scrollY.toFloat() / maxScroll()
        onEvent("location", Locator.pdf(page, offset.coerceIn(0f, 1f)).encode())
        val fraction = if (pageCount <= 1) { if (maxScroll() == 0) 1.0 else offset.toDouble() } else (page + if (page == pageCount - 1 && maxScroll() == 0) 1.0 else offset.toDouble()) / pageCount
        onEvent("readingInfo", JSONObject().put("chapter", "Page ${page + 1} of $pageCount")
            .put("fraction", fraction.coerceIn(0.0, 1.0)).toString())
    }
    override fun turn(forward: Boolean) {
        if (closed) return
        if (loading) { pendingTurns.addLast(forward); return }
        val next = page + if (forward) 1 else -1
        if (next !in 0 until pageCount) { onEvent("boundary", JSONObject().put("end", forward).toString()); return }
        page = next; render()
    }
    override fun moveVertical(direction: Int) {
        if (!loading && !closed) scroll.smoothScrollBy(0, direction * (scroll.height * .7f).toInt())
    }
    override fun preferences(value: ReadingPreferences) {
        frame.setBackgroundColor(if (value.dark) Color.rgb(32, 40, 34) else Color.rgb(245, 240, 229))
        image.colorFilter = if (value.dark) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f
        ))) else null
    }
    /** [href] is "" (first page) or "page:N" with a zero-based N. */
    override fun chapter(href: String) {
        if (closed) return
        val target = if (href.isBlank()) 0 else href.removePrefix("page:").toIntOrNull() ?: return
        if (loading) { pendingTurns.clear(); pendingJump = target; return }
        if (pageCount == 0) return
        page = target.coerceIn(0, pageCount - 1); render()
    }
    override fun close() {
        closed = true
        pendingTurns.clear()
        image.setImageDrawable(null); bitmap?.recycle(); bitmap = null
        worker.execute { renderer?.close(); descriptor?.close() }
        worker.shutdown()
    }
}
