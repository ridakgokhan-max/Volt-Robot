package com.voltcu.robot

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

enum class BodyEvent(val label: String) {
    SHAKEN("Sallandı"), LIFTED("Kaldırıldı"), PUT_DOWN("Yere kondu"),
    FREEFALL("Düşüyor!"), UPSIDE_DOWN("Ters çevrildi")
}

/** Telefonun ivme ölçeriyle sallanma, kaldırılma, düşme ve ters çevrilme algısı. */
class BodySensors(ctx: Context, private val onEvent: (BodyEvent) -> Unit) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val acc: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available get() = acc != null

    var lastEvent: String = "-"
        private set
    var magnitude = 0f
        private set

    private val g = SensorManager.GRAVITY_EARTH
    private var lastEventAt = HashMap<BodyEvent, Long>()

    private var freefallStart = 0L
    private val shakePeaks = ArrayDeque<Long>()
    private var lastPeak = 0L

    private var stillSince = SystemClock.uptimeMillis()
    private var movingSince = 0L
    private var held = false

    private var baseX = 0f; private var baseY = 0f; private var baseZ = 0f; private var hasBase = false
    private var upsideSince = 0L

    fun start() { acc?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm.unregisterListener(this) }

    private fun fire(e: BodyEvent, cooldown: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - (lastEventAt[e] ?: 0L) < cooldown) return
        lastEventAt[e] = now
        lastEvent = e.label
        onEvent(e)
    }

    override fun onSensorChanged(ev: SensorEvent) {
        val x = ev.values[0]; val y = ev.values[1]; val z = ev.values[2]
        val now = SystemClock.uptimeMillis()
        val mag = sqrt(x * x + y * y + z * z)
        magnitude = mag
        val dev = abs(mag - g)

        // Serbest düşme
        if (mag < 3f) {
            if (freefallStart == 0L) freefallStart = now
            else if (now - freefallStart > 80) fire(BodyEvent.FREEFALL, 2000)
        } else freefallStart = 0L

        // Sallama: kısa sürede birkaç güçlü vuruş
        if (dev > 9f && now - lastPeak > 120) {
            lastPeak = now
            shakePeaks.addLast(now)
            while (shakePeaks.isNotEmpty() && now - shakePeaks.first() > 900) shakePeaks.removeFirst()
            if (shakePeaks.size >= 3) { shakePeaks.clear(); fire(BodyEvent.SHAKEN, 2500) }
        }

        // Kaldırılma / yere konma
        if (dev > 1.2f) {
            if (movingSince == 0L) movingSince = now
            stillSince = now
            if (!held && now - movingSince > 350 && (now - (lastEventAt[BodyEvent.SHAKEN] ?: 0L)) > 1500) {
                held = true
                fire(BodyEvent.LIFTED, 4000)
            }
        } else if (dev < 0.35f) {
            if (now - stillSince > 400) movingSince = 0L
            if (held && now - stillSince > 2500) {
                held = false
                fire(BodyEvent.PUT_DOWN, 3000)
            }
            // Duruşu kaydet (normal duruş)
            if (!hasBase && now - stillSince > 1500) {
                baseX = x; baseY = y; baseZ = z; hasBase = true
            }
        }

        // Ters çevrilme: yerçekimi yönü normal duruşun tersine döndüyse
        if (hasBase) {
            val dot = (x * baseX + y * baseY + z * baseZ) / (g * g)
            if (dot < -0.5f) {
                if (upsideSince == 0L) upsideSince = now
                else if (now - upsideSince > 700) fire(BodyEvent.UPSIDE_DOWN, 5000)
            } else upsideSince = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
