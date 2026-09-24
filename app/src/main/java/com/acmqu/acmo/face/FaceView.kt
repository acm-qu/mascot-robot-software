package com.acmqu.acmo.face

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import com.acmqu.acmo.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * The robot face, drawn on a Canvas. A port of Robot Face.dc.html.
 *
 * The face lives on a 16:10 "stage" measured in units, 1 unit being 1 % of the
 * stage width (the design's cqw), so it scales to any screen. Two rounded
 * rectangles are the eyes: they blink, glance around, and bob when excited.
 * Seven fixed slots hold a glyph each -- brows, cheeks, mouth, badge, tear --
 * drawn in JetBrains Mono, and an expression is just a set of eye poses plus
 * the glyphs it wants in those slots. Changing expression shrinks the glyphs
 * that differ to nothing, swaps them 220 ms later and pops the new ones in.
 * While speaking, the mouth cycles through a few frames -- small or big ones
 * for the level of t
 * he voice being heard, and the face's own mouth while it pauses.
 *
 * Everything animates through [Anim], a float that eases toward a target the
 * way a CSS transition does, and the view keeps redrawing only while
 * something is still moving.
 */
class FaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** A value that eases from wherever it is to a target: a CSS transition. */
    private class Anim(initial: Float) {
        private var from = initial
        private var to = initial
        private var start = 0L
        private var duration = 0L
        private var interp: TimeInterpolator = SETTLE

        fun set(target: Float, durationMs: Long, interpolator: TimeInterpolator) {
            if (target == to) return   // like CSS: re-setting the same value does not restart
            val now = SystemClock.uptimeMillis()
            from = value(now)
            to = target
            start = now
            duration = durationMs
            interp = interpolator
        }

        fun value(now: Long): Float {
            if (duration <= 0L) return to
            val t = (now - start).toFloat() / duration
            if (t >= 1f) return to
            if (t <= 0f) return from
            return from + (to - from) * interp.getInterpolation(t)
        }

        fun running(now: Long) = duration > 0L && now - start < duration
    }

    private class EyeState {
        val sx = Anim(1f); val sy = Anim(1f); val rot = Anim(0f)
        val dx = Anim(0f); val dy = Anim(0f)
        val blink = Anim(1f)
        val all = listOf(sx, sy, rot, dx, dy, blink)
    }

    /** A glyph slot: where it sits, how big its type is, and its current transform. */
    private class MarkState(val cx: Float, val cy: Float, val fontUnits: Float, val accent: Boolean) {
        var glyph = ""
        val dx = Anim(0f); val dy = Anim(0f); val rot = Anim(0f); val scale = Anim(0f)
        val all = listOf(dx, dy, rot, scale)
    }

    private val leftEye = EyeState()
    private val rightEye = EyeState()
    private val gazeX = Anim(0f)
    private val gazeY = Anim(0f)

    // Slot centres and type sizes, in stage units, straight from the design.
    private val marks = mapOf(
        Slot.BROW_L to MarkState(33f, 13f, 8f, accent = false),
        Slot.BROW_R to MarkState(67f, 13f, 8f, accent = false),
        Slot.CHEEK_L to MarkState(20f, 38f, 11f, accent = true),
        Slot.CHEEK_R to MarkState(80f, 38f, 11f, accent = true),
        Slot.MOUTH to MarkState(50f, 51f, 9f, accent = false),
        Slot.BADGE to MarkState(78f, 11f, 8f, accent = true),
        Slot.TEAR to MarkState(27f, 42f, 7f, accent = true),
    )
    private val allAnims: List<Anim> =
        leftEye.all + rightEye.all + listOf(gazeX, gazeY) + marks.values.flatMap { it.all }

    /** The expression asked for. Eyes move to it at once; glyphs follow 220 ms later. */
    var expression: Expression = Expression.IDLE
        private set

    /** The expression whose glyphs are on screen; trails [expression] by one swap. */
    private var glyphFace = Expression.IDLE
    private var hiding = false

    var speaking = false
        private set
    /** While speaking: 0 the voice is silent (the face's own mouth), 1 quiet (small frames), 2 loud (big ones). */
    private var mouthLevel = 0
    private var frame = 0

    /** Design props. */
    var eyeSize = 1f
        set(value) { field = value; invalidate() }
    var blinkEnabled = true
    var speakSpeedMs = 110L

    private var theme = FaceTheme.LIGHT
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.ink }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = if (isInEditMode) Typeface.MONOSPACE else loadMono(context)
    }
    private val eyeRect = RectF()

    // ---- timers: posted on the view's handler, so they die with the view ----

    private val swapRunnable = Runnable {
        glyphFace = expression
        hiding = false
        applyMarks()
        invalidate()
    }
    private val speakRunnable = Runnable { if (speaking) speakTick() }
    private val unblinkRunnable = Runnable {
        leftEye.blink.set(1f, 80, EASE_OUT)
        rightEye.blink.set(1f, 80, EASE_OUT)
        invalidate()
    }
    private val blinkRunnable = Runnable {
        if (blinkEnabled) {
            leftEye.blink.set(0.06f, 80, EASE_OUT)
            rightEye.blink.set(0.06f, 80, EASE_OUT)
            postDelayed(unblinkRunnable, 100)
            invalidate()
        }
        scheduleBlink()
    }
    private val gazeRunnable = Runnable {
        gazeX.set(Random.nextFloat() * 2.4f - 1.2f, 600, SETTLE)
        gazeY.set(Random.nextFloat() * 1.4f - 0.6f, 600, SETTLE)
        invalidate()
        scheduleGaze()
    }

    init {
        applyEyes()
        glyphFace = expression
        applyMarks()
    }

    // ---- public API ----

    fun setTheme(t: FaceTheme) {
        theme = t
        eyePaint.color = t.ink
        invalidate()
    }

    /** Fires on the main thread when the shown expression actually changes (or is
     *  first switched from the initial one), with the new expression. MainActivity
     *  uses it to tell the robot which face to react to. */
    var onExpression: ((Expression) -> Unit)? = null

    fun setExpression(e: Expression) {
        if (e == expression) return
        removeCallbacks(swapRunnable)
        expression = e
        onExpression?.invoke(e)
        hiding = true
        applyEyes()
        applyMarks()
        postDelayed(swapRunnable, 220)
        invalidate()
    }

    fun setSpeaking(on: Boolean) {
        if (on == speaking) return
        speaking = on
        mouthLevel = 0   // closed until the voice is heard, and closed again after
        removeCallbacks(speakRunnable)
        applyMarks()
        if (on) speakTick() else invalidate()
    }

    /**
     * How loud the voice being heard is right now; see [mouthLevel]. Opening and closing show at
     * once; a change between quiet and loud waits for the next frame, which is debounce enough.
     */
    fun setMouthLevel(level: Int) {
        if (!speaking || level == mouthLevel) return
        val wasOpen = mouthLevel > 0
        mouthLevel = level
        if ((level > 0) != wasOpen) {
            applyMarks()
            invalidate()
        }
    }

    // ---- the design's state machine ----

    private fun applyEyes() {
        fun EyeState.moveTo(p: EyePose) {
            sx.set(p.sx, 600, SETTLE); sy.set(p.sy, 600, SETTLE); rot.set(p.rot, 600, SETTLE)
            dx.set(p.dx, 600, SETTLE); dy.set(p.dy, 600, SETTLE)
        }
        leftEye.moveTo(expression.left)
        rightEye.moveTo(expression.right)
    }

    /**
     * renderVals() from the design. For each slot, decide which mark shows --
     * the new face's while not hiding (or when the glyph is the same in both,
     * so it need not blink out), the old face's shrinking to nothing otherwise.
     */
    private fun applyMarks() {
        val settle = expression == Expression.SAD || expression == Expression.IDLE
        val duration = if (settle) 500L else 240L
        val interp = if (settle) SETTLE else BACK

        for ((slot, st) in marks) {
            val nm = expression.marks[slot]
            val om = glyphFace.marks[slot]
            val moving = slot == Slot.MOUTH && speaking && mouthLevel > 0
            val same = moving || (nm != null && om != null && nm.glyph == om.glyph)
            var m = if (!hiding || same) nm else om
            val hide = hiding && !same

            if (m == null) {
                st.glyph = ""
                st.dx.set(0f, duration, interp); st.dy.set(0f, duration, interp)
                st.rot.set(0f, duration, interp); st.scale.set(0f, duration, interp)
                continue
            }
            if (moving) m = m.copy(glyph = (if (mouthLevel >= 2) LOUD_FRAMES else QUIET_FRAMES)[frame], rot = 0f, scale = 1.1f)

            st.glyph = m.glyph
            st.dx.set(m.dx, duration, interp)
            st.dy.set(m.dy, duration, interp)
            st.rot.set(m.rot, duration, interp)
            st.scale.set(if (hide) 0f else m.scale, duration, interp)
        }
    }

    private fun speakTick() {
        frame = (frame + 1 + Random.nextInt(2)) % FRAME_COUNT
        if (mouthLevel > 0) {   // a silent stretch keeps the face's own mouth; nothing to redraw
            applyMarks()
            invalidate()
        }
        postDelayed(speakRunnable, (speakSpeedMs * (0.7 + Random.nextDouble() * 0.7)).toLong())
    }

    private fun scheduleBlink() {
        postDelayed(blinkRunnable, 2400 + Random.nextLong(3200))
    }

    private fun scheduleGaze() {
        postDelayed(gazeRunnable, 1800 + Random.nextLong(2600))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleBlink()
        scheduleGaze()
        if (speaking) speakTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(swapRunnable)
        removeCallbacks(speakRunnable)
        removeCallbacks(blinkRunnable)
        removeCallbacks(unblinkRunnable)
        removeCallbacks(gazeRunnable)
        super.onDetachedFromWindow()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        canvas.drawColor(theme.bg)

        // The stage: 16:10, centred, as large as fits.
        val w = width.toFloat()
        val h = height.toFloat()
        val stageW = minOf(w, h * 1.6f)
        val stageH = stageW / 1.6f
        val ox = (w - stageW) / 2f
        val oy = (h - stageH) / 2f
        val u = stageW / 100f

        // rf-bob: the eyes' group rises 2.2 % of its own height (the stage's) and
        // back every half second while excited.
        val bob = if (expression == Expression.EXCITED) {
            -0.022f * stageH * (1f - cos(2.0 * PI * (now % 500) / 500.0).toFloat()) / 2f
        } else 0f

        drawEye(canvas, leftEye, 36f, 30f, u, ox, oy + bob, now)
        drawEye(canvas, rightEye, 64f, 30f, u, ox, oy + bob, now)
        for ((slot, m) in marks) drawMark(canvas, slot, m, u, ox, oy, now)

        if (needsFrames(now)) postInvalidateOnAnimation()
    }

    private fun needsFrames(now: Long): Boolean =
        expression == Expression.EXCITED ||
            glyphFace == Expression.PASSIONATE ||
            allAnims.any { it.running(now) }

    private fun drawEye(c: Canvas, e: EyeState, cxU: Float, cyU: Float, u: Float, ox: Float, oy: Float, now: Long) {
        c.save()
        c.translate(
            ox + (cxU + e.dx.value(now) + gazeX.value(now)) * u,
            oy + (cyU + e.dy.value(now) + gazeY.value(now)) * u,
        )
        c.rotate(e.rot.value(now))
        c.scale(e.sx.value(now) * eyeSize, e.sy.value(now) * eyeSize * e.blink.value(now))
        eyeRect.set(-7f * u, -10f * u, 7f * u, 10f * u)
        val r = 4.2f * u   // border-radius: 40px on the design's 960 px stage
        c.drawRoundRect(eyeRect, r, r, eyePaint)
        c.restore()
    }

    private fun drawMark(c: Canvas, slot: Slot, m: MarkState, u: Float, ox: Float, oy: Float, now: Long) {
        if (m.glyph.isEmpty()) return
        var s = m.scale.value(now)
        // rf-pulse: the badge breathes while the passionate face's glyphs are up.
        if (slot == Slot.BADGE && glyphFace == Expression.PASSIONATE) {
            s *= 1f + 0.2f * (1f - cos(2.0 * PI * (now % 900) / 900.0).toFloat()) / 2f
        }
        if (s <= 0.001f) return

        c.save()
        c.translate(ox + (m.cx + m.dx.value(now)) * u, oy + (m.cy + m.dy.value(now)) * u)
        c.rotate(m.rot.value(now))
        c.scale(s, s)
        glyphPaint.color = if (m.accent) theme.accent else theme.ink
        glyphPaint.textSize = m.fontUnits * u
        // Centre the glyph's ascent-descent box on the slot, as flex centring with
        // line-height 1 does in the design.
        val fm = glyphPaint.fontMetrics
        c.drawText(m.glyph, 0f, -(fm.ascent + fm.descent) / 2f, glyphPaint)
        c.restore()
    }

    companion object {
        /** cubic-bezier(0,.8,.2,1): instant departure, long settle. Eyes, and the calm faces' glyphs. */
        val SETTLE: TimeInterpolator = PathInterpolator(0f, .8f, .2f, 1f)
        /** cubic-bezier(.68,-.55,.265,1.55): the overshoot the lively faces' glyphs pop in with. */
        val BACK: TimeInterpolator = PathInterpolator(.68f, -.55f, .265f, 1.55f)
        /** CSS ease-out, for the blink. */
        val EASE_OUT: TimeInterpolator = PathInterpolator(0f, 0f, .58f, 1f)

        /** The speaking mouth, one cycle for a quiet voice and one for a loud one, in step. */
        val QUIET_FRAMES = listOf("_", "o", "o", "o", "-", "o", "o", "=", "o", "_", "o", "-")
        val LOUD_FRAMES = listOf("o", "O", "O", "o", "=", "O", "O", "o", "O", "o", "O", "=")
        val FRAME_COUNT = QUIET_FRAMES.size

        private fun loadMono(context: Context): Typeface =
            try {
                ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold) ?: Typeface.MONOSPACE
            } catch (e: Exception) {
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
    }
}
