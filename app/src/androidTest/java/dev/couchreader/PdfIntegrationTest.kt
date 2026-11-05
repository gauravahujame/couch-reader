package dev.couchreader

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.couchreader.data.*
import dev.couchreader.importer.*
import dev.couchreader.reader.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfIntegrationTest {
    @Test fun importsPdfTurnsScrollsAndRestoresItsOwnLocator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = LibraryDatabase(context); val books = BookRepository(context, db)
        val source = File.createTempFile("test-pdf-", ".pdf", books.staging)
        val document = PdfDocument()
        try {
            for (i in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 1000, i).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { textSize = 18f; color = Color.BLACK }
                page.canvas.drawText("Couch Reader PDF sample - page $i", 40f, 60f, paint)
                for (line in 1..34) page.canvas.drawText("Line $line: a PDF passage that stays on its own page.", 40f, 90f + line * 24, paint)
                document.finishPage(page)
            }
            source.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        val bytes = source.readBytes()
        val result = ImportBook(books).execute(source, "A PDF book.pdf")
        val book = result.book
        assertEquals("pdf", book.format)
        val duplicate = File.createTempFile("duplicate-", ".pdf", books.staging).apply { writeBytes(bytes) }
        assertTrue(ImportBook(books).execute(duplicate, "same.pdf").duplicate)
        val invalid = File.createTempFile("invalid-", ".pdf", books.staging).apply { writeText("%PDF-not-a-real-document") }
        assertTrue(runCatching { ImportBook(books).execute(invalid, "bad.pdf") }.exceptionOrNull() is ImportFailure)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        var engine: ReaderEngine? = null
        val events = LinkedBlockingQueue<Pair<String, String>>()
        fun awaitLocation(predicate: (Locator) -> Boolean): Locator {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val event = events.poll(1, TimeUnit.SECONDS) ?: continue
                if (event.first == "error") fail(event.second)
                if (event.first == "location") Locator.decode(event.second)?.let { if (predicate(it)) return it }
            }
            throw AssertionError("Timed out waiting for PDF location")
        }
        fun attach(locator: Locator? = null) {
            instrumentation.runOnMainSync {
                engine?.close()
                events.clear()
                val frame = FrameLayout(activity)
                activity.setContentView(frame)
                engine = PdfReaderEngine(activity, books, book, locator, ReadingPreferences()) { type, payload -> events.offer(type to payload) }
                frame.addView(engine!!.view, FrameLayout.LayoutParams(-1, -1))
            }
        }
        try {
            attach(); awaitLocation { it.page == 0 }
            instrumentation.runOnMainSync { engine!!.turn(true) }
            awaitLocation { it.page == 1 }
            instrumentation.runOnMainSync { engine!!.moveVertical(1) }
            val scrolled = awaitLocation { it.page == 1 && it.offset > .05f }
            // Save an observed visible region; the next renderer must restore that exact locator.
            PositionRepository(db).save(book.id, scrolled)
            events.clear(); attach(PositionRepository(db).get(book.id))
            val restored = awaitLocation { it.page == 1 }
            assertEquals(scrolled.offset, restored.offset, .03f)
            assertEquals("pdf", restored.format)
            instrumentation.runOnMainSync { engine!!.turn(false) }
            awaitLocation { it.page == 0 }
        } finally {
            instrumentation.runOnMainSync { engine?.close(); activity.finish() }
            books.remove(book); db.close()
        }
    }
}
