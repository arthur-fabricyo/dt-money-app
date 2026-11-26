package com.elfabrica.dtmoney

import java.text.Normalizer
import java.time.Instant
import java.util.Locale

internal enum class ReviewReason {
    INTERRUPTED, MODEL_UNAVAILABLE, NEEDS_CONFIRMATION
}

internal data class BankReview(
    val id: String,
    val bank: Bank,
    val postedAt: Instant,
    val occurredAt: Instant?,
    val cents: Long?,
    val direction: Direction?,
    val method: PaymentMethod?,
    val reason: ReviewReason,
    val categoryId: Int? = null,
    val incomeImportId: String? = null,
    val expenseImportId: String? = null
)

internal data class PreparedBankAlert(
    val text: String,
    val review: BankReview,
    val completeInput: Boolean
)

internal data class BankProposal(
    val cents: Long,
    val direction: Direction,
    val method: PaymentMethod,
    val amountEvidence: String,
    val eventEvidence: String,
    val categoryId: Int?,
    val type: BankProposalType = if (method == PaymentMethod.PIX) BankProposalType.PIX else BankProposalType.PURCHASE
)

internal enum class BankProposalType { PIX, PURCHASE }

internal data class BankProposalDecision(val movement: BankMovement?, val categoryId: Int?)

private const val MAX_ALERT_CHARS = 520
private val movements = Regex("""\b(?:pix|compras?|(?:pagamento|transacao)\b.{0,60}\b(?:cartao|debito|credito))\b""")
private val excluded = Regex(
    """\b(?:otp|token|senha|codigo\s+(?:(?:para|de)\s+(?:autorizar|autorizacao|seguranca|verificacao|acesso|confirmacao)|d[eo]\s+pix)|""" +
        """verificacao em duas etapas|estorn\w*|chargeback|recusad\w*|negad\w*|rejeitad\w*|agend\w*|""" +
        """fatura|cobranca|promocao|oferta|cashback|cupom)\b"""
)
private val nonFinal = Regex(
    """\b(?:nao|nunca|se|pendente|processando|processamento|cancel\w*|falh\w*|tentativa|solicit\w*|""" +
        """suspeit\w*|previst\w*|disponivel para|a confirmar|confirme)\b"""
)
private val uncertainMoney = Regex("""\b(?:limite|saldo|tarifa|juros|mensalidade|usd|eur|dolares?|euros?)\b|(?:us\s*\x24|€|£)""")
private val pix = Regex("""\bpix\b""")
private val card = Regex("""\b(?:compra|cartao)\b""")
private val incoming = Regex(
    """\bpix\b.{0,72}\b(?:recebid[oa]|creditad[oa]|entrou)\b|\b(?:voce\s+recebeu|recebid[oa]|creditad[oa])\b.{0,72}\bpix\b"""
)
private val outgoing = Regex(
    """\bpix\b.{0,72}\b(?:enviad[oa]|debitad[oa])\b|\b(?:voce\s+enviou|enviad[oa]|debitad[oa])\b.{0,72}\bpix\b"""
)
private val approved = Regex(
    """\b(?:compra|pagamento com cartao)\b[^:]{0,72}\b(?:aprovad[oa]|confirmad[oa]|autorizad[oa])\b|""" +
        """\b(?:aprovamos|confirmamos|autorizamos)\b.{0,40}\bcompra\b"""
)
private val disputedPixDirection = Regex(
    """\b(?:recebid[oa]|creditad[oa])\s+(?:pel[oa]\s+(?:destinatari[oa]|beneficiari[oa]|favorecid[oa])|na conta (?:de|do|da))\b|""" +
        """\b(?:enviad[oa]|debitad[oa])\s+(?:por|da conta (?:de|do|da)|para\s+(?:voce|sua conta)|a\s+voce)\b"""
)
private val cardApproval = Regex("""\bcartao\s+(?:foi\s+)?(?:aprovado|confirmado|autorizado)\b""")
private val credit = Regex("""\bcredito\b""")
private val debit = Regex("""\bdebito\b""")
private val reais = Regex(BANK_AMOUNT + """(?!\d|[.,]\d)""", RegexOption.IGNORE_CASE)
private val realMarkers = Regex("""(?<!\p{L})R\x24""", RegexOption.IGNORE_CASE)
private val negativeAmount = Regex("""[-−]\s*R\x24|R\x24\s*[-−]""", RegexOption.IGNORE_CASE)
private val explicitDate = Regex("""\b(\d{2}/\d{2}/\d{4})\s+(?:(?:as|às)\s+)?(\d{2}:\d{2})\b""", RegexOption.IGNORE_CASE)
private val separators = Regex("""[!?;|\n]+|\.(?=\s|$)""")
private val labeledAmount = Regex("""(?:valor(?:\s+d[ao]\s+(?:pix|transferencia|compra|transacao))?\s*:?\s*)?""" + BANK_AMOUNT,
    RegexOption.IGNORE_CASE)
