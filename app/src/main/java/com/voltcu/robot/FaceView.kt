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
    BORED, CURIOUS, CONFUSED, EXCITED, SHY, SUSPICIOUS, PROUD, ASLEEP, DROWSY,
    JOY, LAUGH, STARSTRUCK, HEART_EYES, DEAD, UNIMPRESSED, CRY, NERVOUS, FOCUSED,
    MISCHIEVOUS, MUSIC, LOW_BATTERY, CUTE, ANNOYED, RELIEVED
}

/** Göz şekli: dikdörtgen dışında çizilen özel gözler */
enum class EyeShape { RECT, ARC, CHEVRON, STAR, HEART, CROSS, LINE, ROUND }

/** Gözlerin etrafındaki efektler */
object Fx {
    const val TEARS = 1; const val SWEAT = 2; const val SPARKLE = 4; const val NOTES = 8
    const val ANGER = 16; const val BATTERY = 32; const val SCANLINE = 64; const val STARS = 128; const val BLUSH = 256
}

/** Kısa süreli göz hareketleri (duygunun üstüne oynatılır) */
enum class Gesture(val ms: Long) {
    WINK_LEFT(450), WINK_RIGHT(450), YAWN(2600), SNEEZE(1300), NOD(900), SHAKE(900),
    GIGGLE(1200), LOOK_AROUND(3200), DOUBLE_TAKE(1100), STARTLE(700), SQUINT_STARE(2200),
    ROLL_EYES(1300), HEART_BEAT(1400), NOD_OFF(2400), STRETCH(1800), PEEK(2000),
    BLINK_SLOW(1400), SCAN(1800), GLITCH(900), JUMP(900), SPIN(1500), CROSS_EYED(1400),
    SIDE_EYE(2000), WIGGLE(1800), SHIVER(1200), BOOP(600), CELEBRATE(2200), SIGH(1800),
    SQUISH(800), LOOK_UP_THINK(2200), DOUBLE_BLINK(600), BOUNCE_HAPPY(1600)
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
        val bottom: Float = 0f, val lS: Float = 1f, val rS: Float = 1f, val bounce: Float = 0f,
        val shape: EyeShape = EyeShape.RECT, val fx: Int = 0, val lShape: EyeShape? = null
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
        Emotion.PROUD -> P(.32f, .40f, .25f, -6f, .4f, 0xFF39E6A0.toInt(), biasY = -.3f, fx = Fx.SPARKLE)
        Emotion.JOY -> P(.32f, .40f, 0f, 0f, 0f, 0xFF3CF5D6.toInt(), shape = EyeShape.ARC, bounce = .4f, fx = Fx.BLUSH)
        Emotion.LAUGH -> P(.30f, .36f, 0f, 0f, 0f, 0xFF3CF5D6.toInt(), shape = EyeShape.CHEVRON, bounce = 1f, fx = Fx.TEARS)
        Emotion.STARSTRUCK -> P(.34f, .50f, 0f, 0f, 0f, 0xFFFFE15A.toInt(), shape = EyeShape.STAR, pulse = 1f, fx = Fx.SPARKLE)
        Emotion.HEART_EYES -> P(.34f, .48f, 0f, 0f, 0f, 0xFFFF5FA8.toInt(), shape = EyeShape.HEART, pulse = 1f, fx = Fx.BLUSH)
        Emotion.DEAD -> P(.30f, .40f, 0f, 0f, 0f, 0xFFB0B0B0.toInt(), shape = EyeShape.CROSS, wobble = .4f, fx = Fx.STARS)
        Emotion.UNIMPRESSED -> P(.32f, .40f, 0f, 0f, 0f, 0xFF20B49C.toInt(), shape = EyeShape.LINE, biasX = .3f)
        Emotion.CRY -> P(.29f, .42f, .25f, -24f, 0f, 0xFF4FA3FF.toInt(), biasY = .25f, fx = Fx.TEARS, jitter = .2f)
        Emotion.NERVOUS -> P(.25f, .36f, .1f, -10f, 0f, 0xFF8FE8FF.toInt(), jitter = .4f, fx = Fx.SWEAT)
        Emotion.FOCUSED -> P(.31f, .40f, .35f, 4f, 0f, 0xFF39C6FF.toInt(), bottom = .2f, fx = Fx.SCANLINE)
        Emotion.MISCHIEVOUS -> P(.30f, .42f, .30f, 14f, .3f, 0xFFB87CFF.toInt(), lS = 1.1f, rS = .75f, biasX = .4f)
        Emotion.MUSIC -> P(.32f, .40f, 0f, 0f, 0f, teal, shape = EyeShape.ARC, bounce = 1f, fx = Fx.NOTES)
        Emotion.LOW_BATTERY -> P(.30f, .34f, .5f, 0f, 0f, 0xFFFF9A3C.toInt(), biasY = .4f, fx = Fx.BATTERY, pulse = .6f)
        Emotion.CUTE -> P(.30f, .40f, 0f, 0f, 0f, 0xFF3FF0D0.toInt(), shape = EyeShape.ROUND, fx = Fx.BLUSH)
        Emotion.ANNOYED -> P(.31f, .40f, .45f, 18f, 0f, 0xFFFF7A50.toInt(), bottom = .15f, biasX = -.4f, fx = Fx.ANGER)
        Emotion.RELIEVED -> P(.32f, .40f, 0f, 0f, 0f, 0xFF3CF5D6.toInt(), shape = EyeShape.ARC, biasY = .2f, fx = Fx.SWEAT)
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

