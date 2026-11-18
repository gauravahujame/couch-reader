package dev.couchreader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import dev.couchreader.importer.CoverArtwork
import dev.couchreader.importer.CoverOutcome
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Bump when EPUB cover resolution, image decoding or PDF blank detection changes, so books whose earlier
 * attempt found nothing (or a worse page) are tried again. Startup prunes entries of other versions.
 */
const val COVER_VERSION = 1

/**
 * Cover thumbnails derived from stored books, in the app-private cache (never inside a book's directory).
 * Each entry is keyed by book ID, content hash and [COVER_VERSION]: `<id>-<hash16>-v<version>.jpg` holds a
 * complete thumbnail, and an empty `.miss` file records a definitive absence (no usable artwork).
 * Files are written to `.tmp-*` and renamed into place under the [BookRepository] lock after checking the
 * book still exists with the same hash, so a deleted book never gets its cover back. Losing any entry
 * (cache clearing, eviction, corruption) only means it is generated again on demand.
 */
class CoverStore(val books: BookRepository) {
    companion object {
        const val DISK_BUDGET = 32L * 1024 * 1024
        /** Miss markers are empty, but still count against the budget so they are trimmed too. */
        private const val MARKER_COST = 4L * 1024
        private const val TEMP_PREFIX = ".tmp-"
        private val entryName = Regex("([A-Za-z0-9-]+)-([0-9a-f]{16})-v(\\d+)\\.(jpg|miss)")

        fun key(book: Book) = "${book.id}-${book.hash.take(16).lowercase()}-v$COVER_VERSION"

        /** Removes every entry of [bookId], whatever its hash or version. Caller holds the repository lock. */
        internal fun removeEntries(directory: File, bookId: String) {
            directory.listFiles()?.filter { entryName.matchEntire(it.name)?.groupValues?.get(1) == bookId }?.forEach { it.delete() }
        }

        /**
         * Startup pruning, before any cover worker runs: abandoned temporary files, unknown names, other
         * algorithm versions, and entries whose book is gone or was replaced.
         */
        internal fun prune(directory: File, known: Map<String, Book>) {
            directory.listFiles()?.forEach { file ->
                val match = entryName.matchEntire(file.name)
                val book = match?.groupValues?.get(1)?.let(known::get)
                val current = book != null && match.groupValues[2] == book.hash.take(16).lowercase() &&
                    match.groupValues[3] == COVER_VERSION.toString() && file.isFile
                if (!current) file.deleteRecursively()
            }
        }
    }

    val directory: File get() = books.covers

    sealed class Entry {
        class Image(val bitmap: Bitmap) : Entry()
        object Miss : Entry()
        object Absent : Entry()
    }

    private fun image(book: Book) = File(directory, "${key(book)}.jpg")
    private fun marker(book: Book) = File(directory, "${key(book)}.miss")

    /** Reads a finished entry. A corrupt thumbnail is deleted and reported absent, so it is regenerated. */
    fun read(book: Book): Entry {
        val image = image(book)
        if (image.isFile) {
            val bitmap = runCatching { BitmapFactory.decodeFile(image.path) }.getOrNull()
            if (bitmap != null && bitmap.width in 1..CoverArtwork.MAX_EDGE && bitmap.height in 1..CoverArtwork.MAX_EDGE) {
                image.setLastModified(System.currentTimeMillis())
                return Entry.Image(bitmap)
            }
            bitmap?.recycle(); image.delete()
        }
        val marker = marker(book)
        if (marker.isFile) { marker.setLastModified(System.currentTimeMillis()); return Entry.Miss }
        return Entry.Absent
    }

    /** Publishes a thumbnail. False when the book was removed meanwhile; throws when storage fails. */
    fun publish(book: Book, bitmap: Bitmap): Boolean = commit(book, image(book)) { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Could not encode cover" }
    }

    /** Records that [book] has no usable artwork under this [COVER_VERSION]. */
    fun publishMiss(book: Book): Boolean = commit(book, marker(book)) { }

    private fun commit(book: Book, target: File, write: (FileOutputStream) -> Unit): Boolean {
        directory.mkdirs()
        val temporary = File(directory, "$TEMP_PREFIX${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output -> write(output); output.flush(); output.fd.sync() }
            val published = synchronized(books) {
                // Same lock as BookRepository.remove(): the book is either still here, or its entries are gone for good.
                books.contains(book) && temporary.renameTo(target)
            }
            if (published) trim()
            return published
        } finally { temporary.delete() }
    }

    /** Keeps the directory within [DISK_BUDGET], deleting the least recently used entries first. */
    private fun trim() {
        val entries = directory.listFiles()?.filter { entryName.matches(it.name) } ?: return
        fun cost(file: File) = if (file.name.endsWith(".miss")) MARKER_COST else file.length()
        var total = entries.sumOf(::cost)
        for (file in entries.sortedBy { it.lastModified() }) {
            if (total <= DISK_BUDGET) break
            total -= cost(file); file.delete()
        }
    }
}

/**
 * Schedules cover work for the shelf and the opening screen on one low-priority background thread, so
 * first paint, D-pad input, reading and uploads never wait for it. Requests for the same book coalesce;
 * the opening book outranks visible tiles, which outrank the next row. Call from the main thread only.
 *
 * Decoded thumbnails stay in an [LruCache] of [MEMORY_BUDGET] bytes. Views hold only what they show, and
 * the shelf releases tiles that scroll well out of view, so a long shelf never pins one bitmap per book.
 */
