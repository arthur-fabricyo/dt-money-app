package com.elfabrica.dtmoney

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "bank_imports_instrumented.db"
    private val scope = "1@https://server.test"
    private val entry = BankMovement(
        "import-1", Bank.NUBANK, Direction.INCOME, PaymentMethod.PIX, 1000L,
        "Pix de teste", Instant.parse("2026-11-26T10:00:00Z"), DateSource.POSTED
    )

    @After fun removeDatabase() { context.deleteDatabase(databaseName) }

    @Test fun migratesLegacyDraftsWithoutTurningThemIntoAutomaticTransactions() {
        context.openOrCreateDatabase(databaseName, 0, null).use { db ->
            db.execSQL("""
                CREATE TABLE purchase (
                    scope TEXT NOT NULL,
                    id TEXT NOT NULL,
                    description TEXT NOT NULL,
                    cents INTEGER NOT NULL,
                    posted_at INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    PRIMARY KEY (scope, id)
                )
            """.trimIndent())
            db.execSQL("INSERT INTO purchase VALUES (?, 'old', 'Compra', 1000, 123, 'uncertain')", arrayOf(scope))
            db.version = 1
        }
        ImportStore(context, databaseName).use { store ->
            assertTrue(store.pending(scope).single().uncertain)
            assertTrue(store.localImports(scope).isEmpty())
            assertTrue(store.record(scope, entry))
        }
        ImportStore(context, databaseName).use { store ->
            assertEquals(entry, store.localImports(scope).single().movement)
            assertTrue(store.pending(scope).single().uncertain)
        }
    }

    @Test fun retryAndDelayedModelCannotReplaceAManualCategoryOrCrossAccounts() {
        ImportStore(context, databaseName).use { store ->
            assertTrue(store.record(scope, entry))
            assertFalse(store.record(scope, entry))
            assertFalse(store.setManualCategory("other", entry.importId, 4))
            assertTrue(store.setManualCategory(scope, entry.importId, 3))
            assertFalse(store.applyCategorySuggestion(scope, entry.importId, 4))
            store.acknowledge(scope, ImportReceipt(8L, entry.importId, 4, CategorySource.MODEL, false), 0)
            val kept = store.localImports(scope).single()
            assertEquals(8L, kept.remoteId)
            assertEquals(3, kept.categoryId)
            assertEquals(CategorySource.MANUAL, kept.categorySource)
            assertTrue(kept.categoryPending)
        }
    }

    @Test fun persistedReviewCanBeConfirmedOfflineOnlyOnce() {
        ImportStore(context, databaseName).use { store ->
            assertTrue(store.addBankReview(scope, review()))
            assertFalse(store.addBankReview(scope, review()))
            assertFalse(store.addBankReview("another", review()))
            assertTrue(store.localImports(scope).isEmpty())
        }
        ImportStore(context, databaseName).use { store ->
            val pending = store.bankReviews(scope).single()
            assertEquals(ReviewReason.INTERRUPTED, pending.reason)
            assertTrue(store.bankReviews("another").isEmpty())
            assertFalse(store.confirmBankReview("another", pending.id, 1, Direction.INCOME, PaymentMethod.PIX, null))
            assertTrue(store.confirmBankReview(scope, pending.id, 1, Direction.INCOME, PaymentMethod.PIX, null))
            assertFalse(store.confirmBankReview(scope, pending.id, 1, Direction.INCOME, PaymentMethod.PIX, null))
            assertTrue(store.bankReviews(scope).isEmpty())
            val transaction = store.localImports(scope).single()
            assertEquals(Direction.INCOME, transaction.movement.direction)
            assertEquals(1L, transaction.movement.cents)
            assertTrue(transaction.classified)
            assertFalse(transaction.sent)
        }
    }

    @Test fun parserUpdatePromotesReviewAndDiscardPreventsLateInference() {
        ImportStore(context, databaseName).use { store ->
            assertTrue(store.addBankReview(scope, review()))
            assertTrue(store.recordCaptured(scope, "alert-1", entry))
            assertFalse(store.recordCaptured(scope, "alert-1", entry.copy(importId = "other")))
            val discarded = review().copy(id = "alert-2")
            assertTrue(store.addBankReview(scope, discarded))
            assertTrue(store.discardBankReview(scope, discarded.id))
            assertFalse(store.recordCaptured(scope, discarded.id, entry.copy(importId = "late")))
            assertFalse(store.addBankReview(scope, discarded))
            assertTrue(store.bankReviews(scope).isEmpty())
            assertEquals(entry, store.localImports(scope).single().movement)
        }
    }

    @Test fun refreshedAlertRejectsAProposalFromAnEarlierRevision() {
        ImportStore(context, databaseName).use { store ->
            val first = requireNotNull(store.saveBankReview(scope, review()))
            val latest = requireNotNull(store.saveBankReview(scope, review().copy(cents = 25)))
            assertFalse(store.recordCaptured(scope, "alert-1", entry, revision = first))
            store.updateBankReview(scope, "alert-1", first, ReviewReason.MODEL_UNAVAILABLE, null)
            assertEquals(25L, store.bankReviews(scope).single().cents)
            assertEquals(ReviewReason.INTERRUPTED, store.bankReviews(scope).single().reason)
            assertTrue(store.recordCaptured(scope, "alert-1", entry.copy(cents = 25), revision = latest))
            val movement = store.localImports(scope).single()
            assertEquals(25L, movement.movement.cents)
            assertTrue(movement.classified)
            assertNull(movement.categoryId)
        }
    }

    private fun review() = BankReview(
        "alert-1", Bank.NUBANK, entry.occurredAt, null, null, null, null, ReviewReason.INTERRUPTED
    )
}
