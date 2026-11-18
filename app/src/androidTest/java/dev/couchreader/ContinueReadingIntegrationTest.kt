package dev.couchreader

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.couchreader.data.BookRepository
import dev.couchreader.data.LibraryDatabase
import dev.couchreader.importer.ImportBook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContinueReadingIntegrationTest {
    private fun await(message: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 30000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }

    @Test fun featuredBookResumesOnceRestoresFocusAndCanBeRemoved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = LibraryDatabase(context)
        val books = BookRepository(context, db)
        books.books().forEach(books::remove)
        val fixture = File.createTempFile("continue-", ".epub", books.staging)
        instrumentation.context.assets.open("fixtures/reading-3.epub").use { input -> fixture.outputStream().use(input::copyTo) }
        val book = ImportBook(books).execute(fixture, "continue.epub").book
        db.writableDatabase.execSQL("UPDATE books SET opened=1234 WHERE id=?", arrayOf(book.id))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun onUi(block: () -> Boolean): Boolean { var value = false; instrumentation.runOnMainSync { value = block() }; return value }
        fun resume() = activity.window.decorView.findViewWithTag<Button>("resume-${book.id}")
        try {
            await("Resume button missing") { onUi { resume() != null } }
            assertTrue("Fresh launch did not focus Resume", onUi { resume().hasFocus() })
            assertTrue("Featured book is missing from the spoken label", onUi { resume().contentDescription.toString().contains(book.title) })
            assertFalse("Featured book was duplicated in the grid", onUi { views(activity.window.decorView).any { it.tag == "tile-${book.id}" } })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Resume did not enter the reader") { onUi { activity.screen == "reader" && activity.ready } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Return did not restore focus to Resume") { onUi { activity.screen == "library" && resume()?.hasFocus() == true } }
            await("Background EPUB indexing did not update the featured section") { onUi {
                val detail = activity.window.decorView.findViewWithTag<TextView>("featured-detail-${book.id}")
                detail != null && detail.text.isNotBlank() && detail.text != "Calculating…" && resume().hasFocus()
            } }

            onUi { resume().performLongClick(); true }
            await("Featured book actions missing") { onUi { views(activity.window.decorView).filterIsInstance<Button>().any { it.text == "Remove from device" } } }
            onUi { views(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Remove from device" }.performClick(); true }
            await("Removal confirmation missing") { onUi { views(activity.window.decorView).filterIsInstance<Button>().any { it.text == "Keep book" } } }
            onUi { views(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Keep book" }.performClick(); true }
            await("Cancel did not restore Resume focus") { onUi { resume()?.hasFocus() == true } }

            onUi { resume().performLongClick(); true }
            await("Featured book actions did not reopen") { onUi { views(activity.window.decorView).filterIsInstance<Button>().any { it.text == "Remove from device" } } }
            onUi { views(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Remove from device" }.performClick(); true }
            await("Removal confirmation did not reopen") { onUi { views(activity.window.decorView).filterIsInstance<Button>().any { it.text == "Keep book" } } }
            onUi { views(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Remove" }.performClick(); true }
            await("Removing the featured book did not show the empty library") { onUi {
                activity.screen == "library" && views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("Add book") }
            } }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            books.books().forEach(books::remove)
            db.close()
        }
    }

    @Test fun pdfWithoutSavedProgressStillOffersResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = LibraryDatabase(context)
        val books = BookRepository(context, db)
        books.books().forEach(books::remove)
        val pdf = File.createTempFile("continue-", ".pdf", books.staging)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
            page.canvas.drawText("A PDF waiting to be resumed", 48f, 72f, Paint())
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val book = ImportBook(books).execute(pdf, "continue.pdf").book
        db.writableDatabase.execSQL("UPDATE books SET opened=1234 WHERE id=?", arrayOf(book.id))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("PDF Resume button missing without saved progress") {
                var present = false
                instrumentation.runOnMainSync { present = activity.window.decorView.findViewWithTag<Button>("resume-${book.id}") != null }
                present
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("PDF Resume did not enter the reader") {
                var ready = false
                instrumentation.runOnMainSync { ready = activity.screen == "reader" && activity.ready }
                ready
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            books.books().forEach(books::remove)
            db.close()
        }
    }
}
