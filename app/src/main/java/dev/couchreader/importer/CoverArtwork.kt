package dev.couchreader.importer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Outcome of one attempt to produce a book's shelf thumbnail. */
sealed class CoverOutcome {
    class Found(val bitmap: Bitmap) : CoverOutcome()
    /** Definitive: the book has no usable artwork under the current algorithm. */
    object Missing : CoverOutcome()
    /** Cancelled or a transient failure (I/O, memory, renderer); nothing may be recorded. */
    object Retry : CoverOutcome()
}

/** Produces thumbnails from the stored publication only: never the network, never the original upload. */
object CoverArtwork {
    /** Long edge of every thumbnail. */
    const val MAX_EDGE = 480
    /** Image files larger than this are skipped (the importer already caps entries at 16 MiB). */
    const val MAX_INPUT_BYTES = 16L * 1024 * 1024
    /** Source dimensions accepted before any pixel is decoded. */
    const val MAX_SOURCE_SIDE = 20_000
    const val MAX_SOURCE_PIXELS = 100_000_000L
    /** Largest intermediate (subsampled) bitmap allocated while decoding. */
    const val MAX_DECODE_BYTES = 8L * 1024 * 1024
    /** Neutral backing for transparent images, so they never show a black or checkered field. */
    val BACKING = Color.rgb(236, 232, 222)

    fun generate(format: String, directory: File, contentPath: String, cancelled: () -> Boolean): CoverOutcome = try {
        if (!directory.isDirectory) CoverOutcome.Retry
        else if (format == "pdf") pdf(directory, contentPath, cancelled) else epub(directory, contentPath, cancelled)
    } catch (_: OutOfMemoryError) { CoverOutcome.Retry } catch (_: Exception) { CoverOutcome.Retry }

    private fun epub(directory: File, opfPath: String, cancelled: () -> Boolean): CoverOutcome {
        val opf = EpubCoverResolver.resolveLocal(directory, "", opfPath) ?: return CoverOutcome.Retry
        if (!opf.isFile) return CoverOutcome.Retry
        for (candidate in EpubCoverResolver(directory, opfPath).candidates()) {
            if (cancelled()) return CoverOutcome.Retry
            decode(candidate)?.let { return CoverOutcome.Found(it) }
        }
        // The book may have been removed while candidates were tried; only a present book is a definitive miss.
        return if (directory.isDirectory && opf.isFile) CoverOutcome.Missing else CoverOutcome.Retry
    }

    private fun pdf(directory: File, contentPath: String, cancelled: () -> Boolean): CoverOutcome {
        val file = EpubCoverResolver.resolveLocal(directory, "", contentPath) ?: return CoverOutcome.Retry
        return when (val choice = PdfCoverPicker.pick(file, cancelled)) {
            is PdfCoverPicker.Choice.Page -> CoverOutcome.Found(choice.value)
            PdfCoverPicker.Choice.None -> CoverOutcome.Missing
            PdfCoverPicker.Choice.Interrupted -> CoverOutcome.Retry
        }
    }

    /**
     * Decodes a raster file to a thumbnail no longer than [MAX_EDGE], honouring EXIF orientation and
     * compositing transparency on [BACKING]. Returns null for anything unusable (corrupt, oversized,
     * zero-sized or wholly transparent), so the caller moves to the next candidate.
     */
    fun decode(file: File): Bitmap? {
        if (!file.isFile || file.length() !in 1..MAX_INPUT_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth; val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE ||
            width.toLong() * height > MAX_SOURCE_PIXELS) return null
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decodedBytes = ((width + sample - 1L) / sample) * ((height + sample - 1L) / sample) * 4
        if (decodedBytes > MAX_DECODE_BYTES) return null
        val source = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        try {
            if (source.width <= 0 || source.height <= 0 || (source.hasAlpha() && whollyTransparent(source))) return null
            val orientation = if (bounds.outMimeType == "image/jpeg") runCatching {
                ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL) else ExifInterface.ORIENTATION_NORMAL
            return compose(source, orientation)
        } finally { source.recycle() }
    }

    /** Scales [source] to fit [MAX_EDGE] after applying an EXIF [orientation], on an opaque backing. */
    private fun compose(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        val turned = orientation in setOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
        val w = if (turned) source.height else source.width
        val h = if (turned) source.width else source.height
        val scale = minOf(1.0, MAX_EDGE.toDouble() / max(w, h))
        val outWidth = (w * scale).roundToInt().coerceAtLeast(1); val outHeight = (h * scale).roundToInt().coerceAtLeast(1)
        // Map the oriented source box onto the output: orient around the origin, re-anchor, then scale.
        val box = android.graphics.RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        matrix.mapRect(box)
        matrix.postTranslate(-box.left, -box.top)
        matrix.postScale(outWidth / box.width(), outHeight / box.height())
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(BACKING)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return output
    }

    /** True when no pixel is visible; scanned a row at a time so no second full-size buffer is held. */
    private fun whollyTransparent(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr 24) > 8 }) return false
        }
        return true
    }
}
