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
}
