package dev.couchreader.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

/** Candidate values as stored in the file. Use [BookMetadata.cleanTitle]/[BookMetadata.cleanAuthor] before display. */
data class PdfMetadata(val xmpTitle: String? = null, val xmpAuthors: List<String> = emptyList(),
                       val infoTitle: String? = null, val infoAuthor: String? = null) {
    /** XMP is preferred because it is Unicode by definition; the info dictionary fills gaps. */
    fun display(): Pair<String?, String?> = Pair(
        BookMetadata.cleanTitle(xmpTitle) ?: BookMetadata.cleanTitle(infoTitle),
        BookMetadata.cleanAuthor(xmpAuthors.joinToString("; ")) ?: BookMetadata.cleanAuthor(infoAuthor))
}

/**
 * Reads the document title and author from a PDF. `PdfRenderer` does not expose metadata, so this looks up
 * the trailer's `/Info` dictionary and the catalog's XMP `/Metadata` stream, including objects packed in
 * compressed object streams (PDF 1.5+). Anything unexpected yields empty metadata rather than an error:
 * metadata is optional and must never block an import. Work is bounded for hostile or very large files.
 */
class PdfMetadataReader {
    fun read(file: File): PdfMetadata = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            // Mapped, not loaded: a 50 MiB PDF would otherwise need 50 MiB of heap on a TV.
            parse(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
        }
    }.getOrDefault(PdfMetadata())

    internal fun parse(bytes: ByteArray): PdfMetadata = runCatching { parse(ByteBuffer.wrap(bytes)) }.getOrDefault(PdfMetadata())

    private fun parse(buffer: ByteBuffer): PdfMetadata {
        val source = Source(buffer)
        val file = Resolver(source, Index.build(source))
        // Encrypted strings are ciphertext; showing them would put noise on the shelf.
        val encrypted = file.index.encrypted
        var xmpTitle: String? = null; var xmpAuthors = emptyList<String>()
        runCatching {
            val catalog = file.trailerRef("/Root")?.let(file::resolve) as? Map<*, *>
            val metadata = catalog?.get("Metadata") as? Ref
            file.stream(metadata)?.let { Xmp.parse(it) }?.let { xmpTitle = it.first; xmpAuthors = it.second }
        }
        var infoTitle: String? = null; var infoAuthor: String? = null
        if (!encrypted) runCatching {
            val info = file.trailerRef("/Info")?.let(file::resolve) as? Map<*, *>
            infoTitle = (file.value(info?.get("Title")) as? PdfString)?.text()
            infoAuthor = (file.value(info?.get("Author")) as? PdfString)?.text()
        }
        return PdfMetadata(xmpTitle, xmpAuthors, infoTitle, infoAuthor)
    }

    internal data class Ref(val number: Int, val generation: Int)
    internal class Name(val value: String)
    internal class PdfString(val bytes: ByteArray) { fun text(): String = PdfText.decode(bytes) }
    private object Keyword

    /** Absolute-index reads over a mapped file or an inflated object stream. */
    internal class Source(private val buffer: ByteBuffer) {
        val size: Int = buffer.limit()
        operator fun get(index: Int): Int = buffer.get(index).toInt() and 0xff
        fun slice(from: Int, to: Int): ByteArray = ByteArray(to - from).also { copyInto(from, it, it.size) }
        fun copyInto(from: Int, target: ByteArray, length: Int) { buffer.duplicate().apply { position(from) }.get(target, 0, length) }
        fun indexOf(pattern: ByteArray, from: Int = 0, to: Int = size): Int {
            var i = from.coerceAtLeast(0); val last = to - pattern.size
            while (i <= last) {
                if (get(i) == (pattern[0].toInt() and 0xff) && matches(pattern, i)) return i
                i++
            }
            return -1
        }
        private fun matches(pattern: ByteArray, at: Int): Boolean {
            for (j in pattern.indices) if (get(at + j) != (pattern[j].toInt() and 0xff)) return false
            return true
        }
    }

    /** A minimal PDF object lexer: enough for dictionaries, arrays, strings, names, numbers and references. */
    internal class Lexer(private val source: Source, var position: Int) {
        private var budget = 20_000 // Tokens per object; stops pathological nesting or giant arrays.
        fun skipSpace() {
            while (position < source.size) {
                val c = source[position]
                if (c == '%'.code) { while (position < source.size && source[position] != 10 && source[position] != 13) position++ }
                else if (isSpace(c)) position++ else return
            }
        }
        fun next(depth: Int = 0): Any? {
            if (--budget < 0 || depth > 32) throw IllegalStateException("PDF object too complex")
            skipSpace()
            if (position >= source.size) throw IllegalStateException("Unexpected end of PDF")
            val c = source[position]
            return when {
                c == '/'.code -> Name(word(position + 1).also { position += it.length + 1 }.let(::decodeName))
                c == '('.code -> PdfString(literal())
                c == '<'.code && peek(1) == '<'.code -> { position += 2; dictionary(depth) }
                c == '<'.code -> PdfString(hex())
                c == '['.code -> { position++; array(depth) }
                c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> number()
                else -> { val word = word(position); position += word.length.coerceAtLeast(1)
                    when (word) { "true" -> true; "false" -> false; "null" -> null; else -> Keyword } }
            }
        }
        private fun dictionary(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (source[position] == '>'.code && peek(1) == '>'.code) { position += 2; return map }
                val key = next(depth + 1) as? Name ?: throw IllegalStateException("Dictionary key is not a name")
                map[key.value] = next(depth + 1)
            }
        }
        private fun array(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            while (true) {
                skipSpace()
                if (source[position] == ']'.code) { position++; return list }
                list += next(depth + 1)
            }
        }
        private fun number(): Any {
            val start = position
            while (position < source.size && (source[position] in '0'.code..'9'.code || source[position] in listOf('+'.code, '-'.code, '.'.code))) position++
            val text = String(source.slice(start, position), Charsets.US_ASCII)
            val integer = text.toIntOrNull() ?: return text.toDoubleOrNull() ?: 0.0
            // "12 0 R" is a reference; anything else leaves the lexer after the first integer.
            val resume = position
            skipSpace()
            val genStart = position
            while (position < source.size && source[position] in '0'.code..'9'.code) position++
            val generation = if (position > genStart) String(source.slice(genStart, position), Charsets.US_ASCII).toIntOrNull() else null
            if (generation != null) {
                skipSpace()
                if (position < source.size && source[position] == 'R'.code && (position + 1 >= source.size || isDelimiterOrSpace(source[position + 1]))) {
                    position++; return Ref(integer, generation)
                }
            }
            position = resume
            return integer
        }
        private fun literal(): ByteArray {
            position++
            val out = ByteArrayOutputStream(); var nesting = 1
            while (position < source.size) {
                if (out.size() > MAX_STRING) throw IllegalStateException("String too long")
                val c = source[position++]
                when (c) {
                    '('.code -> { nesting++; out.write(c) }
                    ')'.code -> { if (--nesting == 0) return out.toByteArray(); out.write(c) }
                    '\\'.code -> {
                        if (position >= source.size) break
                        when (val e = source[position++]) {
                            'n'.code -> out.write(10); 'r'.code -> out.write(13); 't'.code -> out.write(9)
                            'b'.code -> out.write(8); 'f'.code -> out.write(12)
                            13 -> if (position < source.size && source[position] == 10) position++ // Line continuation.
                            10 -> Unit
                            in '0'.code..'7'.code -> {
                                var value = e - '0'.code; var digits = 1
                                while (digits < 3 && position < source.size && source[position] in '0'.code..'7'.code) {
                                    value = value * 8 + (source[position++] - '0'.code); digits++
                                }
                                out.write(value and 0xff)
                            }
                            else -> out.write(e)
                        }
                    }
                    13 -> { out.write(10); if (position < source.size && source[position] == 10) position++ }
                    else -> out.write(c)
                }
            }
            throw IllegalStateException("Unterminated string")
        }
        private fun hex(): ByteArray {
            position++
            val out = ByteArrayOutputStream(); var high = -1
            while (position < source.size) {
                val c = source[position++]
                if (c == '>'.code) { if (high >= 0) out.write(high shl 4); return out.toByteArray() }
                val digit = Character.digit(c, 16)
                if (digit < 0) { if (isSpace(c)) continue else throw IllegalStateException("Bad hex string") }
                if (high < 0) high = digit else { out.write((high shl 4) or digit); high = -1 }
                if (out.size() > MAX_STRING) throw IllegalStateException("String too long")
            }
            throw IllegalStateException("Unterminated hex string")
        }
        private fun word(from: Int): String {
            var end = from
            while (end < source.size && end - from < 256 && !isDelimiterOrSpace(source[end])) end++
            return String(source.slice(from, end), Charsets.ISO_8859_1)
        }
        private fun decodeName(raw: String): String = Regex("#([0-9a-fA-F]{2})").replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        private fun peek(offset: Int): Int = if (position + offset < source.size) source[position + offset] else -1
    }

    /**
     * One forward pass over the file records every "N G obj" header (last copy wins, as incremental
     * updates append), object stream headers, the trailer keys we need and whether the file is encrypted.
     * Lookups then cost nothing; rescanning a 50 MiB file per lookup took seconds on TV hardware.
     */
    internal class Index(val objects: Map<Long, Int>, val objectStreams: List<Int>, val keys: Map<String, List<Int>>, val encrypted: Boolean) {
        companion object {
            private val NAMES = listOf("/Info", "/Root", "/ObjStm", "/Encrypt").map(::ascii)
            fun key(number: Int, generation: Int): Long = (number.toLong() shl 20) or generation.toLong()
            fun build(source: Source): Index {
                val objects = HashMap<Long, Int>(); val streams = LinkedHashSet<Int>()
                val keys = HashMap<String, ArrayList<Int>>(); var encrypted = false
                var lastHeader = -1
                // Bulk-copied chunks: per-byte calls into a mapped buffer are several times slower.
                val chunk = ByteArray(1 shl 20); val overlap = 16
                var base = 0
                while (base < source.size) {
                    val length = minOf(chunk.size, source.size - base)
                    source.copyInto(base, chunk, length)
                    val limit = if (base + length >= source.size) length else length - overlap
                    for (i in 0 until limit) {
                        val b = chunk[i]
                        if (b == O && i + 2 < length && chunk[i + 1] == B && chunk[i + 2] == J) {
                            val header = objectHeader(source, base + i) ?: continue
                            objects[key(header.second, header.third)] = header.first
                            lastHeader = header.first
                        } else if (b == SLASH) {
                            val name = NAMES.firstOrNull { name -> i + name.size <= length && name.indices.all { chunk[i + it] == name[it] } } ?: continue
                            val end = base + i + name.size
                            if (end < source.size && !isDelimiterOrSpace(source[end])) continue // "/InfoX", "/EncryptMetadata".
                            when (String(name, Charsets.US_ASCII)) {
                                "/Encrypt" -> encrypted = true
                                "/ObjStm" -> if (lastHeader >= 0 && streams.size < MAX_OBJECT_STREAMS) streams += lastHeader
                                else -> keys.getOrPut(String(name, Charsets.US_ASCII)) { ArrayList() }.add(end)
                            }
                        }
                    }
                    base += limit
                }
                return Index(objects, streams.toList(), keys, encrypted)
            }
            private val O = 'o'.code.toByte(); private val B = 'b'.code.toByte(); private val J = 'j'.code.toByte(); private val SLASH = '/'.code.toByte()
            /** For "obj" at [at], returns (header start, number, generation) when it follows "N G ". */
            private fun objectHeader(source: Source, at: Int): Triple<Int, Int, Int>? {
                if (at + 3 < source.size && !isDelimiterOrSpace(source[at + 3])) return null
                var i = at - 1
                if (i < 0 || !isSpace(source[i])) return null // Also rejects "endobj".
                while (i >= 0 && isSpace(source[i])) i--
                val genEnd = i + 1
                while (i >= 0 && isDigit(source[i]) && genEnd - i <= 5) i--
                if (i + 1 == genEnd || i < 0 || !isSpace(source[i])) return null
                val generation = digits(source, i + 1, genEnd) ?: return null
                while (i >= 0 && isSpace(source[i])) i--
                val numEnd = i + 1
                while (i >= 0 && isDigit(source[i]) && numEnd - i <= 10) i--
                if (i + 1 == numEnd || (i >= 0 && isDigit(source[i]))) return null
                val number = digits(source, i + 1, numEnd) ?: return null
                return Triple(i + 1, number, generation)
            }
            private fun digits(source: Source, from: Int, to: Int): Int? = String(source.slice(from, to), Charsets.US_ASCII).toIntOrNull()
        }
    }

    /** Locates objects either written directly ("12 0 obj") or packed in object streams; the latest copy wins. */
    internal class Resolver(val source: Source, val index: Index) {
        private var packed: Map<Int, Pair<Int, Int>>? = null // object number -> (object stream offset, index offset)

        fun trailerRef(key: String): Ref? {
            // Latest trailer first: an incremental update's trailer supersedes earlier ones.
            for (end in index.keys[key].orEmpty().asReversed().take(50)) {
                val value = runCatching { Lexer(source, end).next() }.getOrNull()
                if (value is Ref) return value
            }
            return null
        }

        fun value(value: Any?, depth: Int = 0): Any? = if (value is Ref && depth < 4) value(resolve(value), depth + 1) else value

        fun resolve(ref: Ref): Any? {
            val direct = directOffset(ref)
            // Object streams are only worth indexing when one could hold a newer copy than the direct object.
            val packedAt = if (ref.generation == 0 && (direct == null || index.objectStreams.any { it > direct })) packedObjects()[ref.number] else null
            if (packedAt != null && (direct == null || packedAt.first > direct)) return packedObject(packedAt.first, ref.number)
            return direct?.let { header -> Lexer(source, header).apply { skipObjectHeader() }.next() }
        }

        /** Returns the decoded bytes of an uncompressed or Flate-compressed stream object. */
        fun stream(ref: Ref?): ByteArray? {
            ref ?: return null
            val header = directOffset(ref) ?: return null
            val lexer = Lexer(source, header).apply { skipObjectHeader() }
            val dictionary = lexer.next() as? Map<*, *> ?: return null
            val data = streamData(dictionary, lexer.position) ?: return null
            return decode(dictionary, data, MAX_XMP)
        }

        private fun Lexer.skipObjectHeader() {
            next(); next() // Number and generation.
            skipSpace(); position += 3 // "obj".
        }

        private fun directOffset(ref: Ref): Int? = index.objects[Index.key(ref.number, ref.generation)]

        private fun streamData(dictionary: Map<*, *>, afterDictionary: Int): Pair<Int, Int>? {
            val lexer = Lexer(source, afterDictionary); lexer.skipSpace()
            val keyword = ascii("stream")
            if (source.indexOf(keyword, lexer.position, lexer.position + keyword.size) != lexer.position) return null
            var start = lexer.position + keyword.size
            if (start < source.size && source[start] == 13) start++
            if (start < source.size && source[start] == 10) start++
            val declared = (value(dictionary["Length"]) as? Int)?.takeIf { it >= 0 && start + it <= source.size }
            val end = declared?.let { start + it } ?: source.indexOf(ascii("endstream"), start).takeIf { it >= 0 } ?: return null
            return start to end
        }

        private fun decode(dictionary: Map<*, *>, range: Pair<Int, Int>, limit: Int): ByteArray? {
            val filter = when (val f = value(dictionary["Filter"])) {
                null -> null
                is Name -> f.value
                is List<*> -> if (f.size == 1) (f[0] as? Name)?.value else return null
                else -> return null
            }
            val raw = source.slice(range.first, range.second)
            return when (filter) {
                null -> if (raw.size <= limit) raw else raw.copyOf(limit)
                "FlateDecode", "Fl" -> if (dictionary["DecodeParms"] != null && predictor(dictionary) > 1) null else inflate(raw, limit)
                else -> null
            }
        }

        private fun predictor(dictionary: Map<*, *>): Int = ((value(dictionary["DecodeParms"]) as? Map<*, *>)?.get("Predictor") as? Int) ?: 1

        /** Indexes every object stream by reading only its header, so large files stay cheap. */
        private fun packedObjects(): Map<Int, Pair<Int, Int>> = packed ?: HashMap<Int, Pair<Int, Int>>().also { packedIndex ->
            for (numberStart in index.objectStreams) {
                runCatching {
                    val lexer = Lexer(source, numberStart).apply { skipObjectHeader() }
                    val dictionary = lexer.next() as? Map<*, *> ?: return@runCatching
                    val count = (dictionary["N"] as? Int)?.takeIf { it in 1..100_000 } ?: return@runCatching
                    val first = (dictionary["First"] as? Int)?.takeIf { it in 1..4_000_000 } ?: return@runCatching
                    val range = streamData(dictionary, lexer.position) ?: return@runCatching
                    val head = decode(dictionary, range, first) ?: return@runCatching
                    val numbers = Lexer(Source(ByteBuffer.wrap(head)), 0)
                    repeat(count) {
                        val number = numbers.next() as? Int ?: return@runCatching
                        val offset = numbers.next() as? Int ?: return@runCatching
                        packedIndex[number] = numberStart to offset // Later streams replace earlier copies.
                    }
                }
            }
            packed = packedIndex
        }

        private fun packedObject(streamHeader: Int, number: Int): Any? {
            val lexer = Lexer(source, streamHeader).apply { skipObjectHeader() }
            val dictionary = lexer.next() as? Map<*, *> ?: return null
            val first = dictionary["First"] as? Int ?: return null
            val range = streamData(dictionary, lexer.position) ?: return null
            val data = decode(dictionary, range, MAX_OBJECT_STREAM) ?: return null
            val content = Source(ByteBuffer.wrap(data))
            val header = Lexer(content, 0)
            repeat((dictionary["N"] as? Int) ?: 0) {
                val n = header.next() as? Int ?: return null
                val offset = header.next() as? Int ?: return null
                if (n == number) return Lexer(content, first + offset).next()
            }
            return null
        }

        private fun inflate(data: ByteArray, limit: Int): ByteArray? {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
                while (!inflater.finished() && out.size() < limit) {
                    val n = inflater.inflate(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (n == 0) break // Truncated input or a preset dictionary: keep what was decoded.
                    out.write(buffer, 0, n)
                }
                return out.toByteArray().takeIf { it.isNotEmpty() }
            } catch (_: Exception) { return null } finally { inflater.end() }
        }
    }

    companion object {
        private const val MAX_STRING = 64 * 1024
        private const val MAX_XMP = 4 * 1024 * 1024
        private const val MAX_OBJECT_STREAM = 8 * 1024 * 1024
        private const val MAX_OBJECT_STREAMS = 2000
        internal fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
        private fun isSpace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        private fun isDigit(c: Int) = c in '0'.code..'9'.code
        private fun isDelimiterOrSpace(c: Int) = isSpace(c) || c in "()<>[]{}/%".map { it.code }
    }
}

