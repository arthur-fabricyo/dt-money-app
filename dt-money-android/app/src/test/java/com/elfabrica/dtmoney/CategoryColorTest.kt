package com.elfabrica.dtmoney

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryColorTest {
    @Test
    fun acceptsOnlySixDigitHexWithHash() {
        assertEquals("#37BD90", normalizeCategoryColor(" #37bd90 "))
        listOf("37bd90", "#369", "#ff37bd90", "#37zz90").forEach {
            assertNull(normalizeCategoryColor(it))
        }
    }

    @Test
    fun suggestionPrefersUnusedColorsAndIgnoresArchivedCategories() {
        val palette = generateSequence(emptyList<Category>()) { active ->
            val color = suggestCategoryColor(active, random = Random(1))
            if (active.any { it.color == color }) null else active + Category(active.size + 1, "Categoria", color)
        }.last()
        val archived = palette.last()
        val categories = palette.dropLast(1).map { it.copy(color = it.color.lowercase()) } + archived.copy(archived = true)

        repeat(20) { seed ->
            assertEquals(archived.color, suggestCategoryColor(categories, random = Random(seed)))
        }
    }

    @Test
    fun rerollChangesTheColorEvenWhenEveryAlternativeIsAssigned() {
        val allButGreen = listOf("#F75A68", "#FBA94C", "#E1C542", "#62C4C3", "#4C8BDF", "#818CF8",
            "#A87FEA", "#E879AB", "#E98663", "#996D4B", "#7C7C8A").mapIndexed { index, color ->
            Category(index + 1, "Categoria", color)
        }

        repeat(20) { seed ->
            assertNotEquals("#00B37E", suggestCategoryColor(allButGreen, "#00b37e", Random(seed)))
        }
    }
}
