package com.elfabrica.dtmoney

import android.content.Context
import androidx.work.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LedgerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString(BankImportWorker.SCOPE) ?: return Result.failure()
        return try {
            if (synchronize(applicationContext, scope)) Result.success() else Result.retry()
        } catch (error: ApiError) {
            if (error.status == 401 || error.status == 403) Result.failure() else Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        private val mutex = Mutex()
        private fun name(scope: String) = "ledger-sync:$scope"
        private fun periodic(scope: String) = "ledger-periodic:$scope"

        suspend fun synchronize(context: Context, scope: String): Boolean = mutex.withLock {
            val settings = AppSettings(context)
            val credentials = settings.credentials(scope) ?: return@withLock true
            val ledger = ImportStore.get(context).ledger
            val api = MoneyApi(credentials.first, credentials.second.token)
            try {
                repeat(100) {
                    if (settings.credentials(scope) != credentials) return@withLock true
                    val request = ledger.syncRequest(scope)
                    val reply = api.sync(request)
                    ledger.applySync(scope, request, reply)
                    if (!reply.hasMore && !reply.reset && ledger.status(scope).pending == 0) {
                        try { ledger.saveBackupDate(scope, api.lastBackupDate()) } catch (_: IOException) { /* Optional status. */ }
                        return@withLock true
                    }
                }
                false
            } catch (error: IOException) {
                ledger.syncFailed(scope)
                throw error
            }
        }

        fun schedule(context: Context, scope: String) {
            if (AppSettings(context).credentials(scope) == null) return
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val data = workDataOf(BankImportWorker.SCOPE to scope)
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(name(scope), ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LedgerSyncWorker>().setConstraints(constraints).setInputData(data).build())
            manager.enqueueUniquePeriodicWork(periodic(scope), ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LedgerSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints).setInputData(data).build())
        }
        fun cancel(context: Context, scope: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(scope))
            WorkManager.getInstance(context).cancelUniqueWork(periodic(scope))
        }
    }
}
