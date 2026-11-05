package dev.couchreader.importer

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class ImportFailure(message: String) : Exception(message)
data class ValidatedBook(val title: String, val author: String, val contentPath: String, val hash: String)

/** Strict package validation and bounded extraction. No archive path is trusted. */
class EpubValidator {
    companion object {
        const val MAX_COMPRESSED = 50L * 1024 * 1024
        const val MAX_EXPANDED = 200L * 1024 * 1024
        const val MAX_ENTRY = 16L * 1024 * 1024
        const val MAX_ENTRIES = 4000
    }
    fun validate(source: File, destination: File, filename: String): ValidatedBook {
        if (source.length() > MAX_COMPRESSED) throw ImportFailure("File too large. Choose an EPUB smaller than 50 MiB.")
        if (!destination.mkdirs()) throw ImportFailure("Cannot create book storage. Free some space and try again.")
        try {
            ZipFile(source).use { zip ->
                var expanded = 0L
                var count = 0
                val seen = HashSet<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (++count > MAX_ENTRIES) throw ImportFailure("Unsafe archive: too many entries (maximum 4,000).")
                    val path = safePath(entry.name)
                    if (!seen.add(path)) throw ImportFailure("Invalid EPUB: duplicate archive paths.")
                    val target = File(destination, path)
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    if (entry.size > MAX_ENTRY) throw ImportFailure("Unsafe archive: an entry exceeds 16 MiB.")
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n; expanded += n
                            if (size > MAX_ENTRY || expanded > MAX_EXPANDED) throw ImportFailure("Unsafe archive: expanded content exceeds limits.")
                            if (Thread.currentThread().isInterrupted) throw ImportFailure("Transfer interrupted. Open Add book and try again.")
                            out.write(buffer, 0, n)
                        }
                    } }
                }
            }
            if (File(destination, "mimetype").takeIf { it.isFile }?.readText()?.trim() != "application/epub+zip")
                throw ImportFailure("Invalid format. Choose a DRM-free EPUB 2 or EPUB 3 book.")
            if (File(destination, "META-INF/encryption.xml").exists() || File(destination, "META-INF/rights.xml").exists())
                throw ImportFailure("Encrypted or DRM-protected EPUBs are not supported. Use a DRM-free copy.")
            val container = xml(File(destination, "META-INF/container.xml"))
            val roots = container.getElementsByTagNameNS("*", "rootfile")
            if (roots.length == 0) throw ImportFailure("Invalid EPUB: package is missing.")
            val opfPath = safePath((roots.item(0) as Element).getAttribute("full-path"))
            val opfFile = File(destination, opfPath)
            val opf = xml(opfFile)
            val version = opf.documentElement.getAttribute("version")
            if (!version.startsWith("2.") && !version.startsWith("3.")) throw ImportFailure("Unsupported EPUB version. Use EPUB 2 or EPUB 3.")
            val meta = opf.getElementsByTagNameNS("*", "meta")
            for (i in 0 until meta.length) {
                val m = meta.item(i) as Element
                if ((m.getAttribute("property").endsWith("layout") && m.textContent.trim() == "pre-paginated") ||
                    (m.getAttribute("name") == "fixed-layout" && m.getAttribute("content") == "true"))
                    throw ImportFailure("Fixed-layout EPUBs are not supported. Choose a reflowable book.")
            }
            val manifest = opf.getElementsByTagNameNS("*", "item")
            val items = HashMap<String, Pair<String, String>>()
            for (i in 0 until manifest.length) {
                val item = manifest.item(i) as Element
                val href = item.getAttribute("href")
                val uri = URI(href)
                if (uri.isAbsolute || href.startsWith("//")) throw ImportFailure("EPUB requires remote resources. Choose an offline EPUB.")
                val path = resolve(opfPath, href)
                if (!File(destination, path).isFile) throw ImportFailure("Invalid EPUB: a packaged resource is missing.")
                if (item.getAttribute("properties").contains("rendition:layout-pre-paginated")) throw ImportFailure("Fixed-layout EPUBs are not supported.")
                items[item.getAttribute("id")] = path to item.getAttribute("media-type")
            }
            val spines = opf.getElementsByTagNameNS("*", "spine")
            if (spines.length == 0) throw ImportFailure("Invalid EPUB: reading order is missing.")
            if ((spines.item(0) as Element).getAttribute("page-progression-direction") == "rtl")
                throw ImportFailure("Right-to-left EPUBs are not supported in this release.")
            val refs = opf.getElementsByTagNameNS("*", "itemref")
            if (refs.length == 0) throw ImportFailure("Invalid EPUB: book contains no readable sections.")
            for (i in 0 until refs.length) {
                val ref = refs.item(i) as Element
                if (ref.getAttribute("properties").contains("layout-pre-paginated")) throw ImportFailure("Fixed-layout EPUBs are not supported.")
                val item = items[ref.getAttribute("idref")] ?: throw ImportFailure("Invalid EPUB: missing spine section.")
                if (item.second != "application/xhtml+xml") throw ImportFailure("Unsupported section format. Choose a text-based reflowable EPUB.")
            }
            // Sanitize every markup resource, including unlisted ones that might be linked internally.
            destination.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(destination).invariantSeparatorsPath
                val type = items.values.firstOrNull { it.first == relative }?.second
                if (type == "application/xhtml+xml" || file.extension.lowercase() in listOf("xhtml", "html", "htm", "svg")) sanitize(file)
                if (file.extension.equals("css", true)) file.writeText("") // No publisher imports, fonts or network URLs.
            }
            fun metadata(name: String): String = opf.getElementsByTagNameNS("*", name).let {
                if (it.length > 0) it.item(0).textContent.trim().take(300) else ""
            }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input -> val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return ValidatedBook(metadata("title").ifBlank { filename.substringBeforeLast('.').take(300).ifBlank { "Untitled book" } },
                metadata("creator").ifBlank { "Unknown author" }, opfPath,
                digest.digest().joinToString("") { "%02x".format(it) })
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (e is ImportFailure) throw e
            throw ImportFailure("Invalid or interrupted EPUB. Choose another file and try again.")
        }
    }
    internal fun safePath(path: String): String {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
            path.trimEnd('/').split('/').any { it.isEmpty() || it == ".." || it == "." } || path.contains(':') || path.contains('%'))
            throw ImportFailure("Unsafe archive path.")
        return path.trimEnd('/')
    }
    private fun resolve(base: String, href: String): String {
        val resolved = URI(base).resolve(href).normalize()
        if (resolved.isAbsolute) throw ImportFailure("Remote resources are not supported.")
        return safePath(resolved.path)
    }
    private fun xml(file: File): org.w3c.dom.Document {
        if (!file.isFile || file.length() > MAX_ENTRY) throw ImportFailure("Invalid EPUB package.")
        // EPUB XML is UTF-8 or UTF-16. Inspect before parsing because Android's XML
        // implementation does not support every Xerces security-feature switch.
        val bytes = file.readBytes()
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val raw = bytes.toString(charset).removePrefix("\uFEFF")
        if (!raw.trimStart().startsWith("<") || raw.contains('\u0000')) throw ImportFailure("Invalid EPUB XML encoding.")
        if (raw.contains("<!DOCTYPE", true) || raw.contains("<!ENTITY", true)) throw ImportFailure("Invalid EPUB: XML declarations are not allowed.")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        }.parse(file)
    }
    private fun sanitize(file: File) {
        val doc = Jsoup.parse(file, "UTF-8")
        doc.select("script,style,link,iframe,frame,frameset,object,embed,form,input,button,textarea,select,base,meta,audio,video,svg,math").remove()
        for (element in doc.allElements) {
            element.attributes().asList().forEach { attr ->
                val key = attr.key.lowercase()
                val value = attr.value.trim()
                if (key.startsWith("on") || key in listOf("style", "srcset", "srcdoc", "action", "formaction", "background", "ping")) element.removeAttr(attr.key)
                if (key in listOf("href", "src", "xlink:href") && runCatching {
                    val uri = URI(value)
                    uri.isAbsolute || value.startsWith("//") || value.contains('\\')
                }.getOrDefault(true)) element.removeAttr(attr.key)
            }
        }
        doc.head().appendElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
            "default-src 'none'; img-src https://reader.invalid; style-src 'unsafe-inline'; font-src https://reader.invalid; script-src 'none'")
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).charset("UTF-8")
        file.writeText(doc.outerHtml())
    }
}
