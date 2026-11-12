package dev.couchreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val paper = Color.rgb(245, 240, 229)
    val ink = Color.rgb(40, 55, 46)
    val muted = Color.rgb(88, 101, 91)
    val green = Color.rgb(41, 77, 59)
    val line = Color.rgb(194, 199, 181)
    val white = Color.rgb(255, 253, 246)
    val gold = Color.rgb(170, 107, 43)
    val greenTint = Color.rgb(226, 231, 214)
    val greenPressed = Color.rgb(29, 56, 43)
}
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.label(value: String, size: Float = 18f, color: Int = Palette.ink, serif: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color)
    if (serif) typeface = Typeface.createFromAsset(assets, "reader/literata.ttf")
    includeFontPadding = false
    if (android.os.Build.VERSION.SDK_INT >= 28) isFallbackLineSpacing = false
    val metrics = paint.fontMetricsInt
    val desiredHeight = textSize * if (serif) 1.14f else 1.32f
    setLineSpacing(desiredHeight - (metrics.descent - metrics.ascent), 1f)
}
fun shape(color: Int, radius: Float = 12f, border: Int? = null, stroke: Int = 2) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    border?.let { setStroke(stroke, it) }
}
/**
 * The button focus system: a green button has no border at rest; focus adds a 2dp copper outline,
 * a faint 2dp shadow and a 1.02× lift; pressing darkens the fill. A white (secondary) button keeps its
 * 1dp hairline at rest and takes the green focused look, so focus never relies on the outline alone.
 */
private const val FOCUS_SCALE = 1.02f
private fun Context.buttonBackground(focused: Boolean, rest: Int): android.graphics.drawable.Drawable {
    val fill = if (focused) Palette.green else rest
    val border = if (focused) Palette.gold else if (rest == Palette.green) null else Palette.line
    val stroke = dp(if (focused) 2 else 1)
    return android.graphics.drawable.StateListDrawable().apply {
        if (fill == Palette.green) addState(intArrayOf(android.R.attr.state_pressed), shape(Palette.greenPressed, border = border, stroke = stroke))
        addState(intArrayOf(), shape(fill, border = border, stroke = stroke))
    }
}
private fun View.liftForFocus(focused: Boolean) {
    elevation = if (focused) context.dp(2).toFloat() else 0f
    val scale = if (focused) FOCUS_SCALE else 1f
    animate().cancel()
    if (!isAttachedToWindow || scaleX == scale) { scaleX = scale; scaleY = scale }
    else animate().scaleX(scale).scaleY(scale).setDuration(120).start()
}
/** Paints an [action] button. Call sites that replace its focus listener call this from their own. */
fun Button.paintAction(focused: Boolean, primary: Boolean = false) {
    background = context.buttonBackground(focused, if (primary) Palette.green else Palette.white)
    setTextColor(if (focused || primary) Color.WHITE else Palette.ink)
    liftForFocus(focused)
}
fun Context.action(value: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
    text = value; textSize = 16f; isAllCaps = false
    setPadding(dp(22), dp(10), dp(22), dp(10)); minHeight = dp(50)
    isFocusable = true
    // The platform Button's default StateListAnimator adds a 2dp resting elevation whose shadow
    // is clipped by tight parents. Elevation is owned by paint() instead.
    stateListAnimator = null
    // One painter for both states, so a button looks the same before and after its first focus.
    paintAction(false, primary)
    setOnFocusChangeListener { _, focused -> paintAction(focused, primary) }
    setOnClickListener { onClick() }
}
/** A square, icon-only counterpart to [action]. [description] is read by TalkBack and used by tests. */
fun Context.iconAction(icon: Int, description: String, primary: Boolean = false, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
    setImageResource(icon); contentDescription = description
    scaleType = ImageView.ScaleType.CENTER
    minimumWidth = dp(50); minimumHeight = dp(50)
    setPadding(dp(13), dp(13), dp(13), dp(13))
    isFocusable = true
    stateListAnimator = null
    elevation = 0f
    fun paint(focused: Boolean) {
        background = buttonBackground(focused, if (primary) Palette.green else Palette.white)
        imageTintList = ColorStateList.valueOf(if (focused || primary) Color.WHITE else Palette.ink)
        liftForFocus(focused)
    }
    paint(false)
    setOnFocusChangeListener { _, focused -> paint(focused) }
    setOnClickListener { onClick() }
}
/**
 * One option of a mutually exclusive group (for example the reader theme). The active option keeps a
 * tinted fill, green border and check mark, so it stays distinguishable from the focused option,
 * which uses the standard green fill and gold border. Call [refresh] on every option after a change.
 * [restColor] fills the option when it is neither focused nor selected (the Warm light theme shows its page colour).
 */
class ChoiceButton(context: Context, private val value: String, private val selected: () -> Boolean,
                   private val restColor: Int = Palette.white, onClick: () -> Unit) : Button(context) {
    init {
        textSize = 16f; isAllCaps = false
        setPadding(context.dp(22), context.dp(10), context.dp(22), context.dp(10)); minHeight = context.dp(50)
        isFocusable = true
        stateListAnimator = null
        refresh()
        setOnFocusChangeListener { _, _ -> refresh() }
        setOnClickListener { onClick() }
    }
    fun refresh() {
        val focused = hasFocus(); val active = selected()
        text = if (active) "✓  $value" else value
        contentDescription = if (active) "$value, selected" else value
        background = if (focused) context.buttonBackground(true, restColor)
            else shape(if (active) Palette.greenTint else restColor, border = if (active) Palette.green else Palette.line,
                stroke = context.dp(if (active) 2 else 1))
        setTextColor(if (focused) Color.WHITE else Palette.ink)
        liftForFocus(focused)
    }
}
fun LinearLayout.space(size: Int) { addView(View(context), LinearLayout.LayoutParams(context.dp(size), context.dp(size))) }
// clipChildren = false lets a focused button's elevation shadow draw past the container's edges.
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }

/** A drawn mark stays centered independently of font glyph metrics. */
class BookUnderline(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.green; style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = stroke.strokeWidth / 2
        val bottom = height - inset
        val radius = context.dp(7).toFloat().coerceAtMost(height / 2f)
        val right = width - inset
        val path = Path().apply {
            moveTo(inset, inset); lineTo(inset, bottom - radius)
            quadTo(inset, bottom, inset + radius, bottom)
            lineTo(right - radius, bottom); quadTo(right, bottom, right, bottom - radius)
            lineTo(right, inset)
        }
        canvas.drawPath(path, stroke)
    }
}

/** A constrained parent may shrink the QR, but must never change its aspect ratio. */
class SquareQrView(context: Context) : ImageView(context) {
    init { scaleType = ScaleType.FIT_CENTER; adjustViewBounds = true }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val side = minOf(measuredWidth, measuredHeight)
        setMeasuredDimension(side, side)
    }
}
