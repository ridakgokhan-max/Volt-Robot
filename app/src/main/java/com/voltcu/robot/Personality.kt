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

    /** Biri bakıyor ama konuşmuyor */
    private fun watched(now: Long) {
        nextAction = now + r(7000, 14000)
        when (Random.nextInt(9)) {
            0 -> { host.face.play(if (chance(.5f)) Gesture.WINK_LEFT else Gesture.WINK_RIGHT); host.feel(Emotion.HAPPY, 1200); act("göz kırptı") }
            1 -> { host.face.play(Gesture.GIGGLE); host.feel(Emotion.HAPPY, 1300); act("kıkırdadı") }
            2 -> { host.feel(Emotion.SHY, 2500); act("utandı") }
            3 -> { host.face.play(Gesture.HEART_BEAT); host.feel(Emotion.LOVE, 1600); act("kalp gözler") }
            4 -> { host.feel(Emotion.CURIOUS, 2500); host.move(Move.HEAD_UP to 300L); act("merakla baktı") }
            5 -> { host.feel(Emotion.PROUD, 2200); host.move(Move.LIFT_UP to 300L, Move.LIFT_DOWN to 300L); act("hava attı") }
            6 -> {
                if (speak(now, 120_000, "Ne yapıyorsun?", "Benimle oyun oynar mısın?", "Sıkıldım biraz, bir şey sorsana!", "Bugün nasıl geçti?", e = Emotion.CURIOUS)) act("laf attı")
                else { host.face.play(Gesture.NOD); act("başını salladı") }
            }
            7 -> { host.face.play(Gesture.SQUINT_STARE); host.feel(Emotion.SUSPICIOUS, 2000); act("şüpheyle süzdü") }
            else -> { host.feel(Emotion.EXCITED, 1500); host.move(Move.TURN_LEFT to 200L, Move.TURN_RIGHT to 200L); act("heyecanla kıpırdandı") }
        }
    }

    /** Kimse yok, oyalanıyor */
    private fun alone(now: Long) {
        nextAction = now + r(5000, 12000)
        when (Random.nextInt(12)) {
            0, 1 -> { host.face.play(Gesture.LOOK_AROUND); host.move(Move.TURN_LEFT to 500L, Move.TURN_RIGHT to 900L, Move.TURN_LEFT to 400L); act("etrafa bakındı") }
            2 -> { host.face.play(Gesture.PEEK); host.feel(Emotion.CURIOUS, 2000); host.move(Move.TURN_RIGHT to 400L, Move.FORWARD to 300L); act("bir şeye göz attı") }
            3 -> { host.feel(Emotion.BORED, 4000); host.move(Move.HEAD_DOWN to 400L); speak(now, 90_000, "Hıh… sıkıldım.", "Of…", "Kimse yok mu?"); act("sıkıldı") }
            4 -> { host.face.play(Gesture.ROLL_EYES); host.feel(Emotion.BORED, 1500); act("gözlerini devirdi") }
            5 -> { host.feel(Emotion.THINKING, 3500); host.move(Move.HEAD_UP to 400L); act("hayallere daldı") }
            6 -> { host.face.play(Gesture.STRETCH); host.move(Move.LIFT_UP to 500L, Move.LIFT_DOWN to 500L); act("gerindi") }
            7 -> { if (speak(now, 60_000, "Dı dı dım… dı dı dı dım…", "Bip bop, bip bop…", "La la la…", e = Emotion.HAPPY)) act("mırıldandı") else { host.face.play(Gesture.NOD); act("ritim tuttu") } }
            8 -> { host.face.play(Gesture.SNEEZE); host.feel(Emotion.SURPRISED, 1500); speak(now, 45_000, "Hapşu!"); act("hapşırdı") }
            9 -> { host.face.play(Gesture.SQUINT_STARE); host.feel(Emotion.SUSPICIOUS, 2500); host.move(Move.FORWARD to 300L); act("bir şeyi inceledi") }
            10 -> { host.feel(Emotion.EXCITED, 2500); host.move(Move.TURN_LEFT to 300L, Move.TURN_RIGHT to 300L, Move.LIFT_UP to 250L, Move.LIFT_DOWN to 250L); act("kendi kendine dans etti") }
            else -> { host.feel(Emotion.CONFUSED, 2500); host.face.play(Gesture.SHAKE); act("kafası karıştı") }
        }
    }

    /** Uzun süre yalnız: uykusu geliyor */
    private fun drowsy(now: Long) {
        nextAction = now + r(6000, 11000)
        when (Random.nextInt(4)) {
            0 -> { host.face.play(Gesture.YAWN); speak(now, 60_000, "Ahhh… uykum geldi."); act("esnedi") }
            1, 2 -> { host.face.play(Gesture.NOD_OFF); act("uyukladı, irkildi") }
            else -> { host.face.play(Gesture.LOOK_AROUND); act("uykulu uykulu bakındı") }
        }
    }

    /** Uyurken: nefes alır, ara sıra uykusunda konuşur */
    private fun asleep(now: Long) {
        nextAction = now + r(20_000, 45_000)
        if (chance(0.35f) && speak(now, 120_000, "Mmm… pil… şarj…", "Bip… bop… zzz…", "Hmm… paletler…")) act("uykusunda konuştu")
        else { host.face.play(Gesture.NOD); act("uykusunda kıpırdandı") }
    }
}
