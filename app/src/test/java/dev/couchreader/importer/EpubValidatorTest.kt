package dev.couchreader.importer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubValidatorTest {
    @get:Rule val temp = TemporaryFolder()
    private fun epub(version: String = "3.0", extras: Map<String, String> = emptyMap(),
                     metadata: String = "", chapter: String = "<p>A quiet beginning.</p>"): File {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "EPUB/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="$version"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>A Quiet Book</dc:title><dc:creator>A Reader</dc:creator>$metadata</metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "EPUB/chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body>$chapter</body></html>"""
        ).apply { putAll(extras) }
        return temp.newFile().apply { ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } } }
    }
    private fun stage() = File(temp.root, "stage-${System.nanoTime()}")
    @Test fun acceptsEpub2And3AndUsesMetadata() {
        for (version in listOf("2.0", "3.0")) {
            val result = EpubValidator().validate(epub(version), stage(), "fallback.epub")
            assertEquals("A Quiet Book", result.title); assertEquals("A Reader", result.author)
            assertEquals("EPUB/package.opf", result.contentPath); assertEquals(64, result.hash.length)
        }
    }
    @Test fun hashIsStableForIdenticalBytes() {
        val source = epub()
        assertEquals(EpubValidator().validate(source, stage(), "a.epub").hash,
            EpubValidator().validate(source, stage(), "b.epub").hash)
    }
    @Test fun removesScriptsHandlersRemoteImagesAndPublisherCss() {
        val dest = stage()
        EpubValidator().validate(epub(extras = mapOf("EPUB/theme.css" to "@import 'https://evil.invalid/x';"),
            chapter = """<script>alert(1)</script><p onclick="attack()" style="background:url(https://evil.invalid)">Safe text</p><img src="https://evil.invalid/x"/><iframe srcdoc="attack"/><img src="images/local.png"/>"""), dest, "book.epub")
        val text = File(dest, "EPUB/chapter.xhtml").readText()
        assertFalse(text.contains("<script")); assertFalse(text.contains("onclick")); assertFalse(text.contains("evil.invalid"))
        assertFalse(text.contains("<iframe")); assertTrue(text.contains("Safe text")); assertTrue(text.contains("images/local.png"))
        assertEquals("", File(dest, "EPUB/theme.css").readText())
    }
    @Test fun rejectsTraversalAndCleansStage() {
        val dest = stage()
        fails("Unsafe archive") { EpubValidator().validate(epub(extras = mapOf("../escaped" to "bad")), dest, "book.epub") }
        assertFalse(dest.exists()); assertFalse(File(temp.root, "escaped").exists())
    }
    @Test fun rejectsDrm() {
        fails("DRM") { EpubValidator().validate(epub(extras = mapOf("META-INF/encryption.xml" to "<encryption/>")), stage(), "book.epub") }
    }
    @Test fun rejectsFixedLayout() {
        fails("Fixed-layout") { EpubValidator().validate(epub(metadata = """<meta property="rendition:layout">pre-paginated</meta>"""), stage(), "book.epub") }
    }
    @Test fun rejectsXmlExternalEntities() {
        fails("Invalid") { EpubValidator().validate(epub(extras = mapOf("META-INF/container.xml" to
            """<!DOCTYPE container [<!ENTITY external SYSTEM "file:///etc/passwd">]><container>&external;</container>""")), stage(), "book.epub") }
    }
    @Test fun rejectsMissingSpineResource() {
        fails("missing") { EpubValidator().validate(epub(extras = mapOf("EPUB/package.opf" to
            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="x" href="absent.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="x"/></spine></package>""")), stage(), "book.epub") }
    }
    @Test fun rejectsZipBombEntry() {
        fails("16 MiB") { EpubValidator().validate(epub(extras = mapOf("bomb" to "a".repeat(17 * 1024 * 1024))), stage(), "book.epub") }
    }
    @Test fun rejectsTooManyEntries() {
        fails("too many") { EpubValidator().validate(epub(extras = (1..4001).associate { "item-$it" to "" }), stage(), "book.epub") }
    }
    private fun fails(fragment: String, action: () -> Unit) {
        try { action(); fail("Expected ImportFailure") } catch (e: ImportFailure) { assertTrue(e.message, e.message!!.contains(fragment, true)) }
    }
}
