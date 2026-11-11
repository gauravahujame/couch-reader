package dev.couchreader.importer

import java.net.URLDecoder
import java.util.Locale

/** Title and author shown for a book. An empty [author] means none is known; screens hide the line. */
data class DisplayMetadata(val title: String, val author: String)

/**
 * Turns raw metadata and upload filenames into what a reader expects to see on a shelf.
 * Document metadata is often generator noise ("Microsoft Word - draft.docx", "Administrator"),
 * so it is accepted only when it reads like a real title or name; otherwise callers fall back.
 */
object BookMetadata {
    const val MAX_LENGTH = 300
    private val BOOK_EXTENSIONS = setOf("pdf", "epub")
    private val DOCUMENT_EXTENSIONS = BOOK_EXTENSIONS + setOf("doc", "docx", "dot", "dotx", "odt", "rtf", "txt", "md",
        "tex", "dvi", "ps", "indd", "qxd", "pages", "key", "ppt", "pptx", "xls", "xlsx", "htm", "html", "mobi", "azw3")
    private val GENERATOR_PREFIX = Regex("^(microsoft\\s+(office\\s+)?(word|powerpoint|excel)|word|powerpoint|excel)\\s*-\\s*", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER_TITLES = Regex(
        "^(untitled([ _-]*\\d+)?( document)?|new document|document ?\\d*|doc ?\\d+|book ?\\d+|sheet ?\\d+|slide ?\\d+|page ?\\d+|" +
            "presentation ?\\d*|title|no title|notitle|untitled pdf|pdf|cover|print|output|default|unknown|none|null|n/?a|test|draft|\\(anonymous\\))$",
        RegexOption.IGNORE_CASE)
    // Hashes, UUIDs, long numbers and camera/scanner names. Short numbers stay: "1984" is a title.
    private val GENERATED_ID = Regex("^(?:(?=[0-9a-f-]*\\d)[0-9a-f]{8,}(?:-[0-9a-f]{4,})*|\\d{5,}|(?:tmp|temp|scan|img|dsc|file|doc)[ _-]?\\d+[0-9a-z]*)$", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER_AUTHORS = Regex(
        "^(unknown( author)?|author|authors|anonymous|\\(anonymous\\)|none|null|n/?a|-+|\\?+|admin(istrator)?\\d*|user\\d*|owner\\d*|default|test|" +
            "me|pc|home|office|staff|guest|name|your name( here)?|windows user|microsoft office user|authorized user|registered user|" +
            "valued customer|preferred customer|valued gateway client|customer|lenovo|dell|hp|acer|asus|toshiba|samsung)$",
        RegexOption.IGNORE_CASE)
    // Office routing codes used as authors, such as the IRS's "C:DC:TS:CAR:MP".
    private val CODE_AUTHOR = Regex("^[A-Z0-9]{1,6}([:/][A-Z0-9]{1,6})+$")
    private val SOFTWARE_AUTHOR = Regex("^(adobe|acrobat|microsoft|ghostscript|quartz|pdf|latex|pdftex|libreoffice|openoffice|calibre|word)\\b", RegexOption.IGNORE_CASE)
    /** Bracketed release tags from download sites and duplicate-download markers like "(1)". */
    private val RELEASE_TAG = Regex(
        "\\s*[\\[(][^\\])]*?(z-?lib(rary)?|libgen|anna.?s.?archive|pdf ?drive|ebook|e-book|retail|compressed|ocr|\\bpdf\\b|\\bepub\\b)[^\\])]*[\\])]",
        RegexOption.IGNORE_CASE)
    private val DUPLICATE_SUFFIX = Regex("(\\s*\\(\\d{1,2}\\))+$")
    // Windows "name - Copy (2)" and tool suffixes like "_compressed"; a bare trailing word "Copy" is kept.
    private val COPY_SUFFIX = Regex("(\\s+-\\s+|[_-])(copy|compressed)(\\s*\\d+)?$", RegexOption.IGNORE_CASE)
    private val SMALL_WORDS = setOf("a", "an", "the", "and", "or", "nor", "but", "of", "in", "on", "to", "for", "at", "by", "with", "from", "vs")

    /** Collapses whitespace, removes control characters and caps length; never returns padded text. */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val text = raw.replace(Regex("\\u001b[^\\u001b]*\\u001b"), "") // PDF language escape sequences.
            // Invisible format characters, except the joiners that Indic and Persian scripts need.
            .replace(Regex("[[\\p{Cc}\\p{Cf}]&&[^\\t\\n\\r\\u200C\\u200D]]|\\uFFFD"), "")
            .replace(Regex("\\s+"), " ").trim()
        return ellipsize(text, MAX_LENGTH)
    }

    /** A readable title from an upload filename such as "the_great-gatsby (z-lib.org) (1).pdf". */
    fun titleFromFilename(filename: String, fallback: String): String {
        var name = filename.substringAfterLast('/').substringAfterLast('\\')
        if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(name))
            name = runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
        // "notes.docx.pdf" comes from print-to-PDF; drop both extensions.
        name = stripExtension(stripExtension(name, BOOK_EXTENSIONS), DOCUMENT_EXTENSIONS)
        return tidyFileStem(name).ifEmpty { fallback }
    }

