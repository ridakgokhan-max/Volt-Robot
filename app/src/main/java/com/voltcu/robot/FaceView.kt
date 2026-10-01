package com.voltcu.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Emotion { NEUTRAL, HAPPY, SURPRISED, SLEEPY, SAD, ANGRY, LISTENING, THINKING, DIZZY, LOVE, SCARED }

/** Vector tarzı animasyonlu gözler. Tüm çizim ekran üzerinde, kamera görüntüsü gösterilmez. */
class FaceView(context: Context) : View(context) {

    private data class P(
        val w: Float, val h: Float, val top: Float, val angle: Float, val happy: Float,
        val color: Int, val biasX: Float = 0f, val biasY: Float = 0f,
        val wobble: Float = 0f, val jitter: Float = 0f, val pulse: Float = 0f
    )

    private fun paramsFor(e: Emotion): P = when (e) {
        Emotion.NEUTRAL -> P(.30f, .44f, 0f, 0f, 0f, 0xFF29E6C8.toInt())
        Emotion.HAPPY -> P(.32f, .44f, 0f, 0f, .55f, 0xFF29E6C8.toInt())
        Emotion.SURPRISED -> P(.34f, .56f, 0f, 0f, 0f, 0xFF5CF2DA.toInt())
        Emotion.SLEEPY -> P(.32f, .40f, .72f, 0f, 0f, 0xFF1FA894.toInt(), biasY = .35f)
        Emotion.SAD -> P(.29f, .40f, .25f, -22f, 0f, 0xFF4FA3FF.toInt(), biasY = .3f)
        Emotion.ANGRY -> P(.31f, .40f, .30f, 26f, 0f, 0xFFFF5A40.toInt())
        Emotion.LISTENING -> P(.29f, .48f, 0f, 0f, 0f, 0xFF39C6FF.toInt(), pulse = 1f)
        Emotion.THINKING -> P(.28f, .40f, .22f, 8f, 0f, 0xFF29E6C8.toInt(), biasX = .55f, biasY = -.5f)
        Emotion.DIZZY -> P(.28f, .40f, 0f, 0f, 0f, 0xFFE8E040.toInt(), wobble = 1f)
        Emotion.LOVE -> P(.32f, .44f, 0f, 0f, .5f, 0xFFFF5FA8.toInt())
        Emotion.SCARED -> P(.22f, .32f, 0f, -8f, 0f, 0xFFB8F4FF.toInt(), jitter = 1f)
    }

    var emotion: Emotion = Emotion.NEUTRAL
        set(v) { field = v; target = paramsFor(v) }

    /** Hata ayıklama: yüz konumu küçük kutu olarak gösterilir */
    var debugFace: RectF? = null
    var showDebug = false
    var sleepingZ = false

    private var target = paramsFor(Emotion.NEUTRAL)
    private var w = target.w; private var h = target.h; private var top = target.top
    private var angle = 0f; private var happy = 0f
    private var r = Color.red(target.color).toFloat(); private var g = Color.green(target.color).toFloat(); private var b = Color.blue(target.color).toFloat()
    private var biasX = 0f; private var biasY = 0f; private var wobble = 0f; private var jitter = 0f; private var pulse = 0f

    private var lookTX = 0f; private var lookTY = 0f
    private var lookX = 0f; private var lookY = 0f

