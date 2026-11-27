package com.elfabrica.dtmoney

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex

internal val bankImportSync = Mutex()

// Compatibility entry point for capture producers; capture writes are already atomic in SQLite.
internal class BankImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString(SCOPE) ?: return Result.failure()
        val settings = AppSettings(applicationContext)
        ImportStore.get(applicationContext).ledger.migrateCaptures(scope, settings.categories(scope))
        schedule(applicationContext, scope)
        return Result.success()
    }
    companion object {
        const val SCOPE = "scope"
        fun schedule(context: Context, scope: String) {
            BankCategoryWorker.schedule(context, scope)
            LedgerSyncWorker.schedule(context, scope)
        }
        fun cancel(context: Context, scope: String) {
            LedgerSyncWorker.cancel(context, scope)
            BankCategoryWorker.cancel(context, scope)
        }
    }
}