private val privateText = Regex(
    """[\p{L}\d._%+-]+@[\p{L}\d.-]+\.[\p{L}]{2,}|\b\d{3}\.\d{3}\.\d{3}-\d{2}\b|""" +
        """\b\d{2}\.\d{3}\.\d{3}/\d{4}-\d{2}\b|\be2e\s*:\s*[A-Za-z0-9-]+|""" +
        """\b(?:cpf|cnpj|chave pix|ag[eê]ncia|conta|cart[aã]o (?:final|terminado em))\s*[:#]?\s*\d[\d./-]*|""" +
        """\b(?:\+55\s*)?\(?\d{2}\)?[ -]\d{4,5}[- ]\d{4}\b|\b\d{6,}\b""", RegexOption.IGNORE_CASE
)

private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT)

internal fun isExcludedBankAlert(alert: BankAlert): Boolean =
    excluded.containsMatchIn(fold(normalizeBankText(alert.title + " " + alert.body)))

internal fun prepareBankAlert(alert: BankAlert): PreparedBankAlert? {
    if (isExcludedBankAlert(alert)) return null
    val original = listOf(alert.title, alert.body).map(::normalizeBankText).filter(String::isNotEmpty).joinToString("\n")
    if (!movements.containsMatchIn(fold(original))) return null
    val sanitized = privateText.replace(original, "[omitido]")
    val text = sanitized.take(MAX_ALERT_CHARS)
    val complete = sanitized.length <= MAX_ALERT_CHARS
    val occurredAt = explicitDate.findAll(original).mapNotNull {
        parseAlertDate(it.groupValues[1] + " " + it.groupValues[2], alert.postedAt)
    }.distinct().toList().singleOrNull()
    val folded = fold(original)
    val safeMoney = complete && !uncertainMoney.containsMatchIn(folded) && !negativeAmount.containsMatchIn(original)
    val safeDirection = complete && !nonFinal.containsMatchIn(folded)
    val event = verifiedEvent(text).takeIf { safeDirection }
    val cents = uniqueAmount(text)?.takeIf { value ->
        safeMoney && (event?.let { verifiedAmountLink(text, it, value) } ?: fold(text).split(separators).any { clause ->
            movements.containsMatchIn(clause) && uniqueAmount(clause) == value
        })
    }
    val reference = bankReference(original)?.let { "bank:$it" }
    val review = BankReview(
        bankEventId(alert), alert.bank, alert.postedAt, occurredAt,
        cents, event?.direction, event?.method,
        ReviewReason.INTERRUPTED,
        incomeImportId = reference?.let { movementId(alert.bank, Direction.INCOME, it) },
        expenseImportId = reference?.let { movementId(alert.bank, Direction.EXPENSE, it) }
    )
    return PreparedBankAlert(text, review, safeMoney && safeDirection)
}

private fun amountCents(match: MatchResult) = parseCentavos(match.groupValues[1], match.groupValues[2])

private fun uniqueAmount(text: String): Long? {
    if (text.lineSequence().any { realMarkers.findAll(it).count() > 1 }) return null
    val amounts = reais.findAll(text).toList()
    if (amounts.size != realMarkers.findAll(text).count()) return null
    return amounts.map(::amountCents).distinct().singleOrNull()
}

