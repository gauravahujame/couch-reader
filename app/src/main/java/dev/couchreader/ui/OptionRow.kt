package dev.couchreader.ui

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.widget.LinearLayout

/** Owns horizontal/OK input so it can never reach the underlying reader. */
class OptionRow(context: Context, title: String, private val value: () -> String,
                private val change: (Int) -> Unit, focused: () -> Unit) : LinearLayout(context) {
    private val display = context.label("", 18f)
    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        isFocusable = true
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        addView(context.label(title, 18f), LayoutParams(0, -2, 1f))
        addView(display)
        setOnFocusChangeListener { _, hasFocus ->
            background = shape(if (hasFocus) Palette.green else Palette.white,
                border = if (hasFocus) Palette.gold else Palette.line, stroke = context.dp(if (hasFocus) 3 else 1))
            for (i in 0 until childCount) (getChildAt(i) as android.widget.TextView).setTextColor(if (hasFocus) Color.WHITE else Palette.ink)
            if (hasFocus) focused()
        }
        // Match the unfocused look the focus listener restores, so the first focus changes nothing else.
        background = shape(Palette.white, border = Palette.line, stroke = context.dp(1))
        refresh()
    }
    private fun refresh() { display.text = "◀  ${value()}  ▶"; contentDescription = "${(getChildAt(0) as android.widget.TextView).text}, ${value()}" }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                change(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1); refresh()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
