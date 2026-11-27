package com.elfabrica.dtmoney

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

internal class LedgerStore(private val store: ImportStore) {
    private data class Entry(
        val row: Long, val entity: String, val id: String, val data: JSONObject?,
        val revision: Long, val remote: JSONObject?, val conflict: Boolean
    )

    fun categories(scope: String): List<Category> = categories(store.readableDatabase, scope)

    private fun categories(db: SQLiteDatabase, scope: String): List<Category> =
        listOf(Category(FALLBACK_ROW, UNCATEGORIZED, isSystem = true)) +
            entries(db, scope, "entity = 'category'").mapNotNull(::category)
                .sortedBy { categoryKey(it.name) }

    private fun category(entry: Entry): Category? = entry.data?.let {
        Category(entry.row.toInt(), it.getString("name"), it.getString("color"), it.getString("description"),
            it.getBoolean("archived"))
    }

    fun saveCategory(scope: String, id: Int?, value: CategoryDefinition): Int = write {
        val definition = value.validated()
        val existing = id?.let {
            requireNotNull(entry(this, scope, it.toLong())).also { require(it.entity == "category" && it.data != null) }
        }
        require(categories(this, scope).none { it.id != id && categoryKey(it.name) == categoryKey(definition.name) })
        updateLocal(this, scope, "category", existing?.id ?: UUID.randomUUID().toString(),
            categoryData(definition, existing?.data?.getBoolean("archived") ?: false)).toInt()
    }

    fun archiveCategory(scope: String, id: Int, archived: Boolean) = write {
        val existing = requireNotNull(entry(this, scope, id.toLong()))
        require(existing.entity == "category" && existing.data != null)
        val data = JSONObject(existing.data.toString()).put("archived", archived)
        updateLocal(this, scope, "category", existing.id, data)
    }

    fun importCategories(scope: String, definitions: List<CategoryDefinition>): Int = write {
        val knownNames = categories(this, scope).mapTo(mutableSetOf()) { categoryKey(it.name) }
        definitions.map(CategoryDefinition::validated).count { definition ->
            val key = categoryKey(definition.name)
            val stable = "seed:" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
            if (knownNames.add(key) && entry(this, scope, "category", stable) == null) {
                updateLocal(this, scope, "category", stable, categoryData(definition))
                true
            } else false
        }
    }

    fun page(scope: String, filters: Filters, pages: Int): TransactionPage {
        val db = store.readableDatabase
        val catalog = categories(db, scope).associateBy { it.id.toLong() }
        val stableCategories = entries(db, scope, "entity = 'category'").associate { it.id to it.row }
        val waiting = db.rawQuery("SELECT DISTINCT entity_id FROM ledger_outbox WHERE scope = ? AND entity = 'transaction'",
            arrayOf(scope)).use { buildSet { while (it.moveToNext()) add(it.getString(0)) } }
        val rows = entries(db, scope, "entity = 'transaction'").mapNotNull { item ->
            val data = item.data ?: return@mapNotNull null
            val cat = stableCategories[data.getString("categoryId")]?.let(catalog::get) ?: catalog.getValue(FALLBACK_ROW.toLong())
            val instant = data.getString("occurredAt")
            Transaction(item.row, TransactionInput(data.getString("description"), data.getLong("cents"), cat.id,
                data.getInt("typeId")), cat.name, instant, data.optString("importId").takeIf(String::isNotBlank),
                instant, pending = item.id in waiting, syncRejected = item.conflict, categoryColor = cat.color)
        }.filter { it.matches(filters) }.sortedWith(
            compareByDescending<Transaction> { Instant.parse(it.createdAt) }.thenByDescending { it.id }
        )
        val (income, expenses) = rows.partition { it.input.typeId == Direction.INCOME.typeId }
        val revenue = income.sumOf { it.input.cents }
        val expense = expenses.sumOf { it.input.cents }
        return TransactionPage(rows.take(pages * PAGE_SIZE), pages, (rows.size + PAGE_SIZE - 1) / PAGE_SIZE,
            revenue, expense, revenue - expense)
    }

