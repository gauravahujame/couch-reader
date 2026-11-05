package dev.couchreader.importer

import dev.couchreader.data.Book
import dev.couchreader.data.BookRepository
import java.io.File
import java.util.UUID

data class ImportResult(val book: Book, val duplicate: Boolean)
class ImportBook(private val books: BookRepository) {
    @Synchronized fun execute(source: File, filename: String): ImportResult {
        val id = UUID.randomUUID().toString()
        val stage = File(books.root, ".pending-$id")
        try {
            val signature = source.inputStream().use { input -> ByteArray(5).also { input.read(it) } }.toString(Charsets.US_ASCII)
            val format = if (signature == "%PDF-" || filename.endsWith(".pdf", ignoreCase = true)) "pdf" else "epub"
            val validated = if (format == "pdf") PdfValidator().validate(source, stage, filename)
                else EpubValidator().validate(source, stage, filename)
            books.duplicate(validated.hash)?.let { return ImportResult(it, true) }
            if (Thread.currentThread().isInterrupted) throw ImportFailure("Import interrupted. Try again.")
            val book = Book(id, validated.hash, validated.title, validated.author, validated.contentPath, System.currentTimeMillis(), 0, format)
            books.commit(book, stage)
            return ImportResult(book, false)
        } finally { stage.deleteRecursively(); source.delete() }
    }
}
