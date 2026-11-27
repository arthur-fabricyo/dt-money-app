package com.elfabrica.dtmoney

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BankReviewCard(draft: BankReview, enabled: Boolean, review: () -> Unit, discard: () -> Unit) {
    DtCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(draft.bank.appName, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(draft.cents?.let(::money), date(draft.occurredAt ?: draft.postedAt)).joinToString(" · "),
                color = DtColors.Muted)
            Text(when (draft.reason) {
                ReviewReason.INTERRUPTED -> "Aguardando análise ou análise interrompida."
                ReviewReason.MODEL_UNAVAILABLE -> "IA local indisponível para esta captura."
                ReviewReason.NEEDS_CONFIRMATION -> "Confira se a movimentação foi concluída."
            }, color = DtColors.Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = review, enabled = enabled) { Text("Revisar") }
                TextButton(onClick = discard, enabled = enabled) { Text("Descartar") }
            }
        }
    }
}

@Composable
internal fun BankReviewDialog(
    draft: BankReview, categories: List<Category>, busy: Boolean, close: () -> Unit,
    confirm: (Long, Direction, PaymentMethod, Int?) -> Unit
) {
    val initialAmount = draft.cents?.toString().orEmpty()
    val options = listOf(0 to UNCATEGORIZED) + categories.filter(Category::isClassifiable).map { it.id to it.name }
    var amount by remember { mutableStateOf(initialAmount) }
    var direction by remember { mutableStateOf(draft.direction) }
    var method by remember { mutableStateOf(draft.method ?: PaymentMethod.UNKNOWN) }
    var category by remember { mutableStateOf(draft.categoryId ?: 0) }
    val cents = amount.toLongOrNull()?.takeIf { it in 1..999_999_999_999L }

    DtSheet("Revisar captura do ${draft.bank.appName}", onClose = close, closeEnabled = !busy) {
        Text("Confirme os dados da movimentação antes de lançar.", color = DtColors.Muted)
        CurrencyField(amount, { amount = it })
        DirectionSelector(direction?.typeId) { type -> direction = Direction.entries.first { it.typeId == type } }
        Choice("Meio de pagamento", method.ordinal, listOf(
            PaymentMethod.UNKNOWN.ordinal to "Meio desconhecido", PaymentMethod.PIX.ordinal to "Pix",
            PaymentMethod.CREDIT.ordinal to "Crédito", PaymentMethod.DEBIT.ordinal to "Débito"
        )) { method = PaymentMethod.entries[it] }
        Choice("Categoria", category, options) { category = it }
        val selectedDirection = direction
        DtButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && cents != null && selectedDirection != null,
            onClick = {
                if (cents != null && selectedDirection != null) {
                    confirm(cents, selectedDirection, method, category.takeIf { it != 0 })
                }
            }
        ) { Text("Confirmar lançamento") }
    }
}
