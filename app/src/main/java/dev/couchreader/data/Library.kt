package dev.couchreader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

/** Content files are finalized before the catalog transaction. Startup removes orphan directories. */
data class Book(val id: String, val hash: String, val title: String, val author: String,
                val contentPath: String, val imported: Long, val opened: Long, val format: String = "epub")
/** Versioned format-specific payloads; PDF positions never pretend to be EPUB CFIs. */
data class Locator(val cfi: String = "", val href: String = "", val version: Int = 1,
                   val format: String = "epub", val page: Int = 0, val offset: Float = 0f) {
    fun encode(): String = JSONObject().put("version", version).put("format", format).apply {
        if (format == "pdf") { put("page", page); put("offset", offset) }
        else { put("cfi", cfi); put("href", href) }
    }.toString()
    companion object {
        fun pdf(page: Int, offset: Float = 0f) = Locator(format = "pdf", page = page, offset = offset)
        fun decode(value: String?): Locator? = runCatching {
            val obj = JSONObject(value ?: return null)
            require(obj.getInt("version") == 1)
            when (obj.getString("format")) {
                "epub" -> Locator(obj.getString("cfi"), obj.getString("href"))
                "pdf" -> {
                    val page = obj.getInt("page"); val offset = obj.optDouble("offset", 0.0)
                    require(page >= 0 && offset.isFinite() && offset in 0.0..1.0)
                    pdf(page, offset.toFloat())
                }
                else -> null
            }
        }.getOrNull()
    }
}
data class ReadingPreferences(val size: Int = 2, val dark: Boolean = false,
                              val spread: Boolean = false, val sleepMinutes: Int = 30) {
    companion object { val sizes = listOf(24, 28, 32, 36, 42, 48); val sleepOptions = listOf(15, 30, 60, 0) }
}
class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "library.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, hash TEXT NOT NULL UNIQUE, title TEXT NOT NULL, author TEXT NOT NULL, opf TEXT NOT NULL, imported INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 0, format TEXT NOT NULL DEFAULT 'epub')")
        db.execSQL("CREATE TABLE positions(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE, locator TEXT NOT NULL, updated INTEGER NOT NULL)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'epub'")
        check(newVersion <= 2) { "A migration is required before changing the database version" }
    }
}
class BookRepository(context: Context, private val db: LibraryDatabase) {
    val root = File(context.filesDir, "books").apply { mkdirs() }
    val staging = File(context.cacheDir, "imports").apply { mkdirs() }
    @Synchronized fun books(): List<Book> = db.readableDatabase.rawQuery(
        "SELECT id, hash, title, author, opf, imported, opened, format FROM books ORDER BY opened DESC, imported DESC", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(Book(cursor.getString(0), cursor.getString(1), cursor.getString(2),
            cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getLong(6), cursor.getString(7)))
    } }
    fun directory(book: Book) = File(root, book.id)
    @Synchronized fun duplicate(hash: String) = books().firstOrNull { it.hash == hash }
    @Synchronized fun commit(book: Book, stage: File) {
        check(stage.renameTo(directory(book))) { "Not enough storage to finalize book. Try again." }
        val sql = db.writableDatabase
        sql.beginTransaction()
        try {
            sql.insertOrThrow("books", null, ContentValues().apply {
                put("id", book.id); put("hash", book.hash); put("title", book.title); put("format", book.format)
                put("author", book.author); put("opf", book.contentPath); put("imported", book.imported); put("opened", book.opened)
            })
            sql.setTransactionSuccessful()
        } catch (failure: Exception) {
            directory(book).deleteRecursively()
            throw failure
        } finally { sql.endTransaction() }
    }
    @Synchronized fun opened(book: Book) {
        db.writableDatabase.update("books", ContentValues().apply { put("opened", System.currentTimeMillis()) }, "id=?", arrayOf(book.id))
    }
    @Synchronized fun remove(book: Book) {
        // Delete catalog first. A crash leaves an orphan directory that reconciliation removes.
        db.writableDatabase.delete("books", "id=?", arrayOf(book.id))
        directory(book).deleteRecursively()
    }
    @Synchronized fun reconcile() {
        staging.listFiles()?.forEach { it.deleteRecursively() }
        val known = books().associateBy { it.id }
        root.listFiles()?.filter { it.name !in known }?.forEach { it.deleteRecursively() }
        known.values.filter { !File(directory(it), it.contentPath).isFile }.forEach(::remove)
    }
}
class PositionRepository(private val db: LibraryDatabase) {
    @Synchronized fun hasSavedPosition(bookId: String): Boolean = db.readableDatabase.rawQuery(
        "SELECT 1 FROM positions WHERE book_id=?", arrayOf(bookId)
    ).use { it.moveToFirst() }
    @Synchronized fun get(bookId: String): Locator? = db.readableDatabase.rawQuery(
        "SELECT locator FROM positions WHERE book_id=?", arrayOf(bookId)
    ).use { if (it.moveToFirst()) Locator.decode(it.getString(0)) else null }
    @Synchronized fun save(bookId: String, locator: Locator) {
        db.writableDatabase.insertWithOnConflict("positions", null, ContentValues().apply {
            put("book_id", bookId); put("locator", locator.encode()); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
class PreferencesRepository(context: Context) {
    private val storage = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
    fun get() = ReadingPreferences(storage.getInt("size", 2).coerceIn(0, 5), storage.getBoolean("dark", false),
        storage.getBoolean("spread", false), storage.getInt("sleepMinutes", 30).takeIf { it in ReadingPreferences.sleepOptions } ?: 30)
    fun save(value: ReadingPreferences) { storage.edit().putInt("version", 2).putInt("size", value.size).putBoolean("dark", value.dark)
        .putBoolean("spread", value.spread).putInt("sleepMinutes", value.sleepMinutes).apply() }
}
