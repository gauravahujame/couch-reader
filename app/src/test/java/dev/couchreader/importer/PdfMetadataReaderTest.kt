package dev.couchreader.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfMetadataReaderTest {
    private fun read(vararg parts: Any) = PdfMetadataReader().parse(pdf(*parts)).display()

    /** Concatenates text (Latin-1) and raw byte chunks into a PDF body. */
    private fun pdf(vararg parts: Any): ByteArray = ByteArrayOutputStream().apply {
        write("%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n".toByteArray(Charsets.ISO_8859_1))
        parts.forEach { write(if (it is ByteArray) it else (it as String).toByteArray(Charsets.ISO_8859_1)) }
        write("\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
    }.toByteArray()

    private fun deflate(text: String): ByteArray {
        val deflater = Deflater(); deflater.setInput(text.toByteArray(Charsets.UTF_8)); deflater.finish()
        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end(); return out.toByteArray()
    }

    private val catalog = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"

    @Test fun readsLiteralInfoStrings() {
        val result = read(catalog, "3 0 obj\n<< /Title (Deep Work \\(2nd ed.\\)) /Author (Cal Newport) /Producer (pdfTeX) >>\nendobj\n",
            "trailer\n<< /Size 4 /Root 1 0 R /Info 3 0 R >>\nstartxref\n0")
        assertEquals("Deep Work (2nd ed.)" to "Cal Newport", result)
    }

    @Test fun decodesUtf16HexAndPdfDocEncoding() {
        // "Café — Notes" in UTF-16BE with BOM; author in PDFDocEncoding (0x84 is an em dash, 0xE9 is é).
        val title = "FEFF" + "Café — Notes".toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) }
        val author = "4 0 obj\n<< /Title <$title> /Author (Ren\\351e \\204 Studio) >>\nendobj\n"
        assertEquals("Café — Notes" to "Renée — Studio", read(catalog, author, "trailer << /Root 1 0 R /Info 4 0 R >>"))
    }

    @Test fun acceptsBomlessUtf8() {
        val info = "3 0 obj\n<< /Title (".toByteArray(Charsets.ISO_8859_1) + "Ελληνικά".toByteArray(Charsets.UTF_8) +
            ") >>\nendobj\ntrailer << /Root 1 0 R /Info 3 0 R >>".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Ελληνικά", read(catalog, info).first)
    }

    @Test fun resolvesIndirectStringsAndTheLatestIncrementalUpdate() {
        val result = read(catalog,
            "3 0 obj\n<< /Title (Draft) >>\nendobj\ntrailer << /Root 1 0 R /Info 3 0 R >>\n",
            "5 0 obj\n(The Final Title)\nendobj\n3 0 obj\n<< /Title 5 0 R /Author (Jane Doe) >>\nendobj\n",
            "trailer << /Root 1 0 R /Info 3 0 R /Prev 0 >>")
        assertEquals("The Final Title" to "Jane Doe", result)
    }

    @Test fun prefersXmpAndReadsCreatorLists() {
        val xmp = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF>
            <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title><rdf:Alt><rdf:li xml:lang="fr">Le Titre</rdf:li><rdf:li xml:lang="x-default">Pride &amp; Prejudice</rdf:li></rdf:Alt></dc:title>
            <dc:creator><rdf:Seq><rdf:li>Jane Austen</rdf:li><rdf:li>Unknown</rdf:li></rdf:Seq></dc:creator>
            </rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
        val compressed = deflate(xmp)
        val result = read(
            "1 0 obj\n<< /Type /Catalog /Metadata 6 0 R >>\nendobj\n",
            "6 0 obj\n<< /Type /Metadata /Subtype /XML /Filter /FlateDecode /Length ${compressed.size} >>\nstream\n", compressed, "\nendstream\nendobj\n",
            "3 0 obj\n<< /Title (Microsoft Word - pp.docx) /Author (Administrator) >>\nendobj\n",
            "trailer << /Root 1 0 R /Info 3 0 R >>")
        assertEquals("Pride & Prejudice" to "Jane Austen", result)
    }

    @Test fun fallsBackToInfoWhenXmpIsPlaceholder() {
        val xmp = "<x:xmpmeta><rdf:RDF><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Untitled</rdf:li></rdf:Alt></dc:title></rdf:RDF></x:xmpmeta>"
        val result = read(
            "1 0 obj\n<< /Type /Catalog /Metadata 6 0 R >>\nendobj\n",
            "6 0 obj\n<< /Type /Metadata /Length 7 0 R >>\nstream\n$xmp\nendstream\nendobj\n7 0 obj\n${xmp.length}\nendobj\n",
            "3 0 obj\n<< /Title (Field Guide) >>\nendobj\ntrailer << /Root 1 0 R /Info 3 0 R >>")
        assertEquals("Field Guide" to null, result)
    }

    @Test fun readsInfoPackedInCompressedObjectStreams() {
        val objects = "<< /Type /Catalog >> << /Title (Packed Title) /Author (Grace Hopper) >>"
        val header = "1 0 3 20 "
        val body = deflate(header + objects)
        val result = read(
            "8 0 obj\n<< /Type /ObjStm /N 2 /First ${header.length} /Filter /FlateDecode /Length ${body.size} >>\nstream\n", body, "\nendstream\nendobj\n",
            "9 0 obj\n<< /Type /XRef /Size 10 /Root 1 0 R /Info 3 0 R /W [1 2 1] /Length 0 >>\nstream\n\nendstream\nendobj\nstartxref\n0")
        assertEquals("Packed Title" to "Grace Hopper", result)
    }

    @Test fun ignoresEncryptedStrings() {
        val result = read(catalog, "3 0 obj\n<< /Title (\u00a9\u0081\u00f3x\u0010) >>\nendobj\n4 0 obj\n<< /Filter /Standard /V 2 >>\nendobj\n",
            "trailer << /Root 1 0 R /Info 3 0 R /Encrypt 4 0 R >>")
        assertEquals(null to null, result)
    }

    @Test fun survivesMissingAndCorruptMetadata() {
        val reader = PdfMetadataReader()
        assertEquals(PdfMetadata(), reader.parse(ByteArray(0)))
        assertEquals(PdfMetadata(), reader.parse(pdf(catalog, "trailer << /Root 1 0 R >>")))
        assertEquals(PdfMetadata(), reader.parse(pdf("trailer << /Info 3 0 R >>")))
        assertEquals(PdfMetadata(), reader.parse(pdf("3 0 obj\n<< /Title (unterminated\nendobj\ntrailer << /Info 3 0 R >>")))
        assertEquals(PdfMetadata(), reader.parse(pdf("3 0 obj\n" + "[".repeat(5000) + "\nendobj\ntrailer << /Info 3 0 R >>")))
        assertNull(read(catalog, "13 0 obj\n<< /Title (Wrong object) >>\nendobj\ntrailer << /Root 1 0 R /Info 3 0 R >>").first)
    }
}