/** Decodes PDF text strings: UTF-16 with BOM, UTF-8 with BOM, BOM-less UTF-8 (common in practice), else PDFDocEncoding. */
internal object PdfText {
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.any { it < 0 } -> strictUtf8(bytes) ?: pdfDoc(bytes)
        else -> pdfDoc(bytes)
    }
    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }
    // PDFDocEncoding differs from Latin-1 in 0x18–0x1F and 0x80–0xA0 (PDF 32000-1:2008, Annex D.2).
    private val HIGH = "\u2022\u2020\u2021\u2026\u2014\u2013\u0192\u2044\u2039\u203a\u2212\u2030\u201e\u201c\u201d\u2018" +
        "\u2019\u201a\u2122\ufb01\ufb02\u0141\u0152\u0160\u0178\u017d\u0131\u0142\u0153\u0161\u017e\ufffd\u20ac"
    private const val ACCENTS = "\u02d8\u02c7\u02c6\u02d9\u02dd\u02db\u02da\u02dc"
    private fun pdfDoc(bytes: ByteArray): String = buildString(bytes.size) {
        for (b in bytes) {
            val c = b.toInt() and 0xff
            append(when (c) { in 0x18..0x1f -> ACCENTS[c - 0x18]; in 0x80..0xa0 -> HIGH[c - 0x80]; else -> c.toChar() })
        }
    }
}

