package com.elfabrica.dtmoney

import org.json.JSONArray
import org.json.JSONObject

internal data class LedgerMutation(
    val mutationId: String, val entity: String, val id: String, val baseRevision: Long, val data: JSONObject?
) {
    fun json(): JSONObject = JSONObject().put("mutationId", mutationId).put("entity", entity).put("id", id)
        .put("baseRevision", baseRevision).put("data", data ?: JSONObject.NULL)
}

internal data class LedgerRequest(val generation: String?, val cursor: Long, val mutations: List<LedgerMutation>) {
    fun json(): JSONObject = JSONObject().put("generation", generation ?: JSONObject.NULL).put("cursor", cursor)
        .put("mutations", JSONArray(mutations.map(LedgerMutation::json)))
}

internal data class LedgerChange(val entity: String, val id: String, val revision: Long, val data: JSONObject?)
internal data class LedgerAck(val mutationId: String, val status: String, val change: LedgerChange)
internal data class LedgerReply(
    val generation: String, val cursor: Long, val reset: Boolean, val hasMore: Boolean,
    val changes: List<LedgerChange>, val acknowledgements: List<LedgerAck>, val lastBackup: String? = null
) {
    companion object {
        fun parse(value: JSONObject): LedgerReply {
            fun change(row: JSONObject) = LedgerChange(row.getString("entity"), row.getString("id"),
                row.getLong("revision"), if (row.isNull("data")) null else row.getJSONObject("data"))
            val changes = value.getJSONArray("changes")
            val acks = value.getJSONArray("acknowledgements")
            return LedgerReply(value.getString("generation"), value.getLong("cursor"), value.getBoolean("reset"),
                value.getBoolean("hasMore"), List(changes.length()) { change(changes.getJSONObject(it)) },
                List(acks.length()) {
                    val row = acks.getJSONObject(it)
                    LedgerAck(row.getString("mutationId"), row.getString("status"), change(row.getJSONObject("change")))
                }, value.optString("lastBackup").takeIf(String::isNotBlank))
        }
    }
}

internal data class LedgerConflict(
    val rowId: Long, val entity: String, val local: String, val remote: String
)

internal data class LedgerSyncStatus(
    val pending: Int = 0, val lastSync: String? = null, val failed: Boolean = false,
    val conflicts: List<LedgerConflict> = emptyList(), val lastBackup: String? = null
)

internal data class LegacyHistory(val categories: List<Category>, val transactions: List<Transaction>)
