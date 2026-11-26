package com.elfabrica.dtmoney

import org.junit.Assert.assertEquals
import org.junit.Test

class BankCategoryClassifierTest {
    private val catalog = listOf(Category(3, "Saúde"), Category(9001, "Sem categoria"))

    @Test fun acceptsOnlyExactCategoryIdsFromTheCatalog() {
        assertEquals(CategorySuggestion.Match(3), validateCategory("3", catalog))
        for (output in listOf(null, "0", "03", "3, 4", "9001", "9", "3.5", "Categoria: 3")) {
            assertEquals(output, CategorySuggestion.Abstain, validateCategory(output, catalog))
        }
    }
}
