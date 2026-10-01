package com.voltcu.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Emotion {
    NEUTRAL, HAPPY, SURPRISED, SLEEPY, SAD, ANGRY, LISTENING, THINKING, DIZZY, LOVE, SCARED,
    BORED, CURIOUS, CONFUSED, EXCITED, SHY, SUSPICIOUS, PROUD, ASLEEP, DROWSY
}

/** Kısa süreli göz hareketleri (duygunun üstüne oynatılır) */
enum class Gesture(val ms: Long) {
    WINK_LEFT(450), WINK_RIGHT(450), YAWN(2600), SNEEZE(1300), NOD(900), SHAKE(900),
    GIGGLE(1200), LOOK_AROUND(3200), DOUBLE_TAKE(1100), STARTLE(700), SQUINT_STARE(2200),
    ROLL_EYES(1300), HEART_BEAT(1400), NOD_OFF(2400), STRETCH(1800), PEEK(2000)
}

/** Vector tarzı animasyonlu gözler. Tüm çizim ekran üzerinde, kamera görüntüsü gösterilmez. */
class FaceView(context: Context) : View(context) {

    /**
     * w,h: göz boyu  top: üst kapak  bottom: alt kapak  angle: kapak eğimi  happy: mutlu kesim
     * lS/rS: sol/sağ göz boy oranı (asimetri)  bounce: zıplama  wobble: dönme  jitter: titreme  pulse: nefes
     */
    private data class P(
        val w: Float, val h: Float, val top: Float, val angle: Float, val happy: Float,
        val color: Int, val biasX: Float = 0f, val biasY: Float = 0f,
        val wobble: Float = 0f, val jitter: Float = 0f, val pulse: Float = 0f,
        val bottom: Float = 0f, val lS: Float = 1f, val rS: Float = 1f, val bounce: Float = 0f
    )

    private val teal = 0xFF29E6C8.toInt()

    private fun paramsFor(e: Emotion): P = when (e) {
        Emotion.NEUTRAL -> P(.30f, .44f, 0f, 0f, 0f, teal)
        Emotion.HAPPY -> P(.32f, .44f, 0f, 0f, .55f, teal)
        Emotion.SURPRISED -> P(.34f, .56f, 0f, 0f, 0f, 0xFF5CF2DA.toInt())
        Emotion.SLEEPY -> P(.32f, .40f, .72f, 0f, 0f, 0xFF1FA894.toInt(), biasY = .35f)
        Emotion.DROWSY -> P(.32f, .42f, .55f, 0f, 0f, 0xFF22B8A2.toInt(), biasY = .25f, pulse = .5f)
        Emotion.ASLEEP -> P(.34f, .40f, .90f, 0f, 0f, 0xFF178A7A.toInt(), biasY = .45f, pulse = 1f)
        Emotion.SAD -> P(.29f, .40f, .25f, -22f, 0f, 0xFF4FA3FF.toInt(), biasY = .3f)
        Emotion.ANGRY -> P(.31f, .40f, .30f, 26f, 0f, 0xFFFF5A40.toInt())
        Emotion.LISTENING -> P(.29f, .48f, 0f, 0f, 0f, 0xFF39C6FF.toInt(), pulse = 1f)
        Emotion.THINKING -> P(.28f, .40f, .22f, 8f, 0f, teal, biasX = .55f, biasY = -.5f)
        Emotion.DIZZY -> P(.28f, .40f, 0f, 0f, 0f, 0xFFE8E040.toInt(), wobble = 1f)
        Emotion.LOVE -> P(.32f, .44f, 0f, 0f, .5f, 0xFFFF5FA8.toInt(), pulse = 1f)
        Emotion.SCARED -> P(.22f, .32f, 0f, -8f, 0f, 0xFFB8F4FF.toInt(), jitter = 1f)
        Emotion.BORED -> P(.32f, .30f, .45f, 0f, 0f, 0xFF20A08C.toInt(), biasY = .2f, bottom = .1f)
        Emotion.CURIOUS -> P(.30f, .46f, 0f, 0f, 0f, 0xFF3FF0D0.toInt(), lS = 1.18f, rS = .85f)
        Emotion.CONFUSED -> P(.29f, .42f, .15f, 0f, 0f, 0xFFB0E05A.toInt(), lS = .7f, rS = 1.15f, biasX = -.3f)
        Emotion.EXCITED -> P(.33f, .50f, 0f, 0f, .45f, 0xFF4CFFDF.toInt(), bounce = 1f)
        Emotion.SHY -> P(.27f, .38f, .1f, -10f, .35f, 0xFFFF8FB8.toInt(), biasX = -.7f, biasY = .6f)
        Emotion.SUSPICIOUS -> P(.31f, .40f, .40f, 0f, 0f, 0xFFE8B23A.toInt(), bottom = .30f, biasX = .5f)
        Emotion.PROUD -> P(.32f, .40f, .25f, -6f, .4f, 0xFF39E6A0.toInt(), biasY = -.3f)
    }

