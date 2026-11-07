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
import android.widget.Button
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
fun Context.action(value: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
    text = value; textSize = 16f; isAllCaps = false
    setPadding(dp(22), dp(10), dp(22), dp(10)); minHeight = dp(50)
    background = shape(if (primary) Palette.green else Palette.white)
    setTextColor(if (primary) Color.WHITE else Palette.ink)
    isFocusable = true
    // The platform Button's default StateListAnimator adds a 2dp resting elevation whose shadow
    // is clipped by tight parents. Elevation is owned by the focus listener below instead.
    stateListAnimator = null
    elevation = 0f
    setOnFocusChangeListener { _, focused ->
        background = shape(if (focused || primary) Palette.green else Palette.white,
            border = if (focused) Palette.gold else Palette.line, stroke = dp(if (focused) 3 else 1))
        setTextColor(if (focused || primary) Color.WHITE else Palette.ink)
        elevation = if (focused) dp(6).toFloat() else 0f
    }
    setOnClickListener { onClick() }
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