    fun save(
        scope: String, input: TransactionInput, id: Long? = null, draft: PurchaseDraft? = null,
        occurredAt: Instant = Instant.now()
    ): Long = write {
        val existing = id?.let {
            requireNotNull(entry(this, scope, it)).also { require(it.entity == "transaction" && it.data != null) }
        }
        val data = existing?.data
        val importId = draft?.id ?: data?.optString("importId")?.takeIf(String::isNotBlank)
        if (draft != null) {
            val previous = entry(this, scope, "transaction", importStableId(draft.id))
            if (previous != null) return@write previous.row
            check(store.changeState(scope, draft.id, if (draft.uncertain) "uncertain" else "pending", "closed"))
        }
        val previousCategory = data?.optString("categoryId")
        val stableCategory = selectableCategory(this, scope, input.categoryId, previousCategory)
        val date = data?.getString("occurredAt") ?: (draft?.let { Instant.ofEpochMilli(it.postedAt) } ?: occurredAt).toString()
        val row = updateLocal(this, scope, "transaction", existing?.id ?: importId?.let(::importStableId)
            ?: UUID.randomUUID().toString(), transactionData(input, stableCategory, date, importId))
        importId?.let { markCaptureManual(this, scope, it) }
        row
    }

    fun delete(scope: String, id: Long) = write {
        val existing = requireNotNull(entry(this, scope, id))
        require(existing.entity == "transaction")
        updateLocal(this, scope, "transaction", existing.id, null)
    }

    fun importLegacy(scope: String, history: LegacyHistory): Int = write {
        val mapping = legacyCategories(this, scope, history.categories)
        history.transactions.count { row ->
            val id = row.importId?.let(::importStableId) ?: "legacy-transaction:" + row.id
            if (entry(this, scope, "transaction", id) != null) return@count false
            val categoryId = mapping[row.input.categoryId] ?: FALLBACK
            val date = Instant.parse(row.occurredAt ?: row.createdAt).toString()
            updateLocal(this, scope, "transaction", id, transactionData(row.input, categoryId, date, row.importId))
            true
        }
    }

    fun migrateCaptures(scope: String, legacyCategories: List<Category>) = write {
        val captures = store.localImports(scope).filter {
            entry(this, scope, "transaction", importStableId(it.movement.importId)) == null
        }
        if (captures.isEmpty()) return@write
        val mapping = legacyCategories(this, scope, legacyCategories)
        captures.forEach { captured ->
            val movement = captured.movement
            val stable = importStableId(movement.importId)
            if (captured.deleted) {
                // Keep this identity reserved across repeated imports and future reconciliations.
                insertEntry(this, scope, "transaction", stable, null)
            } else {
                writeCapture(this, scope, movement, mapping[captured.categoryId] ?: FALLBACK)
            }
        }
    }

    // Called in the same SQLite transaction as the bank review or capture state update.
    fun recordCapture(db: SQLiteDatabase, scope: String, movement: BankMovement, categoryId: Int?) {
        val id = importStableId(movement.importId)
        if (entry(db, scope, "transaction", id) == null) {
            writeCapture(db, scope, movement, selectableCategory(db, scope, categoryId))
        }
    }

    fun applyCaptureCategory(db: SQLiteDatabase, scope: String, importId: String, categoryId: Int): Boolean {
        val existing = entry(db, scope, "transaction", importStableId(importId)) ?: return false
        val data = existing.data ?: return false
        val selected = selectableCategory(db, scope, categoryId)
        if (categoryId != FALLBACK_ROW && selected == FALLBACK) return false
        updateLocal(db, scope, "transaction", existing.id, JSONObject(data.toString()).put("categoryId", selected))
        return true
    }

    private fun markCaptureManual(db: SQLiteDatabase, scope: String, importId: String) {
        db.update("bank_movement", ContentValues().apply {
            put("category_source", CategorySource.MANUAL.name)
            put("classified", true)
        }, "scope = ? AND id = ?", arrayOf(scope, importId))
    }

    private fun writeCapture(db: SQLiteDatabase, scope: String, movement: BankMovement, categoryId: String) {
        val input = TransactionInput(movement.description, movement.cents, FALLBACK_ROW, movement.direction.typeId)
        updateLocal(db, scope, "transaction", importStableId(movement.importId), transactionData(
            input, categoryId, movement.occurredAt.toString(), movement.importId
        ))
    }

