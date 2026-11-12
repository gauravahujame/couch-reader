package dev.couchreader.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.ScrollView

/**
 * A vertical scroller for a list split into headed sections. It only ever comes to rest with the top
 * of the list or a section heading at its top edge, so a heading is never left half cut under the
 * fixed page title. Register each heading with [addHeading]; it must be a direct child of the single
 * content view.
 *
 * Clips itself (see docs/agents/tv-ui.md): its parents draw children unclipped for focus shadows.
 */
class SectionScrollView(context: Context) : ScrollView(context) {
    private val headings = mutableListOf<View>()
    private var basePaddingBottom = -1

    init {
        outlineProvider = android.view.ViewOutlineProvider.BOUNDS; clipToOutline = true
    }

    fun addHeading(view: View) { headings += view }

    private fun viewport(): Int = height - paddingTop - paddingBottom

    /** Content offsets where the scroller may rest, in ascending order. */
    private fun stops(): List<Int> = (listOf(0) + headings.map { it.top }).distinct().sorted()

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val viewport = viewport()
        val current = scrollY
        if (rect.top >= current && rect.bottom <= current + viewport) return 0
        val stops = stops()
        // The focused rect belongs to the section whose heading is the last stop at or above it.
        val sectionTop = stops.lastOrNull { it <= rect.top } ?: 0
        val target = when {
            // A section taller than the scroller cannot keep its heading in view; show the rect itself.
            rect.bottom - sectionTop > viewport -> if (rect.top < current) rect.top else rect.bottom - viewport
            rect.top < current -> sectionTop
            // Moving down: the smallest scroll that shows the rect whole, but never past its own heading.
            else -> minOf(stops.firstOrNull { it >= rect.bottom - viewport } ?: sectionTop, sectionTop)
        }
        val maxScroll = (getChildAt(0).height - viewport).coerceAtLeast(0)
        return target.coerceIn(0, maxScroll) - current
    }

    /**
     * Pads the end of the content so the furthest scroll is itself a stop. Otherwise reaching the
     * end of the list would rest at an arbitrary offset and cut the heading at the top again.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val child = getChildAt(0) ?: return
        if (basePaddingBottom < 0) basePaddingBottom = child.paddingBottom
        val viewport = viewport()
        val contentEnd = child.height - child.paddingBottom + basePaddingBottom
        val extra = if (contentEnd <= viewport) 0 else {
            val last = stops().firstOrNull { contentEnd - it <= viewport } ?: (contentEnd - viewport)
            (last + viewport - contentEnd).coerceAtLeast(0)
        }
        val wanted = basePaddingBottom + extra
        if (child.paddingBottom != wanted) post { child.setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, wanted) }
    }
}
