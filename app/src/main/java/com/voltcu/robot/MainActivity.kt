package com.voltcu.robot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    // --- Parçalar ---
    private lateinit var face: FaceView
    private lateinit var moveLabel: TextView
    private lateinit var subtitle: TextView
    private lateinit var debugText: TextView

    private lateinit var motors: Motors
    private lateinit var seq: MoveSequencer
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private var voice: VoiceInput? = null
    private var llm: LlmEngine? = null
    private lateinit var personality: Personality
    private lateinit var people: PeopleMemory
    private lateinit var social: Social
    private var recognizer: FaceRecognizer? = null
    private var llmStatus = "bekliyor"
    private var tracker: FaceTracker? = null
    private lateinit var body: BodySensors

    private val main = Handler(Looper.getMainLooper())

    // --- Durum ---
    private var sleeping = false
    private var followMode = true
    private var listeningUntil = 0L
    private var tempEmotionUntil = 0L
    private var lastInteraction = SystemClock.uptimeMillis()
    private var lastFaceSeen = 0L
    private var faceVisible = false
    private var lastGreet = 0L
    private var lastSmileReact = 0L
    private var lastFollowCmd = 0L
    private var lastSearchCmd = 0L
    private var searchLeft = true
    private var lastFace: FaceInfo? = null
    private var petDistance = 0f
    private var lastPetReact = 0L
    private var lastStatusLog = 0L
    private var lastSocialTick = 0L
    private var lastSpeechEnd = 0L
    private var lastMove = "-"

    private fun similar(a: String, b: String): Boolean {
        val n = { x: String -> x.lowercase(Locale("tr", "TR")).replace(Regex("[^a-zçğıöşü ]"), "").trim() }
        val x = n(a); val y = n(b)
        return x.isNotEmpty() && (y.contains(x) || x.contains(y))
    }

    private var camStatus = "bekliyor"
    private var voiceStatus = "bekliyor"
    private var ttsStatus = "bekliyor"
    private var lastHeard = "-"
    private var lastSaid = "-"

    private fun log(msg: String) = Log.i("VoltRobot", msg)

    /** Bilgisayardan test komutları (adb): say=metin, debug=1, listen=1 */
    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.getStringExtra("say")?.let { log("TEST komutu: $it"); handleCommand(it) }
            i.getStringExtra("heard")?.let { log("TEST duyma: $it"); onHeard(it) }
            if (i.hasExtra("debug")) toggleDebug()
            if (i.hasExtra("listen")) onTap()
            if (i.hasExtra("pet")) onPetted()
            i.getStringExtra("gesture")?.let { g -> runCatching { face.play(Gesture.valueOf(g)) } }
            i.getStringExtra("emotion")?.let { e -> runCatching { feel(Emotion.valueOf(e), 3000) } }
            if (i.hasExtra("people")) log("KİŞİLER: " + people.people.joinToString(" | ") { p -> "${p.name} (sahibi=${p.owner}, ${p.samples.size} örnek, ${p.visits} ziyaret) ${p.facts}" })
            if (i.hasExtra("forget_all")) { people.people.toList().forEach { people.remove(it) }; log("KİŞİLER silindi") }
            i.getStringExtra("ask")?.let { log("TEST YZ: $it"); askLlm(it) }
            if (i.hasExtra("status")) log("DURUM " + statusText().replace("\n", " | "))
        }
    }

    // Ses modeli "Volt"u çoğu zaman "bot" diye duyuyor, o yüzden benzerleri de kabul
    private val wakeStarts = listOf("volt", "bolt", "vold", "valt", "robot")
    private val wakeExact = setOf("bot", "vot", "bolt", "volt", "polt", "mot", "bol", "volta", "bold", "bal", "val", "vol", "boldu")
    private fun isWake(w: String) = w in wakeExact || wakeStarts.any { w.startsWith(it) }
    private var askedQuestion = false
    /** Robotun kendiliğinden sorduğu son soru (cevabı yapay zekâya bağlamıyla gitsin diye) */
    private var lastRobotQuestion: String? = null
    private var lastRobotQuestionAt = 0L

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true) startCamera() else camStatus = "İZİN YOK"
        if (res[Manifest.permission.RECORD_AUDIO] == true) startVoice() else voiceStatus = "İZİN YOK"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        buildUi()
        hideSystemBars()

        motors = SimulatedMotors { label ->
            // Hareket yazıları sadece test panelinde görünür (gövde takılınca motorlar yapacak)
            if (label != null) lastMove = label
            moveLabel.text = label ?: ""
            moveLabel.visibility = if (label == null || debugText.visibility != View.VISIBLE) View.INVISIBLE else View.VISIBLE
        }
        seq = MoveSequencer(motors)
        brain = RuleBrain({ batteryPercent() }, { faceVisible })
        speaker = Speaker(this,
            onStatus = { ttsStatus = it },
            onStart = { voice?.pause(true) },
            onDone = {
                lastSpeechEnd = SystemClock.uptimeMillis()
                main.postDelayed({
                    if (!speaker.speaking && llm?.busy != true) {
                        voice?.pause(false)
                        // Robot soru sorduysa cevabı "Volt" demeden dinle
                        // Robot konuştuktan sonra kısa süre "Volt" demeden cevap verilebilir (sohbet akışı)
                        val window = if (askedQuestion) 9000L else 6000L
                        askedQuestion = false
                        if (!sleeping) { listeningUntil = SystemClock.uptimeMillis() + window; if (face.emotion != Emotion.LISTENING && SystemClock.uptimeMillis() > tempEmotionUntil) face.emotion = Emotion.LISTENING }
                    }
                }, 350)
            }
        )
        body = BodySensors(this) { onBodyEvent(it) }

        people = PeopleMemory(this)
        social = Social(people, object : Social.Host {
            override fun now() = SystemClock.uptimeMillis()
            override fun say(text: String, e: Emotion?) { this@MainActivity.say(text, e) }
            override fun isBusy() = speaker.speaking || llm?.busy == true
            override fun isSleeping() = sleeping
            override fun faceVisible() = faceVisible
            override fun log(msg: String) { this@MainActivity.log(msg) }
        })
        Thread { recognizer = FaceRecognizer(this).also { r -> log(if (r.ready) "Yüz tanıma hazır, ${people.people.size} kişi kayıtlı" else "Yüz tanıma YOK: ${r.error}") } }.start()

        personality = Personality(object : Personality.Host {
            override val face get() = this@MainActivity.face
            override fun now() = SystemClock.uptimeMillis()
            override fun isFaceVisible() = faceVisible
            override fun lastFaceSeen() = lastFaceSeen
            override fun lastInteraction() = lastInteraction
            override fun isSleeping() = sleeping
            override fun isBusy() = speaker.speaking || isListening() || llm?.busy == true || seq.isBusy() || (::social.isInitialized && social.inDialog())
            override fun feel(e: Emotion, ms: Long) { this@MainActivity.feel(e, ms) }
            override fun mutter(text: String, e: Emotion?) { say(text, e) }
            override fun move(vararg steps: Pair<Move, Long>) { if (!sleeping) seq.play(*steps) }
            override fun log(msg: String) { this@MainActivity.log(msg) }
        })

        // Yapay zekâ beyni: telefonda Android/data/com.voltcu.robot/files/models/volt.gguf
        val modelDir = java.io.File(getExternalFilesDir(null), "models").apply { mkdirs() }
        llm = LlmEngine(java.io.File(modelDir, "volt.gguf")) { st -> llmStatus = st; log("YZ: $st") }.also { it.load() }

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { startCamera(); startVoice() } else permLauncher.launch(missing.toTypedArray())

        main.post(tick)
        ContextCompat.registerReceiver(this, testReceiver, IntentFilter("com.voltcu.robot.TEST"), ContextCompat.RECEIVER_EXPORTED)
        log("Volt başladı v0.8")
    }

    // ---------------- Arayüz ----------------
    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        face = FaceView(this)
        root.addView(face, FrameLayout.LayoutParams(-1, -1))

        moveLabel = TextView(this).apply {
            setTextColor(0xFFFFC23D.toInt()); textSize = 26f; isFocusable = false
            setShadowLayer(8f, 0f, 0f, Color.BLACK); visibility = View.INVISIBLE
        }
        root.addView(moveLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24 })

        subtitle = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt()); textSize = 17f; gravity = Gravity.CENTER
            setPadding(40, 0, 40, 0)
        }
        root.addView(subtitle, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = 24 })

        debugText = TextView(this).apply {
            setTextColor(0xFFB0FFB0.toInt()); textSize = 12f; setBackgroundColor(0xAA000000.toInt())
            setPadding(16, 12, 16, 12); visibility = View.GONE; typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(debugText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        setContentView(root)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { onBoop(); return true }
            override fun onLongPress(e: MotionEvent) { toggleDebug() }
        })
        var lx = 0f; var ly = 0f
        face.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = ev.x; ly = ev.y; petDistance = 0f }
                MotionEvent.ACTION_MOVE -> {
                    petDistance += hypot(ev.x - lx, ev.y - ly); lx = ev.x; ly = ev.y
                    if (petDistance > 900f) { petDistance = 0f; onPetted() }
                }
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun toggleDebug() {
        val show = debugText.visibility != View.VISIBLE
        debugText.visibility = if (show) View.VISIBLE else View.GONE
        face.showDebug = show
    }

    // ---------------- Başlatma ----------------
    private fun startCamera() {
        if (tracker != null) return
        tracker = FaceTracker(this, this, { onFace(it) }, { camStatus = it }).also { t ->
            t.wantCrop = { recognizer?.ready == true && ::social.isInitialized && social.wantsEmbedding() }
            t.onCrop = { bmp, _ ->
                val emb = try { recognizer?.embed(bmp) } catch (e: Throwable) { null }
                if (emb != null) main.post { social.onEmbedding(emb) }
            }
            t.start()
        }
    }

    private fun startVoice() {
        if (voice != null) return
        voice = VoiceInput(this,
            onText = { onHeard(it) },
            onPartial = { p -> if (isListening()) subtitle.text = "… $p" },
            onStatus = { voiceStatus = it }
        ).also { it.init() }
    }

    // ---------------- Duygu ----------------
    private fun baseEmotion(): Emotion = when {
        sleeping -> Emotion.ASLEEP
        isListening() -> Emotion.LISTENING
        ::personality.isInitialized && personality.mood == Personality.Mood.DROWSY -> Emotion.DROWSY
        else -> Emotion.NEUTRAL
    }

    private fun feel(e: Emotion, ms: Long = 2500) {
        face.emotion = e
        tempEmotionUntil = SystemClock.uptimeMillis() + ms
    }

    private fun isListening() = SystemClock.uptimeMillis() < listeningUntil

    // ---------------- Konuşma ----------------
    private val clearSubtitle = Runnable { if (!speaker.speaking) subtitle.text = "" }

    private fun say(text: String, e: Emotion? = null) {
        main.removeCallbacks(clearSubtitle); main.postDelayed(clearSubtitle, 7000L + text.length * 60L)
        askedQuestion = text.trim().endsWith("?")
        if (askedQuestion) { lastRobotQuestion = text; lastRobotQuestionAt = SystemClock.uptimeMillis() }
        log("SÖYLE: $text")
        lastSaid = text
        subtitle.text = "Volt: $text"
        if (e != null) feel(e, 1500L + text.length * 60L)
        speaker.say(text)
    }

    private fun startListeningWindow(quiet: Boolean = false) {
        listeningUntil = SystemClock.uptimeMillis() + 7000
        face.emotion = Emotion.LISTENING
        face.blink()
        if (!quiet) subtitle.text = "Dinliyorum…"
    }

    private fun onHeard(raw: String) {
        log("DUYDU: $raw (konuşuyor=${speaker.speaking}, dinleme=${isListening()})")
        if (speaker.speaking) return
        val text = raw.lowercase(Locale("tr", "TR"))
        val words = text.split(" ")
        // Kendi sesinin yankısını yok say
        if (SystemClock.uptimeMillis() - lastSpeechEnd < 1500 && similar(text, lastSaid)) { log("Yankı yok sayıldı"); return }
        // Tanışma sohbeti sürüyorsa her cevap ona gider ("Volt" gerekmez)
        if (::social.isInitialized && social.inDialog()) { lastHeard = text; lastInteraction = SystemClock.uptimeMillis(); subtitle.text = "Sen: $text"; try { social.answer(text) } catch (e: Exception) { log("HATA tanışma: $e") }; return }
        val wakeIdx = words.indexOfFirst { isWake(it) }

        if (wakeIdx >= 0) {
            if (sleeping) wakeUp(silent = true)
            val rest = words.drop(wakeIdx + 1).filter { it != "hey" }.joinToString(" ")
            lastHeard = text
            if (rest.isBlank()) {
                startListeningWindow()
                say(listOf("Efendim?", "Evet?", "Dinliyorum!").random(), Emotion.LISTENING)
                listeningUntil = SystemClock.uptimeMillis() + 9000
                return
            }
            handleCommand(rest)
            return
        }
        if (isListening()) {
            lastHeard = text
            handleCommand(text)
            return
        }
        // Biri robota bakıyorsa "Volt" demeden de konuşulabilir
        if (!sleeping && isLookedAt()) {
            log("Yüzüne bakılarak konuşuldu, uyandırma kelimesi gerekmedi")
            lastHeard = text
            handleCommand(text)
        }
    }

    /** Biri şu an kameranın önünde ve robota dönük mü */
    private fun isLookedAt(): Boolean {
        val f = lastFace ?: return false
        val fresh = SystemClock.uptimeMillis() - lastFaceSeen < 1500
        return fresh && faceVisible && kotlin.math.abs(f.nx) < 0.65f && f.size > 0.08f
    }

    private fun handleCommand(text: String) {
        listeningUntil = 0
        lastInteraction = SystemClock.uptimeMillis()
        subtitle.text = "Sen: $text"
        if (social.intercept(text)) return
        val reply = brain.think(text)
        when (reply.action) {
            Action.SLEEP -> { say(reply.text, reply.emotion); main.postDelayed({ goToSleep() }, 2500); return }
            Action.WAKE -> wakeUp(silent = true)
            Action.FOLLOW_ON -> followMode = true
            Action.FOLLOW_OFF -> { followMode = false; seq.cancel() }
            Action.STOP -> { seq.cancel(); followMode = false }
            null -> {}
        }
        if (reply.moves.isNotEmpty()) seq.play(*reply.moves.toTypedArray())
        if (reply.fallback && llm?.state == LlmEngine.State.READY) {
            val q = lastRobotQuestion
            val ctx = if (q != null && SystemClock.uptimeMillis() - lastRobotQuestionAt < 30_000) "(Az önce ben ona şunu sordum: \"$q\") $text" else text
            lastRobotQuestion = null
            askLlm(ctx); return
        }
        if (reply.fallback && llm?.state == LlmEngine.State.LOADING) {
            say("Daha yeni uyandım, beynim ısınıyor. Bir dakika sonra tekrar sorar mısın?", Emotion.SLEEPY); return
        }
        main.postDelayed({ say(reply.text, reply.emotion) }, 150)
    }

    /** Kuralların bilmediği soruları yapay zekâ cevaplar (internetsiz, telefonda) */
    private fun askLlm(question: String) {
        val who = if (::social.isInitialized) social.context() else null
        val engine = llm ?: return
        if (engine.state != LlmEngine.State.READY) { say("Beynim daha hazır değil.", Emotion.SAD); return }
        if (engine.busy) { say("Bir saniye, hâlâ düşünüyorum.", Emotion.THINKING); return }
        lastInteraction = SystemClock.uptimeMillis()
        tracker?.paused = true
        tracker?.stop()   // kamerayı tamamen kapat: işlemci ve ısı yapay zekâya kalsın
        voice?.pause(true)
        face.emotion = Emotion.THINKING
        tempEmotionUntil = SystemClock.uptimeMillis() + 120_000
        say(listOf("Hmm, bir düşüneyim.", "Dur bakalım, düşünüyorum.", "Güzel soru, düşüneyim.").random())
        val started = SystemClock.uptimeMillis()
        var first = true
        engine.ask(question, startContext = who,
            onSentence = { s ->
                if (first) { first = false; log("YZ ilk cümle ${(SystemClock.uptimeMillis() - started) / 1000} sn'de"); face.emotion = Emotion.HAPPY }
                lastSaid = s
                askedQuestion = s.endsWith("?")
                subtitle.text = "Volt: $s"
                speaker.queue(s)
                log("SÖYLE(YZ): $s")
            },
            onDone = { n ->
                log("YZ bitti: $n token, ${(SystemClock.uptimeMillis() - started) / 1000} sn")
                tracker?.paused = false
                tracker?.start()
                tempEmotionUntil = SystemClock.uptimeMillis() + 1500
                if (first) say("Hmm, aklıma bir şey gelmedi.", Emotion.SAD)
                if (!speaker.speaking) voice?.pause(false)
                lastInteraction = SystemClock.uptimeMillis()
            })
    }

    // ---------------- Dokunma ----------------
    private fun onTap() {
        lastInteraction = SystemClock.uptimeMillis()
        if (sleeping) { wakeUp(); return }
        if (voice?.ready == true) startListeningWindow()
        else say("Kulaklarım daha hazır değil.", Emotion.SAD)
    }

    private fun onBoop() {
        lastInteraction = SystemClock.uptimeMillis()
        if (sleeping) wakeUp(silent = true)
        say(listOf("Hey, gıdıklanıyorum!", "Burnum o benim!", "Hi hi!").random(), Emotion.HAPPY)
    }

    private fun onPetted() {
        val now = SystemClock.uptimeMillis()
        lastInteraction = now
        if (now - lastPetReact < 3000) { feel(Emotion.LOVE, 2000); return }
        lastPetReact = now
        if (sleeping) { feel(Emotion.LOVE, 2000); return }
        say(listOf("Çok hoşuma gidiyor!", "Mırr… yani, bip bip!", "Biraz daha sev beni!").random(), Emotion.LOVE)
    }

    // ---------------- Vücut sensörleri ----------------
    private fun onBodyEvent(e: BodyEvent) {
        log("SENSÖR: ${e.label}")
        lastInteraction = SystemClock.uptimeMillis()
        if (sleeping && e != BodyEvent.PUT_DOWN) wakeUp(silent = true)
        when (e) {
            BodyEvent.SHAKEN -> { seq.cancel(); say(listOf("Başım döndü!", "Dur, dur, sallama beni!").random(), Emotion.DIZZY) }
            BodyEvent.LIFTED -> { seq.cancel(); say(listOf("Beni nereye götürüyorsun?", "Uçuyorum!").random(), Emotion.SURPRISED) }
            BodyEvent.PUT_DOWN -> if (!sleeping) say("Oh, yere bastım.", Emotion.HAPPY)
            BodyEvent.FREEFALL -> { seq.cancel(); say("Aaaa düşüyorum!", Emotion.SCARED) }
            BodyEvent.UPSIDE_DOWN -> say("Dünya ters döndü! Beni düzeltir misin?", Emotion.DIZZY)
        }
    }

    // ---------------- Uyku ----------------
    private fun goToSleep() {
        sleeping = true
        listeningUntil = 0
        seq.cancel()
        face.sleepingZ = true
        face.emotion = Emotion.ASLEEP
        face.play(Gesture.YAWN)
    }

    private fun wakeUp(silent: Boolean = false) {
        sleeping = false
        face.sleepingZ = false
        lastInteraction = SystemClock.uptimeMillis()
        feel(Emotion.SURPRISED, 1200)
        if (!silent) say("Uyandım! Buradayım.")
    }

    // ---------------- Yüz takibi ----------------
    private fun onFace(f: FaceInfo?) {
        val now = SystemClock.uptimeMillis()
        lastFace = f
        face.debugFace = f?.box
        if (f == null) {
            if (faceVisible && now - lastFaceSeen > 1500) faceVisible = false
            return
        }
        val wasAbsentFor = now - lastFaceSeen
        lastFaceSeen = now
        faceVisible = true
        if (sleeping) return

        face.lookAt(f.nx, f.ny * 0.8f)

        // Uzun süre sonra biri gelince selam ver
        if (recognizer?.ready != true && wasAbsentFor > 20_000 && now - lastGreet > 60_000 && !speaker.speaking) {
            lastGreet = now
            lastInteraction = now
            say(listOf("Merhaba!", "Selam! Seni görüyorum.", "Hoş geldin!").random(), Emotion.HAPPY)
        }
        // Gülümseyene gülümse
        val smile = f.smile
        if (smile != null && smile > 0.75f && now - lastSmileReact > 8000 && now > tempEmotionUntil) {
            lastSmileReact = now
            feel(Emotion.HAPPY, 2000)
        }

        // Takip: robot bu yönlere dönerdi (şimdilik ekranda gösteriliyor)
        if (followMode && !seq.isBusy() && now - lastFollowCmd > 700) {
            val cmd = when {
                f.nx < -0.35f -> Move.TURN_RIGHT   // kişi robotun sağında
                f.nx > 0.35f -> Move.TURN_LEFT     // kişi robotun solunda
                f.ny < -0.45f -> Move.HEAD_UP
                f.ny > 0.45f -> Move.HEAD_DOWN
                f.size < 0.14f -> Move.FORWARD     // uzakta, yaklaş
                f.size > 0.45f -> Move.BACKWARD    // çok yakın, geri çekil
                else -> null
            }
            if (cmd != null) { lastFollowCmd = now; motors.send(cmd, 400) }
        }
    }

    // ---------------- Ana döngü ----------------
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()

            if (now > tempEmotionUntil && !speaker.speaking) {
                val b = baseEmotion()
                if (face.emotion != b) face.emotion = b
            }
            if (listeningUntil in 1 until now) {
                listeningUntil = 0
                if (subtitle.text.startsWith("Dinliyorum") || subtitle.text.startsWith("…")) subtitle.text = ""
            }

            // Kayıp kişiyi arama
            if (!sleeping && followMode && !faceVisible && lastFaceSeen > 0) {
                val lost = now - lastFaceSeen
                if (lost in 4000..20000 && now - lastSearchCmd > 3000 && !seq.isBusy()) {
                    lastSearchCmd = now
                    searchLeft = !searchLeft
                    motors.send(if (searchLeft) Move.TURN_LEFT else Move.TURN_RIGHT, 700)
                }
            }

            personality.update()
            if (now - lastSocialTick > 1000) { lastSocialTick = now; social.tick() }

            // 3 dakika kimse yoksa uyu
            if (!sleeping && !faceVisible && now - lastInteraction > 180_000 && now - lastFaceSeen > 180_000) goToSleep()

            if (debugText.visibility == View.VISIBLE) debugText.text = statusText()
            if (now - lastStatusLog > 3000) { lastStatusLog = now; log("DURUM " + statusText().replace("\n", " | ")) }
            main.postDelayed(this, 250)
        }
    }

    private fun statusText(): String {
        val f = lastFace
        val faceLine = if (f != null && faceVisible)
            "VAR (${f.count}) x=%.2f y=%.2f boyut=%.2f gülüş=%s".format(f.nx, f.ny, f.size, f.smile?.let { "%.2f".format(it) } ?: "-")
        else "yok"
        return buildString {
            appendLine("── VOLT SİSTEM TESTİ v0.8 ──")
            appendLine("Kamera     : $camStatus  (%.1f fps)".format(tracker?.fps ?: 0f))
            appendLine("Yüz        : $faceLine")
            appendLine("Ses tanıma : $voiceStatus")
            appendLine("Dinleme    : ${if (isListening()) "AÇIK" else "uyandırma kelimesi bekleniyor"}")
            appendLine("Konuşma    : $ttsStatus")
            appendLine("Yapay zekâ : $llmStatus${if (llm?.busy == true) " (düşünüyor)" else ""}")
            appendLine("Sensör     : ${if (body.available) "ivme ölçer OK" else "YOK"}  |a|=%.1f  son: ${body.lastEvent}".format(body.magnitude))
            appendLine("Motor      : ${motors.name}  son: $lastMove")
            appendLine("Tanıdığı   : ${if (recognizer?.ready == true) social.status() else "yüz tanıma kapalı"}")
            appendLine("Ruh hali   : ${personality.mood.label}  son: ${personality.lastAction}")
            appendLine("Takip modu : ${if (followMode) "açık" else "kapalı"}   Uyku: ${if (sleeping) "evet" else "hayır"}")
            appendLine("Pil        : %${batteryPercent()}")
            appendLine("Duydu      : $lastHeard")
            append("Söyledi    : $lastSaid")
        }
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // ---------------- Yaşam döngüsü ----------------
    override fun onResume() {
        super.onResume()
        body.start()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        body.stop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(testReceiver) } catch (_: Exception) {}
        seq.cancel()
        tracker?.release()
        llm?.stop()
        voice?.release()
        speaker.release()
        motors.release()
        super.onDestroy()
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.01f
}
