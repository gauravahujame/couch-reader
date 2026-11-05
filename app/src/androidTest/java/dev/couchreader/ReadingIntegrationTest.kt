package dev.couchreader

import android.app.Activity
import android.content.Intent
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
class ReadingIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun copyFixture(version: Int, repository: BookRepository): File =
        File.createTempFile("fixture-", ".epub", repository.staging).apply {
            instrumentation.context.assets.open("fixtures/reading-$version.epub").use { input -> outputStream().use { input.copyTo(it) } }
        }
    @Test fun importDeduplicatePersistAndDeleteIndependently() {
        val db = LibraryDatabase(context)
        val books = BookRepository(context, db)
        books.books().forEach(books::remove)
        val importer = ImportBook(books)
        val first = importer.execute(copyFixture(2, books), "fixture.epub").book
        val duplicate = importer.execute(copyFixture(2, books), "fixture.epub")
        assertTrue(duplicate.duplicate); assertEquals(first.id, duplicate.book.id)
        val second = importer.execute(copyFixture(3, books), "fixture.epub").book
        val positions = PositionRepository(db)
        val locator = Locator("epubcfi(/6/2!/4/2/1:0)", "one.xhtml")
        positions.save(first.id, locator); positions.save(second.id, locator.copy(href = "two.xhtml"))
        val prefs = PreferencesRepository(context); prefs.save(ReadingPreferences(5, true))
        db.close()
        val reopened = LibraryDatabase(context)
        val repository = BookRepository(context, reopened)
        assertEquals(2, repository.books().size)
        assertEquals(locator, PositionRepository(reopened).get(first.id))
        assertEquals(ReadingPreferences(5, true), PreferencesRepository(context).get())
        repository.remove(first)
        assertNull(PositionRepository(reopened).get(first.id))
        assertNotNull(PositionRepository(reopened).get(second.id))
        File(repository.root, "orphan").mkdirs(); File(repository.staging, "abandoned").writeText("partial")
        repository.reconcile()
        assertFalse(File(repository.root, "orphan").exists()); assertEquals(0, repository.staging.listFiles()!!.size)
        repository.remove(second); reopened.close()
    }
    @Test fun uploadRequiresLiveTokenAndStops() {
        val db = LibraryDatabase(context); val books = BookRepository(context, db)
        var clock = 1000L
        val server = UploadServer(books, ImportBook(books), now = { clock }, onState = { _, _ -> })
        server.start(3000, true)
        val port = server.listeningPort
        fun request(token: String): Int = java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 2000); socket.soTimeout = 2000
            socket.getOutputStream().write("GET /?token=$token HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
        }
        try {
            assertEquals(403, request("invalid")); assertEquals(200, request(server.token))
            clock += 600001; assertEquals(403, request(server.token))
        } finally { server.stop(); db.close() }
        assertTrue(runCatching { request(server.token) }.isFailure)
    }
    @Test fun epub2And3RenderTurnResizeAndRestoreLocators() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val db = LibraryDatabase(context); val books = BookRepository(context, db)
        try {
            for (version in listOf(2, 3)) {
                val book = ImportBook(books).execute(copyFixture(version, books), "fixture.epub").book
                val events = LinkedBlockingQueue<Pair<String, String>>()
                val pending = mutableListOf<Pair<String, String>>()
                var engine: ReaderEngine? = null
                fun await(type: String): JSONObject {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                    while (System.nanoTime() < deadline) {
                        val index = pending.indexOfFirst { it.first == type || it.first == "error" }
                        val event = if (index >= 0) pending.removeAt(index) else events.poll(1, TimeUnit.SECONDS) ?: continue
                        if (event.first == "error") fail(event.second)
                        if (event.first == type) return if (event.second.startsWith("{")) JSONObject(event.second) else JSONObject()
                        pending.add(event)
                    }
                    throw AssertionError("Timed out waiting for $type")
                }
                fun attach(locator: Locator? = null, pref: ReadingPreferences = ReadingPreferences()) {
                    instrumentation.runOnMainSync {
                        engine?.close()
                        val frame = FrameLayout(activity)
                        activity.setContentView(frame)
                        engine = EpubReaderEngine(activity, books, book, locator, pref) { type, payload -> events.offer(type to payload) }
                        frame.addView(engine!!.view, FrameLayout.LayoutParams(-1, -1))
                    }
                }
                attach(); val start = await("location"); await("ready")
                instrumentation.runOnMainSync { engine!!.turn(true) }
                val next = await("location"); assertNotEquals(start.getString("cfi"), next.getString("cfi"))
                val anchor = Locator(next.getString("cfi"), next.getString("href"))
                instrumentation.runOnMainSync { engine!!.preferences(ReadingPreferences(5, true)) }
                await("location")
                events.clear(); pending.clear(); attach(anchor, ReadingPreferences(5, true)); val restored = await("location"); await("ready")
                assertEquals(anchor.href, restored.getString("href"))
                instrumentation.runOnMainSync { engine!!.chapter("two.xhtml") }
                assertTrue(await("location").getString("href").endsWith("two.xhtml"))
                instrumentation.runOnMainSync { engine!!.close(); engine = null }
                books.remove(book)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; db.close() }
    }
}
