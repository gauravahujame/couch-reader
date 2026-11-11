package dev.couchreader.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An indeterminate progress line: a short green segment glides back and forth along a pale track.
 * Runs only while attached, so a removed screen never keeps an animator alive.
 */
class LoadingLine(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.line; strokeCap = Paint.Cap.ROUND }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.green; strokeCap = Paint.Cap.ROUND }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val stroke = height.toFloat(); val y = height / 2f
        track.strokeWidth = stroke; bar.strokeWidth = stroke
        val start = stroke / 2; val end = width - stroke / 2
        canvas.drawLine(start, y, end, y, track)
        val segment = (end - start) * .32f
        val left = start + (end - start - segment) * phase
        canvas.drawLine(left, y, left + segment, y, bar)
    }
}

/**
 * Full-screen waiting state on paper: an optional hero (for example a [BookCover]), a small-caps eyebrow,
 * a serif message, an optional muted detail line and a [LoadingLine]. The content rises in on attach;
 * [dismiss] fades the whole screen out and removes it, revealing whatever is underneath.
 * It is opaque and not focusable, so it can sit over a renderer that is still preparing.
 */
class LoadingScreen(context: Context, eyebrow: String, message: String, hero: View? = null, detail: String? = null) : FrameLayout(context) {
    private val detailLabel: TextView
    private val content: LinearLayout
    private var dismissing = false
    private val hasHero = hero != null
    // The hero drifts gently up and down while waiting, like a book being lifted off the shelf.
    private val float = ValueAnimator.ofFloat(0f, -context.dp(7).toFloat()).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        hero?.let { view -> addUpdateListener { view.translationY = it.animatedValue as Float } }
    }
    init {
        setBackgroundColor(Palette.paper)
        isFocusable = false; clipChildren = false
        content = context.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        hero?.let {
            content.addView(it, LinearLayout.LayoutParams(-2, -2))
            content.space(34)
        }
        content.addView(context.label(eyebrow.uppercase(), 13f, Palette.gold).apply {
            letterSpacing = .22f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        content.space(10)
        content.addView(context.label(message, 30f, serif = true).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        detailLabel = context.label(detail.orEmpty(), 16f, Palette.muted).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            visibility = if (detail.isNullOrBlank()) GONE else VISIBLE
        }
        content.space(8)
        content.addView(detailLabel, LinearLayout.LayoutParams(-1, -2))
        content.space(26)
        content.addView(LoadingLine(context), LinearLayout.LayoutParams(context.dp(150), context.dp(4)))
        // A fixed text width: wrap-content would let the narrow hero and line decide it and clip long titles.
        addView(content, LayoutParams((resources.displayMetrics.widthPixels * .6).toInt(), -2, Gravity.CENTER))
        content.alpha = 0f; content.translationY = context.dp(18).toFloat()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!dismissing) content.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
        if (hasHero) float.start()
    }
    override fun onDetachedFromWindow() { float.cancel(); super.onDetachedFromWindow() }
    /** Replaces the detail line, for information that arrives after the screen is shown. */
    fun setDetail(value: String) {
        detailLabel.text = value
        detailLabel.visibility = if (value.isBlank()) GONE else VISIBLE
    }
    /** Fades out, then removes itself from its parent. Safe to call more than once. */
    fun dismiss() {
        if (dismissing) return
        dismissing = true
        content.animate().cancel()
        animate().alpha(0f).setDuration(320).setInterpolator(DecelerateInterpolator())
            .withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
    }
}
