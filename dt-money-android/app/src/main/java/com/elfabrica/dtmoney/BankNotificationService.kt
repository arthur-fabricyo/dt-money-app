package com.elfabrica.dtmoney

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.Executors

class BankNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = AppSettings(this)
        val config = settings.captureConfig() ?: return
        if (sbn.packageName != config.packageName) return

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val message = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trimEnd(':')
        val purchase = parseBankPurchase(message.toString())
            ?: parseBankPurchase("$title: $message")
            ?: return
        val timestamp = sbn.postTime
        val eventTime = notification.`when`.takeIf { it > 0 } ?: timestamp
        executor.execute {
            if (settings.captureConfig() != config) return@execute
            try {
                val rawKey = "${sbn.key}:$eventTime:${purchase.cents}:${purchase.description}"
                val id = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                ImportStore.get(applicationContext).add(config.scope, id, purchase, timestamp)
            } catch (_: android.database.SQLException) {
                Log.w("DTMoney", "Não foi possível salvar uma compra detectada.")
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
