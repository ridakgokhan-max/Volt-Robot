package com.voltcu.robot

import android.os.Handler
import android.os.Looper

/** Robotun yapabileceği hareketler. Gövde bağlandığında aynı komutlar ESP32'ye gidecek. */
enum class Move(val label: String, val code: String) {
    FORWARD("⬆ İLERİ", "F"),
    BACKWARD("⬇ GERİ", "B"),
    TURN_LEFT("⬅ SOLA DÖN", "L"),
    TURN_RIGHT("➡ SAĞA DÖN", "R"),
    HEAD_UP("⤴ KAFA YUKARI", "HU"),
    HEAD_DOWN("⤵ KAFA AŞAĞI", "HD"),
    LIFT_UP("🔼 KOL YUKARI", "AU"),
    LIFT_DOWN("🔽 KOL AŞAĞI", "AD"),
    STOP("■ DUR", "S")
}

/** Motor sürücü arayüzü. Şimdilik simülasyon; sonra BluetoothMotors (ESP32) eklenecek. */
interface Motors {
    val name: String
    fun send(move: Move, durationMs: Long = 600)
    fun stop() = send(Move.STOP, 0)
    fun release() {}
}

/** Hareketleri gerçek motor yerine ekranda gösterir. */
class SimulatedMotors(private val show: (String?) -> Unit) : Motors {
    override val name = "Simülasyon (ekranda)"
    private val handler = Handler(Looper.getMainLooper())
    private val clear = Runnable { show(null) }

    override fun send(move: Move, durationMs: Long) {
        handler.removeCallbacks(clear)
        if (move == Move.STOP) { show(move.label); handler.postDelayed(clear, 500); return }
        show(move.label)
        handler.postDelayed(clear, durationMs.coerceAtLeast(300))
    }
}

/** Birden fazla hareketi sırayla oynatır (dans, arama gibi). */
class MoveSequencer(private val motors: Motors) {
    private val handler = Handler(Looper.getMainLooper())
    var busyUntil = 0L
        private set

    fun play(vararg steps: Pair<Move, Long>) {
        handler.removeCallbacksAndMessages(null)
        var t = 0L
        for ((m, d) in steps) {
            handler.postDelayed({ motors.send(m, d) }, t)
            t += d + 80
        }
        handler.postDelayed({ motors.stop() }, t)
        busyUntil = System.currentTimeMillis() + t
    }

    fun isBusy() = System.currentTimeMillis() < busyUntil

    fun cancel() { handler.removeCallbacksAndMessages(null); busyUntil = 0; motors.stop() }
}
