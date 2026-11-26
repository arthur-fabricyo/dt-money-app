package com.elfabrica.dtmoney

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val bankPackage: String = ""
)

internal class MoneyViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val store = ImportStore.get(application)
    private val mutableState = MutableStateFlow(AppState(settings.baseUrl, settings.loadSession()))
    val state = mutableState.asStateFlow()

    init {
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
        mutableState.value = AppState(baseUrl, session, busy = true)
        loadDrafts(session)
        loadDashboard(session, 1)
    }

    fun signOut() {
        if (!state.value.busy) clearSession()
    }

    fun refresh() = perform {
        val session = state.value.session ?: return@perform
        loadDrafts(session)
        loadDashboard(session, 1)
    }

    fun filter(filters: Filters) = perform {
        val session = state.value.session ?: return@perform
        val page = api(session).transactions(1, filters)
        mutableState.update { it.copy(filters = filters, transactions = page) }
    }

    fun loadMore() = perform {
        val current = state.value
        val previous = current.transactions ?: return@perform
        val session = current.session ?: return@perform
        if (previous.page >= previous.totalPages) return@perform
        val page = api(session).transactions(previous.page + 1, current.filters)
        val combined = (previous.items + page.items).distinctBy { it.id }
        mutableState.update { it.copy(transactions = page.copy(items = combined)) }
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
        message("Transação excluída.")
        refreshAfterWrite(session)
    }

    private suspend fun refreshAfterWrite(session: Session) {
        try {
            val page = api(session).transactions(1, state.value.filters)
            mutableState.update { it.copy(transactions = page) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message("Alteração salva. Atualize a lista para conferir.")
        }
    }

    private suspend fun loadDashboard(session: Session, page: Int) {
        val api = api(session)
        val categories = api.categories()
        val transactions = api.transactions(page, state.value.filters)
        mutableState.update { it.copy(categories = categories, transactions = transactions) }
    }

    private suspend fun loadDrafts(session: Session) {
        val scope = settings.scope(session)
        val drafts = withContext(Dispatchers.IO) { store.pending(scope) }
        mutableState.update {
            if (it.session == session) it.copy(drafts = drafts, bankPackage = settings.bank(scope)) else it
        }
    }

    private fun api(session: Session) = MoneyApi(state.value.baseUrl, session.token)
    private fun message(message: String) = mutableState.update { it.copy(message = message) }

    private fun clearSession() {
        settings.signOut()
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
