package dev.couchreader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
    // The read-aloud highlight sits over the page image, outside its dark-mode inversion filter.
    private val marker = SentenceMarker(context)
    private val pageFrame = FrameLayout(context)
    private val file = File(books.directory(book), book.contentPath)
    private val textWorker = Executors.newSingleThreadExecutor()
    private var text: PdfText? = null
    private var pageSentences = listOf<PdfSentence>()
    private var pendingSentences: ((List<SpeechSentence>) -> Unit)? = null
    private var sentenceRequest = 0
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
        pageFrame.addView(image, FrameLayout.LayoutParams(-1, -2))
        pageFrame.addView(marker, FrameLayout.LayoutParams(-1, -1))
        scroll.addView(pageFrame, FrameLayout.LayoutParams(-1, -2))
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
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
                    pageSentences = emptyList(); sentenceRequest++; marker.show(emptyList())
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
                            onEvent("page", "{}")
                            pendingSentences?.let { pendingSentences = null; sentences(it) }
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
        // Dark matches the inverted page, which renders a white PDF background as black.
        frame.setBackgroundColor(if (value.dark) Color.BLACK else Color.rgb(245, 240, 229))
        marker.color = if (value.dark) Color.argb(110, 214, 170, 90) else Color.argb(80, 170, 107, 43)
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
    override fun sentences(callback: (List<SpeechSentence>) -> Unit) {
        if (closed) return
        if (loading || pageCount == 0) { pendingSentences = callback; return }
        val index = page; val request = ++sentenceRequest
        textWorker.execute {
            val found = runCatching { (text ?: PdfText(frame.context, file).also { text = it }).sentences(index) }.getOrDefault(emptyList())
            frame.post {
                if (closed || request != sentenceRequest || index != page) return@post
                pageSentences = found
                callback(found.map { SpeechSentence(it.text) })
            }
        }
    }
    override fun highlight(index: Int) {
        val sentence = pageSentences.getOrNull(index)
        marker.show(sentence?.rects ?: emptyList())
        val rects = sentence?.rects?.takeIf { it.isNotEmpty() } ?: return
        // Keep the spoken sentence in view: if any of it is off screen, bring its top to a quarter down.
        val top = (rects.minOf { it.top } * image.height).toInt(); val bottom = (rects.maxOf { it.bottom } * image.height).toInt()
        if (top < scroll.scrollY || bottom > scroll.scrollY + scroll.height)
            scroll.smoothScrollTo(0, (top - scroll.height / 4).coerceIn(0, maxScroll()))
    }
    override fun close() {
        closed = true
        pendingTurns.clear(); pendingSentences = null
        textWorker.execute { text?.close(); text = null }
        textWorker.shutdown()
        image.setImageDrawable(null); bitmap?.recycle(); bitmap = null
        worker.execute { renderer?.close(); descriptor?.close() }
        worker.shutdown()
    }
}

/** Draws the read-aloud highlight: one rounded box per printed line, in page fractions. */
private class SentenceMarker(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rects = listOf<RectF>()
    var color = Color.argb(80, 170, 107, 43)
        set(value) { field = value; invalidate() }
    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun show(value: List<RectF>) { rects = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val pad = width * .004f
        val boxes = rects.map { RectF(it.left * width - pad, it.top * height - pad, it.right * width + pad, it.bottom * height + pad) }
        // Glyph boxes reach above the capitals and below the descenders, so neighbouring lines overlap and the
        // translucent tint doubles there. Split each overlap between the two lines, leaving a hairline gap.
        val gap = resources.displayMetrics.density
        for (box in boxes) {
            val below = boxes.filter { it !== box && it.top > box.top && it.left < box.right && it.right > box.left }.minByOrNull { it.top } ?: continue
            if (box.bottom + gap > below.top) { val middle = (box.bottom + below.top) / 2; box.bottom = middle - gap / 2; below.top = middle + gap / 2 }
        }
        for (box in boxes) canvas.drawRoundRect(box, pad * 2, pad * 2, paint)
    }
}
