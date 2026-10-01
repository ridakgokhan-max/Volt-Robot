package com.voltcu.robot

import kotlin.random.Random

/**
 * Volt'un kendi kendine yaptıkları: kimse bakmazken etrafa bakınma, sıkılma,
 * esneme, uyuklama, uykuda mırıldanma; biri bakarken göz kırpma, kıkırdama…
 */
class Personality(private val host: Host) {

    interface Host {
        val face: FaceView
        fun now(): Long
        fun isFaceVisible(): Boolean
        fun lastFaceSeen(): Long
        fun lastInteraction(): Long
        fun isSleeping(): Boolean
        /** Konuşuyor, dinliyor ya da düşünüyorsa robot meşgul */
        fun isBusy(): Boolean
        fun feel(e: Emotion, ms: Long)
        fun mutter(text: String, e: Emotion? = null)
        fun move(vararg steps: Pair<Move, Long>)
        fun log(msg: String)
    }

    enum class Mood(val label: String) {
        ENGAGED("biriyle ilgileniyor"), WATCHED("izleniyor"), ALONE("yalnız, oyalanıyor"),
        DROWSY("uykusu geliyor"), ASLEEP("uyuyor")
    }

    var mood = Mood.ENGAGED
        private set
    var lastAction = "-"
        private set
    /** Boştayken sesli konuşmaz, her şeyi animasyonla gösterir (Kadir'in tercihi) */
    var chatty = false

    private var nextAction = 0L
    private var lastSpoke = 0L
    private var wasVisible = false
    private var absentSince = 0L

    private fun r(min: Long, max: Long) = min + Random.nextLong(max - min)
    private fun chance(p: Float) = Random.nextFloat() < p

    /** Uzun bir aradan sonra biri görünce */
    fun onFaceAppeared(absentMs: Long) {
        if (host.isSleeping() || host.isBusy()) return
        if (absentMs > 8000) {
            host.face.play(Gesture.DOUBLE_TAKE)
            host.feel(Emotion.EXCITED, 1800)
            lastAction = "biri geldi: çift bakış"
            nextAction = host.now() + r(6000, 10000)
        }
    }

    fun update() {
        val now = host.now()
        val visible = host.isFaceVisible()
        if (visible && !wasVisible) onFaceAppeared(if (absentSince == 0L) 0 else now - absentSince)
        if (!visible && wasVisible) absentSince = now
        wasVisible = visible

        val sinceTalk = now - host.lastInteraction()
        val alone = if (visible) 0L else now - maxOf(host.lastFaceSeen(), host.lastInteraction())
        mood = when {
            host.isSleeping() -> Mood.ASLEEP
            visible && sinceTalk < 15_000 -> Mood.ENGAGED
            visible -> Mood.WATCHED
            alone > 70_000 -> Mood.DROWSY
            alone > 8_000 -> Mood.ALONE
            else -> Mood.ENGAGED
        }

        if (now < nextAction) return
        if (host.isBusy() || host.face.gestureBusy) { nextAction = now + 1500; return }

        when (mood) {
            Mood.ENGAGED -> { nextAction = now + r(3000, 6000) }
            Mood.WATCHED -> watched(now)
            Mood.ALONE -> alone(now)
            Mood.DROWSY -> drowsy(now)
            Mood.ASLEEP -> asleep(now)
        }
    }

    private fun speak(now: Long, gapMs: Long, vararg lines: String, e: Emotion? = null): Boolean {
        if (!chatty || now - lastSpoke < gapMs) return false
        lastSpoke = now
        host.mutter(lines[Random.nextInt(lines.size)], e)
        return true
    }

    private fun act(name: String) { lastAction = name; host.log("KİŞİLİK: ${mood.label} → $name") }

    /** Bir davranış: adı, yüz duygusu, göz hareketi, gövde hareketi */
    private class B(val name: String, val e: Emotion? = null, val ms: Long = 2200, val g: Gesture? = null,
                    val moves: List<Pair<Move, Long>> = emptyList(), val weight: Int = 1)

    private var lastName = ""
    private val recentNames = ArrayDeque<String>()

    /** Ağırlıklı rastgele seçim; son 4 davranışı tekrar etmez */
    private fun pickAndPlay(list: List<B>) {
        val pool = list.filter { it.name !in recentNames }.ifEmpty { list }
        var total = pool.sumOf { it.weight }
        var x = Random.nextInt(total)
        val b = pool.first { x -= it.weight; x < 0 }
        b.g?.let { host.face.play(it) }
        b.e?.let { host.feel(it, b.ms) }
        if (b.moves.isNotEmpty()) host.move(*b.moves.toTypedArray())
        recentNames.addLast(b.name); while (recentNames.size > 4) recentNames.removeFirst()
        act(b.name)
    }

