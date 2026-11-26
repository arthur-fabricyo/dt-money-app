package com.elfabrica.dtmoney

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

internal data class PurchaseDraft(
    val id: String,
    val description: String,
    val cents: Long,
    val postedAt: Long,
    val uncertain: Boolean
)

internal enum class CategorySource { UNSET, MODEL, MANUAL }

internal data class LocalImport(
    val movement: BankMovement,
    val remoteId: Long? = null,
    val sent: Boolean = false,
    val categoryId: Int? = null,
    val categorySource: CategorySource = CategorySource.UNSET,
    val revision: Long = 0,
    val categoryPending: Boolean = false,
    val classified: Boolean = false,
    val deleted: Boolean = false,
    val syncRejected: Boolean = false
)

internal data class ImportReceipt(
    val id: Long,
    val importId: String,
    val categoryId: Int,
    val categorySource: CategorySource,
    val deleted: Boolean
)

internal class ImportStore(context: Context, databaseName: String = "bank_imports.db") :
    SQLiteOpenHelper(context, databaseName, null, 2) {
    private val updates = MutableStateFlow(0L)
    val changes = updates.asStateFlow()
    private val reconciliations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val receipts = reconciliations.asSharedFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE purchase (
                scope TEXT NOT NULL,
                id TEXT NOT NULL,
                description TEXT NOT NULL,
                cents INTEGER NOT NULL,
                posted_at INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'pending',
                PRIMARY KEY (scope, id)
            )
            """.trimIndent()
        )
        createMovements(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) createMovements(db)
        else error("Migração ausente de $oldVersion para $newVersion")
    }

    private fun createMovements(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bank_movement (
                scope TEXT NOT NULL,
                id TEXT NOT NULL,
                bank TEXT NOT NULL,
                direction TEXT NOT NULL,
                method TEXT NOT NULL,
                cents INTEGER NOT NULL,
                description TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                date_source TEXT NOT NULL,
                remote_id INTEGER,
                sent INTEGER NOT NULL DEFAULT 0,
                category_id INTEGER,
                category_source TEXT NOT NULL DEFAULT 'UNSET',
                revision INTEGER NOT NULL DEFAULT 0,
                category_pending INTEGER NOT NULL DEFAULT 0,
                classified INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                sync_rejected INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (scope, id)
            )
            """.trimIndent()
        )
    }

    fun record(scope: String, movement: BankMovement): Boolean {
        val values = ContentValues().apply {
            put("scope", scope)
            put("id", movement.importId)
            put("bank", movement.bank.name)
            put("direction", movement.direction.name)
            put("method", movement.method.name)
            put("cents", movement.cents)
            put("description", movement.description)
            put("occurred_at", movement.occurredAt.toEpochMilli())
            put("date_source", movement.dateSource.name)
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "bank_movement", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (inserted) updates.update { it + 1 }
        return inserted
    }

    fun localImports(scope: String): List<LocalImport> = readableDatabase.rawQuery(
        "SELECT id, bank, direction, method, cents, description, occurred_at, date_source, " +
            "remote_id, sent, category_id, category_source, revision, category_pending, classified, deleted, sync_rejected " +
            "FROM bank_movement WHERE scope = ? ORDER BY occurred_at DESC, id",
        arrayOf(scope)
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) add(
                LocalImport(
                    movement = BankMovement(
                        rows.getString(0), Bank.valueOf(rows.getString(1)), Direction.valueOf(rows.getString(2)),
                        PaymentMethod.valueOf(rows.getString(3)), rows.getLong(4), rows.getString(5),
                        Instant.ofEpochMilli(rows.getLong(6)), DateSource.valueOf(rows.getString(7))
                    ),
                    remoteId = if (rows.isNull(8)) null else rows.getLong(8),
                    sent = rows.getInt(9) != 0,
                    categoryId = if (rows.isNull(10)) null else rows.getInt(10),
                    categorySource = CategorySource.valueOf(rows.getString(11)),
                    revision = rows.getLong(12),
                    categoryPending = rows.getInt(13) != 0,
                    classified = rows.getInt(14) != 0,
                    deleted = rows.getInt(15) != 0,
                    syncRejected = rows.getInt(16) != 0
                )
            )
        }
    }

    fun markSent(scope: String, importId: String) {
        val values = ContentValues().apply { put("sent", true) }
        change(scope, importId, values, " AND sent = 0")
    }

    fun markClassified(scope: String, importId: String) {
        change(scope, importId, ContentValues().apply { put("classified", true) }, " AND classified = 0")
    }

    fun rejectSync(scope: String, entry: LocalImport) {
        val values = ContentValues().apply { put("sync_rejected", true) }
        val changed = writableDatabase.update(
            "bank_movement", values, "scope = ? AND id = ? AND revision = ?",
            arrayOf(scope, entry.movement.importId, entry.revision.toString())
        )
        if (changed > 0) updates.update { it + 1 }
    }

    fun markDeleted(scope: String, remoteId: Long) {
        writableDatabase.update("bank_movement", ContentValues().apply { put("deleted", true) },
            "scope = ? AND remote_id = ?", arrayOf(scope, remoteId.toString()))
        updates.update { it + 1 }
    }

    fun applyCategorySuggestion(scope: String, importId: String, categoryId: Int): Boolean =
        saveCategory(scope, importId, categoryId, CategorySource.MODEL, " AND category_source = 'UNSET'")

    fun setManualCategory(scope: String, importId: String, categoryId: Int): Boolean =
        saveCategory(scope, importId, categoryId, CategorySource.MANUAL, "")

    private fun saveCategory(
        scope: String, importId: String, categoryId: Int, source: CategorySource, condition: String
    ): Boolean {
        val statement = writableDatabase.compileStatement(
            "UPDATE bank_movement SET category_id = ?, category_source = ?, " +
                "revision = revision + 1, category_pending = 1, classified = 1, sync_rejected = 0 " +
                "WHERE scope = ? AND id = ? AND deleted = 0$condition"
        )
        val changed = statement.use {
            it.bindLong(1, categoryId.toLong())
            it.bindString(2, source.name)
            it.bindString(3, scope)
            it.bindString(4, importId)
            it.executeUpdateDelete() == 1
        }
        if (changed) updates.update { it + 1 }
        return changed
    }

    fun acknowledge(scope: String, receipt: ImportReceipt, categoryRevision: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("bank_movement", ContentValues().apply {
                put("remote_id", receipt.id)
                put("sent", true)
                put("deleted", receipt.deleted)
            }, "scope = ? AND id = ?", arrayOf(scope, receipt.importId))
            if (categoryRevision != null) {
                db.update("bank_movement", ContentValues().apply {
                    put("category_id", receipt.categoryId)
                    put("category_source", receipt.categorySource.name)
                    put("category_pending", false)
                }, "scope = ? AND id = ? AND revision = ?", arrayOf(scope, receipt.importId, categoryRevision.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        updates.update { it + 1 }
        reconciliations.tryEmit(Unit)
    }

    private fun change(scope: String, importId: String, values: ContentValues, condition: String) {
        if (writableDatabase.update("bank_movement", values, "scope = ? AND id = ?$condition", arrayOf(scope, importId)) > 0) {
            updates.update { it + 1 }
        }
    }

    fun add(scope: String, id: String, purchase: BankPurchase, postedAt: Long) {
        val values = ContentValues().apply {
            put("scope", scope)
            put("id", id)
            put("description", purchase.description)
            put("cents", purchase.cents)
            put("posted_at", postedAt)
        }
        writableDatabase.insertWithOnConflict("purchase", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun pending(scope: String): List<PurchaseDraft> =
        readableDatabase.rawQuery(
            "SELECT id, description, cents, posted_at, state FROM purchase " +
                "WHERE scope = ? AND state IN ('pending', 'uncertain') ORDER BY posted_at DESC",
            arrayOf(scope)
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    add(PurchaseDraft(rows.getString(0), rows.getString(1), rows.getLong(2),
                        rows.getLong(3), rows.getString(4) == "uncertain"))
                }
            }
        }

    fun changeState(scope: String, id: String, from: String, to: String): Boolean {
        val values = ContentValues().apply {
            put("state", to)
            if (to == "closed") {
                put("description", "")
                put("cents", 0)
            }
        }
        return writableDatabase.update(
            "purchase", values, "scope = ? AND id = ? AND state = ?", arrayOf(scope, id, from)
        ) == 1
    }

    companion object {
        private var instance: ImportStore? = null

        @Synchronized fun get(context: Context): ImportStore =
            instance ?: ImportStore(context.applicationContext).also { instance = it }
    }
}
