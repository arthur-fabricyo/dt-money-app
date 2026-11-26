package com.elfabrica.dtmoney

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.sync.withLock

internal data class AppState(
    val baseUrl: String,
    val session: Session? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val categories: List<Category> = emptyList(),
    val filters: Filters = Filters(),
    val transactions: TransactionPage? = null,
    val drafts: List<PurchaseDraft> = emptyList(),
    val bankReviews: List<BankReview> = emptyList(),
    val bankPackage: String = "",
    val enabledBanks: Set<Bank> = emptySet(),
    val functionGemmaInstalled: Boolean = false
)

internal class MoneyViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val context = application
    private val store = ImportStore.get(application)
    private val mutableState = MutableStateFlow(AppState(settings.baseUrl, settings.loadSession()))
    val state = mutableState.asStateFlow()
    private val snapshots = mutableMapOf<Filters, TransactionPage>()

    init {
        viewModelScope.launch {
            store.changes.collectLatest {
                val session = state.value.session ?: return@collectLatest
                loadDrafts(session)
                showLocalDashboard(session)
            }
        }
        viewModelScope.launch {
            store.receipts.collectLatest {
                delay(350)
                val session = state.value.session ?: return@collectLatest
                try {
                    loadSnapshot(session, 1)
                } catch (_: IOException) {
                    // The last complete snapshot and local rows remain visible.
                }
            }
        }
        if (state.value.session != null) refresh()
    }

    fun authenticate(address: String, email: String, password: String, name: String?) = perform {
        val url = address.trim().toHttpUrlOrNull()
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || (!BuildConfig.DEBUG && !url.isHttps)
        ) {
            message("Informe a URL da API. A versão de produção exige HTTPS.")
            return@perform
        }
        val baseUrl = url.toString().trimEnd('/')
        val session = MoneyApi(baseUrl).authenticate(email.trim(), password, name?.trim())
        settings.saveSession(baseUrl, session)
        snapshots.clear()
        mutableState.value = AppState(baseUrl, session, busy = true)
        BankImportWorker.schedule(context, settings.scope(session))
        loadDrafts(session)
        loadDashboard(session, 1)
    }

    fun signOut() {
        if (!state.value.busy) clearSession()
    }

    fun refresh() = perform {
        val session = state.value.session ?: return@perform
        BankImportWorker.schedule(context, settings.scope(session))
        loadDrafts(session)
        loadDashboard(session, 1)
    }

    fun filter(filters: Filters) = perform {
        val session = state.value.session ?: return@perform
        mutableState.update { it.copy(filters = filters) }
        showLocalDashboard(session)
        loadSnapshot(session, 1)
    }

    fun loadMore() = perform {
        val current = state.value
        val previous = snapshots[current.filters] ?: return@perform
        val session = current.session ?: return@perform
        if (previous.page >= previous.totalPages) return@perform
        loadSnapshot(session, previous.page + 1)
    }

    fun setBankEnabled(bank: Bank, enabled: Boolean) = perform {
        val session = state.value.session ?: return@perform
        settings.setBankEnabled(settings.scope(session), bank, enabled)
        loadDrafts(session)
    }

    fun installFunctionGemma(uri: Uri) = perform {
        val session = state.value.session ?: return@perform
        withContext(Dispatchers.IO) { FunctionGemmaClassifier.install(context, uri) }
        loadDrafts(session)
        BankCategoryWorker.schedule(context, settings.scope(session))
        message("Modelo local instalado.")
    }

    suspend fun classifyInForeground() {
        val session = state.value.session ?: return
        val scope = settings.scope(session)
        val gemma = FunctionGemmaClassifier(context)
        val nano = GeminiNanoClassifier()
        val primaryThenFallback = BankCategoryClassifier { transaction, categories ->
            val suggestion = gemma.suggest(transaction, categories)
            if (suggestion == CategorySuggestion.Unavailable) nano.suggest(transaction, categories) else suggestion
        }
        if (classifyPending(context, scope, primaryThenFallback)) {
            BankImportWorker.schedule(context, scope)
        }
    }

    fun saveImportCategory(row: Transaction, categoryId: Int, close: () -> Unit) = perform {
        val session = state.value.session ?: return@perform
        val scope = settings.scope(session)
        val importId = row.importId ?: return@perform
        val stored = withContext(Dispatchers.IO) { store.setManualCategory(scope, importId, categoryId) }
        if (stored) {
            BankImportWorker.schedule(context, scope)
            showLocalDashboard(session)
        } else {
            bankImportSync.withLock { api(session).updateImportCategory(importId, categoryId, CategorySource.MANUAL) }
            refreshAfterWrite(session)
        }
        close()
    }

    fun saveBank(packageName: String) = perform {
        val session = state.value.session ?: return@perform
        val bank = packageName.trim()
        if (bank.isNotEmpty() && !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(bank)) {
            message("Informe um pacote Android válido, como com.exemplo.banco.")
            return@perform
        }
        settings.saveBank(settings.scope(session), bank)
        loadDrafts(session)
        message(if (bank.isEmpty()) "Captura pausada." else "Pacote salvo.")
    }

    fun refreshDrafts() = perform {
        val session = state.value.session ?: return@perform
        loadDrafts(session)
    }

    fun dismiss(draft: PurchaseDraft) = perform {
        val session = state.value.session ?: return@perform
        val from = if (draft.uncertain) "uncertain" else "pending"
        withContext(Dispatchers.IO) { store.changeState(settings.scope(session), draft.id, from, "closed") }
        loadDrafts(session)
    }

    fun discardBankReview(review: BankReview) = perform {
        val session = state.value.session ?: return@perform
        withContext(Dispatchers.IO) { store.discardBankReview(settings.scope(session), review.id) }
        loadDrafts(session)
    }

    fun confirmBankReview(
        review: BankReview, cents: Long, direction: Direction, method: PaymentMethod, categoryId: Int?, close: () -> Unit
    ) = perform {
        val current = state.value
        val session = current.session ?: return@perform
        val scope = settings.scope(session)
        val validCategory = categoryId?.takeIf { id -> current.categories.any { it.id == id && it.name != UNCATEGORIZED } }
        val saved = withContext(Dispatchers.IO) {
            store.confirmBankReview(scope, review.id, cents, direction, method, validCategory)
        }
        if (saved) BankImportWorker.schedule(context, scope)
        close()
        loadDrafts(session)
        showLocalDashboard(session)
        message(if (saved) "Transação salva." else "Esta captura já foi resolvida.")
    }

    fun reopen(draft: PurchaseDraft) = perform {
        val session = state.value.session ?: return@perform
        withContext(Dispatchers.IO) {
            store.changeState(settings.scope(session), draft.id, "uncertain", "pending")
        }
        loadDrafts(session)
    }

    fun save(input: TransactionInput, id: Long?, draft: PurchaseDraft?, closeEditor: () -> Unit) = perform {
        val session = state.value.session ?: return@perform
        if (draft == null) {
            api(session).save(input, id)
        } else {
            val scope = settings.scope(session)
            val claimed = withContext(Dispatchers.IO) {
                store.changeState(scope, draft.id, "pending", "uncertain")
            }
            if (!claimed) {
                closeEditor()
                loadDrafts(session)
                return@perform
            }
            try {
                api(session).save(input, null)
                withContext(NonCancellable + Dispatchers.IO) {
                    store.changeState(scope, draft.id, "uncertain", "closed")
                }
            } catch (error: ApiError) {
                if (error.status in setOf(400, 401, 403, 422)) {
                    withContext(Dispatchers.IO) { store.changeState(scope, draft.id, "uncertain", "pending") }
                } else {
                    closeEditor()
                }
                throw error
            } catch (error: IOException) {
                closeEditor()
                throw error
            } finally {
                loadDrafts(session)
            }
        }
        closeEditor()
        message("Transação salva.")
        refreshAfterWrite(session)
    }

    fun delete(id: Long) = perform {
        val session = state.value.session ?: return@perform
        api(session).delete(id)
        withContext(Dispatchers.IO) { store.markDeleted(settings.scope(session), id) }
        message("Transação excluída.")
        refreshAfterWrite(session)
    }

    private suspend fun refreshAfterWrite(session: Session) {
        try {
            loadSnapshot(session, 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message("Alteração salva. Atualize a lista para conferir.")
        }
    }

    private suspend fun loadDashboard(session: Session, page: Int) {
        val scope = settings.scope(session)
        val cached = settings.categories(scope)
        mutableState.update { it.copy(categories = cached) }
        showLocalDashboard(session)
        val categories = api(session).categories()
        settings.saveCategories(scope, categories)
        mutableState.update { it.copy(categories = categories) }
        BankCategoryWorker.schedule(context, scope)
        loadSnapshot(session, page)
    }

    private suspend fun loadSnapshot(session: Session, page: Int) {
        val scope = settings.scope(session)
        val filters = state.value.filters
        val snapshot = bankImportSync.withLock {
            val local = withContext(Dispatchers.IO) { store.localImports(scope) }
            val probe = local.asSequence().filter { it.sent && it.remoteId == null && !it.deleted }
                .take(100).map { it.movement.importId }.toSet()
            api(session).transactions(page, filters, probe).copy(
                unsentAtSnapshot = local.asSequence().filter { !it.sent }.map { it.movement.importId }.toSet()
            )
        }
        if (state.value.session != session || state.value.filters != filters) return
        val previous = snapshots[filters]
        snapshots[filters] = if (page > 1 && previous != null) {
            snapshot.copy(items = (previous.items + snapshot.items).distinctBy { it.id })
        } else snapshot
        showLocalDashboard(session)
    }

    private suspend fun showLocalDashboard(session: Session) {
        val scope = settings.scope(session)
        val local = withContext(Dispatchers.IO) { store.localImports(scope) }
        val current = state.value
        if (current.session != session) return
        val snapshot = snapshots[current.filters]
        val dashboard = reconcileImports(snapshot, local, current.filters, current.categories)
        mutableState.update { it.copy(transactions = dashboard) }
    }

    private suspend fun loadDrafts(session: Session) {
        val scope = settings.scope(session)
        val (drafts, bankReviews) = withContext(Dispatchers.IO) { store.pending(scope) to store.bankReviews(scope) }
        mutableState.update {
            if (it.session == session) it.copy(
                drafts = drafts,
                bankReviews = bankReviews,
                bankPackage = settings.bank(scope),
                enabledBanks = settings.captureConfig()?.enabledSince?.keys.orEmpty(),
                functionGemmaInstalled = FunctionGemmaClassifier.installed(context)
            ) else it
        }
    }

    private fun api(session: Session) = MoneyApi(state.value.baseUrl, session.token)
    private fun message(message: String) = mutableState.update { it.copy(message = message) }

    private fun clearSession() {
        val scope = state.value.session?.let(settings::scope)
        settings.signOut()
        if (scope != null) BankImportWorker.cancel(context, scope)
        snapshots.clear()
        mutableState.value = AppState(settings.baseUrl)
    }

    private fun perform(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiError) {
                if (error.status == 401 && state.value.session != null) clearSession()
                message(when (error.status) {
                    401 -> "Confira suas credenciais ou entre novamente."
                    400, 422 -> "A API recusou os dados informados."
                    else -> "A API respondeu com erro ${error.status}."
                })
            } catch (_: IOException) {
                message("Falha de conexão. Confira o histórico antes de repetir um envio.")
            } catch (_: Exception) {
                message("Não foi possível concluir a operação.")
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
