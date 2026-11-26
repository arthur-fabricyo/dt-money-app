package com.elfabrica.dtmoney

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.time.Instant

class BankNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        AppSettings(this).captureConfig()?.let { BankImportWorker.schedule(applicationContext, it.scope) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = AppSettings(this)
        val config = settings.captureConfig() ?: return
        val bank = Bank.entries.find { it.packageName == sbn.packageName }
        val automatic = bank != null && bank in config.enabledSince
        if (!automatic && sbn.packageName != config.legacyPackage) return

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val message = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trimEnd(':')
        val body = message.toString()
        val key = sbn.key
        val timestamp = sbn.postTime
        val eventTime = notification.`when`.takeIf { it > 0 } ?: timestamp
        executor.execute {
            try {
                val posted = Instant.ofEpochMilli(timestamp)
                if (automatic) {
                    if (!config.accepts(bank, posted)) return@execute
                    val movement = parseBankMovement(
                        BankAlert(bank, title, body, key, posted, Instant.ofEpochMilli(eventTime))
                    ) ?: return@execute
                    settings.withCapture(config) {
                        ImportStore.get(applicationContext).record(config.scope, movement)
                        BankImportWorker.schedule(applicationContext, config.scope)
                    }
                } else {
                    val purchase = parseBankPurchase(body) ?: parseBankPurchase("$title: $body") ?: return@execute
                    val rawKey = listOf(key, eventTime, purchase.cents, purchase.description).joinToString(":")
                    val id = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    settings.withCapture(config) {
                        ImportStore.get(applicationContext).add(config.scope, id, purchase, timestamp)
                    }
                }
            } catch (_: android.database.SQLException) {
                Log.w("DTMoney", "Não foi possível salvar uma compra detectada.")
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
