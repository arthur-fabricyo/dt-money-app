package com.elfabrica.dtmoney

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    val functionGemmaInstalled: Boolean = false,
    val syncEnabled: Boolean = false,
    val syncStatus: LedgerSyncStatus = LedgerSyncStatus(),
    val legacyPreview: LegacyHistory? = null,
    val categoryPreview: List<CategoryDefinition>? = null
)

internal class MoneyViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val context = application
    private val store = ImportStore.get(application)
    private val mutableState = MutableStateFlow(AppState(settings.baseUrl, settings.loadSession()))
    val state = mutableState.asStateFlow()
    private var pages = 1

    init {
        viewModelScope.launch {
            store.changes.collectLatest {
                val session = state.value.session ?: return@collectLatest
                loadDrafts(session)
                showLocalDashboard(session)
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
        pages = 1
        mutableState.value = AppState(baseUrl, session, busy = true)
        BankImportWorker.schedule(context, settings.scope(session))
        loadDrafts(session)
        loadDashboard(session, 1)
    }

    fun useLocal() = perform {
        val session = settings.useLocal()
        mutableState.value = AppState(settings.baseUrl, session, busy = true)
        loadDrafts(session)
        loadDashboard(session, 1)
    }

    fun syncNow() = perform {
        val session = state.value.session ?: return@perform
        val finished = LedgerSyncWorker.synchronize(context, settings.scope(session))
        if (!finished) LedgerSyncWorker.schedule(context, settings.scope(session))
        showLocalDashboard(session)
    }

    fun previewLegacy() = perform {
        val session = state.value.session ?: return@perform
        val credentials = settings.credentials(settings.scope(session)) ?: return@perform
        val preview = MoneyApi(credentials.first, credentials.second.token).legacyHistory()
        mutableState.update { it.copy(legacyPreview = preview) }
    }

    fun dismissPreviews() { mutableState.update { it.copy(legacyPreview = null, categoryPreview = null) } }

    fun importLegacy() = perform {
        val current = state.value
        val session = current.session ?: return@perform
        val history = current.legacyPreview ?: return@perform
        val count = withContext(Dispatchers.IO) { store.ledger.importLegacy(settings.scope(session), history) }
        dismissPreviews()
        showLocalDashboard(session)
        message("$count transações importadas.")
    }

    fun previewCategories(uri: Uri) = perform {
        val definitions = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 512 * 1024)
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= 512 * 1024)
                parseCategorySeed(bytes.toString(Charsets.UTF_8))
            } ?: error("Arquivo indisponível")
        }
        mutableState.update { it.copy(categoryPreview = definitions) }
    }

    fun importCategories() = perform {
        val session = state.value.session ?: return@perform
        val definitions = state.value.categoryPreview ?: return@perform
        val count = withContext(Dispatchers.IO) { store.ledger.importCategories(settings.scope(session), definitions) }
        dismissPreviews()
        showLocalDashboard(session)
        message("$count categorias importadas. Edições existentes preservadas.")
    }

    fun saveCategory(id: Int?, definition: CategoryDefinition, close: () -> Unit) = perform {
        val session = state.value.session ?: return@perform
        withContext(Dispatchers.IO) { store.ledger.saveCategory(settings.scope(session), id, definition) }
        close()
        showLocalDashboard(session)
    }

    fun archiveCategory(category: Category) = perform {
        val session = state.value.session ?: return@perform
        withContext(Dispatchers.IO) { store.ledger.archiveCategory(settings.scope(session), category.id, !category.archived) }
        showLocalDashboard(session)
    }

    fun resolveConflict(row: Long, keepLocal: Boolean) = perform {
        val session = state.value.session ?: return@perform
        withContext(Dispatchers.IO) { store.ledger.resolve(settings.scope(session), row, keepLocal) }
        showLocalDashboard(session)
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
        val previous = current.transactions ?: return@perform
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
        withContext(Dispatchers.IO) {
            store.ledger.save(scope, row.input.copy(categoryId = categoryId), row.id)
        }
        showLocalDashboard(session)
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
        val validCategory = categoryId?.takeIf { id -> current.categories.any { it.id == id && it.isClassifiable() } }
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
        withContext(Dispatchers.IO) {
            store.ledger.save(settings.scope(session), input, id, draft)
        }
        loadDrafts(session)
        closeEditor()
        message("Transação salva.")
        refreshAfterWrite(session)
    }

    fun delete(id: Long) = perform {
        val session = state.value.session ?: return@perform
        withContext(Dispatchers.IO) { store.ledger.delete(settings.scope(session), id) }
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
        withContext(Dispatchers.IO) { store.ledger.migrateCaptures(scope, settings.categories(scope)) }
        pages = page
        showLocalDashboard(session)
        BankCategoryWorker.schedule(context, scope)
    }

    private suspend fun loadSnapshot(session: Session, page: Int) {
        pages = page
        showLocalDashboard(session)
    }

    private suspend fun showLocalDashboard(session: Session) {
        val scope = settings.scope(session)
        val filters = state.value.filters
        val result = withContext(Dispatchers.IO) {
            Triple(store.ledger.categories(scope), store.ledger.page(scope, filters, pages), store.ledger.status(scope))
        }
        if (state.value.session != session || settings.activeScope != scope) return
        mutableState.update { it.copy(categories = result.first, transactions = result.second,
            syncStatus = result.third, syncEnabled = settings.credentials(scope) != null) }
        if (result.third.pending > 0 && settings.credentials(scope) != null) LedgerSyncWorker.schedule(context, scope)
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

    private fun message(message: String) = mutableState.update { it.copy(message = message) }

    private fun clearSession() {
        val scope = settings.activeScope
        settings.signOut()
        if (scope != null) LedgerSyncWorker.cancel(context, scope)
        mutableState.update { it.copy(session = settings.loadSession(), syncEnabled = false) }
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
                message("Sem conexão com o servidor. Seus dados locais continuam disponíveis.")
            } catch (_: IllegalArgumentException) {
                message("Confira os campos: nome único, cor hexadecimal e descrição válida.")
            } catch (_: Exception) {
                message("Não foi possível concluir a operação.")
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