/** Extracts dc:title (preferring x-default) and the dc:creator list from an XMP packet. */
internal object Xmp {
    private val OPTIONS = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val ITEM = Regex("<rdf:li\\b([^>]*)>(.*?)</rdf:li>", OPTIONS)
    fun parse(bytes: ByteArray): Pair<String?, List<String>>? {
        val text = PdfText.decode(bytes).takeIf { it.contains("xmpmeta", true) || it.contains("rdf:RDF", true) } ?: return null
        val titleBlock = block(text, "dc:title")
        val titles = titleBlock?.let { items(it) }.orEmpty()
        val title = titles.firstOrNull { it.first.contains("x-default") }?.second ?: titles.firstOrNull()?.second
            ?: titleBlock?.let(::plain)?.takeIf { !it.contains('<') }
        val authors = block(text, "dc:creator")?.let { items(it).map { item -> item.second } }.orEmpty()
        return Pair(title?.takeIf { it.isNotBlank() }, authors.filter { it.isNotBlank() })
    }
    private fun block(text: String, tag: String): String? =
        Regex("<$tag\\b[^>]*?(?<!/)>(.*?)</$tag>", OPTIONS).find(text)?.groupValues?.get(1)
    private fun items(block: String): List<Pair<String, String>> = ITEM.findAll(block).map { it.groupValues[1] to plain(it.groupValues[2]) }.toList()
    private fun plain(xml: String): String = unescape(xml.replace(Regex("<[^>]*>"), " ")).trim()
    private fun unescape(text: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").replace(text) {
        when (val entity = it.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> runCatching {
                val code = if (entity.startsWith("#x")) entity.substring(2).toInt(16) else entity.substring(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault("")
        }
    }
}
