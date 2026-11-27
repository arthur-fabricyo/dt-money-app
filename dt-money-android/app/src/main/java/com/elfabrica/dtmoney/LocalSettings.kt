package com.elfabrica.dtmoney

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun CategorySettings(state: AppState, model: MoneyViewModel) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::previewCategories) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row {
                DtButton(onClick = { creating = true }, enabled = !state.busy) { Text("Nova categoria") }
                TextButton(onClick = { picker.launch(arrayOf("application/json", "text/plain")) }, enabled = !state.busy) { Text("Importar JSON") }
            }
        }
        items(state.categories, key = { it.id }) { category ->
            DtCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(24.dp).background(categoryColor(category.color)))
                        Text(category.name + if (category.archived) " · Arquivada" else "")
                    }
                    if (category.description.isNotBlank()) Text(category.description, style = MaterialTheme.typography.bodySmall)
                    if (!category.isSystem) Row {
                        TextButton(onClick = { editing = category.id }, enabled = !state.busy) { Text("Editar") }
                        TextButton(onClick = { model.archiveCategory(category) }, enabled = !state.busy) {
                            Text(if (category.archived) "Restaurar" else "Arquivar")
                        }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        val category = state.categories.firstOrNull { it.id == editing }
        key(editing, creating) {
            CategoryEditor(category, state.categories, state.busy, state.message, close = { creating = false; editing = null }) { value ->
                model.saveCategory(editing, value) { creating = false; editing = null }
            }
        }
    }
    state.categoryPreview?.let { definitions ->
        AlertDialog(onDismissRequest = model::dismissPreviews,
            title = { Text("Importar categorias") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${definitions.size} definições. Apenas categorias novas serão criadas. Edições e arquivamentos existentes serão preservados.")
                    definitions.forEach { Text("${it.name} · ${it.color}\n${it.description}") }
                }
            },
            confirmButton = { TextButton(onClick = model::importCategories, enabled = !state.busy) { Text("Importar") } },
            dismissButton = { TextButton(onClick = model::dismissPreviews) { Text("Cancelar") } })
    }
}

@Composable
private fun CategoryEditor(category: Category?, categories: List<Category>, busy: Boolean, message: String?, close: () -> Unit, save: (CategoryDefinition) -> Unit) {
    var name by rememberSaveable { mutableStateOf(category?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(category?.description.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(category?.color ?: suggestCategoryColor(categories)) }
    DtSheet(if (category == null) "Nova categoria" else "Editar categoria", onClose = close, closeEnabled = !busy) {
        Field(name, { name = it.take(60) }, "Nome")
        Field(description, { description = it.take(240) }, "Descrição de uso")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(40.dp).background(categoryColor(color)))
            TextButton(onClick = { color = suggestCategoryColor(categories, color) }) { Text("Sortear cor") }
        }
        categoryPalette.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { value ->
                    TextButton(onClick = { color = value }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                        Box(Modifier.size(28.dp).background(categoryColor(value)))
                    }
                }
            }
        }
        Field(color, { color = it.take(7) }, "Cor hexadecimal (#RRGGBB)")
        message?.let { Text(it) }
        val valid = name.isNotBlank() && normalizeCategoryColor(color) != null &&
            categoryKey(name) != categoryKey(UNCATEGORIZED) && categories.none { it.id != category?.id && categoryKey(it.name) == categoryKey(name) }
        DtButton(onClick = { save(CategoryDefinition(name, color, description)) }, enabled = !busy && valid) { Text("Salvar categoria") }
    }
}

private fun categoryColor(value: String): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(DtColors.Muted)

@Composable
internal fun SyncSettings(state: AppState, model: MoneyViewModel) {
    var connecting by rememberSaveable { mutableStateOf(false) }
    if (connecting && !state.syncEnabled) {
        BackHandler { connecting = false }
        Column {
            TextButton(onClick = { connecting = false }) { Text("Voltar à sincronização") }
            SignIn(state, model::authenticate)
        }
        return
    }
    LaunchedEffect(state.syncEnabled) { if (state.syncEnabled) connecting = false }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Sincronização e backup", style = MaterialTheme.typography.titleLarge)
            Text("O banco principal fica neste aparelho. Apenas os dados recebidos pelo servidor entram no backup diário.")
            Text(if (state.syncEnabled) "Conectado: ${state.session?.name} · ${state.baseUrl}" else "Perfil local · sincronização desconectada")
            Text("Pendentes: ${state.syncStatus.pending}")
            Text("Última sincronização: ${state.syncStatus.lastSync ?: "ainda não realizada"}")
            Text("Último backup no servidor: ${state.syncStatus.lastBackup ?: "sem confirmação"}")
            if (state.syncStatus.failed) Text("Não foi possível sincronizar. Os dados estão salvos neste aparelho.", color = DtColors.Red)
            if (state.syncEnabled) {
                DtButton(onClick = model::syncNow, enabled = !state.busy) { Text("Sincronizar agora") }
                TextButton(onClick = model::previewLegacy, enabled = !state.busy) { Text("Prévia do histórico da API anterior") }
                TextButton(onClick = model::signOut, enabled = !state.busy) { Text("Desconectar e manter dados locais") }
            } else {
                DtButton(onClick = { connecting = true }, enabled = !state.busy) { Text("Conectar conta e ativar sincronização") }
                Text("Reconectar à mesma conta preserva este perfil. Outra conta usa um perfil separado.")
            }
        }
        items(state.syncStatus.conflicts, key = { it.rowId }) { conflict ->
            DtCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Conflito: escolha a versão")
                    Text("Neste aparelho: ${conflict.local}")
                    Text("Servidor: ${conflict.remote}")
                    TextButton(onClick = { model.resolveConflict(conflict.rowId, true) }, enabled = !state.busy) { Text("Manter versão local") }
                    TextButton(onClick = { model.resolveConflict(conflict.rowId, false) }, enabled = !state.busy) { Text("Usar versão do servidor") }
                }
            }
        }
    }
    state.legacyPreview?.let { preview ->
        AlertDialog(onDismissRequest = model::dismissPreviews, title = { Text("Importar histórico anterior") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${preview.transactions.size} transações · ${preview.categories.size} categorias de ${state.baseUrl}, conta ${state.session?.name}. Repetições serão ignoradas.")
                    preview.transactions.forEach { Text("${it.input.description} · ${money(it.input.cents)} · ${it.occurredAt ?: it.createdAt}") }
                }
            },
            confirmButton = { TextButton(onClick = model::importLegacy, enabled = !state.busy) { Text("Confirmar importação") } },
            dismissButton = { TextButton(onClick = model::dismissPreviews) { Text("Cancelar") } })
    }
}
