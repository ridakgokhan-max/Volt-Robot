package com.voltcu.robot

import java.util.Locale
import kotlin.random.Random

/**
 * Volt'un insanlarla ilişkisi: yüzünden tanır, yeni biriyle tanışınca
 * sorular sorup hafızasına kaydeder, tanıdığını görünce hatırladıklarını söyler.
 */
class Social(private val people: PeopleMemory, private val host: Host) {

    interface Host {
        fun now(): Long
        fun say(text: String, e: Emotion? = null)
        fun isBusy(): Boolean
        fun isSleeping(): Boolean
        fun faceVisible(): Boolean
        fun log(msg: String)
    }

    private val tr = Locale("tr", "TR")

    var current: Person? = null
        private set
    var lastSimilarity = 0f
        private set

    private val recent = ArrayList<Pair<Long, FloatArray>>()
    private var lastEmbAt = 0L
    private var unknownStreak = 0
    private var greetedThisVisit = false
    private var visitDeclined = false
    private var absentSince = 0L

    // ---- Tanışma sohbeti ----
    private enum class Step { ASK_MEET, ASK_NAME, CONFIRM_NAME, QUESTIONS }
    private var step: Step? = null
    private var stepAskedAt = 0L
    private var pendingName = ""
    private val enrollSamples = ArrayList<FloatArray>()
    private var newPerson: Person? = null
    private var qIndex = 0

    private data class Q(val key: String, val ask: String, val recall: String)
    private val questions = listOf(
        Q("boş zamanlarında", "Boş zamanlarında neler yapmayı seversin?", "Hatırlıyorum, boş zamanlarını sorduğumda \"%s\" demiştin."),
        Q("en sevdiği renk", "En sevdiğin renk hangisi?", "En sevdiğin rengi sorduğumda \"%s\" demiştin, unutmadım!"),
        Q("en sevdiği yemek", "Peki en sevdiğin yemek ne?", "En sevdiğin yemeği sorduğumda \"%s\" demiştin. Bugün yedin mi?")
    )

    fun inDialog() = step != null

    /** Yüz tanıma karesi istensin mi (saniyede ~1 kez yeter) */
    fun wantsEmbedding(): Boolean = host.now() - lastEmbAt > 900 && !host.isSleeping()

    fun onEmbedding(emb: FloatArray) {
        val now = host.now()
        lastEmbAt = now
        recent.add(now to emb)
        recent.removeAll { now - it.first > 4000 }
        if (recent.size > 4) recent.removeAt(0)
        val avg = FaceRecognizer.average(recent.map { it.second })
        if (step != null && current == null) enrollSamples.add(emb)

        val match = people.best(avg)
        val sim = match?.second ?: 0f
        lastSimilarity = sim
        if (match != null && sim >= KNOWN) {
            unknownStreak = 0
            val p = match.first
            if (current != p) host.log("TANIDI: ${p.name} (benzerlik %.2f)".format(sim))
            current = p
            if (sim < 0.85f && Random.nextFloat() < 0.15f) people.learn(p, emb)
            if (!greetedThisVisit && step == null && !host.isBusy()) greet(p)
        } else if (sim < UNKNOWN) {
            if (current == null) unknownStreak++
            host.log("Tanımadığı yüz (en yakın %.2f, seri %d)".format(sim, unknownStreak))
            if (unknownStreak >= 3 && step == null && !visitDeclined && !greetedThisVisit && !host.isBusy()) startMeeting()
        }
    }

    /** Her saniye: ziyaret bitti mi, sohbet zaman aşımı */
    fun tick() {
        val now = host.now()
        if (host.faceVisible()) absentSince = 0L
        else {
            if (absentSince == 0L) absentSince = now
            if (now - absentSince > 20_000 && (current != null || greetedThisVisit || visitDeclined)) {
                current?.let { it.lastSeen = now; people.save() }
                host.log("Ziyaret bitti: ${current?.name ?: "tanımadığı biri"}")
                current = null; greetedThisVisit = false; visitDeclined = false; unknownStreak = 0; recent.clear()
            }
        }
        if (step != null && !host.isBusy() && now - stepAskedAt > 25_000) {
            host.log("Tanışma yarıda kaldı")
            endDialog()
            if (host.faceVisible()) host.say("Neyse, sonra tanışırız.", Emotion.SAD)
        }
    }

