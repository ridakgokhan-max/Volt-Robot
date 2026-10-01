package com.voltcu.robot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.sqrt

/** Yüzden "yüz izi" çıkarır (MobileFaceNet, internetsiz). Aynı kişinin izleri birbirine benzer. */
class FaceRecognizer(ctx: Context) {
    private val interpreter: Interpreter?
    private var inW = 112; private var inH = 112; private var outN = 192
    val ready get() = interpreter != null
    var error: String? = null
        private set

    init {
        interpreter = try {
            val fd = ctx.assets.openFd("mobile_face_net.tflite")
            val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val it = Interpreter(buf, Interpreter.Options().setNumThreads(2))
            val ish = it.getInputTensor(0).shape()   // [1, H, W, 3]
            inH = ish[1]; inW = ish[2]
            outN = it.getOutputTensor(0).shape().last()
            Log.i("VoltRobot", "Yüz tanıma modeli: giriş ${ish.joinToString("x")}, çıkış $outN")
            it
        } catch (e: Throwable) {
            error = e.message
            Log.e("VoltRobot", "Yüz tanıma modeli yüklenemedi", e)
            null
        }
    }

    /** Yüz resminden normalize edilmiş yüz izi */
    @Synchronized
    fun embed(face: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        val bmp = Bitmap.createScaledBitmap(face, inW, inH, true)
        val input = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        val px = IntArray(inW * inH)
        bmp.getPixels(px, 0, inW, 0, 0, inW, inH)
        for (p in px) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        val out = Array(1) { FloatArray(outN) }
        itp.run(input, out)
        return normalize(out[0])
    }

    companion object {
        fun normalize(v: FloatArray): FloatArray {
            var s = 0f; for (x in v) s += x * x
            val n = sqrt(s).coerceAtLeast(1e-6f)
            return FloatArray(v.size) { v[it] / n }
        }
        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
        }
        fun average(list: List<FloatArray>): FloatArray {
            val r = FloatArray(list[0].size)
            for (v in list) for (i in v.indices) r[i] += v[i]
            return normalize(r)
        }
    }
}

/** Tanıdığı biri */
class Person(
    val id: String,
    var name: String,
    val samples: MutableList<FloatArray> = mutableListOf(),
    val facts: LinkedHashMap<String, String> = LinkedHashMap(),
    var firstMet: Long = System.currentTimeMillis(),
    var lastSeen: Long = 0L,
    var visits: Int = 0,
    var owner: Boolean = false
)

/** Kişi hafızası: telefonda bir dosyada saklanır, internete hiç gitmez. */
class PeopleMemory(ctx: Context) {
    private val file = File(ctx.filesDir, "kisiler.json")
    val people = mutableListOf<Person>()
    private val tr = Locale("tr", "TR")

    init { load() }

    fun best(emb: FloatArray): Pair<Person, Float>? {
        var best: Person? = null; var bestS = -1f
        for (p in people) for (s in p.samples) {
            val c = FaceRecognizer.cosine(emb, s)
            if (c > bestS) { bestS = c; best = p }
        }
        return best?.let { it to bestS }
    }

    fun find(name: String) = people.firstOrNull { it.name.lowercase(tr) == name.lowercase(tr) }

    fun add(name: String, samples: List<FloatArray>): Person {
        val p = Person(id = "p" + System.currentTimeMillis(), name = name, owner = people.isEmpty())
        p.samples.addAll(samples.take(6))
        p.lastSeen = System.currentTimeMillis(); p.visits = 1
        people.add(p); save()
        return p
    }

    /** Yeni bir açıdan görüntü ekle (en fazla 8 örnek) */
    fun learn(p: Person, emb: FloatArray) {
        if (p.samples.any { FaceRecognizer.cosine(it, emb) > 0.92f }) return
        if (p.samples.size >= 8) p.samples.removeAt(1)
        p.samples.add(emb)
        save()
    }

    fun remove(p: Person) { people.remove(p); save() }

    fun save() {
        try {
            val arr = JSONArray()
            for (p in people) {
                val o = JSONObject()
                o.put("id", p.id); o.put("name", p.name); o.put("firstMet", p.firstMet)
                o.put("lastSeen", p.lastSeen); o.put("visits", p.visits); o.put("owner", p.owner)
                o.put("facts", JSONObject(p.facts as Map<*, *>))
                val ss = JSONArray()
                for (s in p.samples) ss.put(JSONArray(s.map { it.toDouble() }))
                o.put("samples", ss)
                arr.put(o)
            }
            file.writeText(arr.toString())
        } catch (e: Exception) { Log.e("VoltRobot", "Kişi hafızası kaydedilemedi", e) }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Person(o.getString("id"), o.getString("name"))
                p.firstMet = o.optLong("firstMet"); p.lastSeen = o.optLong("lastSeen")
                p.visits = o.optInt("visits"); p.owner = o.optBoolean("owner")
                o.optJSONObject("facts")?.let { f -> f.keys().forEach { k -> p.facts[k] = f.getString(k) } }
                val ss = o.getJSONArray("samples")
                for (j in 0 until ss.length()) {
                    val a = ss.getJSONArray(j)
                    p.samples.add(FloatArray(a.length()) { a.getDouble(it).toFloat() })
                }
                people.add(p)
            }
        } catch (e: Exception) { Log.e("VoltRobot", "Kişi hafızası okunamadı", e) }
    }

    /** Yapay zekâya verilecek kısa özet */
    fun describe(p: Person): String {
        val f = p.facts.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        return "Konuşan kişi ${p.name}" + (if (p.owner) " (seni yapan kişi)" else "") + (if (f.isNotEmpty()) ". Onun hakkında bildiklerin: $f" else "")
    }
}
