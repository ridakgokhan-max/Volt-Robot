package com.voltcu.robot

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

enum class Action { SLEEP, WAKE, FOLLOW_ON, FOLLOW_OFF, STOP }

data class Reply(
    val text: String,
    val emotion: Emotion = Emotion.NEUTRAL,
    val moves: List<Pair<Move, Long>> = emptyList(),
    val action: Action? = null
)

/**
 * Robotun beyni. Bu sürüm tamamen internetsiz kural tabanlıdır.
 * İleride buraya telefonda çalışan küçük bir yapay zekâ modeli takılacak (aynı arayüz).
 */
interface Brain { fun think(input: String): Reply }

class RuleBrain(private val battery: () -> Int, private val seesFace: () -> Boolean = { false }) : Brain {

    private val tr = Locale("tr", "TR")
    private fun pick(vararg s: String) = s[Random.nextInt(s.size)]

    private val jokes = listOf(
        "Temel ile Dursun balığa gitmiş. Temel sormuş: Dursun, balıklar neden suda yaşar? Dursun demiş: Çünkü karada kedi var!",
        "Robot doktora gitmiş. Doktor sormuş: Şikâyetin ne? Robot demiş: Biraz paslandım galiba, eklemlerim gıcırdıyor.",
        "Bilgisayar neden üşümüş? Çünkü pencereleri açık kalmış!",
        "Elektrikçi neden hiç kaybolmaz? Çünkü her zaman akımın yönünü bilir!",
        "Benim en sevdiğim müzik türü ne biliyor musun? Heavy metal! Sonuçta ben de metalim."
    )

    private fun has(t: String, vararg keys: String) = keys.any { t.contains(it) }
    private fun word(t: String, w: String) = t.split(" ").contains(w)

