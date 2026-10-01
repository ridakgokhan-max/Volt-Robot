package com.voltcu.robot

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/** llama.cpp ile telefonda çalışan yapay zekâ (internetsiz). */
class VoltLlm {
    companion object {
        private var libOk = try { System.loadLibrary("voltllm"); true } catch (e: Throwable) { Log.e("VoltLLM", "kütüphane yok", e); false }
    }

    interface Sink { fun onBytes(b: ByteArray): Boolean }

    external fun nativeLoad(path: String, sys: String, threads: Int, nctx: Int): Boolean
    external fun nativeGenerate(user: String, maxTokens: Int, sink: Sink): Int
    external fun nativeStop()

    val available get() = libOk
}

/**
 * Yapay zekâ beynini yönetir: modeli yükler, soruyu sorar,
 * cevabı cümle cümle verir ki robot düşünürken konuşmaya başlasın.
 */
class LlmEngine(private val modelFile: File, private val onStatus: (String) -> Unit) {

    enum class State { NONE, LOADING, READY, ERROR }

    private val llm = VoltLlm()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var state = State.NONE
        private set
    @Volatile var busy = false
        private set

    private val system = "Sen Volt adında küçük, meraklı ve sevimli bir ev robotusun. Seni Kadir yaptı. " +
            "Paletlerin, bir kolun ve ekranında gözlerin var. Her zaman Türkçe konuşursun. " +
            "Cevapların kısa olur: en fazla 2-3 cümle. Emoji, liste veya işaret kullanmazsın, çünkü cevabın sesli okunur. " +
            "Bilmediğin bir şeyi uydurmazsın, bilmiyorsan dürüstçe söylersin."

    fun load() {
        if (!llm.available) { state = State.ERROR; onStatus("Yapay zekâ kütüphanesi yüklenemedi"); return }
        if (!modelFile.exists()) { state = State.ERROR; onStatus("Model dosyası yok: ${modelFile.name}"); return }
        state = State.LOADING
        onStatus("Yapay zekâ yükleniyor… (${modelFile.length() / 1_000_000} MB)")
        exec.execute {
            val t0 = System.currentTimeMillis()
            val prefix = "<|im_start|>system\n$system<|im_end|>\n"
            val ok = try { llm.nativeLoad(modelFile.absolutePath, prefix, 4, 1024) } catch (e: Throwable) { false }
            val sec = (System.currentTimeMillis() - t0) / 1000
            state = if (ok) State.READY else State.ERROR
            main.post { onStatus(if (ok) "Yapay zekâ hazır (${modelFile.name}, ${sec} sn'de yüklendi)" else "Yapay zekâ yüklenemedi") }
        }
    }

    /** Soruyu sorar; her tamamlanan cümle onSentence ile gelir. */
    fun ask(question: String, onSentence: (String) -> Unit, onDone: (Int) -> Unit) {
        if (state != State.READY || busy) { onDone(-1); return }
        busy = true
        exec.execute {
            val prompt = "<|im_start|>user\n$question<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
            val bytes = java.io.ByteArrayOutputStream()
            var buffer = StringBuilder()
            var sentences = 0
            val sink = object : VoltLlm.Sink {
                override fun onBytes(b: ByteArray): Boolean {
                    buffer.append(String(b, Charsets.UTF_8))
                    bytes.write(b)
                    // Tamamlanan cümleleri hemen gönder
                    while (true) {
                        val idx = findSentenceEnd(buffer)
                        if (idx < 0) break
                        val s = clean(buffer.substring(0, idx + 1))
                        buffer = StringBuilder(buffer.substring(idx + 1))
                        if (s.isNotBlank()) { sentences++; main.post { onSentence(s) } }
                        if (sentences >= 3) return false
                    }
                    return true
                }
            }
            val n = try { llm.nativeGenerate(prompt, 120, sink) } catch (e: Throwable) { -9 }
            val rest = clean(buffer.toString())
            if (rest.isNotBlank() && sentences < 3) main.post { onSentence(rest) }
            Log.i("VoltRobot", "YZ cevap: ${String(bytes.toByteArray(), Charsets.UTF_8)}")
            busy = false
            main.post { onDone(n) }
        }
    }

    fun stop() = llm.nativeStop()

    private fun findSentenceEnd(sb: StringBuilder): Int {
        for (i in sb.indices) {
            val c = sb[i]
            if ((c == '.' || c == '!' || c == '?' || c == '…') && i + 1 < sb.length && sb[i + 1].isWhitespace()) {
                // "1." gibi sayılarda bölme
                if (c == '.' && i > 0 && sb[i - 1].isDigit()) continue
                return i
            }
        }
        return -1
    }

    private fun clean(s: String): String = s
        .replace(Regex("[*#_`~>|]"), "")
        .replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
