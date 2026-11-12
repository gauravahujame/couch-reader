package dev.couchreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout

/**
 * Remote-friendly page chooser for fixed-layout books, built as one focusable control so its size
 * does not grow with the page count. ← / → move one page, ↑ / ↓ move [bigStep] pages, number keys
 * type a page, OK chooses it. Holding a key repeats at a readable pace. TalkBack sees an adjustable
 * range: swipe actions move one page and double-tap chooses.
 */
class PageDial(context: Context, private val count: Int, private val current: Int, private val onChoose: (Int) -> Unit) : LinearLayout(context) {
    /** One-based page shown on the dial. */
    var page = current.coerceIn(1, count)
        private set
    /** About 20 to 25 presses cover the whole book, in round numbers. */
    val bigStep = listOf(10, 25, 50, 100, 250).firstOrNull { count / it <= 25 } ?: 500
    private val number = context.label("", 60f, serif = true).apply { gravity = Gravity.CENTER; minWidth = context.dp(150) }
    private val previous = context.label("‹", 44f, Palette.green)
    private val next = context.label("›", 44f, Palette.green)
    private val track = Track(context)
    private var typed = ""
    private var typedAt = 0L
    private var lastStep = 0L

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true; isClickable = true
        setPadding(context.dp(24), context.dp(14), context.dp(24), context.dp(20))
        // Neighbouring pages repeat the dial's value, so TalkBack reads the dial as one control.
        listOf(number, previous, next).forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(context.row().apply {
            gravity = Gravity.CENTER
            addView(previous); space(24); addView(number); space(24); addView(next)
        })
        addView(context.label("of $count", 18f, Palette.muted).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(View(context), LayoutParams(1, context.dp(16)))
        addView(track, LayoutParams(-1, context.dp(18)))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener { onChoose(page) }
        paint(false)
        setOnFocusChangeListener { _, focused -> paint(focused) }
        update()
    }

    // A large surface reads better with a border-only focus (as the Settings About row) than a solid green fill.
    private fun paint(focused: Boolean) {
        background = shape(Palette.white, context.dp(14).toFloat(), if (focused) Palette.gold else Palette.line, context.dp(if (focused) 2 else 1))
    }

    private fun move(target: Int) {
        val value = target.coerceIn(1, count)
        if (value != page) { page = value; update() }
    }

    private fun update() {
        number.text = "$page"
        previous.alpha = if (page > 1) 1f else .25f
        next.alpha = if (page < count) 1f else .25f
        val state = "Page $page of $count"
        if (Build.VERSION.SDK_INT >= 30) { contentDescription = "Choose page"; stateDescription = state } else contentDescription = state
        track.invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> bigStep
            KeyEvent.KEYCODE_DPAD_DOWN -> -bigStep
            else -> 0
        }
        if (step != 0) {
            // Platform key repeat is about 20 per second; slow it so the number stays readable while held.
            if (event.repeatCount > 0 && event.eventTime - lastStep < REPEAT_MS) return true
            lastStep = event.eventTime; typed = ""
            move(page + step); return true
        }
        val digit = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) {
            typed = if (event.eventTime - typedAt > TYPING_MS) "$digit" else typed + digit
            typedAt = event.eventTime
            // A number past the end starts over with this digit, so "4", "9" in a 48-page book gives 9.
            if ((typed.toIntOrNull() ?: Int.MAX_VALUE) > count) typed = "$digit"
            typed.toInt().takeIf { it >= 1 }?.let(::move)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.SeekBar::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        @Suppress("DEPRECATION")
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1f, count.toFloat(), page.toFloat())
        if (page > 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (page < count) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Open page"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { move(page + 1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { move(page - 1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    /** Where the chosen page sits in the book. The small gold tick marks the page being read. */
    private inner class Track(context: Context) : View(context) {
        private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.line; strokeWidth = context.dp(4).toFloat(); strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.green; strokeWidth = context.dp(4).toFloat(); strokeCap = Paint.Cap.ROUND }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.gold; strokeWidth = context.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.green }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val radius = context.dp(8).toFloat(); val y = height / 2f
            val left = radius; val right = width - radius
            fun x(value: Int) = if (count <= 1) left else left + (right - left) * (value - 1) / (count - 1)
            canvas.drawLine(left, y, right, y, rail)
            canvas.drawLine(left, y, x(page), y, fill)
            val reading = x(current)
            canvas.drawLine(reading, y - radius, reading, y + radius, tick)
            canvas.drawCircle(x(page), y, radius * .8f, knob)
        }
    }

    private companion object {
        const val REPEAT_MS = 140L
        // Digits within this gap build one number. TV remotes are slow to type on, so this is generous.
        const val TYPING_MS = 2500L
    }
}
