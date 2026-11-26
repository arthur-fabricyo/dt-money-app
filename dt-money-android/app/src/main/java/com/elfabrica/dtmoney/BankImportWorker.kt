package com.elfabrica.dtmoney

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Serialize snapshot probes and POSTs on the client so a snapshot never sees
// an ambiguous send without that import's key in its probe.
internal val bankImportSync = Mutex()

internal class BankImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString(SCOPE) ?: return Result.failure()
        val settings = AppSettings(applicationContext)
        val store = ImportStore.get(applicationContext)
        if (settings.credentials(scope) == null) return Result.success()

        return try {
            val initial = store.localImports(scope)
            if (initial.none(::needsSync)) return Result.success()
            val (url, session) = settings.credentials(scope) ?: return Result.success()
            val api = MoneyApi(url, session.token)
            val categories = api.categories()
            val uncategorized = categories.firstOrNull { it.name == UNCATEGORIZED } ?: return Result.retry()
            settings.saveCategories(scope, categories)
            BankCategoryWorker.schedule(applicationContext, scope)
            if (api.transactions(1, Filters()).latestTransactionId == null) return Result.retry()
            for (entry in initial.filter(::needsSync)) {
                bankImportSync.withLock {
                    currentCoroutineContext().ensureActive()
                    if (settings.credentials(scope) != (url to session)) return Result.success()
                    val importId = entry.movement.importId
                    try {
                        if (entry.remoteId == null) {
                            store.markSent(scope, importId)
                            val receipt = api.createImport(entry, uncategorized.id)
                            store.acknowledge(scope, receipt, null)
                            if (receipt.deleted) return@withLock
                        }
                        val categoryId = entry.categoryId
                        if (entry.categoryPending && categoryId != null &&
                            settings.credentials(scope) == (url to session)) {
                            val receipt = api.updateImportCategory(importId, categoryId, entry.categorySource)
                            store.acknowledge(scope, receipt, entry.revision)
                        }
                    } catch (error: ApiError) {
                        if (error.status !in setOf(400, 403, 404, 422)) throw error
                        store.rejectSync(scope, entry)
                    }
                }
            }
            Result.success()
        } catch (error: ApiError) {
            if (error.status == 401) Result.success() else Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private fun needsSync(entry: LocalImport) =
        !entry.deleted && !entry.syncRejected && (entry.remoteId == null || entry.categoryPending)

    companion object {
        const val SCOPE = "scope"
        private fun name(scope: String) = "bank-sync:$scope"

        fun schedule(context: Context, scope: String) {
            val request = OneTimeWorkRequestBuilder<BankImportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(SCOPE to scope))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(scope), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            BankCategoryWorker.schedule(context, scope)
        }

        fun cancel(context: Context, scope: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(scope))
            BankCategoryWorker.cancel(context, scope)
        }
    }
}