    /** Embedded metadata title if it reads like a real title, else null. */
    fun cleanTitle(raw: String?): String? {
        var title = normalize(raw)
        if (title.isEmpty()) return null
        val generator = GENERATOR_PREFIX.find(title)
        val pathLike = title.contains('\\') || title.startsWith("/") || Regex("^[a-zA-Z]:").containsMatchIn(title)
        val fileLike = Regex("\\.([a-zA-Z0-9]{2,5})$").find(title)?.groupValues?.get(1)?.lowercase() in DOCUMENT_EXTENSIONS
        if (generator != null || pathLike || fileLike) {
            title = title.removeRange(0, generator?.range?.last?.plus(1) ?: 0)
            title = title.substringAfterLast('\\').substringAfterLast('/')
            title = tidyFileStem(stripExtension(title, DOCUMENT_EXTENSIONS))
        }
        return title.takeIf(::isMeaningfulTitle)
    }

    /** Embedded metadata author if it names a person or organisation, else null. Lists are joined with commas. */
    fun cleanAuthor(raw: String?): String? {
        val names = normalize(raw).split(Regex("\\s*;\\s*")).map { it.trim().trim(',') }
            .filter { it.isNotEmpty() && it.any(Char::isLetter) && !PLACEHOLDER_AUTHORS.matches(it) && !SOFTWARE_AUTHOR.containsMatchIn(it) && !CODE_AUTHOR.matches(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        return joinAuthors(names)
    }

    /** "A", "A, B", "A, B, C" and "A, B, C and others" for longer lists. */
    fun joinAuthors(names: List<String>): String? {
        val clean = names.map(::normalize).filter { it.isNotEmpty() }
        if (clean.isEmpty()) return null
        val shown = clean.take(3).joinToString(", ")
        return ellipsize(if (clean.size > 3) "$shown and others" else shown, MAX_LENGTH)
    }

    /** Cuts at a word boundary and adds an ellipsis when [value] is longer than [max] characters. */
    fun ellipsize(value: String, max: Int): String {
        if (value.length <= max) return value
        val cut = value.take(max - 1)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.substring(0, space) else cut).trimEnd(' ', ',', ';', ':', '-') + "…"
    }

    private fun isMeaningfulTitle(title: String): Boolean =
        title.length >= 2 && title.any(Char::isLetterOrDigit) && !PLACEHOLDER_TITLES.matches(title) && !GENERATED_ID.matches(title.replace(" ", ""))

    private fun stripExtension(name: String, known: Set<String>): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0 && name.substring(dot + 1).lowercase() in known) name.substring(0, dot) else name
    }

    /** Applies filename conventions: separators to spaces, release tags removed, lowercase slugs title-cased. */
    private fun tidyFileStem(stem: String): String {
        var name = normalize(stem)
        name = RELEASE_TAG.replace(name, "")
        repeat(2) { name = COPY_SUFFIX.replace(DUPLICATE_SUFFIX.replace(name, "").trimEnd(), "").trimEnd() }
        name = name.replace('_', ' ')
        // Only treat dots, pluses and hyphens as separators in names that have no spaces, so
        // "Vol. 2" and "Spider-Man" keep their punctuation while "the.great.gatsby" splits.
        if (!name.contains(' ')) {
            if (name.count { it == '.' } >= 2) name = name.replace('.', ' ')
            if (name.contains('+')) name = name.replace('+', ' ')
            if (name.count { it == '-' } >= 2) name = name.replace('-', ' ')
        }
        name = normalize(name).trim(' ', '-', '.', ',', ';', ':', '·')
        if (name.any(Char::isLetter) && name.none(Char::isUpperCase)) name = titleCase(name)
        return name
    }

    private fun titleCase(text: String): String = text.split(' ').mapIndexed { index, word ->
        // Codes such as "rfc9110" or "v2" read better untouched than as "Rfc9110".
        if ((index > 0 && word in SMALL_WORDS) || word.any(Char::isDigit)) word else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }.joinToString(" ")
}
