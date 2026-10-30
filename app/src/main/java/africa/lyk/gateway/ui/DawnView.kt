package africa.lyk.gateway.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import me.capcom.smsgateway.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * [lyk] The status as a sunrise, the same sun as the logo: up and glowing = ready to send,
 * half set = stopped, below the horizon = not connected.
 */
class DawnView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { Offline, Stopped, Ready }

    private val ink = ContextCompat.getColor(context, R.color.lyk_ink)
    private val teal = ContextCompat.getColor(context, R.color.lyk_teal)
    private val lime = ContextCompat.getColor(context, R.color.lyk_lime)
    private val amber = ContextCompat.getColor(context, R.color.lyk_amber)
    private val off = ContextCompat.getColor(context, R.color.lyk_off)

    private val sky = Paint()
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

    private var target = State.Offline
    private var rise = 0f          // 0 = sun below the horizon, 1 = fully up
    private var tint = off
    private var pulse = 0f

    private val riseAnim = ValueAnimator()
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    fun setState(state: State, animate: Boolean = true) {
        if (state == target && width > 0) return
        target = state
        val (r, c) = when (state) {
            State.Ready -> 1f to lime
            State.Stopped -> 0.42f to amber
            State.Offline -> 0.2f to off
        }
        riseAnim.cancel()
        val fromRise = rise
        val fromTint = tint
        if (!animate || !isAttachedToWindow) { rise = r; tint = c; invalidate() } else {
            riseAnim.setFloatValues(0f, 1f)
            riseAnim.duration = 900
            riseAnim.interpolator = OvershootInterpolator(1.1f)
            riseAnim.removeAllUpdateListeners()
            riseAnim.addUpdateListener {
                val t = it.animatedValue as Float
                rise = fromRise + (r - fromRise) * t
                tint = blend(fromTint, c, t.coerceIn(0f, 1f))
                invalidate()
            }
            riseAnim.start()
        }
        if (state == State.Ready) { if (!pulseAnim.isStarted) pulseAnim.start() } else pulseAnim.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (target == State.Ready && !pulseAnim.isStarted) pulseAnim.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnim.cancel(); riseAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val glowEdge = blend(ink, teal, 0.45f)
        sky.shader = LinearGradient(0f, 0f, 0f, h * 0.80f, ink, glowEdge, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val horizon = h * 0.80f
        val cx = w / 2f
        val r = min(w, h) * 0.34f
        canvas.drawRect(0f, 0f, w, horizon, sky)
        canvas.drawColor(0x00000000)

        // Glow behind the sun, stronger the higher it is.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        val glowR = r * (1.7f + 0.25f * pulse * rise)
        val sunY = horizon + r * (1f - rise)
        glow.shader = android.graphics.RadialGradient(
            cx, sunY, glowR, (tint and 0x00FFFFFF) or ((70 * rise).toInt().coerceIn(0, 255) shl 24), 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.clipRect(0f, 0f, w, horizon)
        canvas.drawCircle(cx, sunY, glowR, glow)

        // Rays fan out above the sun.
        ray.color = tint
        ray.strokeWidth = r * 0.13f
        val count = 7
        for (i in 0 until count) {
            val a = Math.toRadians(-180.0 + (i + 1) * (180.0 / (count + 1)))
            val inner = r * 1.22f
            val len = r * (0.28f + 0.14f * (if (i % 2 == 0) 1f else 0.5f)) * (0.35f + 0.65f * rise)
            ray.alpha = if (target == State.Offline) 70 else ((0.45f + 0.55f * pulse * rise) * 255 * rise.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            canvas.drawLine(
                cx + (cos(a) * inner).toFloat(), sunY + (sin(a) * inner).toFloat(),
                cx + (cos(a) * (inner + len)).toFloat(), sunY + (sin(a) * (inner + len)).toFloat(), ray
            )
        }
        sun.color = tint
        if (target == State.Offline) {
            sun.style = Paint.Style.STROKE; sun.strokeWidth = r * 0.10f
        } else sun.style = Paint.Style.FILL
        canvas.drawCircle(cx, sunY, r, sun)
        canvas.restore()

        // Horizon.
        ground.color = tint
        ground.alpha = 150
        ground.strokeWidth = r * 0.09f
        val pad = w * 0.12f
        canvas.drawLine(pad, horizon, w - pad, horizon, ground)
        ground.alpha = 60
        canvas.drawLine(pad * 2.2f, horizon + r * 0.32f, w - pad * 2.2f, horizon + r * 0.32f, ground)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