    private var lastT = SystemClock.uptimeMillis()
    private var nextBlink = lastT + 2500
    private var blinkStart = -1L
    private var doubleBlink = false
    private var idleLookAt = lastT + 4000

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val dbgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1FA894.toInt(); textSize = 60f; isFakeBoldText = true }
    private val rect = RectF()
    private val path = Path()

    /** Gözlerin bakacağı yön: x, y -1..1 aralığında */
    fun lookAt(x: Float, y: Float) {
        lookTX = x.coerceIn(-1f, 1f); lookTY = y.coerceIn(-1f, 1f)
        idleLookAt = SystemClock.uptimeMillis() + 3000
    }

    /** Göz kırpma */
    fun blink() { blinkStart = SystemClock.uptimeMillis() }

    init { setBackgroundColor(Color.BLACK) }

    private fun step(now: Long) {
        val dt = ((now - lastT).coerceIn(0, 100)) / 1000f
        lastT = now
        val k = 1f - exp(-dt * 10f)
        val t = target
        w += (t.w - w) * k; h += (t.h - h) * k; top += (t.top - top) * k
        angle += (t.angle - angle) * k; happy += (t.happy - happy) * k
        r += (Color.red(t.color) - r) * k; g += (Color.green(t.color) - g) * k; b += (Color.blue(t.color) - b) * k
        biasX += (t.biasX - biasX) * k; biasY += (t.biasY - biasY) * k
        wobble += (t.wobble - wobble) * k; jitter += (t.jitter - jitter) * k; pulse += (t.pulse - pulse) * k

        // Kimse yokken etrafa rastgele bakınma
        if (now > idleLookAt) {
            lookTX = Random.nextFloat() * 1.4f - 0.7f
            lookTY = Random.nextFloat() * 0.8f - 0.4f
            idleLookAt = now + 1500 + Random.nextLong(3500)
        }
        val lk = 1f - exp(-dt * 14f)
        lookX += (lookTX - lookX) * lk; lookY += (lookTY - lookY) * lk

        if (now > nextBlink && emotion != Emotion.SLEEPY) {
            blinkStart = now
            doubleBlink = Random.nextFloat() < 0.2f
            nextBlink = now + 2500 + Random.nextLong(4000)
        }
    }

    private fun blinkScale(now: Long): Float {
        if (blinkStart < 0) return 1f
        val dur = if (doubleBlink) 420L else 180L
        val e = now - blinkStart
        if (e > dur) { blinkStart = -1; return 1f }
        val single = 180f
        val p = (e % single.toLong()) / single
        return if (p < .5f) 1f - p * 2f * 0.92f else 0.08f + (p - .5f) * 2f * 0.92f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        step(now)
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) { postInvalidateOnAnimation(); return }
        val unit = minOf(vh, vw * 0.6f)
        val tSec = now / 1000f

        var ew = w * unit
        var eh = h * unit * blinkScale(now)
        if (pulse > 0.01f) { val s = 1f + pulse * 0.05f * sin(tSec * 6f); ew *= s; eh *= s }

        var lx = (lookX + biasX) * vw * 0.12f
        var ly = (lookY + biasY) * vh * 0.12f
        if (wobble > 0.01f) { lx += cos(tSec * 7f) * vw * 0.05f * wobble; ly += sin(tSec * 7f) * vh * 0.05f * wobble }
        if (jitter > 0.01f) { lx += (Random.nextFloat() - .5f) * 10f * jitter; ly += (Random.nextFloat() - .5f) * 10f * jitter }

        val gap = unit * 0.20f
        val cy = vh / 2f + ly
        val cxL = vw / 2f - gap / 2f - ew / 2f + lx
        val cxR = vw / 2f + gap / 2f + ew / 2f + lx
        eyePaint.color = Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))

        drawEye(canvas, cxL, cy, ew, eh, innerOnRight = true)
        drawEye(canvas, cxR, cy, ew, eh, innerOnRight = false)

        if (sleepingZ) {
            val a = ((tSec % 2f) / 2f)
            zPaint.alpha = (255 * (1f - a)).toInt()
            canvas.drawText("z Z", vw * 0.72f, vh * 0.3f - a * 60f, zPaint)
        }

        if (showDebug) {
            debugFace?.let { f ->
                val mw = vw * 0.18f; val mh = mw * 0.75f
                val ox = vw - mw - 20f; val oy = vh - mh - 20f
                canvas.drawRect(ox, oy, ox + mw, oy + mh, dbgPaint)
                canvas.drawRect(ox + f.left * mw, oy + f.top * mh, ox + f.right * mw, oy + f.bottom * mh, dbgPaint)
            }
        }
        postInvalidateOnAnimation()
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, ew: Float, eh: Float, innerOnRight: Boolean) {
        val l = cx - ew / 2f; val rt = cx + ew / 2f; val tp = cy - eh / 2f; val bt = cy + eh / 2f
        rect.set(l, tp, rt, bt)
        val rad = minOf(ew, eh) * 0.22f
        c.drawRoundRect(rect, rad, rad, eyePaint)

        // Üst göz kapağı (uykulu, kızgın, üzgün)
        val a = angle / 60f
        val inner = (top + a).coerceIn(0f, 1f)
        val outer = (top - a).coerceIn(0f, 1f)
        if (inner > 0.005f || outer > 0.005f) {
            val yInner = tp + eh * inner
            val yOuter = tp + eh * outer
            path.reset()
            path.moveTo(l - 4f, tp - 4f)
            path.lineTo(rt + 4f, tp - 4f)
            if (innerOnRight) { path.lineTo(rt + 4f, yInner); path.lineTo(l - 4f, yOuter) }
            else { path.lineTo(rt + 4f, yOuter); path.lineTo(l - 4f, yInner) }
            path.close()
            c.drawPath(path, bgPaint)
        }
        // Mutlu göz: alttan kavisli kesim
        if (happy > 0.01f) {
            val ovTop = bt - eh * happy
            rect.set(cx - ew, ovTop, cx + ew, ovTop + eh * 2f)
            c.drawOval(rect, bgPaint)
        }
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.001f
}
