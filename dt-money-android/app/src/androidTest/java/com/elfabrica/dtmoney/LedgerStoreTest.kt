package com.elfabrica.dtmoney

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = "local"
    private val instant = Instant.parse("2026-10-23T12:00:00Z")

    @Test fun offlineEditsAndOutboxPersistAcrossReopen() = withStore { store, reopen ->
        val ledger = store.ledger
        val category = ledger.saveCategory(scope, null, CategoryDefinition("Mercado", "#37BD90", "Compras"))
        val id = ledger.save(scope, TransactionInput("Compras", 5100, category, 2), occurredAt = instant)
        val sent = ledger.syncRequest(scope).mutations
        ledger.save(scope, TransactionInput("Mercado", 5300, category, 2), id)
        val restored = reopen().ledger
        assertEquals(-5300L, restored.page(scope, Filters(), 1).balance)
        assertEquals("Mercado", restored.page(scope, Filters(), 1).items.single().input.description)
        assertTrue(restored.syncRequest(scope).mutations.any { it.mutationId == sent.last().mutationId })
        restored.delete(scope, id)
        assertTrue(restored.page(scope, Filters(), 1).items.isEmpty())
    }

    @Test fun archivingKeepsHistoricalCategoryAndRejectsNewAssignments() = withStore { store, _ ->
        val ledger = store.ledger
        val category = ledger.saveCategory(scope, null, CategoryDefinition("Mercado", "#37BD90", "Compras"))
        ledger.save(scope, TransactionInput("Compras", 5100, category, 2), occurredAt = instant)
        ledger.archiveCategory(scope, category, true)
        ledger.save(scope, TransactionInput("Padaria", 1200, category, 2), occurredAt = instant.plusSeconds(60))
        val rows = ledger.page(scope, Filters(), 1).items
        assertEquals("Mercado", rows.last().category)
        assertEquals("Sem categoria", rows.first().category)
    }

    @Test fun categorySeedReplayPreservesManualChanges() = withStore { store, _ ->
        val ledger = store.ledger
        val seed = CategoryDefinition("Mercado", "#37BD90", "Compras")
        assertEquals(1, ledger.importCategories(scope, listOf(seed)))
        val category = ledger.categories(scope).single { it.name == "Mercado" }
        ledger.saveCategory(scope, category.id, CategoryDefinition("Alimentação", "#4C8BDF", "Compras e refeições"))
        ledger.archiveCategory(scope, category.id, true)
        assertEquals(0, ledger.importCategories(scope, listOf(seed)))
        assertTrue(ledger.categories(scope).single { it.name == "Alimentação" }.archived)
    }

    @Test fun oldGenerationDiscardsTheCursorAndPreservesAnUnsentEdit() = withStore { store, _ ->
        val ledger = store.ledger
        val category = ledger.categories(scope).single { it.isSystem }.id
        val id = ledger.save(scope, TransactionInput("Primeira", 3500, category, 1), occurredAt = instant)
        val batch = ledger.syncRequest(scope)
        val mutation = batch.mutations.single()
        val change = LedgerChange(mutation.entity, mutation.id, 1, mutation.data)
        ledger.applySync(scope, batch, LedgerReply("old", 99, false, false, listOf(change), listOf(LedgerAck(mutation.mutationId, "applied", change))))
        ledger.save(scope, TransactionInput("Editada", 4300, category, 1), id)
        val edited = ledger.syncRequest(scope)
        ledger.applySync(scope, edited, LedgerReply("restored", 1, true, false, listOf(change), emptyList()))
        val next = ledger.syncRequest(scope)
        assertEquals("restored", next.generation)
        assertEquals(1L, next.cursor)
        assertEquals(4300L, ledger.page(scope, Filters(), 1).balance)
        assertEquals(1L, next.mutations.single().baseRevision)
    }

    @Test fun replayKeepsMutationIdentityAndAcknowledgementRebasesAnUnsentFollowup() = withStore { store, _ ->
        val ledger = store.ledger
        val row = ledger.save(scope, TransactionInput("Primeira", 3500, 0, 1), occurredAt = instant)
        val first = ledger.syncRequest(scope)
        ledger.save(scope, TransactionInput("Editada", 4300, 0, 1), row)
        assertEquals(first.mutations.single().mutationId, ledger.syncRequest(scope).mutations.single().mutationId)
        val sent = first.mutations.single()
        val change = LedgerChange(sent.entity, sent.id, 1, sent.data)
        ledger.applySync(scope, first, LedgerReply("generation", 1, false, false, listOf(change), listOf(LedgerAck(sent.mutationId, "applied", change))))
        val next = ledger.syncRequest(scope).mutations.single()
        assertNotEquals(sent.mutationId, next.mutationId)
        assertEquals(1L, next.baseRevision)
        assertEquals(4300L, next.data!!.getLong("cents"))
        assertEquals(4300L, ledger.page(scope, Filters(), 1).balance)
    }

    @Test fun conflictSurvivesReopenAndExplicitResolutionKeepsLocalVersion() = withStore { store, reopen ->
        val ledger = store.ledger
        val row = ledger.save(scope, TransactionInput("Local", 3500, 0, 1), occurredAt = instant)
        val request = ledger.syncRequest(scope)
        val mutation = request.mutations.single()
        val remote = org.json.JSONObject(mutation.data.toString()).put("description", "Servidor").put("cents", 2500)
        val change = LedgerChange(mutation.entity, mutation.id, 2, remote)
        ledger.applySync(scope, request, LedgerReply("generation", 2, false, false, listOf(change), listOf(LedgerAck(mutation.mutationId, "conflict", change))))
        val restored = reopen().ledger
        assertEquals(1, restored.status(scope).conflicts.size)
        assertTrue(restored.syncRequest(scope).mutations.isEmpty())
        assertEquals(3500L, restored.page(scope, Filters(), 1).balance)
        restored.resolve(scope, row, true)
        assertTrue(restored.status(scope).conflicts.isEmpty())
        assertEquals(2L, restored.syncRequest(scope).mutations.single().baseRevision)
        assertEquals(3500L, restored.page(scope, Filters(), 1).balance)
    }

    @Test fun legacyHistoryReplayAndProfilesNeverDuplicateOrMixRows() = withStore { store, _ ->
        val ledger = store.ledger
        val history = LegacyHistory(listOf(Category(3, "Mercado")), listOf(
            Transaction(10, TransactionInput("Compras", 5100, 3, 2), "Mercado", instant.toString(), "capture-1")))
        assertEquals(1, ledger.importLegacy(scope, history))
        assertEquals(0, ledger.importLegacy(scope, history))
        assertEquals(-5100L, ledger.page(scope, Filters(), 1).balance)
        assertTrue(ledger.page("another-profile", Filters(), 1).items.isEmpty())
        assertEquals(1, ledger.importLegacy("another-profile", history))
        assertEquals(1, ledger.page(scope, Filters(), 1).items.size)
    }

    @Test fun inFlightFollowupRetainsIndependentRemoteFields() = withStore { store, _ ->
        val ledger = store.ledger
        val row = ledger.save(scope, TransactionInput("Original", 3500, 0, 1), occurredAt = instant)
        val request = ledger.syncRequest(scope)
        val mutation = request.mutations.single()
        ledger.save(scope, TransactionInput("Original", 4300, 0, 1), row)
        val mergedOnServer = org.json.JSONObject(mutation.data.toString()).put("description", "Outra descrição")
        val change = LedgerChange(mutation.entity, mutation.id, 2, mergedOnServer)
        ledger.applySync(scope, request, LedgerReply("generation", 2, false, false, listOf(change), listOf(LedgerAck(mutation.mutationId, "applied", change))))
        val next = ledger.syncRequest(scope).mutations.single()
        assertEquals("Outra descrição", next.data!!.getString("description"))
        assertEquals(4300L, next.data.getLong("cents"))
        assertTrue(ledger.status(scope).conflicts.isEmpty())
    }

    private fun withStore(block: (ImportStore, () -> ImportStore) -> Unit) {
        val name = "ledger-test-${UUID.randomUUID()}.db"
        var store = ImportStore(context, name)
        try {
            block(store) {
                store.close()
                ImportStore(context, name).also { store = it }
            }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