    var emotion: Emotion = Emotion.NEUTRAL
        set(v) { field = v; target = paramsFor(v) }

    /** Hata ayıklama: yüz konumu küçük kutu olarak gösterilir */
    var debugFace: RectF? = null
    var showDebug = false
    var sleepingZ = false

    private var target = paramsFor(Emotion.NEUTRAL)
    private var w = target.w; private var h = target.h; private var top = target.top; private var bottom = 0f
    private var angle = 0f; private var happy = 0f
    private var r = Color.red(target.color).toFloat(); private var g = Color.green(target.color).toFloat(); private var b = Color.blue(target.color).toFloat()
    private var biasX = 0f; private var biasY = 0f; private var wobble = 0f; private var jitter = 0f; private var pulse = 0f
    private var lS = 1f; private var rS = 1f; private var bounce = 0f

    private var lookTX = 0f; private var lookTY = 0f
    private var lookX = 0f; private var lookY = 0f
    /** false iken gözler kendi kendine etrafa bakınmaz (yüz takibi veya gösteri sırasında) */
    var idleWander = true

    private var lastT = SystemClock.uptimeMillis()
    private var nextBlink = lastT + 2500
    private var blinkStart = -1L
    private var doubleBlink = false
    private var idleLookAt = lastT + 4000