    private fun legacyCategories(db: SQLiteDatabase, scope: String, categories: List<Category>): Map<Int, String> {
        val existing = entries(db, scope, "entity = 'category'")
            .mapNotNull { item -> category(item)?.let { categoryKey(it.name) to item.id } }.toMap().toMutableMap()
        return categories.associate { category ->
            val stable = "legacy-category:" + category.id
            val name = category.name.trim().take(60)
            val key = categoryKey(name)
            category.id to when {
                key == categoryKey(UNCATEGORIZED) || name.isBlank() -> FALLBACK
                entry(db, scope, "category", stable)?.data != null -> stable
                key in existing -> existing.getValue(key)
                else -> {
                    updateLocal(db, scope, "category", stable, categoryData(CategoryDefinition(name, category.color,
                        category.description.take(240))))
                    existing[key] = stable
                    stable
                }
            }
        }
    }

    private fun selectableCategory(db: SQLiteDatabase, scope: String, row: Int?, previous: String? = null): String {
        val selected = row?.let { entry(db, scope, it.toLong()) }?.takeIf { it.entity == "category" && it.data != null }
        if (selected != null && (!selected.data!!.getBoolean("archived") || selected.id == previous)) return selected.id
        return FALLBACK
    }

    private fun updateLocal(db: SQLiteDatabase, scope: String, entity: String, id: String, data: JSONObject?): Long {
        val old = entry(db, scope, entity, id)
        if (old != null && same(old.data, data)) return old.row
        val row = old?.row ?: insertEntry(db, scope, entity, id, data)
        if (old != null) updateEntry(db, old.row) { put("data", data?.toString()) }
        if (old?.conflict != true) queue(db, scope, entity, id, old?.revision ?: 0, data)
        return row
    }

    private fun insertEntry(db: SQLiteDatabase, scope: String, entity: String, id: String, data: JSONObject?): Long =
        db.insertOrThrow("ledger_entry", null, ContentValues().apply {
            put("scope", scope); put("entity", entity); put("entity_id", id); put("data", data?.toString())
        })

    private fun updateEntry(db: SQLiteDatabase, row: Long, set: ContentValues.() -> Unit) {
        db.update("ledger_entry", ContentValues().apply(set), "row_id = ?", arrayOf(row.toString()))
    }

    private fun queue(db: SQLiteDatabase, scope: String, entity: String, id: String, revision: Long, data: JSONObject?) {
        // Draft mutations can be coalesced. A mutation that could have reached the server stays immutable.
        db.delete("ledger_outbox", "scope = ? AND entity = ? AND entity_id = ? AND frozen = 0", arrayOf(scope, entity, id))
        db.insertOrThrow("ledger_outbox", null, ContentValues().apply {
            put("scope", scope); put("entity", entity); put("entity_id", id); put("mutation", UUID.randomUUID().toString())
            put("base_revision", revision); put("data", data?.toString())
        })
    }

    private fun entry(db: SQLiteDatabase, scope: String, entity: String, id: String) =
        entries(db, scope, "entity = ? AND entity_id = ?", entity, id).singleOrNull()

    private fun entry(db: SQLiteDatabase, scope: String, row: Long) =
        entries(db, scope, "row_id = ?", row.toString()).singleOrNull()

    private fun entries(db: SQLiteDatabase, scope: String, filter: String = "1", vararg args: String): List<Entry> =
        db.rawQuery("SELECT row_id, entity, entity_id, data, revision, remote, conflict FROM ledger_entry WHERE scope = ? AND ($filter)",
            arrayOf(scope, *args)).use {
            buildList {
                while (it.moveToNext()) add(Entry(it.getLong(0), it.getString(1), it.getString(2), it.json(3),
                    it.getLong(4), it.json(5), it.getInt(6) == 1))
            }
        }

    private fun outbox(db: SQLiteDatabase, scope: String, filter: String = "1", vararg args: String) =
        db.rawQuery("SELECT mutation, entity, entity_id, base_revision, data FROM ledger_outbox WHERE scope = ? AND ($filter) ORDER BY sequence",
            arrayOf(scope, *args)).use {
            buildList {
                while (it.moveToNext()) add(LedgerMutation(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.json(4)))
            }
        }

    private data class SyncState(
        val generation: String? = null, val cursor: Long = 0, val resetting: Boolean = false,
        val lastSync: String? = null, val failed: Boolean = false, val lastBackup: String? = null
    )