class CoverLoader(private val store: CoverStore,
                  private val generate: (Book, () -> Boolean) -> CoverOutcome = { book, cancelled ->
                      CoverArtwork.generate(book.format, store.books.directory(book), book.contentPath, cancelled)
                  }) {
    companion object { const val MEMORY_BUDGET = 8 * 1024 * 1024 }

    enum class Priority { OPEN, VISIBLE, PREFETCH }

    /** A view that shows a book's artwork. [coverKey] is owned by the loader: it names the book bound now. */
    interface Target {
        var coverKey: String?
        /** Shows [bitmap], or the generated design when null. */
        fun showArtwork(bitmap: Bitmap?)
    }

    private class Job(val key: String, val book: Book, var priority: Priority, val order: Long) {
        val targets = LinkedHashSet<Target>()
    }

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BUDGET) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cover-worker").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private val lock = Any()
    private val pending = LinkedHashMap<String, Job>()
    private var running: Job? = null
    private var draining = false
    private var sequence = 0L
    /** Keys not to request again this shelf session: a recorded miss or a failed attempt. Main thread only. */
    private val settled = HashSet<String>()
    @Volatile private var closed = false

    /** Number of generation attempts (cache misses that reached the generator); for tests. */
    @Volatile internal var generated = 0
        private set

    fun cached(book: Book): Bitmap? = memory.get(CoverStore.key(book))

    /** Starts a new shelf session: transient failures may be retried once more. Recorded misses stay on disk. */
    fun newSession() { settled.clear() }

    /** Binds [target] to [book]: shows a cached image at once, or queues the work at [priority]. */
    fun bind(target: Target, book: Book, priority: Priority) {
        val key = CoverStore.key(book)
        if (target.coverKey != key) {
            target.coverKey?.let { old -> detach(target, old) }
            target.coverKey = key
        }
        memory.get(key)?.let { target.showArtwork(it); return }
        if (closed || key in settled) return
        synchronized(lock) {
            val job = running?.takeIf { it.key == key } ?: pending[key]
            if (job != null) {
                job.targets += target
                if (priority < job.priority) job.priority = priority
                return
            }
            pending[key] = Job(key, book, priority, sequence++).also { it.targets += target }
            if (!draining) { draining = true; worker.execute(::drain) }
        }
    }

    /** Unbinds a tile that scrolled away: drops its image (back to the generated design) and its request. */
    fun release(target: Target) {
        val key = target.coverKey ?: return
        detach(target, key)
        target.coverKey = null
        target.showArtwork(null)
    }

    /** Binds [target] to [book] and shows a thumbnail already in memory, without queuing any work. */
    fun showCached(target: Target, book: Book): Boolean {
        val key = CoverStore.key(book)
        target.coverKey?.takeIf { it != key }?.let { old -> detach(target, old) }
        target.coverKey = key
        val bitmap = memory.get(key) ?: return false
        target.showArtwork(bitmap); return true
    }

    /**
     * Drops pending requests and view callbacks except those of [keep], e.g. when the shelf is replaced by
     * the opening screen. An attempt left with nobody waiting stops at its next cancellation check.
     */
    fun detachAll(keep: Target? = null) {
        synchronized(lock) {
            pending.values.forEach { job -> job.targets.retainAll { it === keep } }
            pending.values.removeAll { it.targets.isEmpty() }
            running?.targets?.retainAll { it === keep }
        }
    }

    /** Stops the worker. Late results are discarded; an interrupted attempt leaves no entry behind. */
    fun close() {
        closed = true
        detachAll()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun detach(target: Target, key: String) {
        synchronized(lock) {
            running?.takeIf { it.key == key }?.targets?.remove(target)
            pending[key]?.let { job -> job.targets.remove(target); if (job.targets.isEmpty()) pending.remove(key) }
        }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val next = pending.values.minWithOrNull(compareBy<Job>({ it.priority }, { it.order }))
                if (next == null || closed) { running = null; draining = false; return }
                pending.remove(next.key); running = next; next
            }
            val cancelled = { closed || Thread.currentThread().isInterrupted || synchronized(lock) { job.targets.isEmpty() } }
            val result = runCatching { produce(job.book, cancelled) }.getOrElse { Done.FAILED }
            synchronized(lock) { if (running === job) running = null }
            if (result is Done.Image) memory.put(job.key, result.bitmap)
            main.post {
                if (closed) return@post
                val targets = synchronized(lock) { job.targets.toList().also { job.targets.clear() } }
                when (result) {
                    is Done.Image -> targets.filter { it.coverKey == job.key }.forEach { it.showArtwork(result.bitmap) }
                    Done.MISSING -> settled += job.key
                    // A failure with someone waiting is not retried this session; an abandoned attempt may be.
                    Done.FAILED -> if (targets.isNotEmpty()) settled += job.key
                }
            }
        }
    }

    private sealed class Done {
        class Image(val bitmap: Bitmap) : Done()
        object MISSING : Done()
        object FAILED : Done()
    }

    private fun produce(book: Book, cancelled: () -> Boolean): Done {
        when (val entry = store.read(book)) {
            is CoverStore.Entry.Image -> return Done.Image(entry.bitmap)
            CoverStore.Entry.Miss -> return Done.MISSING
            CoverStore.Entry.Absent -> Unit
        }
        if (cancelled()) return Done.FAILED
        generated++
        return when (val outcome = generate(book, cancelled)) {
            is CoverOutcome.Found -> {
                // Low storage only costs the disk copy: the image still shows from memory this session.
                val published = runCatching { store.publish(book, outcome.bitmap) }.getOrDefault(true)
                if (published) Done.Image(outcome.bitmap) else { outcome.bitmap.recycle(); Done.FAILED }
            }
            CoverOutcome.Missing -> if (runCatching { store.publishMiss(book) }.getOrDefault(true)) Done.MISSING else Done.FAILED
            CoverOutcome.Retry -> Done.FAILED
        }
    }
}
