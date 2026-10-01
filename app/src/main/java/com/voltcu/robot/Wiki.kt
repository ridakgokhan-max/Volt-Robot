package com.voltcu.robot

import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Telefondaki Türkçe Vikipedi (Kiwix ZIM dosyası) — internetsiz bilgi kaynağı.
 * "X nedir / kimdir / nerede" sorularını buradan cevaplar.
 */
class Wiki(private val file: File) {

    private val tr = Locale("tr", "TR")
    private var archive: org.kiwix.libzim.Archive? = null
    var status = "yok"
        private set

    val ready get() = archive != null

    fun open() {
        if (!file.exists()) { status = "dosya yok (${file.path})"; return }
        try {
            archive = org.kiwix.libzim.Archive(file.absolutePath)
            status = "hazır (${file.length() / 1_000_000} MB, ${archive?.articleCount ?: 0} madde)"
        } catch (e: Throwable) {
            status = "açılamadı: ${e.message}"
            Log.e("VoltRobot", "Vikipedi açılamadı", e)
        }
    }

    /** Sorudan aranacak konuyu çıkarır; bilgi sorusu değilse null */
    fun topicOf(question: String): String? {
        var t = question.lowercase(tr).replace(Regex("[?!.,]"), " ").replace(Regex("\\s+"), " ").trim()
        val prefixes = listOf("bana ", "peki ", "acaba ", "sence ", "hey ")
        for (p in prefixes) t = t.removePrefix(p)
        val patterns = listOf(
            Regex("^(.+?) (nedir|ne demek|ne demektir|ne işe yarar|kimdir|kim|kimdi|nerede|neresi|neresidir|nerededir)$"),
            Regex("^(.+?) hakkında (bilgi ver|bilgi|ne biliyorsun|bir şey anlat|bir şeyler anlat|anlat)$"),
            Regex("^(.+?) (ile ilgili|hakkında) .*$"),
            Regex("^(.+?)(\\'?(n?[ıiuü]n)?) (anlamı ne|tanımı ne)$")
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            val topic = m.groupValues[1].trim()
            if (topic.length >= 2 && topic.split(" ").size <= 5 && topic !in setOf("sen", "ben", "bu", "şu", "o", "adın", "senin adın")) return topic
        }
        return null
    }

    /** Konuyu arayıp ilk 1-2 cümlelik özeti döndürür */
    fun lookup(topic: String): Pair<String, String>? {
        val a = archive ?: return null
        return try {
            val path = findPath(a, topic) ?: return null
            var entry = a.getEntryByPath(path)
            val title = entry.title
            val item = entry.getItem(true)
            val raw: Any = item.data.data
            val html = if (raw is ByteArray) String(raw, Charsets.UTF_8) else raw.toString()
            val text = summarize(html) ?: return null
            title to text
        } catch (e: Throwable) {
            Log.e("VoltRobot", "Vikipedi arama hatası: $topic", e)
            null
        }
    }

    private fun findPath(a: org.kiwix.libzim.Archive, topic: String): String? {
        // 1) Tam başlık (Türkçe büyük harfle)
        val candidates = linkedSetOf(
            topic.replaceFirstChar { it.titlecase(tr) },
            topic.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(tr) } },
            topic
        )
        for (c in candidates) {
            try { if (a.hasEntryByTitle(c)) return a.getEntryByTitle(c).path } catch (_: Throwable) {}
            try { val p = c.replace(' ', '_'); if (a.hasEntryByPath(p)) return p } catch (_: Throwable) {}
            try { val p = "A/" + c.replace(' ', '_'); if (a.hasEntryByPath(p)) return p } catch (_: Throwable) {}
        }
        // 2) Başlık önerisi araması
        return try {
            val searcher = org.kiwix.libzim.SuggestionSearcher(a)
            val search = searcher.suggest(topic)
            val it = search.getResults(0, 3)
            if (it.hasNext()) it.next().path else null
        } catch (e: Throwable) { Log.e("VoltRobot", "öneri araması hatası", e); null }
    }

    /** HTML maddeden ilk anlamlı paragrafın ilk 1-2 cümlesi */
    private fun summarize(html: String): String? {
        val paras = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (m in paras) {
            var p = m.groupValues[1]
            p = p.replace(Regex("<sup.*?</sup>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace(Regex("&[a-z#0-9]+;"), "")
                .replace(Regex("\\[[^\\]]*\\]"), "")
                .replace(Regex("\\s+"), " ").trim()
            // Telaffuz, doğum tarihi gibi parantezleri at (ilk paragrafta çok uzun olur)
            p = p.replace(Regex("\\s*\\([^()]{0,160}\\)"), "").replace(Regex("\\s+"), " ").trim()
            if (p.length < 40) continue
            val sentences = splitSentences(p)
            val out = StringBuilder()
            for (s in sentences) {
                if (out.isNotEmpty() && out.length + s.length > 320) break
                if (out.isNotEmpty()) out.append(' ')
                out.append(s)
                if (out.length > 160) break
            }
            return out.toString().take(400)
        }
        return null
    }

    private fun splitSentences(p: String): List<String> {
        val res = ArrayList<String>()
        var start = 0
        for (i in p.indices) {
            val c = p[i]
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == p.length || p[i + 1] == ' ')) {
                // "M.Ö.", "1." gibi kısaltma ve sayıları bölme
                val prev = p.substring(maxOf(start, i - 3), i)
                if (prev.isNotEmpty() && (prev.last().isDigit() || prev.trim().length <= 1 || prev.endsWith("M.Ö") || prev.endsWith("vb"))) continue
                res.add(p.substring(start, i + 1).trim()); start = i + 1
            }
        }
        if (start < p.length) res.add(p.substring(start).trim())
        return res.filter { it.isNotBlank() }
    }
}
