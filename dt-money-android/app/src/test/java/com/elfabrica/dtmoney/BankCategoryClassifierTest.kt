package com.elfabrica.dtmoney

import org.junit.Assert.assertEquals
import org.junit.Test

class BankCategoryClassifierTest {
    private val catalog = listOf(Category(3, "Saúde"), Category(9001, "Sem categoria"))

    @Test fun relevantDescriptionRanksPastEarlierCategoriesAndExcludesInactiveOptions() {
        val others = (1..14).map { Category(it, "Outra $it", description = "Despesa diversa") }
        val pharmacy = Category(22, "Saúde", description = "Drogaria e farmácia")
        val archived = pharmacy.copy(id = 21, archived = true)
        val fallback = pharmacy.copy(id = 23, isSystem = true)
        val candidates = rankBankCategories("DROGARIA", others + listOf(pharmacy, archived, fallback))
        assertEquals(pharmacy, candidates.first())
        assertEquals(CategorySuggestion.Abstain, validateCategory("21", candidates + archived))
        assertEquals(CategorySuggestion.Abstain, validateCategory("23", candidates + fallback))
    }

    @Test fun acceptsOnlyExactCategoryIdsFromTheCatalog() {
        assertEquals(CategorySuggestion.Match(3), validateCategory("3", catalog))
        for (output in listOf(null, "0", "03", "3, 4", "9001", "9", "3.5", "Categoria: 3")) {
            assertEquals(output, CategorySuggestion.Abstain, validateCategory(output, catalog))
        }
    }
}