    /** Şu an çizilen göz şekli; yeni şekle geçerken göz kısa bir an kapanıp açılır */
    private var shapeNow = EyeShape.RECT
    private var shapeSwapStart = -1L

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
        if (t.shape != shapeNow && shapeSwapStart < 0) shapeSwapStart = now
        if (shapeSwapStart >= 0 && now - shapeSwapStart > 110) shapeNow = t.shape
        if (shapeSwapStart >= 0 && now - shapeSwapStart > 220) shapeSwapStart = -1L

        // Kimse yokken etrafa rastgele bakınma
        if (idleWander && now > idleLookAt && emotion != Emotion.ASLEEP) {
            lookTX = Random.nextFloat() * 1.4f - 0.7f
            lookTY = Random.nextFloat() * 0.8f - 0.4f
            idleLookAt = now + 1500 + Random.nextLong(3500)
        }
        val lk = 1f - exp(-dt * 14f)
        lookX += (lookTX - lookX) * lk; lookY += (lookTY - lookY) * lk

        val sleepy = emotion == Emotion.SLEEPY || emotion == Emotion.ASLEEP || emotion == Emotion.DEAD || shapeNow != EyeShape.RECT
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
        var scan = -1f; var glitch = false; var cross = 0f; var party = false
    }
    private val fx = Fx()

    /** Aktif hareketin bu andaki etkisini hesaplar */
    private fun gestureFx(now: Long, vw: Float, vh: Float) {
        fx.dx = 0f; fx.dy = 0f; fx.sx = 1f; fx.sy = 1f; fx.lH = 1f; fx.rH = 1f; fx.lid = 0f; fx.tilt = 0f; fx.bot = 0f
        fx.scan = -1f; fx.glitch = false; fx.cross = 0f; fx.party = false
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
            Gesture.BLINK_SLOW -> { val q = bell(p); fx.lid = 0.85f * q; fx.bot = 0.1f * q }
            Gesture.SCAN -> { fx.lid = 0.15f; fx.bot = 0.15f; fx.dx = vw * 0.12f * sin(p * 2 * PI.toFloat()) * s; fx.scan = p }
            Gesture.GLITCH -> { if (Random.nextFloat() < 0.5f) { fx.dx = (Random.nextFloat() - .5f) * vw * 0.08f; fx.dy = (Random.nextFloat() - .5f) * vh * 0.06f }; fx.glitch = true; fx.sy = if (Random.nextFloat() < .2f) 0.2f else 1f }
            Gesture.JUMP -> { val q = sin(p * PI.toFloat()); fx.dy = -vh * 0.12f * q; fx.sy = if (p < .15f || p > .85f) 0.75f else 1.1f; fx.sx = if (p < .15f || p > .85f) 1.15f else 0.95f }
            Gesture.SPIN -> { val a = p * 2 * PI.toFloat() * 2; fx.dx = vw * 0.06f * cos(a); fx.dy = vh * 0.06f * sin(a); fx.tilt = 20f * sin(a) * (1f - p) }
            Gesture.CROSS_EYED -> { val q = bell(p); fx.cross = q }
            Gesture.SIDE_EYE -> { val q = bell(p); fx.dx = vw * 0.13f * q * s; fx.lid = 0.4f * q; fx.bot = 0.1f * q }
            Gesture.WIGGLE -> { fx.tilt = 14f * sin(p * 2 * PI.toFloat() * 3); fx.dx = vw * 0.03f * sin(p * 2 * PI.toFloat() * 3) }
            Gesture.SHIVER -> { fx.dx = (Random.nextFloat() - .5f) * 14f; fx.dy = (Random.nextFloat() - .5f) * 6f; fx.sx = 0.9f; fx.sy = 0.9f }
            Gesture.BOOP -> { val q = bell(p); fx.sx = 1f + 0.25f * q; fx.sy = 1f - 0.45f * q }
            Gesture.CELEBRATE -> { fx.dy = -abs(sin(p * PI.toFloat() * 5)) * vh * 0.08f; fx.tilt = 8f * sin(p * 2 * PI.toFloat() * 2); fx.party = true }
            Gesture.SIGH -> { val q = bell(p); fx.dy = vh * 0.06f * q; fx.lid = 0.35f * q; fx.sx = 1f + 0.05f * q }
            Gesture.SQUISH -> { val q = bell(p); fx.sx = 1f - 0.2f * q; fx.sy = 1f - 0.5f * q; fx.bot = 0.3f * q }
            Gesture.LOOK_UP_THINK -> { val q = bell(p); fx.dx = vw * 0.09f * q * s; fx.dy = -vh * 0.1f * q; fx.lid = 0.15f * q }
            Gesture.DOUBLE_BLINK -> { val q = abs(sin(p * PI.toFloat() * 2)); fx.lH = 1f - 0.92f * q; fx.rH = fx.lH }
            Gesture.BOUNCE_HAPPY -> { fx.dy = -abs(sin(p * PI.toFloat() * 4)) * vh * 0.05f; fx.bot = 0.4f * bell(p) }
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
        if (shapeSwapStart >= 0) { val q = ((now - shapeSwapStart) / 220f).coerceIn(0f, 1f); eh *= 1f - 0.9f * bell(q) }
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
        val cxL = vw / 2f - gap / 2f - ew / 2f + lx + fx.cross * ew * 0.35f
        val cxR = vw / 2f + gap / 2f + ew / 2f + lx - fx.cross * ew * 0.35f
        eyePaint.color = if (fx.glitch && Random.nextFloat() < .35f) listOf(0xFFFF3B6B, 0xFF3BB2FF, 0xFFFFFFFF).random().toInt()
            else Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        eyeL = cxL; eyeR = cxR; eyeY = cy; eyeW = ew; eyeH = eh

        canvas.save()
        if (fx.tilt != 0f) canvas.rotate(fx.tilt, vw / 2f, cy)
        drawEye(canvas, cxL, cy, ew, eh * lS * fx.lH, innerOnRight = true)
        drawEye(canvas, cxR, cy, ew, eh * rS * fx.rH, innerOnRight = false)
        canvas.restore()

        if (emotion == Emotion.LOVE || gesture == Gesture.HEART_BEAT) drawHearts(canvas, vw, vh, tSec)
        drawEffects(canvas, vw, vh, tSec, target.fx)
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

    private var eyeL = 0f; private var eyeR = 0f; private var eyeY = 0f; private var eyeW = 0f; private var eyeH = 0f
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Dikdörtgen olmayan göz şekilleri */
    private fun drawShape(c: Canvas, shape: EyeShape, cx: Float, cy: Float, ew: Float, eh: Float, innerOnRight: Boolean) {
        strokePaint.color = eyePaint.color
        strokePaint.strokeWidth = minOf(ew, eh) * 0.22f
        val hw = ew / 2f; val hh = eh / 2f
        when (shape) {
            EyeShape.ARC -> { // ^ ^ mutlu kapalı göz
                path.reset(); path.moveTo(cx - hw * .9f, cy + hh * .35f)
                path.quadTo(cx, cy - hh * 1.1f, cx + hw * .9f, cy + hh * .35f)
                c.drawPath(path, strokePaint)
            }
            EyeShape.CHEVRON -> { // > <  gülmekten kısılmış
                val d = if (innerOnRight) 1f else -1f
                path.reset(); path.moveTo(cx - hw * .7f * d, cy - hh * .6f)
                path.lineTo(cx + hw * .6f * d, cy); path.lineTo(cx - hw * .7f * d, cy + hh * .6f)
                c.drawPath(path, strokePaint)
            }
            EyeShape.STAR -> {
                path.reset()
                val R = minOf(hw, hh) * 1.05f; val r2 = R * 0.45f
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) R else r2
                    val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
                    val x = cx + rad * cos(a); val y = cy + rad * sin(a)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); c.drawPath(path, eyePaint)
            }
            EyeShape.HEART -> {
                val sz = minOf(hw, hh) * 0.55f
                path.reset()
                path.moveTo(cx, cy + sz * 1.6f)
                path.cubicTo(cx - sz * 2.4f, cy - sz * 0.2f, cx - sz * 1.0f, cy - sz * 2.2f, cx, cy - sz * 0.7f)
                path.cubicTo(cx + sz * 1.0f, cy - sz * 2.2f, cx + sz * 2.4f, cy - sz * 0.2f, cx, cy + sz * 1.6f)
                c.drawPath(path, eyePaint)
            }
            EyeShape.CROSS -> { // X X
                val q = minOf(hw, hh) * .7f
                c.drawLine(cx - q, cy - q, cx + q, cy + q, strokePaint); c.drawLine(cx - q, cy + q, cx + q, cy - q, strokePaint)
            }
            EyeShape.LINE -> c.drawLine(cx - hw * .85f, cy, cx + hw * .85f, cy, strokePaint)
            EyeShape.ROUND -> {
                val rr = minOf(hw, hh) * 0.95f
                c.drawCircle(cx, cy, rr, eyePaint)
                fxPaint.color = Color.BLACK; c.drawCircle(cx + rr * .1f, cy + rr * .1f, rr * .45f, fxPaint)
                fxPaint.color = Color.WHITE; c.drawCircle(cx - rr * .25f, cy - rr * .3f, rr * .2f, fxPaint)
            }
            EyeShape.RECT -> {}
        }
    }

    private fun drawEffects(c: Canvas, vw: Float, vh: Float, tSec: Float, flags: Int) {
        val col = eyePaint.color
        if (flags and Fx.TEARS != 0) {
            fxPaint.color = 0xFF6FC3FF.toInt()
            for ((i, x) in listOf(eyeL, eyeR).withIndex()) {
                val a = ((tSec * 0.8f + i * 0.5f) % 1f)
                fxPaint.alpha = (230 * (1f - a)).toInt()
                val y = eyeY + eyeH * 0.5f + a * vh * 0.25f
                drop(c, x + (if (i == 0) -eyeW * .3f else eyeW * .3f), y, vh * 0.022f)
            }
        }
        if (flags and Fx.SWEAT != 0) {
            fxPaint.color = 0xFF9FDCFF.toInt(); val a = (tSec * 0.5f) % 1f
            fxPaint.alpha = (230 * (1f - a * .6f)).toInt()
            drop(c, eyeR + eyeW * .7f, eyeY - eyeH * .6f + a * vh * .08f, vh * 0.03f)
        }
        if (flags and Fx.BLUSH != 0) {
            fxPaint.color = 0x66FF6FA8; 
            c.drawOval(eyeL - eyeW * .55f, eyeY + eyeH * .55f, eyeL + eyeW * .15f, eyeY + eyeH * .8f, fxPaint)
            c.drawOval(eyeR - eyeW * .15f, eyeY + eyeH * .55f, eyeR + eyeW * .55f, eyeY + eyeH * .8f, fxPaint)
        }
        if (flags and Fx.SPARKLE != 0 || fx.party) {
            for (i in 0 until 6) {
                val a = ((tSec * 0.9f + i * 0.17f) % 1f)
                fxPaint.color = if (fx.party) listOf(0xFFFFD25A, 0xFFFF5FA8, 0xFF5AD1FF, 0xFF7CFF8A)[i % 4].toInt() else col
                fxPaint.alpha = (255 * bell(a)).toInt()
                val x = vw * (0.1f + 0.16f * i) + sin(tSec + i) * 20f
                val y = vh * (0.15f + 0.7f * ((i * 0.37f) % 1f))
                sparkle(c, x, y, vh * 0.025f * (0.5f + bell(a)))
            }
        }
        if (flags and Fx.NOTES != 0) {
            zPaint.color = col; zPaint.textSize = vh * 0.09f
            for (i in 0..1) {
                val a = ((tSec * 0.5f + i * 0.5f) % 1f)
                zPaint.alpha = (255 * bell(a)).toInt()
                c.drawText(if (i == 0) "♪" else "♫", vw * (0.12f + 0.72f * i) + sin(tSec * 3 + i) * 15f, vh * (0.75f - 0.45f * a), zPaint)
            }
            zPaint.color = 0xFF1FA894.toInt()
        }
        if (flags and Fx.ANGER != 0) {
            strokePaint.color = 0xFFFF4A3A.toInt(); strokePaint.strokeWidth = vh * 0.012f
            val x = eyeR + eyeW * .75f; val y = eyeY - eyeH * .75f; val s = vh * 0.035f * (1f + 0.15f * sin(tSec * 8))
            for (k in 0 until 4) {
                val a = k * PI.toFloat() / 2 + PI.toFloat() / 4
                c.drawLine(x + cos(a) * s * .4f, y + sin(a) * s * .4f, x + cos(a) * s, y + sin(a) * s, strokePaint)
            }
        }
        if (flags and Fx.BATTERY != 0) {
            val bw = vw * 0.08f; val bh = vh * 0.07f; val x = vw * 0.5f - bw / 2; val y = vh * 0.12f
            strokePaint.color = 0xFFFF9A3C.toInt(); strokePaint.strokeWidth = vh * 0.008f
            c.drawRoundRect(x, y, x + bw, y + bh, 6f, 6f, strokePaint)
            fxPaint.color = 0xFFFF9A3C.toInt(); fxPaint.alpha = if (sin(tSec * 4) > 0) 255 else 60
            c.drawRect(x + bw, y + bh * .3f, x + bw + bw * .08f, y + bh * .7f, fxPaint)
            c.drawRect(x + bw * .1f, y + bh * .2f, x + bw * .25f, y + bh * .8f, fxPaint)
        }
        val scanP = if (fx.scan >= 0) fx.scan else if (flags and Fx.SCANLINE != 0) (tSec * 0.7f) % 1f else -1f
        if (scanP >= 0) {
            fxPaint.color = col; fxPaint.alpha = 160
            val y = eyeY - eyeH / 2 + eyeH * scanP
            c.drawRect(eyeL - eyeW / 2 - 6, y - 3, eyeR + eyeW / 2 + 6, y + 3, fxPaint)
        }
        if (flags and Fx.STARS != 0) {
            fxPaint.color = 0xFFFFE15A.toInt(); fxPaint.alpha = 230
            for (i in 0..2) {
                val a = tSec * 2.5f + i * 2.1f
                sparkle(c, vw / 2 + cos(a) * vw * 0.22f, eyeY - eyeH * .9f + sin(a) * vh * 0.05f, vh * 0.03f)
            }
        }
    }

    private fun drop(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset(); path.moveTo(x, y - s * 1.6f)
        path.quadTo(x + s, y, x, y + s); path.quadTo(x - s, y, x, y - s * 1.6f)
        c.drawPath(path, fxPaint)
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset(); path.moveTo(x, y - s); path.quadTo(x, y, x + s, y); path.quadTo(x, y, x, y + s)
        path.quadTo(x, y, x - s, y); path.quadTo(x, y, x, y - s); c.drawPath(path, fxPaint)
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, ew: Float, eh: Float, innerOnRight: Boolean) {
        if (shapeNow != EyeShape.RECT) { drawShape(c, shapeNow, cx, cy, ew, eh, innerOnRight); return }
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
