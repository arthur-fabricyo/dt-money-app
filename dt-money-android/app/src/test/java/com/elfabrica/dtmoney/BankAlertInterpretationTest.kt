package com.elfabrica.dtmoney

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BankAlertInterpretationTest {
    private fun alert(body: String, bank: Bank = Bank.NUBANK, title: String = "Movimentação") = BankAlert(
        bank, title, body, "android-key", Instant.parse("2026-11-26T15:00:00Z"), null
    )

    @Test fun recognizesNewLayoutsForBothDirectionsAndCards() {
        listOf(
            Triple("O Pix de R$ 0,01 foi recebido na sua conta.", Direction.INCOME, PaymentMethod.PIX),
            Triple("Foi enviado um Pix no valor de R$ 0,01.", Direction.EXPENSE, PaymentMethod.PIX),
            Triple("Sua compra de R$ 0,01 no crédito foi aprovada.", Direction.EXPENSE, PaymentMethod.CREDIT),
            Triple("Sua compra de R$ 0,01 no débito foi confirmada.", Direction.EXPENSE, PaymentMethod.DEBIT),
            Triple("Sua compra de R$ 0,01 foi aprovada.", Direction.EXPENSE, PaymentMethod.UNKNOWN)
        ).forEachIndexed { index, (body, direction, method) ->
            val alert = alert(body, Bank.entries[index % 2])
            assertNull(parseBankMovement(alert))
            val input = requireNotNull(prepareBankAlert(alert))
            val proposal = BankProposal(1, direction, method, "R$ 0,01", body, 8)
            val result = validateBankProposal(input, proposal, listOf(Category(8, "Alimentação")))
            assertEquals(direction, result.movement?.direction)
            assertEquals(method, result.movement?.method)
            assertEquals(1L, result.movement?.cents)
            assertEquals(8, result.categoryId)
        }
    }

    @Test fun inventedAmountInvertedDirectionAndMissingEvidenceStayOutOfBalance() {
        val body = "O Pix de R$ 0,01 foi recebido na sua conta."
        val input = requireNotNull(prepareBankAlert(alert(body)))
        val proposal = BankProposal(1, Direction.INCOME, PaymentMethod.PIX, "R$ 0,01", body, null)
        assertNull(validateBankProposal(input, proposal.copy(cents = 100), emptyList()).movement)
        assertNull(validateBankProposal(input, proposal.copy(direction = Direction.EXPENSE), emptyList()).movement)
        assertNull(validateBankProposal(input, proposal.copy(eventEvidence = "foi recebido R$ 0,01"), emptyList()).movement)
        assertNull(validateBankProposal(input, proposal.copy(amountEvidence = "0,01"), emptyList()).movement)
    }

    @Test fun extraValuesNegationAndForeignCurrencyCannotAuthorizeAnImport() {
        listOf(
            "O Pix de R$ 0,01 foi recebido. Saldo: R$ 200,00.",
            "O Pix de R$ 0,01 não foi recebido.",
            "O Pix abaixo de -R$ 0,01 foi recebido.",
            "O Pix de USD 0,01 foi recebido.",
            "Aviso de Pix. O limite é R$ 0,01."
        ).forEach { body ->
            val input = requireNotNull(prepareBankAlert(alert(body)))
            assertNull(validateBankProposal(input, BankProposal(
                1, Direction.INCOME, PaymentMethod.PIX, "R$ 0,01", body, null
            ), emptyList()).movement)
        }
    }

    @Test fun completedPixVerbsAloneDoNotEstablishTheAccountDirection() {
        listOf(
            "O Pix de R$ 0,01 foi recebido pelo destinatário." to Direction.INCOME,
            "O Pix de R$ 0,01 foi enviado para você." to Direction.EXPENSE,
            "O Pix de R$ 0,01 foi enviado por Ana." to Direction.EXPENSE,
            "O Pix de R$ 0,01 foi enviado da conta de terceiros." to Direction.EXPENSE
        ).forEach { (body, direction) ->
            val input = requireNotNull(prepareBankAlert(alert(body)))
            val proposal = BankProposal(1, direction, PaymentMethod.PIX, "R$ 0,01", body, null)
            assertNull(body, validateBankProposal(input, proposal, emptyList()).movement)
        }
    }

    @Test fun unrelatedAmountsAndPredicatesCannotCompleteTheProposal() {
        val received = "Você recebeu um Pix. Sua mensalidade é de R$ 99,00."
        val card = "Compra de R$ 99,00: cartão aprovado."
        listOf(received to PaymentMethod.PIX, card to PaymentMethod.CREDIT).forEach { (body, method) ->
            val input = requireNotNull(prepareBankAlert(alert(body)))
            val direction = if (method == PaymentMethod.PIX) Direction.INCOME else Direction.EXPENSE
            assertNull(validateBankProposal(input, BankProposal(9900, direction, method, "R$ 99,00", body, null),
                emptyList()).movement)
        }
    }

    @Test fun explicitValueInBodyCanSupportCompletedEventInTitle() {
        val title = "Você recebeu um Pix!"
        val input = requireNotNull(prepareBankAlert(alert("Valor da transferência: R$ 3,49.", title = title)))
        val result = validateBankProposal(input, BankProposal(
            349, Direction.INCOME, PaymentMethod.PIX, "R$ 3,49", title, null
        ), emptyList())
        assertEquals(349L, result.movement?.cents)
    }

    @Test fun removesIdentifiersAndKeepsAQualifiedAmountForManualReview() {
        val candidate = requireNotNull(prepareBankAlert(alert(
            "Pix de R$ 3,49 em processamento. Conta 1234-5, agência 0420, cartão final 9876, p@exemplo.com."
        )))
        listOf("1234-5", "0420", "9876", "p@exemplo.com").forEach { assertFalse(candidate.text.contains(it)) }
        assertEquals(349L, candidate.review.cents)
        assertNull(candidate.review.direction)
    }

    @Test fun eventIdentityKeepsTheOriginalClockDuringUpdates() {
        val first = alert("Pix de teste").copy(eventTime = Instant.parse("2026-11-26T15:00:00Z"))
        val updated = first.copy(postedAt = first.postedAt.plusSeconds(2), eventTime = first.eventTime?.plusSeconds(2))
        assertEquals(bankEventId(first.copy(identityTime = first.eventTime)),
            bankEventId(updated.copy(identityTime = first.eventTime)))
        assertNotEquals(bankEventId(first), bankEventId(updated.copy(notificationKey = "different")))
    }

    @Test fun excludedAlertsNeverBecomeCandidates() {
        listOf(
            "Seu código para autorizar Pix de R$ 0,01 é 123456.",
            "Compra recusada de R$ 0,01.",
            "Pix de R$ 0,01 foi agendado.",
            "Estorno de compra de R$ 0,01.",
            "Sua fatura com compras de R$ 0,01 fechou.",
            "Oferta: faça um Pix e ganhe R$ 0,01."
        ).forEach { assertNull(prepareBankAlert(alert(it))) }
    }

    @Test fun categoryOutsideCatalogAndUnsupportedCardTypeDoNotBlockValidPurchase() {
        val body = "Sua compra de R$ 3,49 foi aprovada."
        val input = requireNotNull(prepareBankAlert(alert(body)))
        val result = validateBankProposal(input, BankProposal(
            349, Direction.EXPENSE, PaymentMethod.CREDIT, "R$ 3,49", body, 5
        ), listOf(Category(7, "Mercado")))
        assertEquals(PaymentMethod.UNKNOWN, result.movement?.method)
        assertNull(result.categoryId)
    }

    @Test fun modelContractRequiresACompletedEventAndExactIntegers() {
        val fields = mapOf(
            "estado" to "CONCLUIDO", "centavos" to 1.0, "direcao" to "INCOME", "tipo" to "PIX", "meio" to "PIX",
            "valorNoAlerta" to "R$ 0,01", "eventoNoAlerta" to "Pix recebido", "categoria" to 8
        )
        assertEquals(1L, parseBankProposal(fields)?.cents)
        assertNull(parseBankProposal(fields + ("estado" to "PENDENTE")))
        assertNull(parseBankProposal(fields + ("centavos" to 1.2)))
        assertNull(parseBankProposal(fields + ("meio" to "INDEFINIDO")))
        assertNull(parseBankProposal(fields - "tipo"))
    }

    @Test fun fallbackStopsWhenCaptureSessionChanges() = runBlocking {
        var current = true
        val result = interpretBankAlert(
            active = { current },
            primary = { current = false; BankModelResult.Unavailable },
            fallback = { error("modelo alternativo não deve receber um alerta de outra sessão") }
        )
        assertNull(result)
    }

    @Test fun unavailablePrimaryUsesFallbackOnlyForCurrentCapture() = runBlocking {
        assertEquals(BankModelResult.Abstained, interpretBankAlert(
            active = { true }, primary = { BankModelResult.Unavailable }, fallback = { BankModelResult.Abstained }
        ))
    }
}
