package com.elfabrica.dtmoney

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

internal val categoryPalette = listOf(
    "#00B37E", "#F75A68", "#FBA94C", "#E1C542", "#62C4C3", "#4C8BDF", "#818CF8",
    "#A87FEA", "#E879AB", "#E98663", "#996D4B", "#7C7C8A"
)

internal fun normalizeCategoryColor(input: String): String? = input.trim()
    .takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.uppercase(Locale.ROOT)

internal fun suggestCategoryColor(
    categories: List<Category>, current: String? = null, random: Random = Random.Default
): String {
    val excluded = current?.let(::normalizeCategoryColor)
    val choices = categoryPalette.filterNot { it == excluded }
    val used = categories.filterNot { it.archived }.mapNotNull { normalizeCategoryColor(it.color) }.toSet()
    return choices.filterNot { it in used }.ifEmpty { choices }.random(random)
}

internal fun categoryKey(input: String): String = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

internal fun Category.isClassifiable(): Boolean = !archived && !isSystem && name != UNCATEGORIZED

internal data class CategoryDefinition(val name: String, val color: String, val description: String) {
    fun validated(): CategoryDefinition {
        val name = name.trim()
        val description = description.trim()
        val color = normalizeCategoryColor(color)
        require(name.isNotEmpty() && name.length <= 60 && categoryKey(name) != categoryKey(UNCATEGORIZED))
        require(description.length <= 240 && color != null)
        return CategoryDefinition(name, color, description)
    }
}

internal fun parseCategorySeed(json: String): List<CategoryDefinition> {
    val entries = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json).getJSONArray("categories")
    require(entries.length() in 1..500)
    return List(entries.length()) { index ->
        val row = entries.getJSONObject(index)
        require(row.length() == 3 && row.has("name") && row.has("color") && row.has("description"))
        CategoryDefinition(row.get("name") as String, row.get("color") as String, row.get("description") as String).validated()
    }.distinctBy { categoryKey(it.name) }
}

internal fun rankBankCategories(description: String, categories: List<Category>): List<Category> {
    val terms = categoryKey(description).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
    return categories.filter(Category::isClassifiable).sortedByDescending { category ->
        val name = categoryKey(category.name).split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        val usage = categoryKey(category.description).split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        terms.count { it in name } * 3 + terms.count { it in usage }
    }.take(12)
}
