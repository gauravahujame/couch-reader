package dev.couchreader.data

import dev.couchreader.importer.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibraryIntegrationTest {
    private lateinit var db: LibraryDatabase
    private lateinit var books: BookRepository
    @Before fun create() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("library.db")
        db = LibraryDatabase(context); books = BookRepository(context, db)
        books.reconcile()
    }
    @After fun close() { books.books().forEach(books::remove); db.close() }
    private fun fixture(version: Int = 3): File = File.createTempFile("fixture-", ".epub", books.staging).apply {
        File("src/androidTest/assets/fixtures/reading-$version.epub").copyTo(this, overwrite = true)
    }
    @Test fun catalogAndLocatorsSurviveReopenAndDeleteIndependently() {
        val action = ImportBook(books)
        val first = action.execute(fixture(2), "book.epub").book
        assertTrue(action.execute(fixture(2), "book.epub").duplicate)
        val second = action.execute(fixture(3), "book.epub").book
        val position = Locator("epubcfi(/6/2!/4/4/1:15)", "one.xhtml")
        PositionRepository(db).save(first.id, position)
        PositionRepository(db).save(second.id, position.copy(href = "two.xhtml"))
        val context = RuntimeEnvironment.getApplication()
        PreferencesRepository(context).save(ReadingPreferences(4, true))
        db.close(); db = LibraryDatabase(context); books = BookRepository(context, db)
        assertEquals(2, books.books().size)
        assertEquals(position, PositionRepository(db).get(first.id))
        assertEquals(ReadingPreferences(4, true), PreferencesRepository(context).get())
        books.remove(first)
        assertFalse(books.directory(first).exists()); assertNull(PositionRepository(db).get(first.id))
        assertEquals("two.xhtml", PositionRepository(db).get(second.id)!!.href)
        assertEquals(ReadingPreferences(4, true), PreferencesRepository(context).get())
    }
    @Test fun catalogUsesStableIdOrderForEqualOpenTimesAndKeepsUnopenedBooksAfterThem() {
        val importer = ImportBook(books)
        val first = importer.execute(fixture(2), "first.epub").book
        val second = importer.execute(fixture(3), "second.epub").book
        val thirdFile = fixture(3).apply { appendText(" ") }
        val third = importer.execute(thirdFile, "third.epub").book
        db.writableDatabase.execSQL("UPDATE books SET opened=1234 WHERE id IN (?, ?)", arrayOf(first.id, second.id))
        val ordered = books.books()
        val featured = ordered.firstOrNull { it.opened > 0 }
        assertEquals(minOf(first.id, second.id), featured?.id)
        assertEquals(listOf(maxOf(first.id, second.id)), ordered.filter { it.opened > 0 && it.id != featured?.id }.map { it.id })
        assertEquals(listOf(third.id), ordered.filter { it.opened == 0L }.map { it.id })
        assertEquals(3, ordered.size)
    }
    @Test fun progressMigrationPersistsAndCascadesWithoutChangingCfi() {
        val book = ImportBook(books).execute(fixture(), "book.epub").book
        val locator = Locator("epubcfi(/6/2!/4/4/1:15)", "one.xhtml")
        PositionRepository(db).save(book.id, locator)
        db.writableDatabase.execSQL("DROP TABLE reading_progress")
        db.writableDatabase.version = 2
        db.close()
        db = LibraryDatabase(RuntimeEnvironment.getApplication()); books = BookRepository(RuntimeEnvironment.getApplication(), db)
        val progress = ProgressRepository(db)
        assertTrue(progress.all().isEmpty())
        progress.save(book.id, ReadingProgress(.42, "Chapter one"))
        assertEquals(locator, PositionRepository(db).get(book.id))
        assertEquals(ReadingProgress(.42, "Chapter one"), ProgressRepository(db).all()[book.id])
        books.remove(book)
        assertTrue(progress.all().isEmpty())
    }
    @Test fun reconciliationRemovesAbandonedFilesAndMissingCatalogResources() {
        val book = ImportBook(books).execute(fixture(), "book.epub").book
        File(books.root, ".pending-abandoned").mkdirs()
        File(books.staging, "interrupted.epub").writeText("incomplete")
        File(books.directory(book), book.contentPath).delete()
        books.reconcile()
        assertTrue(books.books().isEmpty()); assertTrue(books.root.listFiles()!!.isEmpty())
        assertTrue(books.staging.listFiles()!!.isEmpty())
    }
    @Test fun failedImportLeavesLibraryAndExistingPositionUntouched() {
        val action = ImportBook(books)
        val book = action.execute(fixture(), "book.epub").book
        val locator = Locator("epubcfi(/6/2!/4/4/1:15)", "one.xhtml")
        PositionRepository(db).save(book.id, locator)
        val invalid = File.createTempFile("bad", ".epub", books.staging).apply { writeText("not a zip") }
        assertTrue(runCatching { action.execute(invalid, "bad.epub") }.exceptionOrNull() is ImportFailure)
        assertEquals(listOf(book), books.books()); assertEquals(locator, PositionRepository(db).get(book.id))
        assertEquals(1, books.root.listFiles()!!.size); assertFalse(invalid.exists())
    }
    @Test fun locatorRejectsUnknownVersionAndCorruptData() {
        assertNull(Locator.decode("invalid"))
        assertNull(Locator.decode("""{"version":2,"format":"epub","cfi":"x","href":"one.xhtml"}"""))
        val locator = Locator("epubcfi(/6/2!/4/4/1:15)", "one.xhtml")
        assertEquals(locator, Locator.decode(locator.encode()))
    }
    @Test fun migratesExistingEpubCatalogAndPositionsWithoutLoss() {
        db.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("library.db")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("library.db"), null)
        old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, hash TEXT NOT NULL UNIQUE, title TEXT NOT NULL, author TEXT NOT NULL, opf TEXT NOT NULL, imported INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("CREATE TABLE positions(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE, locator TEXT NOT NULL, updated INTEGER NOT NULL)")
        val locator = Locator("epubcfi(/6/2!/4/2/1:0)", "one.xhtml")
        old.execSQL("INSERT INTO books VALUES('existing','hash','Existing book','Author','package.opf',1,2)")
        old.execSQL("INSERT INTO positions VALUES('existing',?,1)", arrayOf(locator.encode()))
        old.version = 1; old.close()
        db = LibraryDatabase(context); books = BookRepository(context, db)
        assertEquals("Existing book", books.books().single().title)
        assertEquals("epub", books.books().single().format)
        assertEquals(locator, PositionRepository(db).get("existing"))
    }
    @Test fun refreshesTitlesAndAuthorsImportedByOlderReleasesOnce() {
        db.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("library.db")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("library.db"), null)
        old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, hash TEXT NOT NULL UNIQUE, title TEXT NOT NULL, author TEXT NOT NULL, opf TEXT NOT NULL, imported INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 0, format TEXT NOT NULL DEFAULT 'epub')")
        old.execSQL("CREATE TABLE positions(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE, locator TEXT NOT NULL, updated INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE reading_progress(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE, fraction REAL, chapter TEXT NOT NULL)")
        old.execSQL("INSERT INTO books VALUES('plain','h1','Understanding_Harness_Engineering','Unknown author','book.pdf',1,0,'pdf')")
        old.execSQL("INSERT INTO books VALUES('tagged','h2','scan_0001','Unknown author','book.pdf',1,0,'pdf')")
        old.execSQL("INSERT INTO books VALUES('epub','h3','A Quiet Book','Unknown author','package.opf',1,0,'epub')")
        old.version = 3; old.close()
        db = LibraryDatabase(context); books = BookRepository(context, db)
        File(books.root, "plain").apply { mkdirs() }.resolve("book.pdf").writeText("%PDF-1.4\ntrailer << >>\n%%EOF")
        File(books.root, "tagged").apply { mkdirs() }.resolve("book.pdf")
            .writeText("%PDF-1.4\n3 0 obj\n<< /Title (Field Notes) /Author (Jane Doe) >>\nendobj\ntrailer << /Info 3 0 R >>\n%%EOF")
        books.refreshMetadata()
        val byId = books.books().associateBy { it.id }
        assertEquals("Understanding Harness Engineering", byId.getValue("plain").title)
        assertEquals("", byId.getValue("plain").author)
        assertEquals("Field Notes", byId.getValue("tagged").title)
        assertEquals("Jane Doe", byId.getValue("tagged").author)
        assertEquals("A Quiet Book", byId.getValue("epub").title)
        assertEquals("", byId.getValue("epub").author)
        // A second start does not re-read files: changes made afterwards are kept.
        db.writableDatabase.execSQL("UPDATE books SET title='Kept' WHERE id='plain'")
        books.refreshMetadata()
        assertEquals("Kept", books.books().first { it.id == "plain" }.title)
    }
    @Test fun pdfLocatorsRoundTripAndRejectInvalidOffsets() {
        val locator = Locator.pdf(8, .375f)
        assertEquals(locator, Locator.decode(locator.encode()))
        assertFalse(locator.encode().contains("cfi"))
        assertNull(Locator.decode("""{"version":1,"format":"pdf","page":-1}"""))
        assertNull(Locator.decode("""{"version":1,"format":"pdf","page":0,"offset":2}"""))
    }
    @Test fun browserCodePairsWithSessionCookieAndLimitsGuesses() {
        var clock = 1000L
        val server = UploadServer(books, ImportBook(books), now = { clock }, onState = { _, _ -> })
        server.start(3000, true)
        data class Reply(val status: Int, val body: String, val cookie: String?)
        fun request(path: String = "/", code: String? = null, cookie: String? = null,
                    body: ByteArray? = null, origin: String? = null): Reply {
            // HttpURLConnection silently drops Origin; use raw HTTP so these security checks
            // exercise the exact headers a browser sends.
            return Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.listeningPort), 3000); socket.soTimeout = 10000
                val headers = buildString {
                    append(if (code != null || body != null) "POST " else "GET ")
                    append("$path HTTP/1.1\r\nHost: 127.0.0.1:${server.listeningPort}\r\nConnection: close\r\n")
                    code?.let { append("X-Pairing-Code: $it\r\n") }
                    cookie?.let { append("Cookie: ${it.substringBefore(';')}\r\n") }
                    origin?.let { append("Origin: $it\r\n") }
                    append("Content-Length: ${body?.size ?: 0}\r\nContent-Type: application/epub+zip\r\n\r\n")
                }
                socket.getOutputStream().write(headers.toByteArray())
                body?.let { socket.getOutputStream().write(it) }
                val input = socket.getInputStream().buffered()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) { val next = input.read(); if (next < 0 || next == 10) break; if (next != 13) bytes.write(next) }
                    return bytes.toString("UTF-8")
                }
                val status = line().split(' ')[1].toInt()
                var length = 0
                var responseCookie: String? = null
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    if (header.startsWith("Set-Cookie:", true)) responseCookie = header.substringAfter(':').trim()
                }
                val content = input.readNBytes(length)
                assertEquals(length, content.size)
                Reply(status, content.toString(Charsets.UTF_8), responseCookie)
            }
        }
        try {
            assertTrue(server.code.matches(Regex("[0-9]{6}")))
            val landing = request()
            assertEquals(200, landing.status)
            assertNull(landing.cookie)
            assertFalse(landing.body.contains(server.token))
            assertTrue(landing.body.contains("id=\"upload\" hidden"))
            assertEquals(403, request("/upload", body = fixture().readBytes()).status)
            val wrong = if (server.code == "000000") "000001" else "000000"
            assertEquals(403, request("/pair", code = server.code, origin = "http://other.example").status)
            repeat(5) { assertEquals(403, request("/pair", code = wrong).status) }
            assertEquals(429, request("/pair", code = server.code).status)
            // Rotating the TV code does not bypass the cooldown.
            server.refreshPairing()
            assertEquals(429, request("/pair", code = server.code).status)
            clock += 30001
            val paired = request("/pair", code = server.code)
            assertEquals(200, paired.status)
            val cookie = paired.cookie!!
            assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict"))
            assertFalse(cookie.contains("Max-Age"))
            assertFalse(request(cookie = cookie).body.contains("id=\"upload\" hidden"))
            val data = fixture().readBytes()
            assertEquals(200, request("/upload?name=Browser.epub", cookie = cookie, body = data).status)
            assertEquals(1, books.books().size)
            assertEquals(403, request("/upload", cookie = cookie, body = data, origin = "http://other.example").status)
            val qr = request("/?token=${server.token}")
            assertEquals(200, qr.status); assertNotNull(qr.cookie)
            assertEquals(200, request("/upload", cookie = qr.cookie, body = data).status)
            val oldToken = server.token
            val oldCode = server.code
            server.refreshPairing()
            assertNotEquals(oldCode, server.code)
            assertEquals(403, request("/pair", code = oldCode).status)
            assertEquals(403, request("/upload", cookie = cookie, body = data).status)
            assertEquals(403, request("/?token=$oldToken").status)
            val renewed = request("/pair", code = server.code)
            assertEquals(200, renewed.status)
            clock += 600001
            assertEquals(403, request("/pair", code = server.code).status)
            assertEquals(403, request("/upload", cookie = renewed.cookie, body = data).status)
            assertTrue(request(cookie = renewed.cookie).body.contains("id=\"upload\" hidden"))
        } finally { server.stop() }
    }
    @Test fun rawUploadDeduplicatesRejectsBadTokensAndStopsAtExpiry() {
        var clock = 1000L
        val server = UploadServer(books, ImportBook(books), now = { clock }, onState = { _, _ -> })
        server.start(3000, true)
        val port = server.listeningPort
        fun request(path: String, body: ByteArray? = null, size: Long? = null): Pair<Int, String> = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000); socket.soTimeout = 10000
            val header = buildString {
                append(if (body != null) "POST " else "GET "); append(path)
                append(" HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
                if (body != null) append("Content-Type: application/epub+zip\r\nContent-Length: ${size ?: body.size.toLong()}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(header.toByteArray())
            body?.let { socket.getOutputStream().write(it) }
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val next = input.read()
                    if (next < 0 || next == 10) break
                    if (next != 13) bytes.write(next)
                }
                return bytes.toString("UTF-8")
            }
            val status = line().split(" ")[1].toInt()
            var length = 0
            while (true) {
                val headerLine = line()
                if (headerLine.isEmpty()) break
                if (headerLine.startsWith("Content-Length:", true)) length = headerLine.substringAfter(':').trim().toInt()
            }
            // HTTP messages end at Content-Length, not socket EOF. Rejected uploads may reset
            // the socket afterwards because the request body intentionally remains unread.
            val content = input.readNBytes(length)
            assertEquals(length, content.size)
            status to content.toString(Charsets.UTF_8)
        }
        try {
            assertEquals(200, request("/").first)
            assertEquals(403, request("/upload?token=wrong", byteArrayOf(1)).first)
            assertEquals(200, request("/?token=${server.token}").first)
            val data = fixture().readBytes()
            val upload = "/upload?token=${server.token}&name=${URLEncoder.encode("A Book.epub", "UTF-8")}"
            assertEquals(200, request(upload, data).first)
            assertEquals(1, books.books().size)
            assertTrue(request(upload, data).second.contains("Already in library"))
            assertEquals(1, books.books().size)
            assertEquals(413, request(upload, byteArrayOf(), EpubValidator.MAX_COMPRESSED + 1).first)
            assertEquals(400, request(upload, "not a zip".toByteArray()).first)
            assertEquals(1, books.books().size)
            clock += 600001
            assertEquals(403, request(upload, data).first)
            val expiredToken = server.token
            assertTrue(server.refreshIfExpired())
            assertNotEquals(expiredToken, server.token)
            assertEquals(600, server.remainingSeconds())
            assertEquals(403, request("/?token=$expiredToken").first)
            assertEquals(200, request("/?token=${server.token}").first)
            assertFalse(server.refreshIfExpired())
            val previousToken = server.token
            assertTrue(server.refreshPairing())
            assertEquals(403, request("/?token=$previousToken").first)
            assertEquals(200, request("/?token=${server.token}").first)
        } finally { server.stop() }
        assertTrue(runCatching { request("/?token=${server.token}") }.isFailure)
    }
}
