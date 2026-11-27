package com.elfabrica.dtmoney

import android.app.DatePickerDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val model: MoneyViewModel by viewModels()
    private var notificationAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.session, state.categories) {
                if (state.session != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    model.classifyInForeground()
                }
            }
            DtMoneyTheme {
                Scaffold(containerColor = DtColors.Secondary) { padding ->
                    Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.message?.let { message ->
                            Surface(color = DtColors.Surface, modifier = Modifier.fillMaxWidth()) {
                                Text(message, Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                        if (state.session == null) {
                            SignIn(state, model::authenticate, model::useLocal)
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
        bankModelForeground = true
        val component = ComponentName(this, BankNotificationService::class.java)
        notificationAccess = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component)
        if (model.state.value.session != null) model.refresh()
    }

    override fun onPause() {
        bankModelForeground = false
        super.onPause()
    }
}

private data class EditTarget(val transaction: Transaction? = null, val draft: PurchaseDraft? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(state: AppState, model: MoneyViewModel, access: Boolean, openSettings: () -> Unit) {
    var inbox by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var target by remember { mutableStateOf<EditTarget?>(null) }
    var categorizing by remember { mutableStateOf<Transaction?>(null) }
    var deleting by remember { mutableStateOf<Transaction?>(null) }
    var reopening by remember { mutableStateOf<PurchaseDraft?>(null) }
    var reviewing by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf(state.filters.search) }
    val active = !state.busy
    val canEdit = active && state.categories.isNotEmpty()
    val inset = Modifier.padding(horizontal = 20.dp)
    val focusManager = LocalFocusManager.current
    val canSearch = active && !inbox && !settingsOpen && !showFilters && target == null && categorizing == null && deleting == null
    LaunchedEffect(search, canSearch, state.filters) {
        if (canSearch && search != state.filters.search) {
            delay(500)
            model.filter(state.filters.copy(search = search))
        }
    }
    if (settingsOpen) {
        SettingsScreen(state, model, access, openSettings, onBack = { settingsOpen = false })
        return
    }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) { if (!state.busy) refreshing = false }
    PullToRefreshBox(
        isRefreshing = refreshing && state.busy,
        onRefresh = { if (active && !inbox) { refreshing = true; model.refresh() } },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            DashboardHeader(active, canEdit, onNew = { target = EditTarget() }, onSettings = {
                focusManager.clearFocus()
                settingsOpen = true
            })
        }
        item {
            SecondaryTabRow(selectedTabIndex = if (inbox) 1 else 0, modifier = inset,
                containerColor = DtColors.Secondary, contentColor = DtColors.Green) {
                Tab(selected = !inbox, onClick = { inbox = false }, text = { Text("Transações") })
                Tab(selected = inbox, onClick = { inbox = true; model.refreshDrafts() },
                    text = { Text("Capturas (${state.drafts.size + state.bankReviews.size})") })
            }
        }
        if (inbox) {
            item { TextButton(onClick = model::refreshDrafts, modifier = inset, enabled = active) { Text("Atualizar capturas") } }
            if (state.drafts.isEmpty() && state.bankReviews.isEmpty()) item { EmptyMessage("Nenhuma captura para revisar.") }
            items(state.bankReviews, key = { "bank-${it.id}" }) { review ->
                Box(inset) {
                    BankReviewCard(review, active, review = { reviewing = review.id }, discard = { model.discardBankReview(review) })
                }
            }
            items(state.drafts, key = { it.id }) { draft ->
                DtCard(inset.fillMaxWidth()) {
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
            if (page != null) item { AccountSummary(page, state.filters) }
            item {
                Box(inset) {
                    TransactionsToolbar(
                        state.filters, search, page?.takeIf { !it.partial && it.page >= it.totalPages }?.items?.size, active,
                        onSearch = { search = it },
                        onFilters = { focusManager.clearFocus(); showFilters = true }, onRefresh = model::refresh
                    )
                }
            }
            if (page != null) {
                if (page.items.isEmpty()) item { EmptyMessage("Nenhuma transação encontrada.") }
                items(page.items, key = { it.importId ?: it.id }) { row ->
                    Box(inset) {
                        TransactionItem(row, active, canEdit, onEdit = {
                            if (row.importId == null) target = EditTarget(transaction = row)
                            else categorizing = row
                        }, onDelete = { deleting = row })
                    }
                }
                if (page.page < page.totalPages) item(key = "load-more") {
                    val busy by rememberUpdatedState(state.busy)
                    LaunchedEffect(page.page, state.filters) {
                        snapshotFlow { busy }.first { !it }
                        model.loadMore()
                    }
                    Box(inset.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!state.busy && state.message != null) {
                            DtButton(model::loadMore, enabled = true, outlined = true) { Text("Tentar novamente") }
                        } else {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
    }
    categorizing?.let { row ->
        var selected by remember(row) { mutableIntStateOf(row.input.categoryId) }
        DtSheet("Alterar categoria", onClose = { categorizing = null }, closeEnabled = active) {
            Choice("Categoria", selected, state.categories.filterNot { it.archived }.map { it.id to it.name }) { selected = it }
            DtButton(onClick = { model.saveImportCategory(row, selected) { categorizing = null } },
                modifier = Modifier.fillMaxWidth(), enabled = active && state.categories.any { it.id == selected }) {
                Text("Salvar")
            }
        }
    }
    target?.let { editor ->
        EditTransaction(editor, state.categories, state.busy, state.message, close = { target = null }) { input ->
            model.save(input, editor.transaction?.id, editor.draft) { target = null }
        }
    }
    state.bankReviews.firstOrNull { it.id == reviewing }?.let { review ->
        key(review.id) {
            BankReviewDialog(review, state.categories, state.busy, close = { reviewing = null }) { cents, direction, method, category ->
                model.confirmBankReview(review, cents, direction, method, category) { reviewing = null }
            }
        }
    }
    if (showFilters) FilterDialog(state.filters.copy(search = search), state.categories, active, close = { showFilters = false }) {
        search = it.search
        showFilters = false
        model.filter(it)
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Apagar transação") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.input.description)
                    Text("Esta ação não pode ser desfeita.", color = DtColors.Muted)
                }
            },
            confirmButton = {
                Button(onClick = { deleting = null; model.delete(row.id) }, enabled = active,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DtColors.RedDark, contentColor = Color.White)) {
                    Text("Apagar")
                }
            },
            dismissButton = { DtButton(onClick = { deleting = null }, outlined = true) { Text("Cancelar") } }
        )
    }
    reopening?.let { draft ->
        AlertDialog(
            onDismissRequest = { reopening = null },
            title = { Text("Voltar para revisão?") },
            text = { Text("Confirme no histórico que essa compra ainda precisa ser cadastrada.") },
            confirmButton = {
                TextButton(onClick = { reopening = null; model.reopen(draft) }, enabled = active) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { reopening = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun SettingsScreen(state: AppState, model: MoneyViewModel, access: Boolean, openSettings: () -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Configurações", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Voltar") }
        }
        SecondaryTabRow(selectedTabIndex = tab, containerColor = DtColors.Secondary, contentColor = DtColors.Green) {
            listOf("Capturas", "Categorias", "Sincronização").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> LazyColumn(contentPadding = PaddingValues(20.dp)) {
                item { CaptureSettings(state, access, model::saveBank, model::setBankEnabled, model::installFunctionGemma, openSettings) }
            }
            1 -> CategorySettings(state, model)
            else -> SyncSettings(state, model)
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(message, color = DtColors.Muted)
    }
}

@Composable
private fun CaptureSettings(
    state: AppState, access: Boolean, save: (String) -> Unit, setEnabled: (Bank, Boolean) -> Unit,
    installModel: (android.net.Uri) -> Unit, open: () -> Unit
) {
    val bank = state.bankPackage
    val active = !state.busy
    var field by rememberSaveable(bank) { mutableStateOf(bank) }
    val selectModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(installModel)
    }
    DtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notificações bancárias", style = MaterialTheme.typography.titleLarge)
            Text(if (access) "Acesso às notificações concedido." else "Conceda acesso às notificações pelo Android.")
            TextButton(onClick = open) { Text("Configurações do Android") }
            if (BuildConfig.DEBUG) {
                Text("Lançamentos automáticos experimentais")
                Bank.entries.forEach { source ->
                    val enabled = access && active
                    val checked = source in state.enabledBanks
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled,
                        role = Role.Switch, onValueChange = { setEnabled(source, it) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(source.appName, Modifier.weight(1f))
                        Switch(checked, onCheckedChange = null, enabled = enabled)
                    }
                }
                TextButton(onClick = { selectModel.launch(arrayOf("*/*")) }, enabled = active) {
                    Text(if (state.functionGemmaInstalled) "Substituir FunctionGemma local" else "Instalar FunctionGemma local")
                }
            }
            Field(field, { field = it }, "Pacote para revisão manual")
            Row {
                DtButton(onClick = { save(field) }, enabled = active && field.isNotBlank()) { Text("Salvar pacote") }
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
    var description by rememberSaveable { mutableStateOf(existing?.description ?: draft?.description.orEmpty()) }
    val initialValue = (existing?.cents ?: draft?.cents)?.toString()
    var amount by rememberSaveable { mutableStateOf(initialValue.orEmpty()) }
    var type by rememberSaveable { mutableIntStateOf(existing?.typeId ?: 2) }
    var category by rememberSaveable { mutableStateOf(existing?.categoryId) }
    val cents = amount.toLongOrNull()?.takeIf { it > 0 }
    val title = when {
        draft != null -> "Revisar compra"
        existing != null -> "Editar transação"
        else -> "Nova transação"
    }
    DtSheet(title, onClose = close, closeEnabled = !busy) {
        Field(description, { description = it }, "Descrição")
        CurrencyField(amount, { amount = it })
        if (draft == null) DirectionSelector(type) { type = it }
        Choice("Categoria", category, categories.filter { !it.archived || it.id == existing?.categoryId }.map { it.id to it.name }) { category = it }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        else message?.let { Text(it) }
        val categoryId = category
        DtButton(
            onClick = { if (cents != null && categoryId != null) save(TransactionInput(description.trim(), cents, categoryId, type)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && description.isNotBlank() && cents != null && categories.any { it.id == categoryId }
        ) { Text(if (existing == null) "Cadastrar" else "Salvar alterações") }
    }
}

@Composable
private fun FilterDialog(current: Filters, categories: List<Category>, active: Boolean, close: () -> Unit, apply: (Filters) -> Unit) {
    val zone = ZoneId.systemDefault()
    var search by remember { mutableStateOf(current.search) }
    var type by remember { mutableIntStateOf(current.typeId ?: 0) }
    var selected by remember { mutableStateOf(current.categoryIds) }
    var from by remember { mutableStateOf(current.from?.let { Instant.parse(it).atZone(zone).toLocalDate() }) }
    var to by remember { mutableStateOf(current.to?.let { Instant.parse(it).atZone(zone).toLocalDate() }) }
    DtSheet("Filtrar transações", onClose = close) {
        Field(search, { search = it }, "Buscar")
        DirectionSelector(type, includeAll = true) { type = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickDate("Data inicial", from, Modifier.weight(1f)) { from = it }
            PickDate("Data final", to, Modifier.weight(1f)) { to = it }
        }
        Text("Categorias", style = MaterialTheme.typography.titleMedium)
        categories.forEach { category ->
            val categorySelected = category.id in selected
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(categorySelected, role = Role.Checkbox) { checked ->
                selected = if (checked) selected + category.id else selected - category.id
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(categorySelected, onCheckedChange = null)
                Text(category.name)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DtButton(onClick = { apply(Filters()) }, modifier = Modifier.weight(1f), enabled = active, outlined = true) {
                Text("Limpar")
            }
            DtButton(
                modifier = Modifier.weight(1f),
                enabled = active && (from?.let { start -> to?.isBefore(start) != true } ?: true),
                onClick = {
                    apply(Filters(search, type.takeIf { it != 0 }, selected,
                        from?.atStartOfDay(zone)?.toInstant()?.toString(),
                        to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.minusMillis(1)?.toString()))
                }
            ) { Text("Aplicar") }
        }
    }
}

@Composable
private fun PickDate(label: String, selected: LocalDate?, modifier: Modifier, choose: (LocalDate?) -> Unit) {
    val context = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = DtColors.Muted)
        DtButton(onClick = {
            val day = selected ?: LocalDate.now()
            DatePickerDialog(context, { _, year, month, dayOfMonth ->
                choose(LocalDate.of(year, month + 1, dayOfMonth))
            }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }, outlined = true) {
            Text(selected?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "Selecionar")
        }
        if (selected != null) TextButton(onClick = { choose(null) }) { Text("Remover") }
    }
}

@Composable
internal fun Choice(label: String, selected: Int?, options: List<Pair<Int, String>>, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = DtColors.Muted, style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                enabled = options.isNotEmpty(), shape = RoundedCornerShape(6.dp)) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Selecionar", Modifier.weight(1f), color = DtColors.Text)
                Text("⌄", color = DtColors.Muted)
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.width(maxWidth)) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(
                        text = { Text(title, color = if (id == selected) DtColors.Green else DtColors.Text) },
                        onClick = { expanded = false; choose(id) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun DirectionSelector(selected: Int?, includeAll: Boolean = false, choose: (Int) -> Unit) {
    val options = if (includeAll) listOf(0 to "Todos", 1 to "Entrada", 2 to "Saída") else listOf(1 to "Entrada", 2 to "Saída")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (type, label) ->
            val active = selected == type
            val color = if (type == 2) DtColors.Red else DtColors.Green
            val title = when (type) { 1 -> "↑ $label"; 2 -> "↓ $label"; else -> label }
            FilterChip(
                selected = active,
                onClick = { choose(type) },
                label = { Text(title) },
                modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DtColors.Base,
                    selectedLabelColor = color, labelColor = DtColors.Text)
            )
        }
    }
}

@Composable
internal fun Field(value: String, change: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    DtField(value, change, label, keyboard, modifier = Modifier.fillMaxWidth())
}

internal fun money(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(BigDecimal.valueOf(cents, 2))

internal fun date(instant: Instant): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(instant)
