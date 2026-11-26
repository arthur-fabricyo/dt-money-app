package com.elfabrica.dtmoney

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class PurchaseDraft(
    val id: String,
    val description: String,
    val cents: Long,
    val postedAt: Long,
    val uncertain: Boolean
)

internal class ImportStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "bank_imports.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Migração ausente de $oldVersion para $newVersion")

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