    private val watchedList = listOf(
        B("göz kırptı", Emotion.HAPPY, 1200, Gesture.WINK_LEFT, weight = 2),
        B("sağ gözünü kırptı", Emotion.MISCHIEVOUS, 1500, Gesture.WINK_RIGHT),
        B("kıkırdadı", Emotion.HAPPY, 1300, Gesture.GIGGLE, weight = 2),
        B("utandı", Emotion.SHY, 2500, weight = 2),
        B("kalp gözlerle baktı", Emotion.HEART_EYES, 2200, Gesture.HEART_BEAT),
        B("yavaşça göz kırptı (sevgi)", Emotion.CUTE, 2000, Gesture.BLINK_SLOW, weight = 2),
        B("merakla baktı", Emotion.CURIOUS, 2500, null, listOf(Move.HEAD_UP to 300L)),
        B("hava attı", Emotion.PROUD, 2200, null, listOf(Move.LIFT_UP to 300L, Move.LIFT_DOWN to 300L)),
        B("sevinçten zıpladı", Emotion.JOY, 1800, Gesture.JUMP, listOf(Move.LIFT_UP to 200L, Move.LIFT_DOWN to 200L)),
        B("şüpheyle süzdü", Emotion.SUSPICIOUS, 2000, Gesture.SQUINT_STARE),
        B("yan gözle baktı", Emotion.MISCHIEVOUS, 2000, Gesture.SIDE_EYE),
        B("yüzünü taradı", Emotion.FOCUSED, 1800, Gesture.SCAN),
        B("başını salladı", Emotion.HAPPY, 1000, Gesture.NOD),
        B("heyecanla kıpırdandı", Emotion.EXCITED, 1500, Gesture.WIGGLE, listOf(Move.TURN_LEFT to 200L, Move.TURN_RIGHT to 200L)),
        B("hayran hayran baktı", Emotion.STARSTRUCK, 2000),
        B("çift göz kırptı", Emotion.CUTE, 900, Gesture.DOUBLE_BLINK)
    )

    private val aloneList = listOf(
        B("etrafa bakındı", null, 0, Gesture.LOOK_AROUND, listOf(Move.TURN_LEFT to 500L, Move.TURN_RIGHT to 900L, Move.TURN_LEFT to 400L), 3),
        B("bir şeye göz attı", Emotion.CURIOUS, 2000, Gesture.PEEK, listOf(Move.TURN_RIGHT to 400L, Move.FORWARD to 300L), 2),
        B("sıkıldı", Emotion.BORED, 4000, null, listOf(Move.HEAD_DOWN to 400L), 2),
        B("iç çekti", Emotion.BORED, 2000, Gesture.SIGH),
        B("gözlerini devirdi", Emotion.UNIMPRESSED, 1800, Gesture.ROLL_EYES),
        B("hayallere daldı", Emotion.THINKING, 3000, Gesture.LOOK_UP_THINK, listOf(Move.HEAD_UP to 400L)),
        B("gerindi", null, 0, Gesture.STRETCH, listOf(Move.LIFT_UP to 500L, Move.LIFT_DOWN to 500L)),
        B("içinden şarkı söyledi", Emotion.MUSIC, 3500, Gesture.WIGGLE),
        B("hapşırdı", Emotion.SURPRISED, 1500, Gesture.SNEEZE),
        B("bir şeyi inceledi", Emotion.FOCUSED, 2500, Gesture.SCAN, listOf(Move.FORWARD to 300L)),
        B("kendi kendine dans etti", Emotion.MUSIC, 3000, Gesture.CELEBRATE, listOf(Move.TURN_LEFT to 300L, Move.TURN_RIGHT to 300L, Move.LIFT_UP to 250L, Move.LIFT_DOWN to 250L)),
        B("kafası karıştı", Emotion.CONFUSED, 2500, Gesture.SHAKE),
        B("şaşı baktı (kendi kendine şaka)", Emotion.CUTE, 1500, Gesture.CROSS_EYED),
        B("sistemini kontrol etti", Emotion.FOCUSED, 1500, Gesture.GLITCH),
        B("kendi etrafında döndü", Emotion.JOY, 1600, Gesture.SPIN, listOf(Move.TURN_LEFT to 1200L)),
        B("üşüdü", Emotion.NERVOUS, 1500, Gesture.SHIVER),
        B("kendine güldü", Emotion.LAUGH, 1600, Gesture.BOUNCE_HAPPY)
    )

    private val drowsyList = listOf(
        B("esnedi", null, 0, Gesture.YAWN, weight = 2),
        B("uyukladı, irkildi", Emotion.DROWSY, 2500, Gesture.NOD_OFF, weight = 3),
        B("uykulu uykulu bakındı", null, 0, Gesture.LOOK_AROUND),
        B("yavaşça göz kırptı", null, 0, Gesture.BLINK_SLOW),
        B("iç çekti", null, 0, Gesture.SIGH)
    )

    /** Biri bakıyor ama konuşmuyor */
    private fun watched(now: Long) { nextAction = now + r(7000, 14000); pickAndPlay(watchedList) }

    /** Kimse yok, oyalanıyor */
    private fun alone(now: Long) { nextAction = now + r(5000, 12000); pickAndPlay(aloneList) }

    /** Uzun süre yalnız: uykusu geliyor */
    private fun drowsy(now: Long) { nextAction = now + r(6000, 11000); pickAndPlay(drowsyList) }

    /** Uyurken: nefes alır, ara sıra uykusunda konuşur */
    private fun asleep(now: Long) {
        nextAction = now + r(20_000, 45_000)
        if (chance(0.35f) && speak(now, 120_000, "Mmm… pil… şarj…", "Bip… bop… zzz…", "Hmm… paletler…")) act("uykusunda konuştu")
        else { host.face.play(Gesture.NOD); act("uykusunda kıpırdandı") }
    }
}
