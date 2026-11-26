package com.elfabrica.dtmoney

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BankMovementTest {
    private val posted = Instant.parse("2026-11-26T18:30:00Z")

    private fun alert(bank: Bank, text: String, key: String = "a", title: String = "") =
        BankAlert(bank, title, text, key, posted, posted)

    @Test fun recognizesCompletedPixAndCardMovements() {
        val received = parseBankMovement(alert(Bank.NUBANK, "Você recebeu um Pix de R$ 1.234,56 de Maria"))
        assertEquals(Direction.INCOME, received?.direction)
        assertEquals(123456L, received?.cents)
        assertEquals(PaymentMethod.PIX, received?.method)
        val sent = parseBankMovement(alert(Bank.SANTANDER, "Pix enviado de R$ 10,00 para Lucas"))
        assertEquals(Direction.EXPENSE, sent?.direction)
        assertEquals(PaymentMethod.PIX, sent?.method)
        for ((bank, text, method) in listOf(
            Triple(Bank.NUBANK, "Compra aprovada no crédito de R$ 12,34 em Loja", PaymentMethod.CREDIT),
            Triple(Bank.SANTANDER, "Compra aprovada no débito de R$ 12,34 em Loja", PaymentMethod.DEBIT),
            Triple(Bank.SANTANDER, "Compra aprovada de R$ 12,34 em Loja", PaymentMethod.UNKNOWN),
        )) {
            val movement = parseBankMovement(alert(bank, text))
            assertEquals(Direction.EXPENSE, movement?.direction)
            assertEquals(method, movement?.method)
        }
    }

    @Test fun rejectsUnclearOrUncompletedAlerts() {
        for (text in listOf(
            "Pix agendado de R$ 10,00 para Lucas",
            "Compra recusada de R$ 10,00 em Loja",
            "Compra aprovada de USD 10,00 em Loja",
            "Compra aprovada de R$ 10,00 em Loja. Total R$ 20,00",
            "Compra aprovada de R$ 999999999999999999999,00 em Loja",
            "Seu código de segurança é 123456",
            "Pix enviado de R$ 0,00",
            "Compra aprovada de R$ 10,00 em Loja | Cancelada",
        )) assertNull(text, parseBankMovement(alert(Bank.NUBANK, text)))
        assertNull(parseBankMovement(alert(
            Bank.SANTANDER, "Compra aprovada de R$ 10,00 em Loja", title = "Estorno"
        )))
    }

    @Test fun prefersAnExplicitDateAndSeparatesTheSidesOfAnOwnAccountPix() {
        val incoming = "Pix recebido de R$ 10,00 de Maria | Data: 26/11/2026 11:20 | E2E: E123456789"
        val outgoing = "Pix enviado de R$ 10,00 para Maria | Data: 26/11/2026 11:20 | E2E: E123456789"
        val first = parseBankMovement(alert(Bank.NUBANK, incoming))!!
        assertEquals(Instant.parse("2026-11-26T14:20:00Z"), first.occurredAt)
        assertEquals(DateSource.ALERT, first.dateSource)
        assertEquals(first.importId, parseBankMovement(alert(Bank.NUBANK, incoming, "repost"))?.importId)
        assertNotEquals(first.importId, parseBankMovement(alert(Bank.SANTANDER, outgoing))?.importId)
        val purchase = "Compra aprovada de R$ 10,00 em Loja"
        assertNotEquals(
            parseBankMovement(alert(Bank.NUBANK, purchase, "first"))?.importId,
            parseBankMovement(alert(Bank.NUBANK, purchase, "second"))?.importId,
        )
        assertEquals(DateSource.POSTED, parseBankMovement(alert(Bank.NUBANK, purchase))?.dateSource)
    }
}
