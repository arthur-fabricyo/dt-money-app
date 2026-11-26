package com.elfabrica.dtmoney

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class Session(val userId: Long, val name: String, val token: String)
internal data class Category(val id: Int, val name: String)
internal data class TransactionInput(
    val description: String,
    val cents: Long,
    val categoryId: Int,
    val typeId: Int
)
internal data class Transaction(
    val id: Long,
    val input: TransactionInput,
    val category: String,
    val createdAt: String
)
internal data class Filters(
    val search: String = "",
    val typeId: Int? = null,
    val categoryIds: Set<Int> = emptySet(),
    val from: String? = null,
    val to: String? = null
)
internal data class TransactionPage(
    val items: List<Transaction>,
    val page: Int,
    val totalPages: Int,
    val revenue: Long,
    val expense: Long,
    val balance: Long
)

internal class ApiError(val status: Int) : IOException("HTTP $status")

internal class MoneyApi(baseUrl: String, private val token: String? = null) {
    private val base = baseUrl.toHttpUrl()

    suspend fun authenticate(email: String, password: String, name: String?): Session {
        val body = JSONObject().put("email", email).put("password", password)
        if (name != null) body.put("name", name)
        val path = if (name == null) "auth/login" else "auth/register"
        val result = JSONObject(request(path, "POST", body))
        val user = result.getJSONObject("user")
        return Session(user.getLong("id"), user.getString("name"), result.getString("token"))
    }

    suspend fun categories(): List<Category> {
        val result = JSONArray(request("transaction/categories"))
        return List(result.length()) { index ->
            val row = result.getJSONObject(index)
            Category(row.getInt("id"), row.getString("name"))
        }
    }

    suspend fun transactions(page: Int, filters: Filters): TransactionPage {
        val result = JSONObject(request("transaction", query = {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", "15")
            filters.search.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("searchText", it) }
            filters.typeId?.let { addQueryParameter("typeId", it.toString()) }
            filters.categoryIds.forEach { addQueryParameter("categoryIds", it.toString()) }
            // The legacy API applies both end-date comparisons when no start date is supplied.
            val start = filters.from ?: filters.to?.let { Instant.EPOCH.toString() }
            start?.let { addQueryParameter("from", it) }
            filters.to?.let { addQueryParameter("to", it) }
        }))
        val rows = result.getJSONArray("data")
        val totals = result.getJSONObject("totalTransactions")
        return TransactionPage(
            items = List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                Transaction(
                    row.getLong("id"),
                    TransactionInput(
                        row.getString("description"), row.cents("value"),
                        row.getInt("categoryId"), row.getInt("typeId")
                    ),
                    row.optJSONObject("category")?.optString("name").orEmpty(),
                    row.optString("createdAt")
                )
            },
            page = result.getInt("page"),
            totalPages = result.getInt("totalPages"),
            revenue = totals.cents("revenue"),
            expense = totals.cents("expense"),
            balance = totals.cents("total")
        )
    }

    suspend fun save(input: TransactionInput, id: Long?) {
        val body = JSONObject()
            .put("description", input.description)
            .put("value", BigDecimal.valueOf(input.cents, 2))
            .put("categoryId", input.categoryId)
            .put("typeId", input.typeId)
        if (id != null) body.put("id", id)
        request("transaction", if (id == null) "POST" else "PUT", body)
    }

    suspend fun delete(id: Long) {
        request("transaction/$id", "DELETE")
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        query: okhttp3.HttpUrl.Builder.() -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments(path).apply(query).build()
        val builder = Request.Builder().url(url)
            .method(method, body?.toString()?.toRequestBody(jsonType))
        token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiError(response.code)
            response.body?.string().orEmpty()
        }
    }

    private fun JSONObject.cents(field: String): Long =
        getString(field).toBigDecimal().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    companion object {
        private val jsonType = "application/json".toMediaType()
        private val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
    }
}