    private var gesture: Gesture? = null
    private var gestureStart = 0L
    private var gestureSide = 1f

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val dbgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1FA894.toInt(); isFakeBoldText = true }
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** Gözlerin bakacağı yön: x, y -1..1 aralığında */
    fun lookAt(x: Float, y: Float) {
        lookTX = x.coerceIn(-1f, 1f); lookTY = y.coerceIn(-1f, 1f)
        idleLookAt = SystemClock.uptimeMillis() + 3000
    }

    /** Göz kırpma */
    fun blink() { blinkStart = SystemClock.uptimeMillis() }

    /** Kısa bir göz hareketi oynat */
    fun play(gst: Gesture) {
        gesture = gst
        gestureStart = SystemClock.uptimeMillis()
        gestureSide = if (Random.nextBoolean()) 1f else -1f
    }

    val gestureBusy get() = gesture != null

    init { setBackgroundColor(Color.BLACK) }

    private fun step(now: Long) {
        val dt = ((now - lastT).coerceIn(0, 100)) / 1000f
        lastT = now
        val k = 1f - exp(-dt * 10f)
        val t = target
        w += (t.w - w) * k; h += (t.h - h) * k; top += (t.top - top) * k; bottom += (t.bottom - bottom) * k
        angle += (t.angle - angle) * k; happy += (t.happy - happy) * k
        r += (Color.red(t.color) - r) * k; g += (Color.green(t.color) - g) * k; b += (Color.blue(t.color) - b) * k
        biasX += (t.biasX - biasX) * k; biasY += (t.biasY - biasY) * k
        wobble += (t.wobble - wobble) * k; jitter += (t.jitter - jitter) * k; pulse += (t.pulse - pulse) * k
        lS += (t.lS - lS) * k; rS += (t.rS - rS) * k; bounce += (t.bounce - bounce) * k

        // Kimse yokken etrafa rastgele bakınma
        if (idleWander && now > idleLookAt && emotion != Emotion.ASLEEP) {
            lookTX = Random.nextFloat() * 1.4f - 0.7f
            lookTY = Random.nextFloat() * 0.8f - 0.4f
            idleLookAt = now + 1500 + Random.nextLong(3500)
        }
        val lk = 1f - exp(-dt * 14f)
        lookX += (lookTX - lookX) * lk; lookY += (lookTY - lookY) * lk

        val sleepy = emotion == Emotion.SLEEPY || emotion == Emotion.ASLEEP
        if (now > nextBlink && !sleepy) {
            blinkStart = now
            doubleBlink = Random.nextFloat() < 0.2f
            nextBlink = now + 2500 + Random.nextLong(4000)
        }
        gesture?.let { if (now - gestureStart > it.ms) gesture = null }
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

    /** 0→1→0 yumuşak eğri */
    private fun bell(p: Float) = sin(p.coerceIn(0f, 1f) * PI.toFloat())

    private class Fx {
        var dx = 0f; var dy = 0f; var sx = 1f; var sy = 1f
        var lH = 1f; var rH = 1f; var lid = 0f; var tilt = 0f; var bot = 0f
    }
    private val fx = Fx()

    /** Aktif hareketin bu andaki etkisini hesaplar */
    private fun gestureFx(now: Long, vw: Float, vh: Float) {
        fx.dx = 0f; fx.dy = 0f; fx.sx = 1f; fx.sy = 1f; fx.lH = 1f; fx.rH = 1f; fx.lid = 0f; fx.tilt = 0f; fx.bot = 0f
        val gst = gesture ?: return
        val p = ((now - gestureStart).toFloat() / gst.ms).coerceIn(0f, 1f)
        val s = gestureSide
        when (gst) {
            Gesture.WINK_LEFT -> fx.lH = 1f - 0.92f * bell(p)
            Gesture.WINK_RIGHT -> fx.rH = 1f - 0.92f * bell(p)
            Gesture.YAWN -> {
                // gözler kısılır, yukarı bakar, sonra iri açılıp kırpılır
                if (p < 0.65f) { val q = bell(p / 0.65f); fx.lid = 0.75f * q; fx.dy = -vh * 0.06f * q; fx.sx = 1f + 0.12f * q }
                else { val q = bell((p - 0.65f) / 0.35f); fx.sy = 1f + 0.18f * q; fx.lH = 1f - 0.5f * q; fx.rH = fx.lH }
            }
            Gesture.SNEEZE -> {
                if (p < 0.6f) { val q = p / 0.6f; fx.lid = 0.5f * q; fx.dy = -vh * 0.05f * q }
                else { val q = bell((p - 0.6f) / 0.4f); fx.dy = vh * 0.10f * q; fx.lH = 0.1f; fx.rH = 0.1f; fx.sx = 1.1f }
            }
            Gesture.NOD -> fx.dy = vh * 0.08f * sin(p * 2 * PI.toFloat() * 2)
            Gesture.SHAKE -> fx.dx = vw * 0.06f * sin(p * 2 * PI.toFloat() * 3) * (1f - p)
            Gesture.GIGGLE -> { fx.dy = -abs(sin(p * PI.toFloat() * 6)) * vh * 0.05f; fx.bot = 0.45f * bell(p) }
            Gesture.LOOK_AROUND -> {
                fx.dx = when {
                    p < 0.3f -> -vw * 0.13f * bell(p / 0.3f * 0.5f)
                    p < 0.4f -> -vw * 0.13f
                    p < 0.75f -> -vw * 0.13f + vw * 0.26f * ((p - 0.4f) / 0.35f)
                    else -> vw * 0.13f * (1f - (p - 0.75f) / 0.25f)
                } * s
            }
            Gesture.DOUBLE_TAKE -> {
                fx.dx = (if (p < 0.4f) vw * 0.1f * bell(p / 0.4f) else 0f) * s
                if (p > 0.45f) { val q = bell((p - 0.45f) / 0.55f); fx.sy = 1f + 0.25f * q; fx.sx = 1f + 0.12f * q }
            }
            Gesture.STARTLE -> { val q = bell(p); fx.sy = 1f + 0.3f * q; fx.sx = 0.85f; fx.dy = -vh * 0.04f * q }
            Gesture.SQUINT_STARE -> { val q = bell(p); fx.lid = 0.45f * q; fx.bot = 0.35f * q; fx.dx = vw * 0.08f * q * s }
            Gesture.ROLL_EYES -> {
                val a = p * 2 * PI.toFloat()
                fx.dx = vw * 0.07f * sin(a) * s; fx.dy = -vh * 0.09f * abs(cos(a * 0.5f)); fx.lid = 0.25f * bell(p)
            }
            Gesture.HEART_BEAT -> { val q = abs(sin(p * PI.toFloat() * 4)); fx.sx = 1f + 0.12f * q; fx.sy = 1f + 0.12f * q }
            Gesture.NOD_OFF -> {
                // yavaş yavaş kapanır, başı düşer, sonra irkilip açılır
                if (p < 0.8f) { val q = p / 0.8f; fx.lid = 0.9f * q; fx.dy = vh * 0.12f * q * q }
                else { val q = bell((p - 0.8f) / 0.2f); fx.sy = 1f + 0.3f * q; fx.dy = -vh * 0.03f * q }
            }
            Gesture.STRETCH -> { val q = bell(p); fx.sy = 1f + 0.35f * q; fx.sx = 1f - 0.15f * q; fx.dy = -vh * 0.06f * q; fx.lid = 0.3f * q }
            Gesture.PEEK -> { val q = bell(p); fx.dx = vw * 0.28f * q * s; fx.dy = vh * 0.05f * q; fx.lid = 0.2f * q; fx.tilt = 12f * q * s }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        step(now)
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) { postInvalidateOnAnimation(); return }
        val unit = minOf(vh, vw * 0.6f)
        val tSec = now / 1000f
        gestureFx(now, vw, vh)

        var ew = w * unit * fx.sx
        var eh = h * unit * blinkScale(now) * fx.sy
        if (pulse > 0.01f) {
            val speed = if (emotion == Emotion.ASLEEP) 1.6f else 6f
            val s = 1f + pulse * 0.05f * sin(tSec * speed); ew *= s; eh *= s
        }

        var lx = (lookX + biasX) * vw * 0.12f + fx.dx
        var ly = (lookY + biasY) * vh * 0.12f + fx.dy
        if (wobble > 0.01f) { lx += cos(tSec * 7f) * vw * 0.05f * wobble; ly += sin(tSec * 7f) * vh * 0.05f * wobble }
        if (jitter > 0.01f) { lx += (Random.nextFloat() - .5f) * 10f * jitter; ly += (Random.nextFloat() - .5f) * 10f * jitter }
        if (bounce > 0.01f) ly -= abs(sin(tSec * 7f)) * vh * 0.035f * bounce

        val gap = unit * 0.20f
        val cy = vh / 2f + ly
        val cxL = vw / 2f - gap / 2f - ew / 2f + lx
        val cxR = vw / 2f + gap / 2f + ew / 2f + lx
        eyePaint.color = Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))

        canvas.save()
        if (fx.tilt != 0f) canvas.rotate(fx.tilt, vw / 2f, cy)
        drawEye(canvas, cxL, cy, ew, eh * lS * fx.lH, innerOnRight = true)
        drawEye(canvas, cxR, cy, ew, eh * rS * fx.rH, innerOnRight = false)
        canvas.restore()

        if (emotion == Emotion.LOVE || gesture == Gesture.HEART_BEAT) drawHearts(canvas, vw, vh, tSec)
        if (sleepingZ) drawZ(canvas, vw, vh, tSec)
        if (emotion == Emotion.CONFUSED) drawMark(canvas, "?", vw * 0.78f, vh * 0.22f, tSec)
        if (gesture == Gesture.SNEEZE && (now - gestureStart) > gesture!!.ms * 0.6f) drawMark(canvas, "!", vw * 0.8f, vh * 0.25f, tSec)

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

    private fun drawZ(c: Canvas, vw: Float, vh: Float, tSec: Float) {
        for (i in 0..2) {
            val a = ((tSec + i * 0.9f) % 2.7f) / 2.7f
            zPaint.textSize = vh * (0.05f + 0.03f * i)
            zPaint.alpha = (255 * (1f - a)).toInt()
            c.drawText("z", vw * (0.70f + 0.04f * i), vh * (0.36f - 0.06f * i) - a * vh * 0.08f, zPaint)
        }
    }

    private fun drawHearts(c: Canvas, vw: Float, vh: Float, tSec: Float) {
        fxPaint.color = 0xFFFF5FA8.toInt()
        for (i in 0..2) {
            val a = ((tSec * 0.6f + i * 0.33f) % 1f)
            fxPaint.alpha = (200 * (1f - a)).toInt()
            val x = vw * (0.15f + 0.35f * i) + sin(tSec * 2 + i) * 15f
            val y = vh * (0.85f - 0.6f * a)
            heart(c, x, y, vh * 0.035f)
        }
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y + s)
        path.cubicTo(x - s * 2f, y - s * 0.6f, x - s * 0.6f, y - s * 1.8f, x, y - s * 0.6f)
        path.cubicTo(x + s * 0.6f, y - s * 1.8f, x + s * 2f, y - s * 0.6f, x, y + s)
        c.drawPath(path, fxPaint)
    }

    private fun drawMark(c: Canvas, m: String, x: Float, y: Float, tSec: Float) {
        zPaint.textSize = height * 0.12f
        zPaint.alpha = 220
        c.drawText(m, x, y + sin(tSec * 4f) * 8f, zPaint.apply { color = eyePaint.color })
        zPaint.color = 0xFF1FA894.toInt()
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, ew: Float, eh: Float, innerOnRight: Boolean) {
        val l = cx - ew / 2f; val rt = cx + ew / 2f; val tp = cy - eh / 2f; val bt = cy + eh / 2f
        rect.set(l, tp, rt, bt)
        val rad = minOf(ew, eh) * 0.22f
        c.drawRoundRect(rect, rad, rad, eyePaint)

        // Üst göz kapağı (uykulu, kızgın, üzgün)
        val a = angle / 60f
        val base = (top + fx.lid).coerceIn(0f, 1f)
        val inner = (base + a).coerceIn(0f, 1f)
        val outer = (base - a).coerceIn(0f, 1f)
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
        // Alt göz kapağı (şüpheli, kıkırdama)
        val bl = (bottom + fx.bot).coerceIn(0f, 0.8f)
        if (bl > 0.005f) c.drawRect(l - 4f, bt - eh * bl, rt + 4f, bt + 4f, bgPaint)
        // Mutlu göz: alttan kavisli kesim
        if (happy > 0.01f) {
            val ovTop = bt - eh * happy
            rect.set(cx - ew, ovTop, cx + ew, ovTop + eh * 2f)
            c.drawOval(rect, bgPaint)
        }
    }
}
