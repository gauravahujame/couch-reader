package dev.couchreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Cover colors. A book keeps the same tone everywhere (library card, opening screen) and across sessions. */
object CoverTones {
    private val tones = intArrayOf(0xff365347.toInt(), 0xff74513d.toInt(), 0xff515d70.toInt(), 0xff6b6743.toInt(), 0xff5e4a5c.toInt(), 0xff3f5c63.toInt())
    fun forKey(key: String): Int = tones[Math.floorMod(key.hashCode(), tones.size)]
}

/**
 * Paints a cloth-bound cover: the tone with a soft top-left sheen, a darker spine band on the left and a
 * thin highlight where the spine meets the board. Supplies a rounded outline so elevation casts a real shadow.
 */
class CoverDrawable(private val tone: Int, private val radius: Float, private val spineWidth: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val hinge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2effffff }
    private val rect = RectF()
    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        sheen.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0x24ffffff, 0x00ffffff, 0x1a000000), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, sheen)
        // The spine band is clipped to the rounded cover so its left corners stay round.
        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.left + spineWidth, rect.bottom)
        canvas.drawRoundRect(rect, radius, radius, spine)
        canvas.restore()
        canvas.drawRect(rect.left + spineWidth, rect.top, rect.left + spineWidth + spineWidth / 6f, rect.bottom, hinge)
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius) }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A typographic book cover: format eyebrow, serif title and author on a [CoverDrawable].
 * Size it with layout params; [title] is exposed so a constrained parent can trim its line count.
 * Not focusable; the controls around it own focus.
 */
class BookCover(context: Context, title: String, author: String, format: String, tone: Int, titleSize: Float = 22f) : LinearLayout(context) {
    val title: TextView = context.label(title, titleSize, Color.WHITE, serif = true).apply {
        gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
    }
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        isFocusable = false
        val spine = context.dp(9).toFloat()
        background = CoverDrawable(tone, context.dp(4).toFloat(), spine)
        // Extra left padding keeps the text centered on the board rather than over the spine.
        setPadding(context.dp(16) + spine.toInt(), context.dp(12), context.dp(16), context.dp(12))
        val scale = titleSize / 22f
        addView(context.label("—  ${format.uppercase()}  —", 10f * scale, 0xffe9dfc4.toInt()).apply { letterSpacing = .15f })
        space((10 * scale).toInt())
        addView(this.title)
        space((10 * scale).toInt())
        // No author is known for many PDFs; the title stays centered without an empty line under it.
        if (author.isNotBlank()) addView(context.label(author, 12f * scale, 0xfff1e9d8.toInt()).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        contentDescription = if (author.isBlank()) title else "$title by $author"
    }
}
