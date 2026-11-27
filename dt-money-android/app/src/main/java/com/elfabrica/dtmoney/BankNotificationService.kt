package com.elfabrica.dtmoney

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

@Volatile internal var bankModelForeground = false

class BankNotificationService : NotificationListenerService() {
    private data class ActiveBankEvent(val scope: String, val time: Instant)
    private val activeBankEvents = mutableMapOf<String, ActiveBankEvent>()

    override fun onListenerConnected() {
        activeBankEvents.keys.retainAll(activeNotifications.orEmpty().mapTo(mutableSetOf()) { it.key })
        AppSettings(this).captureConfig()?.let { BankImportWorker.schedule(applicationContext, it.scope) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeBankEvents.remove(sbn.key)
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
        val posted = Instant.ofEpochMilli(timestamp)
        val eventInstant = Instant.ofEpochMilli(eventTime)
        val identity = if (automatic && config.accepts(bank, posted)) {
            val event = activeBankEvents.getOrPut(key) { ActiveBankEvent(config.scope, eventInstant) }
            if (event.scope != config.scope) return
            event.time
        } else eventInstant
        executor.execute {
            try {
                if (automatic) {
                    if (!config.accepts(bank, posted)) return@execute
                    captureBankAlert(settings, config, BankAlert(bank, title, body, key, posted, eventInstant, identity))
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
                Log.w("DTMoney", "Não foi possível salvar uma captura bancária.")
            }
        }
    }

    private fun captureBankAlert(settings: AppSettings, config: CaptureConfig, alert: BankAlert) {
        if (isExcludedBankAlert(alert)) return
        val context = applicationContext
        val store = ImportStore.get(context)
        val movement = parseBankMovement(alert)
        if (movement != null) {
            settings.withCapture(config) {
                if (store.recordCaptured(config.scope, bankEventId(alert), movement)) {
                    BankImportWorker.schedule(context, config.scope)
                }
            }
            return
        }

        val prepared = prepareBankAlert(alert) ?: return
        val id = prepared.review.id
        var revision: Long? = null
        settings.withCapture(config) { revision = store.saveBankReview(config.scope, prepared.review) }
        val currentRevision = revision ?: return
        if (!interpretationSlot.tryAcquire()) {
            settings.withCapture(config) {
                store.updateBankReview(config.scope, id, currentRevision, ReviewReason.MODEL_UNAVAILABLE, null)
            }
            return
        }
        inferenceScope.launch {
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                val component = ComponentName(context, BankNotificationService::class.java)
                fun active() = settings.captureConfig() == config && manager.isNotificationListenerAccessGranted(component)

                val categories = store.ledger.categories(config.scope)
                val result = interpretBankAlert(
                    active = ::active,
                    primary = { FunctionGemmaClassifier(context).interpret(prepared, categories) },
                    fallback = {
                        if (bankModelForeground) GeminiNanoClassifier().interpret(prepared, categories)
                        else BankModelResult.Unavailable
                    }
                ) ?: return@launch
                val decision = validateBankProposal(prepared, (result as? BankModelResult.Proposed)?.proposal, categories)
                settings.withCapture(config) {
                    if (!manager.isNotificationListenerAccessGranted(component)) return@withCapture
                    val inferred = decision.movement
                    if (inferred != null) {
                        if (store.recordCaptured(config.scope, id, inferred, decision.categoryId, currentRevision)) {
                            BankImportWorker.schedule(context, config.scope)
                        }
                    } else {
                        val reason = if (result == BankModelResult.Unavailable) ReviewReason.MODEL_UNAVAILABLE
                        else ReviewReason.NEEDS_CONFIRMATION
                        store.updateBankReview(config.scope, id, currentRevision, reason, decision.categoryId)
                    }
                }
            } catch (_: android.database.SQLException) {
                Log.w("DTMoney", "Não foi possível atualizar uma captura bancária.")
            } finally {
                interpretationSlot.release()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private val inferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val interpretationSlot = Semaphore(4)
    }
}
