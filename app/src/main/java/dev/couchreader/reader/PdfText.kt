package dev.couchreader.reader

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.text.BreakIterator
import java.util.Locale

/** A PDF sentence and where it sits on the page, as fractions (0..1) of the page width and height. */
class PdfSentence(val text: String, val rects: List<RectF>)

/**
 * Pulls sentences and their on-page boxes out of a PDF for read-aloud, using PdfBox-Android
 * (Android's PdfRenderer has no text API before Android 15). Not thread-safe: call from one worker.
 * Scanned pages have no text layer and return an empty list.
 */
class PdfText(context: Context, private val file: File) : Closeable {
    private val appContext = context.applicationContext
    private var document: PDDocument? = null

    fun sentences(pageIndex: Int): List<PdfSentence> {
        val doc = document ?: run {
            PDFBoxResourceLoader.init(appContext)
            // Reads from the private file on demand; scratch buffers stay in memory.
            PDDocument.load(file, MemoryUsageSetting.setupMainMemoryOnly()).also { document = it }
        }
        if (pageIndex !in 0 until doc.numberOfPages) return emptyList()
        val page = doc.getPage(pageIndex)
        val box = page.cropBox
        val rotated = page.rotation % 180 != 0
        val pageWidth = (if (rotated) box.height else box.width).coerceAtLeast(1f)
        val pageHeight = (if (rotated) box.width else box.height).coerceAtLeast(1f)
        val text = StringBuilder()
        val boxes = ArrayList<RectF?>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(value: String, positions: MutableList<TextPosition>) {
                for (position in positions) {
                    val glyph = position.unicode ?: continue
                    val height = position.heightDir.coerceAtLeast(position.fontSizeInPt * .7f)
                    val rect = RectF(position.xDirAdj / pageWidth, (position.yDirAdj - height * 1.1f) / pageHeight,
                        (position.xDirAdj + position.widthDirAdj) / pageWidth, (position.yDirAdj + height * .3f) / pageHeight)
                    for (char in glyph) { text.append(char); boxes.add(rect) }
                }
            }
            override fun writeWordSeparator() { separator(' ') }
            override fun writeLineSeparator() {
                // Join words hyphenated across a line break ("exam-" + "ple").
                if (text.length >= 2 && text[text.length - 1] == '-' && text[text.length - 2].isLetter()) {
                    text.setLength(text.length - 1); boxes.removeAt(boxes.lastIndex)
                } else separator(' ')
            }
            override fun writeParagraphEnd() { separator('\n') }
            /** Appends a space or paragraph break; a paragraph break replaces a trailing space. */
            private fun separator(char: Char) {
                if (text.isEmpty() || text.last() == '\n') return
                if (text.last() == ' ') { if (char == ' ') return; text.setLength(text.length - 1); boxes.removeAt(boxes.lastIndex) }
                text.append(char); boxes.add(null)
            }
        }
        stripper.startPage = pageIndex + 1; stripper.endPage = pageIndex + 1
        stripper.getText(doc)
        return split(text.toString(), boxes)
    }

    private fun split(text: String, boxes: List<RectF?>): List<PdfSentence> {
        val result = mutableListOf<PdfSentence>()
        val breaks = BreakIterator.getSentenceInstance(Locale.getDefault()).apply { setText(text) }
        var start = breaks.first(); var end = breaks.next()
        while (end != BreakIterator.DONE) {
            val raw = text.substring(start, end)
            if (raw.any { it.isLetterOrDigit() }) {
                result += PdfSentence(raw.replace(Regex("\\s+"), " ").trim(), lines(boxes.subList(start, end).filterNotNull()))
            }
            start = end; end = breaks.next()
        }
        return result
    }

    /** Merges glyph boxes into one box per printed line, so the highlight reads as a marker stroke. */
    private fun lines(glyphs: List<RectF>): List<RectF> {
        val merged = mutableListOf<RectF>()
        for (glyph in glyphs) {
            val last = merged.lastOrNull()
            val sameLine = last != null && kotlin.math.abs(last.centerY() - glyph.centerY()) < last.height() * .5f && glyph.left >= last.left - .01f
            if (sameLine) last!!.union(glyph) else merged += RectF(glyph)
        }
        return merged
    }

    override fun close() { runCatching { document?.close() }; document = null }
}
