package dev.couchreader

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.couchreader.data.PreferencesRepository
import dev.couchreader.data.ReadingPreferences
import dev.couchreader.ui.OptionRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsIntegrationTest {
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(50) // Poll a condition rather than assume a fixed render duration.
        }
        fail(message)
    }
    @Test fun readerSettingsRetainEngineAndPassageAndSleepResets() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val db = dev.couchreader.data.LibraryDatabase(context)
        val books = dev.couchreader.data.BookRepository(context, db)
        val file = java.io.File.createTempFile("settings-", ".epub", books.staging)
        i.context.assets.open("fixtures/reading-3.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = dev.couchreader.importer.ImportBook(books).execute(file, "settings.epub").book
        PreferencesRepository(context).save(ReadingPreferences())
        val activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun js(script: String): String {
            val result = java.util.concurrent.LinkedBlockingQueue<String>()
            i.runOnMainSync {
                val engine = activity.engine as dev.couchreader.reader.EpubReaderEngine
                engine.view.evaluateJavascript(script) { result.offer(it) }
            }
            return result.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("No JS result")
        }
        try {
            awaitCondition("Library not ready") {
                var done = false
                i.runOnMainSync { done = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Settings" } }
                done
            }
            i.runOnMainSync { activity.open(book) }
            awaitCondition("Reader not ready") {
                var done = false; i.runOnMainSync { done = activity.ready }; done
            }
            fun settle() {
                js("window.settingsSettled=false;queue.then(()=>{window.settingsSettled=true;});")
                awaitCondition("Reader did not finish reflow") { js("window.settingsSettled") == "true" }
            }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            val anchor = js("rendition.currentLocation().start.cfi").trim('"')
            val sameEngine = activity.engine
            i.runOnMainSync { activity.settings() }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            assertEquals("2", js("rendition.layout().divisor"))
            assertEquals("40", js("rendition.layout().gap"))
            fun assertAnchor() {
                assertEquals("true", js("(()=>{const l=rendition.currentLocation(), c=new ePub.CFI();return c.compare(l.start.cfi,'$anchor')<=0 && c.compare('$anchor',l.end.cfi)<=0;})()"))
            }
            assertAnchor()
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            // Step through all sizes repeatedly, asserting the original passage after each reflow.
            for (code in List(7) { KeyEvent.KEYCODE_DPAD_RIGHT } + List(7) { KeyEvent.KEYCODE_DPAD_LEFT }) {
                i.sendKeyDownUpSync(code); settle(); assertAnchor()
            }
            assertEquals(0, PreferencesRepository(context).get().size)
            repeat(7) { i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle(); assertAnchor() }
            assertEquals(5, PreferencesRepository(context).get().size)
            val positions = dev.couchreader.data.PositionRepository(db)
            awaitCondition("Saved passage drifted") { positions.get(book.id)?.cfi == anchor }
            // Navigation clears the pending passage so later changes anchor to the newly read page.
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
            assertEquals("null", js("pendingAnchor"))
            i.runOnMainSync { activity.settings() }
            i.runOnMainSync {
                assertSame(sameEngine, activity.engine)
                activity.sleepTimer.run()
                assertEquals(0, activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            i.runOnMainSync { assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
            js("tell('error',{message:'Synthetic settings reflow failure'})")
            i.waitForIdleSync()
            i.runOnMainSync {
                assertEquals("settings", activity.screen)
                assertFalse(all(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Make yourself comfortable" })
            }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            i.runOnMainSync {
                assertEquals("reader", activity.screen); assertSame(sameEngine, activity.engine)
                assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Make yourself comfortable" })
            }
        } finally {
            i.runOnMainSync { activity.finish() }; i.waitForIdleSync()
            books.remove(book); db.close()
            PreferencesRepository(context).save(ReadingPreferences())
        }
    }
    @Test fun remoteNavigationPersistsAndAboutLoads() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        PreferencesRepository(context).save(ReadingPreferences())
        val activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            awaitCondition("Library not ready") {
                var done = false
                i.runOnMainSync { done = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Settings" } }
                done
            }
            i.runOnMainSync { activity.settings() }
            i.waitForIdleSync()
            fun key(code: Int) { i.sendKeyDownUpSync(code); i.waitForIdleSync() }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(3, PreferencesRepository(context).get().size)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            i.runOnMainSync { assertTrue(activity.currentFocus is OptionRow) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(PreferencesRepository(context).get().spread)
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(15, PreferencesRepository(context).get().sleepMinutes)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(15, PreferencesRepository(context).get().sleepMinutes)
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertEquals(0, PreferencesRepository(context).get().sleepMinutes)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            i.runOnMainSync { assertTrue(activity.currentFocus !is OptionRow); assertTrue(activity.currentFocus?.isFocusable == true) }
            awaitCondition("About totals not ready") {
                var done = false
                i.runOnMainSync { done = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("stored ·") } }
                done
            }
            i.runOnMainSync {
                val texts = all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                val database = dev.couchreader.data.LibraryDatabase(context)
                val count = dev.couchreader.data.BookRepository(context, database).books().size
                database.close()
                assertTrue(texts.any { it.startsWith("$count ") && it.contains("stored ·") })
                assertTrue(texts.any { it.startsWith("Version  ") })
            }
            key(KeyEvent.KEYCODE_BACK)
            i.runOnMainSync { assertEquals("Settings", (activity.currentFocus as TextView).text.toString()) }
            // Read the XML file directly: a SharedPreferences get() only proves its in-memory cache.
            val stored = java.io.File(context.applicationInfo.dataDir, "shared_prefs/reading.xml")
            awaitCondition("Preferences did not reach disk") {
                runCatching {
                    val parser = android.util.Xml.newPullParser()
                    val values = mutableMapOf<String, String>()
                    stored.reader().use { reader ->
                        parser.setInput(reader)
                        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                                val name = parser.getAttributeValue(null, "name")
                                if (name != null) values[name] = parser.getAttributeValue(null, "value").orEmpty()
                            }
                            parser.next()
                        }
                    }
                    values["version"] == "2" && values["size"] == "3" && values["dark"] == "false" && values["spread"] == "true" && values["sleepMinutes"] == "15"
                }.getOrDefault(false)
            }
        } finally {
            i.runOnMainSync { if (!activity.isDestroyed) activity.finish() }
            PreferencesRepository(context).save(ReadingPreferences())
        }
    }
}
