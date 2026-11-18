package dev.couchreader.importer

import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.File

/**
 * Finds the publisher's cover image in a stored (already validated and sanitized) EPUB, so fresh imports
 * and books imported by older releases are handled the same way without the original upload.
 *
 * [candidates] lists local raster files in priority order; the caller decodes them in turn and moves on
 * when one cannot produce an image. Priorities (document order within each):
 * 1. EPUB 3 manifest items whose `properties` contain the exact token `cover-image`;
 * 2. EPUB 2 `<meta name="cover" content="manifest-id">`;
 * 3. the OPF guide reference of type `cover` (a raster image, or a cover page holding one), then
 *    manifest images explicitly named "cover" (`images/cover.jpg`, `id="cover"`).
 * No arbitrary illustration is guessed: the generated cover is preferred to a wrong picture.
 */
class EpubCoverResolver(private val bookDirectory: File, private val opfPath: String) {
    companion object {
        /** Distinct image files offered to the decoder. */
        const val MAX_CANDIDATES = 8
        /** A cover page (XHTML wrapper) may point at an image; that image may not point further. */
        const val MAX_WRAPPER_DEPTH = 1
        const val MAX_WRAPPER_BYTES = 1L * 1024 * 1024
        private val rasterTypes = setOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/bmp")
        private val rasterExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        private val markupTypes = setOf("application/xhtml+xml", "text/html")
        private val markupExtensions = setOf("xhtml", "html", "htm")
        private val coverName = Regex("(^|[^a-z])cover([^a-z]|$)", RegexOption.IGNORE_CASE)

        /**
         * Resolves [reference], declared inside the book file at [declaringPath] (relative to [root]), to a
         * regular file inside [root]. Fragments and queries are dropped, percent escapes decoded and case kept.
         * Returns null for schemes (including `data:`), authorities, absolute paths, traversal out of the
         * book, symlinks leaving it, and anything that is not an existing regular file.
         */
        internal fun resolveLocal(root: File, declaringPath: String, reference: String): File? {
            val raw = reference.trim()
            if (raw.isEmpty() || raw.startsWith("/") || raw.contains('\\') || raw.contains('\u0000')) return null
            val path = raw.substringBefore('#').substringBefore('?')
            if (path.isEmpty()) return null
            // A colon before the first slash is a scheme (http:, file:, data:); EPUB paths never need one.
            val colon = path.indexOf(':'); val slash = path.indexOf('/')
            if (colon >= 0 && (slash < 0 || colon < slash)) return null
            val decoded = percentDecode(path) ?: return null
            if (decoded.contains('\u0000') || decoded.contains('\\')) return null
            val segments = ArrayList(declaringPath.split('/').dropLast(1).filter { it.isNotEmpty() })
            for (segment in decoded.split('/')) when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
            if (segments.isEmpty()) return null
            val file = File(root, segments.joinToString("/"))
            return runCatching {
                val base = root.canonicalFile
                val canonical = file.canonicalFile
                canonical.takeIf { it.path.startsWith(base.path + File.separator) && it.isFile }
            }.getOrNull()
        }

        /** Strict RFC 3986 percent decoding (UTF-8); '+' stays a plus. Null for a malformed escape. */
        private fun percentDecode(value: String): String? {
            if (!value.contains('%')) return value
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    out.write(byte); i += 3
                } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
            }
            return out.toString("UTF-8")
        }

        fun isRaster(file: File, mediaType: String = ""): Boolean =
            mediaType.lowercase() in rasterTypes || (mediaType.isBlank() && file.extension.lowercase() in rasterExtensions)
    }

    private data class Item(val id: String, val href: String, val mediaType: String, val properties: List<String>, val file: File?)

    /** Local raster images to try, best first, at most [MAX_CANDIDATES]. Never throws for a malformed book. */
    fun candidates(): List<File> {
        val opfFile = resolveLocal(bookDirectory, "", opfPath) ?: return emptyList()
        val opf = runCatching { EpubValidator().xml(opfFile) }.getOrNull() ?: return emptyList()
        val items = opf.getElementsByTagNameNS("*", "item").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }.map { item ->
                val href = item.getAttribute("href")
                Item(item.getAttribute("id"), href, item.getAttribute("media-type").trim(),
                    item.getAttribute("properties").split(Regex("\\s+")).filter { it.isNotEmpty() },
                    resolveLocal(bookDirectory, opfPath, href))
            }
        }
        val byId = items.associateBy { it.id }
        val result = LinkedHashSet<File>()
        fun offer(file: File?) { if (file != null && result.size < MAX_CANDIDATES) result += file }
        fun offerItem(item: Item?) {
            val file = item?.file ?: return
            when {
                isRaster(file, item.mediaType) -> offer(file)
                item.mediaType in markupTypes || (item.mediaType.isBlank() && file.extension.lowercase() in markupExtensions) ->
                    wrapperImages(file).forEach(::offer)
            }
        }
        // 1. EPUB 3: exact `cover-image` token, not a substring such as "cover-image-small".
        items.filter { "cover-image" in it.properties }.forEach(::offerItem)
        // 2. EPUB 2: <meta name="cover" content="manifest id">.
        opf.getElementsByTagNameNS("*", "meta").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttribute("name").trim().equals("cover", true) }
                .forEach { offerItem(byId[it.getAttribute("content").trim()]) }
        }
        // 3. Guide reference of type cover, then images explicitly named "cover".
        opf.getElementsByTagNameNS("*", "reference").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttribute("type").trim().equals("cover", true) }
                .forEach { reference ->
                    val file = resolveLocal(bookDirectory, opfPath, reference.getAttribute("href")) ?: return@forEach
                    val declared = items.firstOrNull { it.file == file }
                    offerItem(declared ?: Item("", "", "", emptyList(), file))
                }
        }
        items.filter { item -> item.file != null && isRaster(item.file, item.mediaType) &&
            !item.file.nameWithoutExtension.contains("back", true) &&
            (coverName.containsMatchIn(item.file.nameWithoutExtension) || coverName.containsMatchIn(item.id)) }
            .forEach(::offerItem)
        return result.toList()
    }

    /**
     * Raster images referenced by a cover page, in document order. The page was sanitized at import:
     * scripts, styles and inline SVG are gone, so only a surviving local `<img src>` qualifies.
     * Pure vector covers fall through to the next candidate or the generated cover.
     */
    private fun wrapperImages(wrapper: File): List<File> {
        if (MAX_WRAPPER_DEPTH < 1 || wrapper.length() > MAX_WRAPPER_BYTES) return emptyList()
        val declaringPath = wrapper.canonicalFile.relativeTo(bookDirectory.canonicalFile).invariantSeparatorsPath
        val document = runCatching { Jsoup.parse(wrapper, "UTF-8") }.getOrNull() ?: return emptyList()
        return document.select("img[src]").mapNotNull { image ->
            resolveLocal(bookDirectory, declaringPath, image.attr("src"))?.takeIf { isRaster(it) }
        }
    }
}