    private fun greet(p: Person) {
        greetedThisVisit = true
        val now = host.now()
        val gap = if (p.lastSeen > 0) now - p.lastSeen else Long.MAX_VALUE
        p.visits++
        val part = mutableListOf<String>()
        part += when {
            gap < 10 * 60_000L -> listOf("${p.name}, yine geldin!", "Aa, ${p.name} geri döndü!").random()
            gap < 3 * 3_600_000L -> listOf("Tekrar hoş geldin ${p.name}!", "${p.name}! Seni yine görmek güzel.").random()
            gap < 2 * 86_400_000L -> listOf("Merhaba ${p.name}!", "Selam ${p.name}, seni tanıdım!").random()
            else -> "${p.name}! Uzun zamandır görüşmüyorduk, seni özledim!"
        }
        if (p.visits % 10 == 0) part += "Bu seninle ${p.visits}. görüşmemiz!"
        else if (p.facts.isNotEmpty() && Random.nextFloat() < 0.5f) {
            val (k, v) = p.facts.entries.random().toPair()
            questions.firstOrNull { it.key == k }?.let { part += it.recall.format(v) }
        }
        p.lastSeen = now
        people.save()
        host.log("SELAMLADI: ${p.name} (${p.visits}. ziyaret)")
        host.say(part.joinToString(" "), if (p.owner) Emotion.LOVE else Emotion.HAPPY)
    }

    private fun startMeeting() {
        greetedThisVisit = true
        enrollSamples.clear()
        enrollSamples.addAll(recent.map { it.second })
        if (people.people.isEmpty()) {
            // Hiç kimseyi tanımıyor: ilk tanışma (büyük ihtimalle yaratıcısı)
            setStep(Step.ASK_NAME)
            host.say("Merhaba! Ben Volt. Henüz kimseyi tanımıyorum. Senin adın ne?", Emotion.CURIOUS)
        } else {
            setStep(Step.ASK_MEET)
            host.say(listOf("Merhaba! Seni daha önce görmedim. Tanışalım mı?", "Aa, yeni bir yüz! Seninle tanışabilir miyim?").random(), Emotion.CURIOUS)
        }
    }

    private fun setStep(s: Step) { step = s; stepAskedAt = host.now() }

    private fun endDialog() { step = null; newPerson = null; pendingName = ""; qIndex = 0 }

    // Android 9 düzenli ifadelerde Türkçe harfleri desteklemediği için kelime kelime bakıyoruz
    private fun words(t: String) = t.split(" ", ",", ".", "!", "?").map { it.trim() }.filter { it.isNotEmpty() }
    private val yesWords = setOf("evet", "olur", "tabii", "tabi", "tamam", "aynen", "doğru", "he", "hıhı", "isterim", "elbette", "tanışalım", "olsun", "kesinlikle")
    private val noWords = setOf("hayır", "hayir", "yok", "olmaz", "istemiyorum", "yanlış", "değil", "yanlis")
    private fun yes(t: String) = words(t).any { it in yesWords } && !no(t)
    private fun no(t: String) = words(t).any { it in noWords }

    /** Tanışma sırasında duyulanı işler */
    fun answer(raw: String) {
        val t = raw.lowercase(tr).trim()
        stepAskedAt = host.now()
        host.log("TANIŞMA cevabı ($step): $t")
        when (step) {
            Step.ASK_MEET -> when {
                yes(t) -> { setStep(Step.ASK_NAME); host.say("Harika! Ben Volt. Senin adın ne?", Emotion.EXCITED) }
                no(t) -> { visitDeclined = true; endDialog(); host.say("Tamam, sorun değil. Seni kaydetmiyorum.", Emotion.SHY) }
                else -> { // doğrudan adını söylemiş olabilir
                    val n = extractName(t)
                    if (n != null && t.contains("ad")) confirmName(n) else host.say("Tanışalım mı? Evet ya da hayır de.", Emotion.CURIOUS)
                }
            }
            Step.ASK_NAME -> {
                val n = extractName(t)
                if (n == null) host.say("Anlayamadım, sadece adını söyler misin?", Emotion.CONFUSED) else confirmName(n)
            }
            Step.CONFIRM_NAME -> when {
                yes(t) -> saveNewPerson()
                no(t) -> { setStep(Step.ASK_NAME); host.say("Pardon! Adını bir daha söyler misin?", Emotion.SHY) }
                else -> {
                    val n = extractName(t)
                    if (n != null) confirmName(n) else host.say("Adın $pendingName, doğru mu?", Emotion.CURIOUS)
                }
            }
            Step.QUESTIONS -> {
                val p = newPerson ?: run { endDialog(); return }
                val q = questions[qIndex]
                p.facts[q.key] = cleanAnswer(t)
                people.save()
                host.log("ÖĞRENDİ: ${p.name} → ${q.key}: ${p.facts[q.key]}")
                qIndex++
                if (qIndex < questions.size) {
                    setStep(Step.QUESTIONS)
                    host.say(listOf("Güzel!", "Ooo, harika.", "Not ettim!").random() + " " + questions[qIndex].ask, Emotion.HAPPY)
                } else {
                    endDialog()
                    host.say("Seni artık tanıyorum ${p.name}! Bir dahaki sefere seni görünce hatırlayacağım.", Emotion.LOVE)
                }
            }
            null -> {}
        }
    }

    private fun confirmName(n: String) {
        pendingName = n
        setStep(Step.CONFIRM_NAME)
        host.say("Adın $n, doğru mu?", Emotion.CURIOUS)
    }

