package com.elfabrica.dtmoney

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ImportReconciliationTest {
    private fun entry(id: String, cents: Long, direction: Direction, remoteId: Long? = null, sent: Boolean = false) =
        LocalImport(
            BankMovement(id, Bank.NUBANK, direction, PaymentMethod.PIX, cents, "Pix de teste",
                Instant.parse("2025-01-06T15:30:00Z"), DateSource.POSTED),
            remoteId = remoteId,
            sent = sent
        )

    @Test fun countsOfflineEntriesAndNeverAddsAnAcknowledgedRemoteEntryTwice() {
        val income = entry("income", 1000, Direction.INCOME)
        val expense = entry("expense", 400, Direction.EXPENSE)
        assertEquals(600L, reconcileImports(null, listOf(income, expense), Filters(), emptyList()).balance)
        val snapshot = TransactionPage(emptyList(), 1, 1, 1000, 0, 1000, latestTransactionId = 10)
        val merged = reconcileImports(snapshot, listOf(income.copy(remoteId = 9), expense), Filters(), emptyList())
        assertEquals(600L, merged.balance)
        assertEquals(listOf("expense"), merged.items.map { it.importId })
    }

    @Test fun aLostPostResponseAndAnUnloadedRemoteRowDoNotDuplicateTheBalance() {
        val income = entry("income", 1000, Direction.INCOME, sent = true)
        val snapshot = TransactionPage(emptyList(), 1, 2, 1000, 0, 1000,
            latestTransactionId = 9, probedImportIds = setOf("income"), foundImportIds = setOf("income"))
        assertEquals(1000L, reconcileImports(snapshot, listOf(income), Filters(), emptyList()).balance)
        assertTrue(reconcileImports(snapshot, listOf(income), Filters(), emptyList()).items.isEmpty())
    }

    @Test fun aSentButUnprobedEntryDoesNotInflateARemoteTotal() {
        val income = entry("income", 1000, Direction.INCOME, sent = true)
        val snapshot = TransactionPage(emptyList(), 1, 2, 1000, 0, 1000, latestTransactionId = 9)
        val merged = reconcileImports(snapshot, listOf(income), Filters(), emptyList())
        assertEquals(1000L, merged.balance)
        assertTrue(merged.partial)
        assertEquals("income", merged.items.single().importId)
    }

    @Test fun aPendingManualCategoryIsVisibleOnTheRemoteRow() {
        val category = Category(3, "Saúde")
        val local = entry("income", 1000, Direction.INCOME, remoteId = 9)
            .copy(categoryId = category.id, categorySource = CategorySource.MANUAL, categoryPending = true)
        val row = Transaction(9, TransactionInput("Pix de teste", 1000, 9001, 1),
            UNCATEGORIZED, local.movement.occurredAt.toString(), importId = "income")
        val snapshot = TransactionPage(listOf(row), 1, 1, 1000, 0, 1000, latestTransactionId = 9)
        val merged = reconcileImports(snapshot, listOf(local), Filters(), listOf(category))
        assertEquals(category.name, merged.items.single().category)
        assertTrue(merged.items.single().pending)
    }

    @Test fun rejectsEventsBeforeActivationAndForAnUnselectedBank() {
        val since = Instant.parse("2025-01-06T15:30:00Z")
        val config = CaptureConfig("user@server", mapOf(Bank.NUBANK to since), "")
        assertFalse(config.accepts(Bank.NUBANK, since.minusMillis(1)))
        assertFalse(config.accepts(Bank.SANTANDER, since.plusMillis(1)))
        assertTrue(config.accepts(Bank.NUBANK, since.plusMillis(1)))
    }
}
