package dev.couchreader.importer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decides whether a rendered PDF page is blank enough to skip when choosing a shelf thumbnail.
 * Pure function on ARGB pixels, so it runs on the host. When in doubt it keeps the page: skipping a real
 * cover is a more visible mistake than showing a pale one.
 *
 * Method: estimate the background from eight border patches (inset past a narrow scan border, and clear
 * of the running header and footer bands); if they disagree, keep the page. Then split the interior into
 * small cells, mark cells whose pixels differ from the background (max RGB channel difference, so dark and
 * coloured artwork count as much as black text), and group marked cells into 8-connected regions. Isolated
 * specks are ignored; any coherent region in the body keeps the page. Marks confined to the header or
 * footer band (page numbers, running titles) are ignored while they stay small.
 *
 * Thresholds were calibrated on the synthetic fixtures in PdfCoverPickerTest at a 480 px long edge.
 */
object PdfBlankDetector {
    /** Background patches sit this far in from the left/right edges (skips a scan border up to 4%). */
    private const val PATCH_X = .06
    /** Patch rows: below the header band, the middle, and above the footer band. */
    private val PATCH_Y = doubleArrayOf(.10, .50, .86)
    /** Patch side as a fraction of the short edge. */
    private const val PATCH_SIZE = .035
    /** Max channel difference between a patch median and the background median for a consistent border. */
    private const val BORDER_TOLERANCE = 24
    /** Interior analysis ignores this outer margin, where scanners leave a narrow uniform border. */
    private const val SCAN_MARGIN = .04
    /** The running-header and running-footer bands, as fractions of the page height. */
    private const val HEADER_BAND = .08
    private const val FOOTER_BAND = .12
    /** A pixel differs from the background by more than this on some channel: clear ink. */
    private const val STRONG_DELTA = 48
    /** A softer difference: pale print or a faint design whose significance is uncertain. */
    private const val FAINT_DELTA = 20
    /** Cells per long edge. 64 gives 7–8 px cells at 480 px. */
    private const val CELLS_LONG_EDGE = 64
    /** A cell is marked when this share of its pixels passes the strong threshold. */
    private const val STRONG_CELL_SHARE = .05
    /** ...or this share passes the faint threshold (needs coverage, so scanner grain does not count). */
    private const val FAINT_CELL_SHARE = .12
    /** A region this many cells big, or holding this many strong pixels, is content, not a speck. */
    private const val SIGNIFICANT_CELLS = 3
    private const val SIGNIFICANT_PIXELS = 24
    /** Header/footer marks are ignored up to this share of the band's cells. */
    private const val BAND_SHARE = .15

    fun isBlank(pixels: IntArray, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0 && pixels.size >= width * height)
        val background = background(pixels, width, height) ?: return false
        val cell = max(2, (max(width, height).toDouble() / CELLS_LONG_EDGE).roundToInt())
        val x0 = (width * SCAN_MARGIN).toInt(); val x1 = width - x0
        val y0 = (height * SCAN_MARGIN).toInt(); val y1 = height - y0
        val columns = (x1 - x0 + cell - 1) / cell; val rows = (y1 - y0 + cell - 1) / cell
        if (columns <= 0 || rows <= 0) return false
        val strongCounts = IntArray(columns * rows)
        val marked = BooleanArray(columns * rows)
        val br = red(background); val bg = green(background); val bb = blue(background)
        for (row in 0 until rows) for (column in 0 until columns) {
            var strong = 0; var faint = 0; var total = 0
            val top = y0 + row * cell; val left = x0 + column * cell
            for (y in top until minOf(top + cell, y1)) for (x in left until minOf(left + cell, x1)) {
                val p = pixels[y * width + x]
                val d = maxOf(abs(red(p) - br), abs(green(p) - bg), abs(blue(p) - bb))
                if (d > STRONG_DELTA) strong++
                if (d > FAINT_DELTA) faint++
                total++
            }
            val index = row * columns + column
            strongCounts[index] = strong
            marked[index] = total > 0 && (strong >= max(2.0, total * STRONG_CELL_SHARE) || faint >= total * FAINT_CELL_SHARE)
        }
        // Group marked cells; classify each region by where it lies.
        val seen = BooleanArray(marked.size)
        var bandCells = 0
        val bandTotal = (0 until rows).count { inBand(it, rows, cell, y0, height) } * columns
        val stack = ArrayDeque<Int>()
        for (start in marked.indices) {
            if (!marked[start] || seen[start]) continue
            var size = 0; var strong = 0; var allInBand = true
            stack.addLast(start); seen[start] = true
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                size++; strong += strongCounts[index]
                val row = index / columns; val column = index % columns
                if (!inBand(row, rows, cell, y0, height)) allInBand = false
                for (dy in -1..1) for (dx in -1..1) {
                    val r = row + dy; val c = column + dx
                    if (r !in 0 until rows || c !in 0 until columns) continue
                    val next = r * columns + c
                    if (marked[next] && !seen[next]) { seen[next] = true; stack.addLast(next) }
                }
            }
            val significant = size >= SIGNIFICANT_CELLS || strong >= SIGNIFICANT_PIXELS
            if (allInBand) bandCells += size
            else if (significant) return false
        }
        return bandTotal == 0 || bandCells <= bandTotal * BAND_SHARE
    }

    private fun inBand(row: Int, rows: Int, cell: Int, y0: Int, height: Int): Boolean {
        val center = y0 + row * cell + cell / 2.0
        return center < height * HEADER_BAND || center > height * (1 - FOOTER_BAND)
    }

    /** The page colour from border patches, or null when they disagree (a design runs to the edges). */
    private fun background(pixels: IntArray, width: Int, height: Int): Int? {
        val side = max(3, (minOf(width, height) * PATCH_SIZE).roundToInt())
        val medians = mutableListOf<Int>()
        for (py in PATCH_Y) for (px in doubleArrayOf(PATCH_X, .5, 1 - PATCH_X)) {
            if (px == .5 && py == .50) continue
            val cx = (width * px).toInt(); val cy = (height * py).toInt()
            val left = (cx - side / 2).coerceIn(0, max(0, width - side)); val top = (cy - side / 2).coerceIn(0, max(0, height - side))
            val values = buildList { for (y in top until minOf(top + side, height)) for (x in left until minOf(left + side, width)) add(pixels[y * width + x]) }
            if (values.isEmpty()) return null
            medians += median(values)
        }
        val background = median(medians)
        val consistent = medians.all { m ->
            maxOf(abs(red(m) - red(background)), abs(green(m) - green(background)),
                abs(blue(m) - blue(background))) <= BORDER_TOLERANCE
        }
        return background.takeIf { consistent }
    }

    private fun red(color: Int) = (color shr 16) and 0xff
    private fun green(color: Int) = (color shr 8) and 0xff
    private fun blue(color: Int) = color and 0xff
    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    private fun median(values: List<Int>): Int {
        fun channel(shift: Int) = values.map { (it shr shift) and 0xff }.sorted()[values.size / 2]
        return rgb(channel(16), channel(8), channel(0))
    }
}

