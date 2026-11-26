package com.elfabrica.dtmoney

import android.app.DatePickerDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: MoneyViewModel by viewModels()
    private var notificationAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF75D4AE))) {
                Scaffold { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                        if (state.session == null) {
                            SignIn(state, model::authenticate)
                        } else {
                            key(state.session?.userId, state.baseUrl) {
                                Dashboard(state, model, notificationAccess) {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val component = ComponentName(this, BankNotificationService::class.java)
        notificationAccess = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component)
        if (model.state.value.session != null) model.refreshDrafts()
    }
}

@Composable
private fun SignIn(state: AppState, authenticate: (String, String, String, String?) -> Unit) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DT Money", style = MaterialTheme.typography.headlineLarge)
        Text(if (registering) "Criar conta" else "Entrar")
        Field(url, { url = it }, "URL da API", KeyboardType.Uri)
        if (registering) Field(name, { name = it }, "Nome")
        Field(email, { email = it }, "E-mail", KeyboardType.Email)
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Senha") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Button(
            onClick = { authenticate(url, email, password, name.takeIf { registering }) },
            enabled = !state.busy && url.isNotBlank() && email.isNotBlank() && password.isNotEmpty() &&
                (!registering || name.isNotBlank())
        ) { Text(if (registering) "Cadastrar" else "Entrar") }
        TextButton(onClick = { registering = !registering }, enabled = !state.busy) {
            Text(if (registering) "Já tenho uma conta" else "Criar conta")
        }
    }
}

private data class EditTarget(val transaction: Transaction? = null, val draft: PurchaseDraft? = null)

