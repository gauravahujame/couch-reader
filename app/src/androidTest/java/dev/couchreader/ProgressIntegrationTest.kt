package dev.couchreader

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.couchreader.data.*
import dev.couchreader.importer.ImportBook
import dev.couchreader.reader.EpubReaderEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProgressIntegrationTest {
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 25000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }
    @Test fun backgroundIndexCachesAndControlsAndCardsShowProgress() {
        val i = InstrumentationRegistry.getInstrumentation(); val context = i.targetContext
        val db = LibraryDatabase(context); val books = BookRepository(context, db)
        val file = File.createTempFile("progress-", ".epub", books.staging)
        i.context.assets.open("fixtures/reading-3.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = ImportBook(books).execute(file, "progress.epub").book
        val cache = books.locationCache(book)
        cache.delete()
        val activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun onUi(condition: () -> Boolean): Boolean { var value = false; i.runOnMainSync { value = condition() }; return value }
        fun js(script: String): String {
            val queue = LinkedBlockingQueue<String>()
            i.runOnMainSync { (activity.engine as EpubReaderEngine).view.evaluateJavascript(script) { queue.offer(it) } }
            return queue.poll(5, TimeUnit.SECONDS) ?: error("No JS result")
        }
        fun capture(name: String) {
            val drawn = java.util.concurrent.CountDownLatch(1)
            i.runOnMainSync {
                activity.window.decorView.postOnAnimation {
                    activity.window.decorView.postOnAnimation {
                        activity.window.decorView.postOnAnimation { drawn.countDown() }
                    }
                }
            }
            assertTrue("UI did not draw", drawn.await(3, TimeUnit.SECONDS))
            i.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            await("Library not ready") { onUi { views(activity.window.decorView).any { it.contentDescription == "Settings" } } }
            i.runOnMainSync { activity.open(book) }
            await("Reader not ready") { onUi { activity.ready } }
            // No generated page number may enter the resume locator.
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Index not cached") { runCatching { JSONObject(cache.readText()).getJSONArray("locations").length() > 0 }.getOrDefault(false) }
            await("Progress not delivered") { ProgressRepository(db).all()[book.id]?.fraction != null }
            assertEquals(book.hash, JSONObject(cache.readText()).getString("hash"))
            assertFalse(PositionRepository(db).get(book.id)!!.encode().contains("\"page\""))
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Controls missing chapter/progress") { onUi {
                val text = activity.window.decorView.findViewWithTag<TextView>("reader-progress")?.text.toString()
                text.contains("% read") && text.contains("A place to begin")
            } }
            capture("progress-controls.png")
            val before = js("book.locations.percentageFromCfi((pendingAnchor||rendition.currentLocation().start).cfi)")
            i.runOnMainSync { activity.engine!!.preferences(ReadingPreferences(5, false, true)) }
            js("window.progressSettled=false;queue.then(()=>window.progressSettled=true)")
            await("Reflow pending") { js("window.progressSettled") == "true" }
            assertEquals(before, js("book.locations.percentageFromCfi(pendingAnchor.cfi)"))
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Library card missing progress") { onUi {
                val card = activity.window.decorView.findViewWithTag<View>("progress-${book.id}")
                card != null && views(card).any { it is android.widget.ProgressBar }
            } }
            capture("progress-library.png")
            val modified = cache.lastModified()
            val events = LinkedBlockingQueue<Pair<String, String>>()
            var worker: EpubReaderEngine? = null
            i.runOnMainSync { worker = EpubReaderEngine(activity, books, book, null, ReadingPreferences(), indexOnly = true) { type, payload -> events.offer(type to payload) } }
            val result = events.poll(15, TimeUnit.SECONDS) ?: error("No cache result")
            assertEquals("indexed", result.first)
            assertTrue(JSONObject(result.second).getBoolean("cached"))
            assertEquals(modified, cache.lastModified())
            i.runOnMainSync { worker!!.close() }
            // Corrupt caches are rebuilt rather than preventing reading or displaying made-up progress.
            cache.writeText("{\"version\":1,\"hash\":\"wrong\",\"locations\":[]}")
            i.runOnMainSync { worker = EpubReaderEngine(activity, books, book, null, ReadingPreferences(), indexOnly = true) { type, payload -> events.offer(type to payload) } }
            assertEquals("indexed", events.poll(15, TimeUnit.SECONDS)?.first)
            assertEquals(book.hash, JSONObject(cache.readText()).getString("hash"))
            i.runOnMainSync { worker!!.close() }
        } finally {
            i.runOnMainSync { activity.finish() }; i.waitForIdleSync()
            books.remove(book); db.close()
        }
    }
}
