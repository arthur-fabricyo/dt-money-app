package com.elfabrica.dtmoney

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankAlertTest {
    @Test
    fun readsApprovedPurchaseInBrazilianFormat() {
        val expected = BankPurchase(123490, "Mercado X")
        assertEquals(expected, parseBankPurchase("Compra aprovada de R$ 1.234,90 em Mercado X"))
        assertEquals(expected, parseBankPurchase("Compra aprovada: R$ 1.234,90 em Mercado X"))
    }

    @Test
    fun rejectsUnrecognizedAndNonExpenseAlerts() {
        listOf(
            "Compra recusada de R$ 42,90 em Mercado X",
            "Pix recebido de R$ 42,90",
            "Estorno: Compra aprovada de R$ 42,90 em Mercado X",
            "Compra aprovada de R$ 0,00 em Mercado X",
            "Compra aprovada de R$ 1.23,45 em Mercado X",
            "Compra aprovada de R$ 42,90"
        ).forEach { assertNull(it, parseBankPurchase(it)) }
    }
}
