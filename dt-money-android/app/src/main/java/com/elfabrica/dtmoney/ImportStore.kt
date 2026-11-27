package com.elfabrica.dtmoney

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
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
    SQLiteOpenHelper(context, databaseName, null, 4) {
    val ledger = LedgerStore(this)
    fun notifyLedgerChanged() { updates.update { it + 1 } }
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
        createBankReviews(db)
        LedgerStore.createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMovements(db)
        if (oldVersion < 3) createBankReviews(db)
        if (oldVersion < 4) LedgerStore.createSchema(db)
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
        val inserted = writableDatabase.transaction {
            val added = insertMovement(this, scope, movement, null, CategorySource.UNSET)
            if (added) ledger.recordCapture(this, scope, movement, null)
            added
        }
        if (inserted) updates.update { it + 1 }
        return inserted
    }

    private fun insertMovement(
        db: SQLiteDatabase, scope: String, movement: BankMovement, categoryId: Int?, source: CategorySource
    ): Boolean {
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
            put("category_id", categoryId)
            put("category_source", if (categoryId == null) CategorySource.UNSET.name else source.name)
            put("category_pending", categoryId != null)
            put("classified", source != CategorySource.UNSET)
        }
        return db.insertWithOnConflict(
            "bank_movement", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    private fun createBankReviews(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bank_alert (
                scope TEXT NOT NULL,
                id TEXT NOT NULL,
                state TEXT NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
                bank TEXT,
                posted_at INTEGER,
                occurred_at INTEGER,
                cents INTEGER,
                direction TEXT,
                method TEXT,
                reason TEXT,
                category_id INTEGER,
                income_import_id TEXT,
                expense_import_id TEXT,
                PRIMARY KEY (scope, id),
                UNIQUE (id)
            )
            """.trimIndent()
        )
    }

    fun addBankReview(scope: String, review: BankReview): Boolean = saveBankReview(scope, review, false) != null

    fun saveBankReview(scope: String, review: BankReview, replacePending: Boolean = true): Long? {
        val values = ContentValues().apply {
            put("scope", scope)
            put("id", review.id)
            put("state", "pending")
            put("bank", review.bank.name)
            put("posted_at", review.postedAt.toEpochMilli())
            put("occurred_at", review.occurredAt?.toEpochMilli())
            put("cents", review.cents)
            put("direction", review.direction?.name)
            put("method", review.method?.name)
            put("reason", review.reason.name)
            putNull("category_id")
            put("income_import_id", review.incomeImportId)
            put("expense_import_id", review.expenseImportId)
        }
        val currentRevision = writableDatabase.transaction {
            val inserted = insertWithOnConflict("bank_alert", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (inserted) return@transaction 0L
            if (!replacePending) return@transaction null
            val previous = rawQuery(
                "SELECT revision FROM bank_alert WHERE scope = ? AND id = ? AND state = 'pending'",
                arrayOf(scope, review.id)
            ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@transaction null
            values.put("revision", previous + 1)
            updatePendingReview(this, scope, review.id, values)
            previous + 1
        }
        if (currentRevision != null) updates.update { it + 1 }
        return currentRevision
    }

    fun bankReviews(scope: String): List<BankReview> = readBankReviews(readableDatabase, scope, null)

    private fun readBankReviews(db: SQLiteDatabase, scope: String, id: String?): List<BankReview> {
        val byId = if (id == null) "" else " AND id = ?"
        return db.rawQuery(
            "SELECT id, bank, posted_at, occurred_at, cents, direction, method, reason, category_id, " +
                "income_import_id, expense_import_id FROM bank_alert " +
                "WHERE scope = ? AND state = 'pending'$byId ORDER BY posted_at DESC, id",
            if (id == null) arrayOf(scope) else arrayOf(scope, id)
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) add(BankReview(
                    rows.getString(0), Bank.valueOf(rows.getString(1)), Instant.ofEpochMilli(rows.getLong(2)),
                    if (rows.isNull(3)) null else Instant.ofEpochMilli(rows.getLong(3)),
                    if (rows.isNull(4)) null else rows.getLong(4),
                    rows.getString(5)?.let(Direction::valueOf), rows.getString(6)?.let(PaymentMethod::valueOf),
                    ReviewReason.valueOf(rows.getString(7)), if (rows.isNull(8)) null else rows.getInt(8),
                    rows.getString(9), rows.getString(10)
                ))
            }
        }
    }

    fun updateBankReview(scope: String, id: String, revision: Long, reason: ReviewReason, categoryId: Int?) {
        val values = ContentValues().apply {
            put("reason", reason.name)
            put("category_id", categoryId)
        }
        if (updatePendingReview(writableDatabase, scope, id, values, revision)) updates.update { it + 1 }
    }

    fun discardBankReview(scope: String, id: String): Boolean {
        val discarded = updatePendingReview(writableDatabase, scope, id, closedReview("discarded"))
        if (discarded) updates.update { it + 1 }
        return discarded
    }

    fun recordCaptured(
        scope: String, id: String, movement: BankMovement, categoryId: Int? = null, revision: Long? = null
    ): Boolean {
        val recorded = writableDatabase.transaction {
            finishBankReview(this, scope, id, movement, categoryId,
                if (revision == null && categoryId == null) CategorySource.UNSET else CategorySource.MODEL, revision)
        }
        if (recorded) updates.update { it + 1 }
        return recorded
    }

    fun confirmBankReview(
        scope: String, id: String, cents: Long, direction: Direction, method: PaymentMethod, categoryId: Int?
    ): Boolean {
        if (cents !in 1..999_999_999_999L) return false
        val recorded = writableDatabase.transaction {
            val review = readBankReviews(this, scope, id).singleOrNull() ?: return@transaction false
            val movement = reviewMovement(review, cents, direction, method)
            finishBankReview(this, scope, id, movement, categoryId, CategorySource.MANUAL)
        }
        if (recorded) updates.update { it + 1 }
        return recorded
    }

    private fun finishBankReview(
        db: SQLiteDatabase, scope: String, id: String, movement: BankMovement, categoryId: Int?, source: CategorySource,
        revision: Long? = null
    ): Boolean {
        if (!updatePendingReview(db, scope, id, closedReview("recorded"), revision)) {
            if (revision != null) return false
            val values = ContentValues().apply {
                put("scope", scope)
                put("id", id)
                put("state", "recorded")
            }
            if (db.insertWithOnConflict("bank_alert", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                return false
            }
        }
        if (insertMovement(db, scope, movement, categoryId, source)) {
            ledger.recordCapture(db, scope, movement, categoryId)
        }
        return true
    }

    private fun updatePendingReview(
        db: SQLiteDatabase, scope: String, id: String, values: ContentValues, revision: Long? = null
    ): Boolean {
        val where = "scope = ? AND id = ? AND state = 'pending'"
        return if (revision == null) db.update("bank_alert", values, where, arrayOf(scope, id)) == 1
        else db.update("bank_alert", values, "$where AND revision = ?", arrayOf(scope, id, revision.toString())) == 1
    }

    private fun closedReview(state: String) = ContentValues().apply {
        put("state", state)
        listOf("bank", "posted_at", "occurred_at", "cents", "direction", "method", "reason", "category_id",
            "income_import_id", "expense_import_id").forEach(::putNull)
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
        val changed = writableDatabase.transaction {
            val statement = compileStatement(
            "UPDATE bank_movement SET category_id = ?, category_source = ?, " +
                "revision = revision + 1, category_pending = 1, classified = 1, sync_rejected = 0 " +
                "WHERE scope = ? AND id = ? AND deleted = 0$condition"
        )
            val updated = statement.use {
                it.bindLong(1, categoryId.toLong())
                it.bindString(2, source.name)
                it.bindString(3, scope)
                it.bindString(4, importId)
                it.executeUpdateDelete() == 1
            }
            if (updated && !ledger.applyCaptureCategory(this, scope, importId, categoryId) && source == CategorySource.MODEL) {
                // Roll back classification when the catalog changed during inference.
                update("bank_movement", ContentValues().apply {
                    putNull("category_id")
                    put("category_source", CategorySource.UNSET.name)
                    put("classified", true)
                    put("category_pending", false)
                }, "scope = ? AND id = ?", arrayOf(scope, importId))
                false
            } else updated
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
        if (writableDatabase.insertWithOnConflict("purchase", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
            notifyLedgerChanged()
        }
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

    fun moveUnlinkedProfile(target: String) = writableDatabase.transaction {
        val values = ContentValues().apply { put("scope", target) }
        listOf("purchase", "bank_movement", "bank_alert", "ledger_entry", "ledger_outbox").forEach { table ->
            updateWithOnConflict(table, values, "scope = ?", arrayOf(AppSettings.LOCAL_SCOPE), SQLiteDatabase.CONFLICT_IGNORE)
        }
        notifyLedgerChanged()
    }

    companion object {
        private var instance: ImportStore? = null

        @Synchronized fun get(context: Context): ImportStore =
            instance ?: ImportStore(context.applicationContext).also { instance = it }
    }
}