private data class VerifiedEvent(val direction: Direction, val method: PaymentMethod, val matches: List<String>)

private fun verifiedEvent(text: String): VerifiedEvent? {
    val folded = fold(text)
    val clauses = folded.split(separators)
    val received = clauses.flatMap { clause -> incoming.findAll(clause).map { it.value }.toList() }
    val sent = clauses.flatMap { clause -> outgoing.findAll(clause).map { it.value }.toList() }
    val purchases = clauses.filterNot(cardApproval::containsMatchIn)
        .flatMap { clause -> approved.findAll(clause).map { it.value }.toList() }
    if (listOf(received, sent, purchases).count { it.isNotEmpty() } != 1) return null
    return when {
        purchases.isNotEmpty() && !pix.containsMatchIn(folded) -> {
            val method = when {
                credit.containsMatchIn(folded) && !debit.containsMatchIn(folded) -> PaymentMethod.CREDIT
                debit.containsMatchIn(folded) && !credit.containsMatchIn(folded) -> PaymentMethod.DEBIT
                else -> PaymentMethod.UNKNOWN
            }
            VerifiedEvent(Direction.EXPENSE, method, purchases)
        }
        card.containsMatchIn(folded) || disputedPixDirection.containsMatchIn(folded) -> null
        received.isNotEmpty() -> VerifiedEvent(Direction.INCOME, PaymentMethod.PIX, received)
        sent.isNotEmpty() -> VerifiedEvent(Direction.EXPENSE, PaymentMethod.PIX, sent)
        else -> null
    }
}

private fun verifiedAmountLink(text: String, event: VerifiedEvent, cents: Long): Boolean {
    val clauses = fold(text).split(separators).map(String::trim)
    if (clauses.any { clause -> event.matches.any(clause::contains) && uniqueAmount(clause) == cents }) return true
    val sections = text.split('\n', limit = 2)
    if (sections.size != 2 || event.matches.none(fold(sections[0])::contains)) return false
    return fold(sections[1]).split(separators).any { clause ->
        val labeled = labeledAmount.matchEntire(clause.trim())
        labeled != null && amountCents(labeled) == cents
    }
}

internal fun validateBankProposal(
    input: PreparedBankAlert, proposal: BankProposal?, categories: List<Category>
): BankProposalDecision {
    val category = validateCategory(proposal?.categoryId?.toString(), categories) as? CategorySuggestion.Match
    val reviewOnly = BankProposalDecision(null, category?.categoryId)
    if (proposal == null || !input.completeInput) return reviewOnly
    val verified = verifiedEvent(input.text) ?: return reviewOnly
    val source = fold(normalizeBankText(input.text))
    val amountEvidence = fold(normalizeBankText(proposal.amountEvidence))
    val eventEvidence = fold(normalizeBankText(proposal.eventEvidence))
    val amountMatch = reais.matchEntire(amountEvidence) ?: return reviewOnly
    val type = if (verified.method == PaymentMethod.PIX) BankProposalType.PIX else BankProposalType.PURCHASE
    if (amountCents(amountMatch) != proposal.cents || input.review.cents != proposal.cents ||
        !source.contains(amountEvidence) || !source.contains(eventEvidence) ||
        verified.matches.none(eventEvidence::contains) || proposal.direction != verified.direction ||
        proposal.type != type ||
        (proposal.method == PaymentMethod.PIX) != (verified.method == PaymentMethod.PIX)
    ) return reviewOnly

    val method = if (proposal.method == verified.method) verified.method else PaymentMethod.UNKNOWN
    val movement = reviewMovement(input.review, proposal.cents, proposal.direction, method)
    return BankProposalDecision(movement, category?.categoryId)
}

internal fun reviewMovement(
    review: BankReview, cents: Long, direction: Direction, method: PaymentMethod
): BankMovement {
    val alert = BankAlert(review.bank, "", "", "", review.postedAt, null)
    val movement = buildMovement(alert, direction, method, cents, "", review.occurredAt, null)
    val importId = if (direction == Direction.INCOME) review.incomeImportId else review.expenseImportId
    return movement.copy(importId = importId ?: review.id)
}
