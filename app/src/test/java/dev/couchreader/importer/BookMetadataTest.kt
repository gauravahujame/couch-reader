package dev.couchreader.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMetadataTest {
    private fun file(name: String) = BookMetadata.titleFromFilename(name, "Untitled PDF")

    @Test fun filenamesBecomeReadableTitles() {
        assertEquals("Understanding Harness Engineering", file("Understanding_Harness_Engineering.pdf"))
        assertEquals("The Great Gatsby", file("the-great-gatsby.pdf"))
        assertEquals("The Great Gatsby", file("the.great.gatsby.pdf"))
        assertEquals("War and Peace", file("war+and+peace.pdf"))
        assertEquals("My Book", file("My%20Book.pdf"))
        assertEquals("Annual Report", file("C:\\Users\\me\\Downloads\\Annual Report.pdf"))
        assertEquals("Meeting Notes", file("meeting_notes.docx.pdf"))
    }

    @Test fun filenamesKeepMeaningfulPunctuationAndCase() {
        assertEquals("Spider-Man", file("Spider-Man.pdf"))
        assertEquals("Vol. 2 - The Return", file("Vol. 2 - The Return.pdf"))
        assertEquals("iOS Programming", file("iOS Programming.pdf"))
        assertEquals("Annual Report (2019)", file("Annual Report (2019).pdf"))
        assertEquals("Advertising Copy", file("Advertising Copy.pdf"))
        assertEquals("1984", file("1984.pdf"))
        assertEquals("rfc9110", file("rfc9110.pdf"))
        assertEquals("Notes.backup", file("Notes.backup"))
    }

    @Test fun filenamesDropDownloadAndDuplicateNoise() {
        assertEquals("Deep Work", file("Deep Work (z-lib.org).pdf"))
        assertEquals("Deep Work", file("Deep Work [PDF] (1).pdf"))
        assertEquals("Deep Work", file("Deep Work - Copy (2).pdf"))
        assertEquals("Deep Work", file("deep_work_compressed.pdf"))
        assertEquals("Deep Work", file("Deep Work (Anna’s Archive).epub"))
    }

    @Test fun emptyFilenamesFallBack() {
        assertEquals("Untitled PDF", file(".pdf"))
        assertEquals("Untitled PDF", file("___.pdf"))
        assertEquals("Untitled PDF", file("(1).pdf"))
    }

    @Test fun metadataTitlesRejectGeneratorNoise() {
        assertEquals("Quarterly Plan", BookMetadata.cleanTitle("Microsoft Word - Quarterly Plan.docx"))
        assertEquals("Thesis Final", BookMetadata.cleanTitle("C:\\work\\thesis_final.tex"))
        assertEquals("Slides", BookMetadata.cleanTitle("slides.pptx"))
        assertNull(BookMetadata.cleanTitle("Microsoft Word - Document1"))
        listOf("", "   ", "Untitled", "untitled-1", "Untitled document", "Document1", "title", "Slide 1", "PDF", "--",
            "a1b2c3d4e5f60718", "123456789", "scan0042", "IMG_2041", "(anonymous)").forEach {
            assertNull("\"$it\" should be rejected", BookMetadata.cleanTitle(it))
        }
    }

    @Test fun metadataTitlesKeepRealTitles() {
        assertEquals("1984", BookMetadata.cleanTitle("1984"))
        assertEquals("Filesystems in Practice", BookMetadata.cleanTitle("Filesystems in Practice"))
        assertEquals("Node.js Design Patterns", BookMetadata.cleanTitle("Node.js Design Patterns"))
        assertEquals("Café Society", BookMetadata.cleanTitle("  Café\u0000  Society\n"))
        assertEquals("मानक हिन्दी", BookMetadata.cleanTitle("मानक हिन्दी"))
    }

    @Test fun longTitlesAreCutAtAWord() {
        val title = BookMetadata.cleanTitle(("word ").repeat(100))!!
        assertTrue(title.length <= BookMetadata.MAX_LENGTH)
        assertTrue(title.endsWith("word…"))
    }

    @Test fun authorsRejectPlaceholdersAndSoftware() {
        listOf("Unknown", "Administrator", "admin1", "user", "Owner", "Microsoft Office User", "Windows User", "HP", "n/a",
            "Adobe Acrobat 9.0", "pdfTeX-1.40.21", "LaTeX with hyperref", "?").forEach {
            assertNull("\"$it\" should be rejected", BookMetadata.cleanAuthor(it))
        }
        assertNull(BookMetadata.cleanAuthor(null))
    }

    @Test fun authorsKeepPeopleAndJoinLists() {
        assertEquals("Ursula K. Le Guin", BookMetadata.cleanAuthor(" Ursula K.  Le Guin "))
        assertEquals("Ada Lovelace, Charles Babbage", BookMetadata.cleanAuthor("Ada Lovelace; Charles Babbage; ada lovelace"))
        assertEquals("A, B, C and others", BookMetadata.cleanAuthor("A; B; C; D"))
        assertEquals("Jane Doe", BookMetadata.cleanAuthor("Administrator; Jane Doe"))
        assertEquals("IBM", BookMetadata.cleanAuthor("IBM"))
        assertNull(BookMetadata.cleanAuthor("C:DC:TS:CAR:MP"))
    }
}
