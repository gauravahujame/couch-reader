package dev.couchreader.ui

import android.content.Context
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout

/**
 * One book on the library shelf: cover, title, author and a progress slot, focused as a single unit.
 * OK opens the book; holding OK or pressing Menu opens its options. At rest the tile has no frame, so the
 * cover leads; focus adds the standard 2dp gold outline on a white card, a 2dp shadow and a 1.02× lift.
 */
class BookTile(context: Context, val cover: View, title: String, author: String, coverHeight: Int,
               onOpen: () -> Unit, private val onOptions: () -> Unit) : LinearLayout(context) {
    /** Filled by the caller; holds the non-focusable progress bar and percentage. */
    val progress: LinearLayout = context.row()

    init {
        orientation = VERTICAL
        clipChildren = false; clipToPadding = false
        isFocusable = true; isClickable = true; isLongClickable = true
        val inset = context.dp(PADDING_DP)
        setPadding(inset, inset, inset, inset)
        addView(cover, LayoutParams(-1, coverHeight))
        addView(View(context), LayoutParams(1, context.dp(10)))
        // The text block always reserves two title lines and one author line, so progress bars line up across a row.
        val text = context.column()
        val titleView = context.label(title, 15f).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val authorView = context.label(author, 13f, Palette.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        text.addView(titleView, LayoutParams(-1, -2))
        text.addView(View(context), LayoutParams(1, context.dp(3)))
        text.addView(authorView, LayoutParams(-1, -2))
        text.minimumHeight = titleView.lineHeight * 2 + context.dp(3) + authorView.lineHeight
        addView(text, LayoutParams(-1, -2))
        addView(View(context), LayoutParams(1, context.dp(8)))
        progress.minimumHeight = context.dp(16)
        addView(progress, LayoutParams(-1, -2))
        contentDescription = if (author.isBlank()) title else "$title by $author"
        stateListAnimator = null
        paint(false)
        setOnFocusChangeListener { _, focused -> paint(focused) }
        setOnClickListener { onOpen() }
        setOnLongClickListener { onOptions(); true }
    }

    /** The single painter for this tile; a replacement focus listener must call it. */
    fun paint(focused: Boolean) {
        background = if (!focused) null else android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(Palette.greenTint, border = Palette.gold, stroke = context.dp(2)))
            addState(intArrayOf(), shape(Palette.white, border = Palette.gold, stroke = context.dp(2)))
        }
        liftForFocus(focused)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { if (event.repeatCount == 0) onOptions(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Open book"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Book options"))
    }

    companion object {
        /** Inner padding, so the focus frame sits just outside the cover. */
        const val PADDING_DP = 6
    }
}
