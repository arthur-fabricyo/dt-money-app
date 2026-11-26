package com.elfabrica.dtmoney

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

internal enum class Bank(val appName: String, val packageName: String) {
    NUBANK("Nubank", "com.nu.production"),
    SANTANDER("Santander", "com.santander.app")
}

internal enum class Direction(val typeId: Int) { INCOME(1), EXPENSE(2) }
internal enum class PaymentMethod { PIX, CREDIT, DEBIT, UNKNOWN }
internal enum class DateSource { ALERT, POSTED }

internal data class BankAlert(
    val bank: Bank,
    val title: String,
    val body: String,
    val notificationKey: String,
    val postedAt: Instant,
    val eventTime: Instant?
)

internal data class BankMovement(
    val importId: String,
    val bank: Bank,
    val direction: Direction,
    val method: PaymentMethod,
    val cents: Long,
    val description: String,
    val occurredAt: Instant,
    val dateSource: DateSource
)

private const val amount = """R\x24\s*(\d{1,3}(?:\.\d{3})+|\d+),(\d{2})"""
private val currency = Regex("""R\x24""", RegexOption.IGNORE_CASE)
private val receivedPix = Regex(
    """(?:Pix recebido|Voc[eê] recebeu (?:um )?Pix)(?: de|:)?\s+""" + amount + """(?:\s+de\s+(.+))?""",
    RegexOption.IGNORE_CASE
)
private val sentPix = Regex(
    """(?:Pix enviado|Voc[eê] enviou (?:um )?Pix)(?: de|:)?\s+""" + amount + """(?:\s+para\s+(.+))?""",
    RegexOption.IGNORE_CASE
)
private val card = Regex(
    """Compra aprovada(?: (?:no|com cartão de) (cr[eé]dito|d[eé]bito))?(?: de|:)?\s+""" +
        amount + """(?:\s+em\s+(.+))?""",
    RegexOption.IGNORE_CASE
)
private val alertDate = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
    .withResolverStyle(ResolverStyle.STRICT)
private val reference = Regex("""E2E:\s*([A-Za-z0-9-]{8,64})""", RegexOption.IGNORE_CASE)
private val incompatibleStatus = Regex(
    """\b(?:estorno|estornad[oa]|cancelad[oa]|recusad[oa]|agendad[oa]|pendente|c[oó]digo de seguran[çc]a)\b""",
    RegexOption.IGNORE_CASE
)
private val sensitiveDescription = Regex(
    """[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|\b(?:\d[ .()-]*){8,}\b"""
)

internal fun parseBankMovement(alert: BankAlert): BankMovement? {
    val body = normalizeBankText(alert.body)
    val title = normalizeBankText(alert.title).trimEnd(':')
    if (incompatibleStatus.containsMatchIn(title) || incompatibleStatus.containsMatchIn(body)) return null
    return parseMovementText(alert, body)
        ?: parseMovementText(alert, if (title.isEmpty()) body else "$title: $body")
}

private fun parseMovementText(alert: BankAlert, text: String): BankMovement? {
    if (text.length > 650 || currency.findAll(text).count() != 1) return null
    val parts = text.split('|').map(String::trim)
    val incoming = receivedPix.matchEntire(parts[0])
    val outgoing = sentPix.matchEntire(parts[0])
    val purchase = card.matchEntire(parts[0])
    val match = incoming ?: outgoing ?: purchase ?: return null
    val direction = if (incoming != null) Direction.INCOME else Direction.EXPENSE
    val method = when {
        purchase == null -> PaymentMethod.PIX
        purchase.groupValues[1].startsWith("cr", ignoreCase = true) -> PaymentMethod.CREDIT
        purchase.groupValues[1].startsWith("d", ignoreCase = true) -> PaymentMethod.DEBIT
        else -> PaymentMethod.UNKNOWN
    }
    val offset = if (purchase == null) 0 else 1
    val cents = (match.groupValues[1 + offset].replace(".", "") + match.groupValues[2 + offset])
        .toLongOrNull()?.takeIf { it in 1..999_999_999_999L } ?: return null
    val fallback = if (method == PaymentMethod.PIX) {
        if (direction == Direction.INCOME) "Pix recebido" else "Pix enviado"
    } else "Compra aprovada"
    val description = sensitiveDescription.replace(match.groupValues[3 + offset], "").trim()
        .take(120).ifBlank { "$fallback · " + alert.bank.appName }
    val datePart = parts.drop(1).singleOrNull { it.startsWith("Data:", ignoreCase = true) }
    val date = datePart?.let {
        runCatching {
            LocalDateTime.parse(it.substringAfter(':').trim(), alertDate)
                .atZone(ZoneId.of("America/Sao_Paulo")).toInstant()
        }.getOrNull()?.takeUnless { it.isAfter(alert.postedAt.plusSeconds(300)) }
    }
    val bankReference = parts.drop(1).mapNotNull { reference.matchEntire(it)?.groupValues?.get(1) }
        .singleOrNull()?.uppercase(Locale.ROOT)
    val eventIdentity = if (bankReference != null) "bank:$bankReference" else
        "android:" + listOf(alert.notificationKey, alert.eventTime ?: alert.postedAt, method, cents, description)
            .joinToString("|")
    val key = MessageDigest.getInstance("SHA-256")
        .digest((alert.bank.name + "|" + direction.name + "|" + eventIdentity).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return BankMovement(
        key, alert.bank, direction, method, cents, description, date ?: alert.postedAt,
        if (date == null) DateSource.POSTED else DateSource.ALERT
    )
}

private fun normalizeBankText(text: String) =
    text.replace('\u00a0', ' ').replace('\u202f', ' ').replace(Regex("""\s+"""), " ").trim()
