package com.elfabrica.dtmoney

import java.math.BigDecimal
import java.time.Instant

internal const val UNCATEGORIZED = "Sem categoria"

internal fun reconcileImports(
    remote: TransactionPage?,
    local: List<LocalImport>,
    filters: Filters,
    categories: List<Category>
): TransactionPage {
    val base = remote ?: TransactionPage(emptyList(), 1, 0, 0, 0, 0, partial = true)
    val remoteKeys = base.foundImportIds + base.items.mapNotNull(Transaction::importId)
    val watermark = base.latestTransactionId
    val localOnly = local.filter { entry ->
        !entry.deleted && entry.movement.importId !in remoteKeys &&
            (entry.remoteId == null || watermark == null || entry.remoteId > watermark)
    }
    val effectiveCategories = categories.associate { it.id to it.name }
    val unset = categories.firstOrNull { it.name == UNCATEGORIZED }?.id ?: 0
    val ambiguous = if (remote == null) emptySet() else localOnly.filter {
        val id = it.movement.importId
        it.sent && it.remoteId == null && id !in base.probedImportIds && id !in base.unsentAtSnapshot
    }.mapTo(hashSetOf()) { it.movement.importId }
    val rows = localOnly.map { entry ->
        val movement = entry.movement
        val categoryId = entry.categoryId ?: unset
        Transaction(
            id = entry.remoteId ?: 0L,
            input = TransactionInput(movement.description, movement.cents, categoryId, movement.direction.typeId),
            category = effectiveCategories[categoryId] ?: UNCATEGORIZED,
            createdAt = movement.occurredAt.toString(),
            occurredAt = movement.occurredAt.toString(),
            importId = movement.importId,
            pending = !entry.syncRejected,
            syncRejected = entry.syncRejected
        )
    }.filter { it.matches(filters) }
    val counted = rows.filter { it.importId !in ambiguous }
    val revenue = base.revenue + counted.filter { it.input.typeId == Direction.INCOME.typeId }.sumOf { it.input.cents }
    val expense = base.expense + counted.filter { it.input.typeId == Direction.EXPENSE.typeId }.sumOf { it.input.cents }
    val localById = local.associateBy { it.movement.importId }
    val visibleRemote = base.items.map { row ->
        val pending = row.importId?.let(localById::get)?.takeIf { it.categoryPending }
        val categoryId = pending?.categoryId
        if (categoryId == null) row else row.copy(
            input = row.input.copy(categoryId = categoryId),
            category = effectiveCategories[categoryId] ?: row.category,
            pending = !pending.syncRejected, syncRejected = pending.syncRejected
        )
    }
    return base.copy(
        items = (rows + visibleRemote).sortedByDescending { it.movementTime() },
        revenue = revenue, expense = expense, balance = revenue - expense,
        partial = base.partial || ambiguous.isNotEmpty()
    )
}

private fun Transaction.matches(filters: Filters): Boolean {
    val occurred = movementTime()
    if (filters.from?.let(Instant::parse)?.let(occurred::isBefore) == true) return false
    if (filters.to?.let(Instant::parse)?.let(occurred::isAfter) == true) return false
    if (filters.typeId != null && input.typeId != filters.typeId) return false
    if (filters.categoryIds.isNotEmpty() && input.categoryId !in filters.categoryIds) return false
    val search = filters.search.trim()
    val type = if (input.typeId == 1) "Entrada" else "Saída"
    return search.isEmpty() || listOf(
        input.description, category, type, BigDecimal.valueOf(input.cents, 2).stripTrailingZeros().toPlainString()
    ).any { it.contains(search, ignoreCase = true) }
}

private fun Transaction.movementTime(): Instant =
    runCatching { Instant.parse(occurredAt ?: createdAt) }.getOrDefault(Instant.EPOCH)
