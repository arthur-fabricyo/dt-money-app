package com.elfabrica.dtmoney

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardHeader(active: Boolean, canCreate: Boolean, onNew: () -> Unit, onSettings: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().background(DtColors.Base).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            DtLogo(Modifier.width(130.dp).height(30.dp))
            Row(
                modifier = Modifier.clickable(role = Role.Button, onClick = onSettings)
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardGlyph(Glyph.Settings, DtColors.Green, Modifier.size(18.dp))
                Text("Configurações", color = DtColors.Text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DtButton(
            onClick = onNew,
            modifier = Modifier.widthIn(min = 130.dp),
            enabled = active && canCreate
        ) {
            Text("Nova transação", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun AccountSummary(page: TransactionPage, filters: Filters) {
    val lastExpense = page.items.firstOrNull { it.input.typeId == Direction.EXPENSE.typeId }
    val lastIncome = page.items.firstOrNull { it.input.typeId == Direction.INCOME.typeId }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Spacer(Modifier.fillMaxWidth().height(50.dp).background(DtColors.Base))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SummaryCard("Saída", page.expense, lastMovement(lastExpense, "Última saída"), Glyph.Down, DtColors.Red)
                SummaryCard("Entrada", page.revenue, lastMovement(lastIncome, "Última entrada"), Glyph.Up, DtColors.Green)
                SummaryCard("Total", page.balance, filterPeriod(filters), Glyph.Money, Color.White, total = true)
            }
        }
        if (page.partial) {
            Text("Saldo parcial · aguardando dados do servidor", Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp),
                color = DtColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    amount: Long,
    details: String,
    glyph: Glyph,
    glyphColor: Color,
    total: Boolean = false
) {
    DtCard(
        modifier = Modifier.width(250.dp).heightIn(min = 141.dp),
        color = if (total) DtColors.BrandDark else DtColors.Surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 141.dp).padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                DashboardGlyph(glyph, glyphColor, Modifier.size(26.dp))
            }
            Column(Modifier.padding(top = 8.dp)) {
                Text(money(amount), color = DtColors.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(details, color = if (total) Color.White else DtColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun TransactionsToolbar(
    filters: Filters,
    search: String,
    totalRows: Int?,
    active: Boolean,
    onSearch: (String) -> Unit,
    onFilters: () -> Unit,
    onRefresh: () -> Unit
) {
    val filtered = filters.typeId != null || filters.categoryIds.isNotEmpty() || filters.from != null || filters.to != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Transações", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            totalRows?.let {
                Text("$it ${if (it == 1) "item" else "itens"}", color = DtColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            GlyphButton(Glyph.Refresh, "Atualizar transações", onRefresh, active)
        }
        DtField(
            value = search,
            onValueChange = onSearch,
            label = "",
            placeholder = "Busque uma transação",
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                GlyphButton(
                    Glyph.Filter, "Filtros", onFilters, active, tint = DtColors.Green,
                    modifier = Modifier.semantics { if (filtered) stateDescription = "Filtros aplicados" }
                )
            }
        )
    }
}

@Composable
internal fun TransactionItem(row: Transaction, active: Boolean, canEdit: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val editEnabled = active && canEdit
    val hasDelete = row.id > 0
    val deleteEnabled = active && hasDelete
    val editLabel = if (row.importId == null) "Editar" else "Alterar categoria"
    var menuOpen by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(Reveal.Closed) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val actionWidth = 80.dp
    val actionPixels = with(LocalDensity.current) { actionWidth.toPx() }
    val settledPosition = remember { Animatable(0f) }
    val position = if (dragging) dragPosition else settledPosition.value

    fun settle(target: Reveal) {
        reveal = target
        settleJob?.cancel()
        settleJob = scope.launch {
            if (dragging) settledPosition.snapTo(dragPosition)
            dragging = false
            settledPosition.animateTo(target.sign * actionPixels)
        }
    }

    fun closeActions() {
        menuOpen = false
        settle(Reveal.Closed)
    }

    LaunchedEffect(editEnabled, deleteEnabled, actionPixels) {
        if (dragging || (reveal == Reveal.Edit && !editEnabled) || (reveal == Reveal.Delete && !deleteEnabled)) {
            settle(Reveal.Closed)
        }
        if (!active) menuOpen = false
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))) {
        if (position != 0f) {
            val edit = position > 0f
            val label = if (edit) editLabel else "Excluir"
            val enabled = !dragging && (if (edit) editEnabled else deleteEnabled)
            Box(Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier.align(if (edit) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                        .width(actionWidth)
                        .fillMaxHeight()
                        .background(if (edit) Color(0xFF284DAA) else DtColors.RedDark)
                        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                            closeActions()
                            if (edit) onEdit() else onDelete()
                        }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center
                ) {
                    DashboardGlyph(if (edit) Glyph.Edit else Glyph.Delete, Color.White, Modifier.size(28.dp))
                }
            }
        }
        DtCard(
            Modifier.fillMaxWidth().absoluteOffset { IntOffset(position.roundToInt(), 0) }
                .pointerInput(editEnabled, deleteEnabled, actionPixels) {
                    if (!editEnabled && !deleteEnabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            dragPosition = settledPosition.value
                            dragging = true
                        },
                        onDragCancel = { settle(reveal) },
                        onDragEnd = {
                            val threshold = actionPixels / 2
                            val target = when {
                                dragPosition > threshold && editEnabled -> Reveal.Edit
                                dragPosition < -threshold && deleteEnabled -> Reveal.Delete
                                else -> Reveal.Closed
                            }
                            settle(target)
                        }
                    ) { change, distance ->
                        change.consume()
                        val minimum = if (deleteEnabled) -actionPixels else 0f
                        val maximum = if (editEnabled) actionPixels else 0f
                        dragPosition = (dragPosition + distance).coerceIn(minimum, maximum)
                    }
                }
        ) {
            Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(start = 24.dp, top = 12.dp, end = 16.dp, bottom = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.input.description, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Box {
                        GlyphButton(Glyph.More, "Ações da transação", { menuOpen = true }, active && (canEdit || hasDelete))
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(editLabel) },
                                onClick = {
                                    closeActions()
                                    onEdit()
                                },
                                enabled = editEnabled,
                                leadingIcon = { DashboardGlyph(Glyph.Edit, DtColors.Muted, Modifier.size(20.dp)) }
                            )
                            if (hasDelete) {
                                DropdownMenuItem(
                                    text = { Text("Excluir") },
                                    onClick = {
                                        closeActions()
                                        onDelete()
                                    },
                                    enabled = deleteEnabled,
                                    leadingIcon = { DashboardGlyph(Glyph.Delete, DtColors.Red, Modifier.size(20.dp)) }
                                )
                            }
                        }
                    }
                }
                val expense = row.input.typeId == Direction.EXPENSE.typeId
                val amount = money(row.input.cents)
                Text(
                    if (expense && row.input.cents >= 0) "−$amount" else amount,
                    color = if (expense) DtColors.Red else DtColors.Green,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                val posted = movementDate(row)?.format(cardDate)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (row.category.isNotBlank()) {
                        TransactionDetail(Glyph.Label, row.category, Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (posted != null) TransactionDetail(Glyph.Calendar, posted)
                }
                when {
                    row.syncRejected -> Text("Sincronização recusada", Modifier.padding(top = 8.dp), color = DtColors.Red, style = MaterialTheme.typography.bodySmall)
                    row.pending -> Text("Aguardando sincronização", Modifier.padding(top = 8.dp), color = DtColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TransactionDetail(glyph: Glyph, label: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        DashboardGlyph(glyph, DtColors.Muted, Modifier.size(20.dp))
        Text(label, color = DtColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GlyphButton(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = DtColors.Muted
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.semantics { contentDescription = label }) {
        DashboardGlyph(glyph, if (enabled) tint else tint.copy(alpha = 0.65f), Modifier.size(24.dp))
    }
}

private enum class Reveal(val sign: Int) { Closed(0), Edit(1), Delete(-1) }

private val ptBr = Locale.forLanguageTag("pt-BR")
private val cardDate = DateTimeFormatter.ofPattern("dd/MM/yyyy", ptBr)
private val lastMovementDate = DateTimeFormatter.ofPattern("d 'de' MMM", ptBr)
private val periodDate = DateTimeFormatter.ofPattern("d 'de' MMMM", ptBr)

private fun localDate(value: String?) = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
}

private fun movementDate(row: Transaction) = localDate(row.occurredAt) ?: localDate(row.createdAt)

private fun lastMovement(row: Transaction?, label: String): String = row?.let(::movementDate)?.let {
    "$label em ${it.format(lastMovementDate)}"
} ?: "Nenhuma transação encontrada"

private fun filterPeriod(filters: Filters): String {
    val from = localDate(filters.from)?.format(periodDate)
    val to = localDate(filters.to)?.format(periodDate)
    return when {
        from != null && to != null -> "$from até $to"
        from != null -> "Desde $from"
        to != null -> "Até $to"
        else -> "Todo o período"
    }
}

private enum class Glyph { Settings, Down, Up, Money, Filter, Refresh, More, Label, Calendar, Edit, Delete }

@Composable
private fun DashboardGlyph(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.65f, cap = StrokeCap.Round)
            fun line(vararg coordinates: Float) {
                val path = Path().apply {
                    moveTo(coordinates[0], coordinates[1])
                    for (index in 2 until coordinates.size step 2) lineTo(coordinates[index], coordinates[index + 1])
                }
                drawPath(path, color, style = stroke)
            }
            when (glyph) {
                Glyph.Settings -> {
                    line(3f, 6f, 21f, 6f)
                    line(3f, 12f, 21f, 12f)
                    line(3f, 18f, 21f, 18f)
                    line(8f, 3f, 8f, 9f)
                    line(16f, 9f, 16f, 15f)
                    line(10f, 15f, 10f, 21f)
                }
                Glyph.Down, Glyph.Up -> {
                    drawCircle(color, radius = 9f, center = Offset(12f, 12f), style = stroke)
                    val tip = if (glyph == Glyph.Up) 7.5f else 16.5f
                    val arms = if (glyph == Glyph.Up) 11f else 13f
                    line(12f, 24f - tip, 12f, tip)
                    line(8.5f, arms, 12f, tip, 15.5f, arms)
                }
                Glyph.Money -> {
                    val curve = Path().apply {
                        moveTo(16f, 7f)
                        cubicTo(14f, 4.5f, 7f, 5f, 7f, 9f)
                        cubicTo(7f, 13f, 17f, 10.5f, 17f, 15f)
                        cubicTo(17f, 19f, 9f, 19.5f, 6.5f, 16.5f)
                    }
                    drawPath(curve, color, style = stroke)
                    line(12f, 3f, 12f, 21f)
                }
                Glyph.Filter -> {
                    line(3f, 6f, 21f, 6f)
                    line(6f, 12f, 18f, 12f)
                    line(10f, 18f, 14f, 18f)
                }
                Glyph.Refresh -> {
                    drawArc(color, -37f, 290f, false, Offset(5f, 5f), Size(14f, 14f), style = stroke)
                    line(15f, 8f, 19f, 8f, 19f, 4f)
                }
                Glyph.More -> {
                    for (y in listOf(6f, 12f, 18f)) drawCircle(color, 1.5f, Offset(12f, y))
                }
                Glyph.Label -> {
                    line(3f, 6f, 14f, 6f, 21f, 12f, 14f, 18f, 3f, 18f, 3f, 6f)
                }
                Glyph.Calendar -> {
                    line(4f, 6f, 20f, 6f, 20f, 20f, 4f, 20f, 4f, 6f)
                    line(4f, 10f, 20f, 10f)
                    line(8f, 4f, 8f, 7f)
                    line(16f, 4f, 16f, 7f)
                }
                Glyph.Edit -> {
                    line(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, 4f, 20f)
                    line(14f, 6f, 18f, 10f)
                }
                Glyph.Delete -> {
                    line(4f, 7f, 20f, 7f)
                    line(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
                    line(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f)
                    line(10f, 10f, 10f, 16f)
                    line(14f, 10f, 14f, 16f)
                }
            }
        }
    }
}