    private fun saveNewPerson() {
        val samples = if (enrollSamples.isNotEmpty()) enrollSamples.toList() else recent.map { it.second }
        if (samples.isEmpty()) { endDialog(); host.say("Yüzünü göremedim, kameraya bakar mısın?", Emotion.CONFUSED); return }
        val existing = people.find(pendingName)
        val p = if (existing != null) { samples.take(4).forEach { people.learn(existing, it) }; existing } else people.add(pendingName, samples)
        current = p
        newPerson = p
        host.log("KAYDETTİ: ${p.name} (${samples.size} yüz örneği, sahibi=${p.owner})")
        qIndex = 0
        setStep(Step.QUESTIONS)
        val hi = if (p.owner) "Memnun oldum ${p.name}! Demek beni yapan sensin." else "Memnun oldum ${p.name}!"
        host.say("$hi Seni daha iyi tanımak için birkaç şey sorayım. ${questions[0].ask}", Emotion.EXCITED)
    }

    private val filler = setOf("benim", "adım", "ismim", "ben", "merhaba", "selam", "volt", "bot", "bol", "bal", "adim", "evet",
        "hayır", "tabii", "tamam", "ki", "ve", "de", "da", "şey", "yani", "bana", "derler", "diyebilirsin", "diye", "seslen", "adı", "ismi", "sadece", "olarak", "bu", "işte", "aslında", "hey", "yok", "değil", "doğru", "yanlış")

    private fun extractName(t: String): String? {
        val w = t.split(" ").map { it.trim(',', '.', '!', '?') }.filter { it.length >= 2 && it !in filler }
        val n = w.firstOrNull() ?: return null
        return n.replaceFirstChar { it.titlecase(tr) }
    }

    private fun cleanAnswer(t: String): String {
        var s = t
        for (p in listOf("benim", "en sevdiğim renk", "en sevdiğim yemek", "en sevdiğim", "en çok", "boş zamanlarımda", "genelde")) s = s.removePrefix(p).trim()
        return s.ifBlank { t }
    }

    /** Konuşma içindeki kişi komutları. true dönerse işlendi. */
    fun intercept(raw: String): Boolean {
        val t = raw.lowercase(tr)
        val p = current
        when {
            t.contains("ben kimim") || t.contains("beni tanıyor musun") || t.contains("beni hatırlıyor musun") || t.contains("adımı biliyor musun") -> {
                if (p != null) {
                    val extra = p.facts.entries.firstOrNull()?.let { (k, v) -> questions.firstOrNull { it.key == k }?.recall?.format(v) } ?: ""
                    host.say("Tabii ki tanıyorum, sen ${p.name}! $extra".trim(), Emotion.LOVE)
                } else if (host.faceVisible()) {
                    host.say("Seni henüz tanımıyorum. Tanışalım mı?", Emotion.CURIOUS)
                    greetedThisVisit = true; enrollSamples.clear(); enrollSamples.addAll(recent.map { it.second }); setStep(Step.ASK_MEET)
                } else host.say("Seni göremiyorum ki! Kameramın önüne gelir misin?", Emotion.CONFUSED)
                return true
            }
            t.contains("kimleri tanıyorsun") || t.contains("kimi tanıyorsun") || t.contains("kaç kişi tanıyorsun") -> {
                val names = people.people.map { it.name }
                host.say(if (names.isEmpty()) "Henüz kimseyi tanımıyorum." else "${names.size} kişi tanıyorum: ${names.joinToString(", ")}.", Emotion.PROUD)
                return true
            }
            t.contains("beni unut") -> {
                if (p != null) { people.remove(p); current = null; host.say("Tamam ${p.name}, seni hafızamdan sildim. Biraz üzüldüm ama.", Emotion.SAD) }
                else host.say("Zaten seni tanımıyorum.", Emotion.CONFUSED)
                return true
            }
            (t.contains("benim adım") || t.contains("benim ismim")) -> {
                val n = extractName(t.substringAfter("adım").substringAfter("ismim")) ?: return false
                if (p != null && p.name != n) { p.name = n; people.save(); host.say("Tamam, artık sana $n diyeceğim!", Emotion.HAPPY); return true }
                if (p == null && host.faceVisible()) {
                    greetedThisVisit = true; enrollSamples.clear(); enrollSamples.addAll(recent.map { it.second })
                    confirmName(n); return true
                }
                return false
            }
        }
        return false
    }

    /** Yapay zekâya kim olduğunu ve bildiklerini söyle */
    fun context(): String? = current?.let { people.describe(it) }

    fun status(): String = (current?.name ?: "tanımıyor") + " (benzerlik %.2f, %d kişi kayıtlı)".format(lastSimilarity, people.people.size) +
            (step?.let { " — tanışıyor: $it" } ?: "")

    companion object {
        const val KNOWN = 0.62f
        const val UNKNOWN = 0.50f
    }
}