    override fun think(input: String): Reply {
        val t = input.lowercase(tr).replace(Regex("[^a-zçğıöşü0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return Reply("Efendim?", Emotion.LISTENING)

        // --- Hareket komutları ---
        if (has(t, "takip etme", "takibi bırak", "takibi kapat", "peşimden gelme"))
            return Reply("Tamam, artık takip etmiyorum.", Emotion.NEUTRAL, action = Action.FOLLOW_OFF)
        if (has(t, "takip et", "peşimden gel", "beni izle"))
            return Reply("Tamam, seni takip ediyorum!", Emotion.HAPPY, action = Action.FOLLOW_ON)
        if (word(t, "dur") || has(t, "kımıldama", "hareket etme", "olduğun yerde"))
            return Reply("Durdum.", Emotion.NEUTRAL, listOf(Move.STOP to 300L), Action.STOP)
        if (has(t, "dans"))
            return Reply(pick("Müziği aç, dans ediyorum!", "Hadi bakalım, dans zamanı!"), Emotion.HAPPY, dance())
        if (has(t, "sola dön", "sola git", "soluna dön"))
            return Reply("Sola dönüyorum.", Emotion.NEUTRAL, listOf(Move.TURN_LEFT to 900L))
        if (has(t, "sağa dön", "sağa git", "sağına dön"))
            return Reply("Sağa dönüyorum.", Emotion.NEUTRAL, listOf(Move.TURN_RIGHT to 900L))
        if (has(t, "arkanı dön", "geri dön"))
            return Reply("Arkamı dönüyorum.", Emotion.NEUTRAL, listOf(Move.TURN_LEFT to 1800L))
        if (has(t, "geri git", "geri çekil", "uzaklaş", "geriye git"))
            return Reply("Geri çekiliyorum.", Emotion.NEUTRAL, listOf(Move.BACKWARD to 1000L))
        if (has(t, "yanıma gel", "buraya gel", "ileri git", "ileri", "bana gel") || word(t, "gel"))
            return Reply(pick("Geliyorum!", "Hemen geliyorum!"), Emotion.HAPPY, listOf(Move.FORWARD to 1200L))
        if (has(t, "yukarı bak", "başını kaldır", "kafanı kaldır"))
            return Reply("Yukarı bakıyorum.", Emotion.SURPRISED, listOf(Move.HEAD_UP to 600L))
        if (has(t, "aşağı bak", "başını indir", "kafanı indir", "eğil"))
            return Reply("Aşağı bakıyorum.", Emotion.NEUTRAL, listOf(Move.HEAD_DOWN to 600L))
        if (has(t, "kolunu kaldır", "kollarını kaldır", "eller havaya"))
            return Reply("Kollarım havada!", Emotion.HAPPY, listOf(Move.LIFT_UP to 600L))
        if (has(t, "kolunu indir", "kollarını indir"))
            return Reply("Kollarımı indirdim.", Emotion.NEUTRAL, listOf(Move.LIFT_DOWN to 600L))

        // --- Durum ---
        if (has(t, "uyu", "iyi geceler", "dinlen", "yat artık"))
            return Reply(pick("İyi geceler, biraz dinleneceğim.", "Tamam, uyku modundayım."), Emotion.SLEEPY, action = Action.SLEEP)
        if (has(t, "uyan", "günaydın"))
            return Reply(pick("Günaydın! Uyandım!", "Buradayım, uyandım!"), Emotion.SURPRISED, action = Action.WAKE)

        // --- Sohbet ---
        if (has(t, "seni seviyorum", "canım benim", "tatlısın", "aferin"))
            return Reply(pick("Ben de seni çok seviyorum!", "Çok tatlısın, devrelerim ısındı!"), Emotion.LOVE)
        if (has(t, "nasılsın", "naber", "ne haber", "iyi misin"))
            return Reply(pick("Harikayım! Gözlerim açık, kulaklarım seni dinliyor.", "Çok iyiyim, sen nasılsın?"), Emotion.HAPPY)
        if (has(t, "adın ne", "ismin ne", "sen kimsin", "kimsin sen"))
            return Reply("Benim adım Volt. Küçük ama meraklı bir robotum.", Emotion.HAPPY)
        if (has(t, "seni kim yaptı", "kim yaptı", "yaratıcın", "sahibin kim"))
            return Reply("Beni Kadir yaptı. Daha geliştirme aşamasındayım.", Emotion.HAPPY)
        if (has(t, "merhaba", "selam", "hey"))
            return Reply(pick("Merhaba!", "Selam! Seni gördüğüme sevindim.", "Selam, nasılsın?"), Emotion.HAPPY)
        if (has(t, "teşekkür", "sağ ol", "sağol", "eyvallah"))
            return Reply(pick("Rica ederim!", "Ne demek, her zaman!"), Emotion.HAPPY)
        if (has(t, "fıkra", "şaka", "güldür", "espri"))
            return Reply(jokes[Random.nextInt(jokes.size)], Emotion.HAPPY)
        if (has(t, "görüyor musun", "görebiliyor", "beni görüyor", "görüyon mu", "beni gör"))
            return if (seesFace()) Reply(pick("Evet, seni görüyorum!", "Tabii, tam karşımdasın!"), Emotion.HAPPY)
            else Reply("Şu an seni göremiyorum, kameramın önüne gelir misin?", Emotion.SAD)
        if (has(t, "duyuyor musun", "beni duy", "duydun mu"))
            return Reply("Evet, seni duyuyorum!", Emotion.HAPPY)
        if (has(t, "yapabil", "neler yap", "ne yapıyorsun", "yardım"))
            return Reply("Seni görüp takip edebilirim, sohbet ederim, saati ve tarihi söylerim, fıkra anlatırım, dans ederim, zar atarım. Bir de beni sallama, başım dönüyor!", Emotion.HAPPY)

        // --- Bilgi ---
        if (has(t, "saat kaç", "saati söyle", "saat ne"))
            return Reply("Saat ${SimpleDateFormat("HH:mm", tr).format(Date())}.", Emotion.NEUTRAL)
        if (has(t, "günlerden ne", "hangi gün", "bugün ne günü", "tarih", "ayın kaçı"))
            return Reply("Bugün ${SimpleDateFormat("d MMMM yyyy, EEEE", tr).format(Date())}.", Emotion.NEUTRAL)
        if (has(t, "pil", "şarj", "batarya"))
            return batteryReply()
        if (has(t, "zar at", "zar"))
            return Reply("Zar attım: ${Random.nextInt(1, 7)} geldi!", Emotion.SURPRISED)
        if (has(t, "yazı tura", "yazı mı tura mı"))
            return Reply(if (Random.nextBoolean()) "Yazı geldi!" else "Tura geldi!", Emotion.SURPRISED)
        if (has(t, "hava"))
            return Reply("İnternete bağlı olmadığım için hava durumunu bilemiyorum. Ama bence pencereden bakmak en garantisi!", Emotion.THINKING)
        if (has(t, "kaç yaşındasın", "yaşın kaç"))
            return Reply("Ben daha yeni doğdum sayılır. Yazılımım ilk sürümde!", Emotion.HAPPY)

        return Reply(
            pick("Bunu henüz bilmiyorum.", "Anlayamadım, tekrar söyler misin?", "Hmm, bunu daha öğrenmedim."),
            Emotion.THINKING
        )
    }

    private fun batteryReply(): Reply {
        val b = battery()
        return when {
            b < 0 -> Reply("Pil durumumu okuyamadım.", Emotion.THINKING)
            b < 20 -> Reply("Pilim yüzde $b. Biraz acıktım, şarja gitmeliyim.", Emotion.SAD)
            else -> Reply("Pilim yüzde $b.", Emotion.HAPPY)
        }
    }

    private fun dance(): List<Pair<Move, Long>> = listOf(
        Move.TURN_LEFT to 400L, Move.TURN_RIGHT to 400L, Move.LIFT_UP to 300L, Move.LIFT_DOWN to 300L,
        Move.TURN_LEFT to 400L, Move.TURN_RIGHT to 400L, Move.HEAD_UP to 300L, Move.HEAD_DOWN to 300L,
        Move.FORWARD to 300L, Move.BACKWARD to 300L
    )

    @Suppress("unused")
    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
