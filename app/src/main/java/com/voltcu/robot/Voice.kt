package com.voltcu.robot

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.concurrent.thread

/** İnternetsiz Türkçe konuşma tanıma (Vosk). Model uygulamanın içinde gelir. */
class VoiceInput(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var service: SpeechService? = null
    var ready = false
        private set
    private var paused = false

    fun init() {
        onStatus("Ses modeli yükleniyor…")
        thread(name = "vosk-load") {
            try {
                val dir = prepareModel()
                val m = Model(dir.absolutePath)
                main.post {
                    model = m
                    startListening()
                }
            } catch (e: Throwable) {
                main.post { onStatus("Ses tanıma HATA: ${e.message}") }
            }
        }
    }

    private fun startListening() {
        val m = model ?: return
        try {
            val rec = Recognizer(m, 16000.0f)
            val s = SpeechService(rec, 16000.0f)
            s.startListening(this)
            service = s
            ready = true
            if (paused) s.setPause(true)
            onStatus("Dinliyor (internetsiz)")
        } catch (e: Exception) {
            onStatus("Mikrofon HATA: ${e.message}")
        }
    }

    /** Robot konuşurken kendi sesini duymasın diye dinlemeyi durdurur */
    fun pause(p: Boolean) {
        paused = p
        service?.setPause(p)
    }

    override fun onResult(hypothesis: String?) = emit(hypothesis)
    override fun onFinalResult(hypothesis: String?) = emit(hypothesis)

    private fun emit(h: String?) {
        if (h == null) return
        val text = try { JSONObject(h).optString("text") } catch (_: Exception) { "" }
        if (text.isNotBlank()) onText(text.trim())
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis == null) return
        val p = try { JSONObject(hypothesis).optString("partial") } catch (_: Exception) { "" }
        if (p.isNotBlank()) onPartial(p)
    }

    override fun onError(exception: Exception?) {
        onStatus("Dinleme HATA: ${exception?.message}")
    }

    override fun onTimeout() {}

    /** Modeli ilk açılışta uygulamanın içinden telefon hafızasına kopyalar */
    private fun prepareModel(): File {
        val dest = File(ctx.filesDir, "model-tr")
        val marker = File(dest, ".hazir-v1")
        if (marker.exists()) return dest
        val assetList = ctx.assets.list("model-tr")
        if (assetList == null || assetList.isEmpty()) throw IllegalStateException("Türkçe ses modeli uygulamada yok")
        dest.deleteRecursively()
        copyAsset("model-tr", dest)
        marker.writeText("ok")
        return dest
    }

    private fun copyAsset(path: String, dest: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAsset("$path/$c", File(dest, c))
        }
    }

    fun release() {
        try { service?.stop(); service?.shutdown() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
    }
}

/** Telefonun kendi ses motoruyla Türkçe konuşma (internetsiz Türkçe ses paketi gerekir). */
class Speaker(
    ctx: Context,
    private val onStatus: (String) -> Unit,
    private val onStart: () -> Unit,
    private val onDone: () -> Unit
) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(ctx.applicationContext, this)
    var ok = false
        private set
    var speaking = false
        private set
    private var counter = 0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { onStatus("Konuşma motoru HATA"); return }
        val r = tts.setLanguage(Locale("tr", "TR"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            onStatus("Türkçe ses paketi YOK (ayarlardan indir)")
            return
        }
        tts.setPitch(1.4f)
        tts.setSpeechRate(1.05f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { speaking = true; onStart() } }
            override fun onDone(utteranceId: String?) { main.post { speaking = false; onDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { speaking = false; onDone() } }
        })
        ok = true
        onStatus("Konuşma hazır (Türkçe)")
    }

    fun say(text: String) {
        if (!ok) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "volt-${counter++}")
    }

    fun stop() { tts.stop(); speaking = false }

    fun release() { tts.stop(); tts.shutdown() }
}