    private fun syncState(db: SQLiteDatabase, scope: String): SyncState =
        db.rawQuery("SELECT generation, cursor, resetting, last_sync, failed, last_backup FROM ledger_sync WHERE scope = ?",
            arrayOf(scope)).use {
            if (it.moveToFirst()) SyncState(it.getString(0), it.getLong(1), it.getInt(2) == 1, it.getString(3),
                it.getInt(4) == 1, it.getString(5)) else SyncState()
        }

    private fun syncValues(db: SQLiteDatabase, scope: String, update: ContentValues.() -> Unit) {
        db.insertWithOnConflict("ledger_sync", null, ContentValues().apply { put("scope", scope) }, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("ledger_sync", ContentValues().apply(update), "scope = ?", arrayOf(scope))
    }

    fun syncRequest(scope: String): LedgerRequest = store.writableDatabase.transaction {
        val state = syncState(this, scope)
        val mutations = if (state.resetting) emptyList() else outbox(this, scope)
            .distinctBy { it.entity to it.id }.take(SYNC_BATCH)
        mutations.forEach {
            update("ledger_outbox", ContentValues().apply { put("frozen", true) },
                "scope = ? AND mutation = ?", arrayOf(scope, it.mutationId))
        }
        LedgerRequest(state.generation, state.cursor, mutations)
    }

    fun applySync(scope: String, request: LedgerRequest, reply: LedgerReply) = write {
        val state = syncState(this, scope)
        check(state.generation == request.generation && state.cursor == request.cursor)
        check(reply.reset || request.generation == null || reply.generation == request.generation)
        val resetting = state.resetting || reply.reset
        if (reply.reset) delete("ledger_restore", "scope = ?", arrayOf(scope))
        if (resetting) {
            reply.changes.forEach { storeRestored(this, scope, it) }
            if (!reply.hasMore) finishRestore(this, scope)
        } else {
            // Apply acknowledgements first, then fold in newer changes from the ordered pull stream.
            val submitted = request.mutations.associateBy { it.mutationId }
            reply.acknowledgements.forEach { ack ->
                val mutation = requireNotNull(submitted[ack.mutationId])
                applyAck(this, scope, mutation, ack)
            }
            reply.changes.forEach { applyRemote(this, scope, it) }
        }
        syncValues(this, scope) {
            put("generation", reply.generation)
            put("cursor", reply.cursor)
            put("resetting", resetting && reply.hasMore)
            put("failed", false)
            if (!reply.hasMore) put("last_sync", Instant.now().toString())
        }
    }

    private fun applyAck(db: SQLiteDatabase, scope: String, mutation: LedgerMutation, ack: LedgerAck) {
        val change = ack.change
        check(change.entity == mutation.entity && ack.status in setOf("applied", "conflict"))
        val local = requireNotNull(entry(db, scope, mutation.entity, mutation.id))
        db.delete("ledger_outbox", "scope = ? AND mutation = ?", arrayOf(scope, mutation.mutationId))
        val followups = outbox(db, scope, "entity = ? AND entity_id = ?", mutation.entity, mutation.id)
        val canonical = if (local.id == change.id) local else {
            val duplicate = entry(db, scope, change.entity, change.id)
            if (duplicate == null) {
                updateEntry(db, local.row) { put("entity_id", change.id) }
                local.copy(id = change.id)
            } else {
                updateEntry(db, local.row) { putNull("data") }
                duplicate
            }
        }
        db.delete("ledger_outbox", "scope = ? AND entity = ? AND entity_id = ?", arrayOf(scope, local.entity, local.id))
        val remoteIsNewer = change.revision >= canonical.revision
        val revision = maxOf(canonical.revision, change.revision)
        val remote = if (remoteIsNewer) change.data else canonical.remote
        val merged = if (followups.isNotEmpty() && ack.status == "applied") mergeFollowup(mutation.data, local.data, remote)
            else local.data to false
        val conflict = ack.status == "conflict" || merged.second
        val data = if (conflict) local.data else if (followups.isNotEmpty()) merged.first else remote
        updateEntry(db, canonical.row) {
            put("revision", revision); put("remote", remote?.toString())
            put("conflict", conflict); put("data", data?.toString())
        }
        if (!conflict && followups.isNotEmpty() && !same(data, remote)) {
            queue(db, scope, canonical.entity, canonical.id, revision, data)
        }
    }

    // A user can edit again while a frozen mutation is in flight. Preserve remote fields
    // untouched by that later edit instead of resubmitting the old complete document.
    private fun mergeFollowup(base: JSONObject?, local: JSONObject?, remote: JSONObject?): Pair<JSONObject?, Boolean> {
        if (same(local, base)) return remote to false
        if (same(remote, base) || same(remote, local)) return local to false
        if (base == null || local == null || remote == null) return local to true
        val result = JSONObject(remote.toString())
        val keys = (base.keys().asSequence() + local.keys().asSequence() + remote.keys().asSequence()).toSet()
        for (key in keys) {
            val before = base.opt(key)?.toString()
            val ours = local.opt(key)?.toString()
            val theirs = remote.opt(key)?.toString()
            if (ours == before) continue
            if (theirs != before && theirs != ours) return local to true
            if (local.has(key)) result.put(key, local.get(key)) else result.remove(key)
        }
        return result to false
    }

    private fun applyRemote(db: SQLiteDatabase, scope: String, change: LedgerChange) {
        if (change.entity == "category" && change.id == FALLBACK) return
        val local = entry(db, scope, change.entity, change.id)
        if (local != null && local.revision >= change.revision) return
        val row = local?.row ?: insertEntry(db, scope, change.entity, change.id, change.data)
        val pending = outbox(db, scope, "entity = ? AND entity_id = ?", change.entity, change.id).isNotEmpty()
        updateEntry(db, row) {
            put("revision", change.revision); put("remote", change.data?.toString())
            if (!pending && local?.conflict != true) put("data", change.data?.toString())
        }
    }

    private fun storeRestored(db: SQLiteDatabase, scope: String, change: LedgerChange) {
        if (change.entity == "category" && change.id == FALLBACK) return
        db.insertWithOnConflict("ledger_restore", null, ContentValues().apply {
            put("scope", scope); put("entity", change.entity); put("entity_id", change.id); put("revision", change.revision)
            put("data", change.data?.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun finishRestore(db: SQLiteDatabase, scope: String) {
        val snapshot = db.rawQuery("SELECT entity, entity_id, revision, data FROM ledger_restore WHERE scope = ?", arrayOf(scope))
            .use { buildMap { while (it.moveToNext()) put(it.getString(0) to it.getString(1), it.getLong(2) to it.json(3)) } }
            .toMutableMap()
        entries(db, scope).forEach { local ->
            val (revision, remote) = snapshot.remove(local.entity to local.id) ?: (0L to null)
            val unsent = outbox(db, scope, "entity = ? AND entity_id = ?", local.entity, local.id).isNotEmpty()
            val conflict = !same(remote, local.data) && (local.conflict || !same(remote, local.remote))
            updateEntry(db, local.row) {
                put("revision", revision); put("remote", remote?.toString()); put("conflict", conflict)
            }
            db.delete("ledger_outbox", "scope = ? AND entity = ? AND entity_id = ?", arrayOf(scope, local.entity, local.id))
            if (unsent && !conflict && !same(remote, local.data)) {
                queue(db, scope, local.entity, local.id, revision, local.data)
            }
        }
        snapshot.forEach { (key, remote) ->
            applyRemote(db, scope, LedgerChange(key.first, key.second, remote.first, remote.second))
        }
        db.delete("ledger_restore", "scope = ?", arrayOf(scope))
    }

    fun resolve(scope: String, row: Long, keepLocal: Boolean) = write {
        val local = requireNotNull(entry(this, scope, row))
        check(local.conflict)
        updateEntry(this, row) {
            put("conflict", false)
            if (!keepLocal) put("data", local.remote?.toString())
        }
        if (keepLocal && !same(local.data, local.remote)) {
            queue(this, scope, local.entity, local.id, local.revision, local.data)
        }
    }

    fun syncFailed(scope: String) = write { syncValues(this, scope) { put("failed", true) } }

    fun saveBackupDate(scope: String, date: String?) = write { syncValues(this, scope) { put("last_backup", date) } }

    fun status(scope: String): LedgerSyncStatus {
        val db = store.readableDatabase
        val state = syncState(db, scope)
        val pending = db.rawQuery("SELECT COUNT(*) FROM ledger_outbox WHERE scope = ?", arrayOf(scope))
            .use { it.moveToFirst(); it.getInt(0) }
        val conflicts = entries(db, scope, "conflict = 1").map {
            LedgerConflict(it.row, it.entity, summary(db, scope, it.data), summary(db, scope, it.remote))
        }
        return LedgerSyncStatus(pending, state.lastSync, state.failed, conflicts, state.lastBackup)
    }

    private fun summary(db: SQLiteDatabase, scope: String, data: JSONObject?): String = when {
        data == null -> "Excluído"
        data.has("name") -> "${data.getString("name")} · ${data.getString("color")} · ${data.getString("description")}" +
            if (data.getBoolean("archived")) " · Arquivada" else ""
        else -> "${data.getString("description")} · ${money(data.getLong("cents"))} · " +
            (if (data.getInt("typeId") == 1) "Entrada" else "Saída") + " · ${data.getString("occurredAt")}" +
            " · " + (if (data.getString("categoryId") == FALLBACK) UNCATEGORIZED
                else entry(db, scope, "category", data.getString("categoryId"))?.data?.optString("name") ?: "Categoria indisponível")
    }

    private inline fun <T> write(block: SQLiteDatabase.() -> T): T =
        store.writableDatabase.transaction(body = block).also { store.notifyLedgerChanged() }

    companion object {
        const val FALLBACK_ROW = 0
        private const val FALLBACK = "uncategorized"
        private const val PAGE_SIZE = 15
        private const val SYNC_BATCH = 50

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE ledger_entry (
                row_id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, entity TEXT NOT NULL,
                entity_id TEXT NOT NULL, data TEXT, revision INTEGER NOT NULL DEFAULT 0, remote TEXT,
                conflict INTEGER NOT NULL DEFAULT 0, UNIQUE (scope, entity, entity_id)
            )""")
            db.execSQL("""CREATE TABLE ledger_outbox (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, entity TEXT NOT NULL,
                entity_id TEXT NOT NULL, mutation TEXT NOT NULL UNIQUE, base_revision INTEGER NOT NULL,
                data TEXT, frozen INTEGER NOT NULL DEFAULT 0
            )""")
            db.execSQL("CREATE INDEX ledger_outbox_scope ON ledger_outbox (scope, entity, entity_id)")
            db.execSQL("""CREATE TABLE ledger_sync (
                scope TEXT PRIMARY KEY, generation TEXT, cursor INTEGER NOT NULL DEFAULT 0,
                resetting INTEGER NOT NULL DEFAULT 0, last_sync TEXT, failed INTEGER NOT NULL DEFAULT 0, last_backup TEXT
            )""")
            db.execSQL("""CREATE TABLE ledger_restore (
                scope TEXT NOT NULL, entity TEXT NOT NULL, entity_id TEXT NOT NULL, revision INTEGER NOT NULL,
                data TEXT, PRIMARY KEY (scope, entity, entity_id)
            )""")
        }

        private fun importStableId(importId: String) = "import:" + importId

        private fun categoryData(definition: CategoryDefinition, archived: Boolean = false) =
            JSONObject().put("name", definition.name).put("color", definition.color)
                .put("description", definition.description).put("archived", archived)

        private fun transactionData(input: TransactionInput, categoryId: String, date: String, importId: String?): JSONObject {
            val description = input.description.trim()
            require(description.isNotBlank() && description.length <= 300)
            require(input.cents in 1..9_007_199_254_740_991L && input.typeId in 1..2)
            return JSONObject().put("description", description).put("cents", input.cents).put("typeId", input.typeId)
                .put("categoryId", categoryId).put("occurredAt", date).apply { importId?.let { put("importId", it) } }
        }

        private fun Cursor.json(column: Int): JSONObject? = getString(column)?.let(::JSONObject)

        private fun same(left: JSONObject?, right: JSONObject?): Boolean {
            if (left == null || right == null) return left == null && right == null
            val keys = (left.keys().asSequence() + right.keys().asSequence()).toSet()
            return keys.all { left.opt(it)?.toString() == right.opt(it)?.toString() }
        }
    }
}
