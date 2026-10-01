package com.voltcu.robot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon açıldığında robotu otomatik başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (_: Exception) {}
        }
    }
}