@Composable
private fun Dashboard(state: AppState, model: MoneyViewModel, access: Boolean, openSettings: () -> Unit) {
    var inbox by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var target by remember { mutableStateOf<EditTarget?>(null) }
    var deleting by remember { mutableStateOf<Transaction?>(null) }
    var reopening by remember { mutableStateOf<PurchaseDraft?>(null) }
    val active = !state.busy
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("DT Money", style = MaterialTheme.typography.headlineMedium)
            Text(state.session?.name.orEmpty())
            Row {
                TextButton(onClick = { inbox = false }, enabled = inbox) { Text("Transações") }
                TextButton(onClick = { inbox = true; model.refreshDrafts() }, enabled = !inbox && active) {
                    Text("Capturas (${state.drafts.size})")
                }
                TextButton(onClick = model::signOut, enabled = active) { Text("Sair") }
            }
        }
        if (inbox) {
            item { CaptureSettings(state.bankPackage, access, active, model::saveBank, openSettings) }
            item { TextButton(onClick = model::refreshDrafts, enabled = active) { Text("Atualizar capturas") } }
            if (state.drafts.isEmpty()) item { Text("Nenhuma compra para revisar.") }
            items(state.drafts, key = { it.id }) { draft ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(draft.description, style = MaterialTheme.typography.titleMedium)
                        Text("${money(draft.cents)} · ${date(Instant.ofEpochMilli(draft.postedAt))}")
                        if (draft.uncertain) Text("Envio sem confirmação. Confira a lista de transações.")
                        Row {
                            if (!draft.uncertain) TextButton(
                                onClick = { target = EditTarget(draft = draft) },
                                enabled = active && state.categories.isNotEmpty()
                            ) { Text("Revisar") }
                            if (draft.uncertain) TextButton(onClick = { reopening = draft }, enabled = active) {
                                Text("Voltar para revisão")
                            }
                            TextButton(onClick = { model.dismiss(draft) }, enabled = active) {
                                Text(if (draft.uncertain) "Arquivar" else "Descartar")
                            }
                        }
                    }
                }
            }
        } else {
            val page = state.transactions
            if (page != null) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Saldo", style = MaterialTheme.typography.labelLarge)
                        Text(money(page.balance), style = MaterialTheme.typography.headlineMedium)
                        Text("Entradas ${money(page.revenue)} · Saídas ${money(page.expense)}")
                    }
                }
            }
            item {
                Row {
                    Button(onClick = { target = EditTarget() }, enabled = active && state.categories.isNotEmpty()) {
                        Text("Nova transação")
                    }
                    TextButton(onClick = { showFilters = true }, enabled = active) { Text("Filtros") }
                    TextButton(onClick = model::refresh, enabled = active) { Text("Atualizar") }
                }
            }
            if (page != null) {
                if (page.items.isEmpty()) item { Text("Nenhuma transação encontrada.") }
                items(page.items, key = { it.id }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(row.input.description, style = MaterialTheme.typography.titleMedium)
                            Text(if (row.input.typeId == 2) "−${money(row.input.cents)}" else money(row.input.cents))
                            val posted = runCatching { date(Instant.parse(row.createdAt)) }.getOrDefault("")
                            Text(listOf(row.category, posted).filter(String::isNotEmpty).joinToString(" · "))
                            Row {
                                TextButton(onClick = { target = EditTarget(transaction = row) }, enabled = active) {
                                    Text("Editar")
                                }
                                TextButton(onClick = { deleting = row }, enabled = active) { Text("Excluir") }
                            }
                        }
                    }
                }
                if (page.page < page.totalPages) item {
                    TextButton(onClick = model::loadMore, enabled = active) { Text("Carregar mais") }
                }
            }
        }
    }
    target?.let { editor ->
        EditTransaction(editor, state.categories, state.busy, state.message, close = { target = null }) { input ->
            model.save(input, editor.transaction?.id, editor.draft) { target = null }
        }
    }
    if (showFilters) FilterDialog(state.filters, state.categories, close = { showFilters = false }) {
        showFilters = false
        model.filter(it)
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir transação?") },
            text = { Text(row.input.description) },
            confirmButton = { TextButton(onClick = { deleting = null; model.delete(row.id) }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
        )
    }
    reopening?.let { draft ->
        AlertDialog(
            onDismissRequest = { reopening = null },
            title = { Text("Voltar para revisão?") },
            text = { Text("Confirme no histórico que essa compra ainda precisa ser cadastrada.") },
            confirmButton = {
                TextButton(onClick = { reopening = null; model.reopen(draft) }) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { reopening = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun CaptureSettings(bank: String, access: Boolean, active: Boolean, save: (String) -> Unit, open: () -> Unit) {
    var field by rememberSaveable(bank) { mutableStateOf(bank) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Captura de compras", style = MaterialTheme.typography.titleLarge)
            Text(if (access) "Acesso às notificações concedido." else "Conceda acesso às notificações pelo Android.")
            TextButton(onClick = open) { Text("Configurações do Android") }
            Field(field, { field = it }, "Pacote do aplicativo do banco")
            Text("Formato reconhecido: Compra aprovada de R$ 42,90 em Mercado X")
            Row {
                Button(onClick = { save(field) }, enabled = active && field.isNotBlank()) { Text("Salvar pacote") }
                if (bank.isNotEmpty()) TextButton(onClick = { save("") }, enabled = active) { Text("Pausar") }
            }
        }
    }
}

@Composable
private fun EditTransaction(
    editor: EditTarget,
    categories: List<Category>,
    busy: Boolean,
    message: String?,
    close: () -> Unit,
    save: (TransactionInput) -> Unit
) {
    val existing = editor.transaction?.input
    val draft = editor.draft
    var description by remember { mutableStateOf(existing?.description ?: draft?.description.orEmpty()) }
    val initialValue = (existing?.cents ?: draft?.cents)?.let { BigDecimal.valueOf(it, 2).toPlainString().replace('.', ',') }
    var amount by remember { mutableStateOf(initialValue.orEmpty()) }
    var type by remember { mutableIntStateOf(existing?.typeId ?: 2) }
    var category by remember { mutableStateOf(existing?.categoryId) }
    val cents = amount.trim().takeIf { Regex("[0-9]+(?:[,.][0-9]{1,2})?").matches(it) }
        ?.replace(',', '.')?.toBigDecimalOrNull()?.movePointRight(2)
        ?.let { runCatching { it.longValueExact() }.getOrNull() }?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (draft != null) "Revisar compra" else "Transação") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(description, { description = it }, "Descrição")
                Field(amount, { amount = it }, "Valor em reais", KeyboardType.Decimal)
                if (draft == null) Choice("Tipo", type, listOf(1 to "Entrada", 2 to "Saída")) { type = it }
                Choice("Categoria", category, categories.map { it.id to it.name }) { category = it }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else message?.let { Text(it) }
            }
        },
        confirmButton = {
            val categoryId = category
            TextButton(
                onClick = { if (cents != null && categoryId != null) save(TransactionInput(description.trim(), cents, categoryId, type)) },
                enabled = !busy && description.isNotBlank() && cents != null && categories.any { it.id == categoryId }
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Cancelar") } }
    )
}

@Composable
private fun FilterDialog(current: Filters, categories: List<Category>, close: () -> Unit, apply: (Filters) -> Unit) {
    val zone = ZoneId.systemDefault()
    var search by remember { mutableStateOf(current.search) }
    var type by remember { mutableIntStateOf(current.typeId ?: 0) }
    var selected by remember { mutableStateOf(current.categoryIds) }
    var from by remember { mutableStateOf(current.from?.let { Instant.parse(it).atZone(zone).toLocalDate() }) }
    var to by remember { mutableStateOf(current.to?.let { Instant.parse(it).atZone(zone).toLocalDate() }) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Filtrar transações") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(search, { search = it }, "Buscar")
                Choice("Tipo", type, listOf(0 to "Todos", 1 to "Entradas", 2 to "Saídas")) { type = it }
                PickDate("De", from) { from = it }
                PickDate("Até", to) { to = it }
                Text("Categorias")
                categories.forEach { category ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(category.id in selected, { checked ->
                            selected = if (checked) selected + category.id else selected - category.id
                        })
                        Text(category.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = from?.let { start -> to?.isBefore(start) != true } ?: true,
                onClick = {
                    apply(Filters(search, type.takeIf { it != 0 }, selected,
                        from?.atStartOfDay(zone)?.toInstant()?.toString(),
                        to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.minusMillis(1)?.toString()))
                }
            ) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = { apply(Filters()) }) { Text("Limpar") } }
    )
}

@Composable
private fun PickDate(label: String, selected: LocalDate?, choose: (LocalDate?) -> Unit) {
    val context = LocalContext.current
    Row {
        TextButton(onClick = {
            val day = selected ?: LocalDate.now()
            DatePickerDialog(context, { _, year, month, dayOfMonth ->
                choose(LocalDate.of(year, month + 1, dayOfMonth))
            }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }) { Text("$label ${selected?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "—"}") }
        if (selected != null) TextButton(onClick = { choose(null) }) { Text("Remover") }
    }
}

@Composable
private fun Choice(label: String, selected: Int?, options: List<Pair<Int, String>>, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(options.firstOrNull { it.first == selected }?.second ?: label)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; choose(id) })
            }
        }
    }
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

private fun money(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(BigDecimal.valueOf(cents, 2))

private fun date(instant: Instant): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(instant)
