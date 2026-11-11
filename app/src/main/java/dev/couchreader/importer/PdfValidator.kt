package dev.couchreader.importer

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest

/** Native PDF parsing performs no scripting or remote resource loading. */
class PdfValidator {
    fun validate(source: File, destination: File, filename: String): ValidatedBook {
        if (source.length() <= 5 || source.length() > EpubValidator.MAX_COMPRESSED)
            throw ImportFailure("Choose a non-empty PDF smaller than 50 MiB.")
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0 || renderer.pageCount > 10000)
                        throw ImportFailure("This PDF has no readable pages or exceeds the 10,000-page limit.")
                    renderer.openPage(0).use { page ->
                        if (page.width <= 0 || page.height <= 0) throw ImportFailure("Invalid PDF page dimensions.")
                    }
                }
            }
            check(destination.mkdirs())
            source.copyTo(File(destination, "book.pdf"))
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input -> val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val metadata = describe(source, filename)
            return ValidatedBook(metadata.title, metadata.author, "book.pdf", digest.digest().joinToString("") { "%02x".format(it) })
        } catch (failure: Exception) {
            destination.deleteRecursively()
            if (failure is ImportFailure) throw failure
            if (failure is SecurityException) throw ImportFailure("Password-protected PDFs are not supported. Choose an unlocked copy.")
            throw ImportFailure("This PDF could not be opened. Choose a valid, unlocked PDF and try again.")
        }
    }
    companion object {
        const val UNTITLED = "Untitled PDF"
        /** Embedded title and author when they are meaningful, else a title tidied from [filename] and no author. */
        fun describe(pdf: File, filename: String): DisplayMetadata {
            val (title, author) = PdfMetadataReader().read(pdf).display()
            return DisplayMetadata(title ?: BookMetadata.titleFromFilename(filename, UNTITLED), author.orEmpty())
        }
    }
}