/** Which page of a PDF stands in for its cover on the shelf. */
object PdfCoverPicker {
    /** Pages inspected from the start; a cover after them is not looked for. */
    const val MAX_PAGES = 5
    const val MAX_EDGE = 480

    sealed class Choice<out T> {
        data class Page<T>(val index: Int, val value: T) : Choice<T>()
        /** Every inspected page was blank or failed: show the generated cover. */
        object None : Choice<Nothing>()
        /** Stopped early (cancelled, or the renderer itself broke); try again later. */
        object Interrupted : Choice<Nothing>()
    }

    /**
     * Inspects pages 0 until min([MAX_PAGES], [pageCount]) one at a time. [render] returns null when that page
     * failed but the document can continue, and throws [IllegalStateException] when the renderer is unusable.
     * Each rejected value is passed to [release] before the next page renders.
     */
    fun <T : Any> choose(pageCount: Int, render: (Int) -> T?, isBlank: (T) -> Boolean, release: (T) -> Unit,
                         cancelled: () -> Boolean): Choice<T> {
        for (index in 0 until minOf(MAX_PAGES, pageCount)) {
            if (cancelled()) return Choice.Interrupted
            val value = try { render(index) } catch (_: IllegalStateException) { return Choice.Interrupted } ?: continue
            if (cancelled()) { release(value); return Choice.Interrupted }
            if (!isBlank(value)) return Choice.Page(index, value)
            release(value)
        }
        return Choice.None
    }

    /** Renders with a private read-only [PdfRenderer]; the reader's own renderer is never shared. */
    fun pick(file: File, cancelled: () -> Boolean): Choice<Bitmap> {
        val descriptor = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
            catch (_: Exception) { return Choice.Interrupted }
        descriptor.use {
            val renderer = try { PdfRenderer(descriptor) } catch (_: Exception) { return Choice.Interrupted }
            try {
                return choose(renderer.pageCount, { index -> renderPage(renderer, index) },
                    { bitmap ->
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        PdfBlankDetector.isBlank(pixels, bitmap.width, bitmap.height)
                    }, Bitmap::recycle, cancelled)
            } finally { renderer.close() }
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap? {
        val page = try { renderer.openPage(index) } catch (failure: IllegalStateException) { throw failure } catch (_: Exception) { return null }
        page.use {
            if (page.width <= 0 || page.height <= 0) return null
            val scale = MAX_EDGE.toDouble() / max(page.width, page.height)
            val width = (page.width * scale).roundToInt().coerceIn(1, MAX_EDGE)
            val height = (page.height * scale).roundToInt().coerceIn(1, MAX_EDGE)
            val bitmap = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return null }
            return try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (failure: IllegalStateException) { bitmap.recycle(); throw failure
            } catch (_: Exception) { bitmap.recycle(); null }
        }
    }
}
