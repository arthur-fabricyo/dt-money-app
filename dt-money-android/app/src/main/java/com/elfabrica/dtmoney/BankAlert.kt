package com.elfabrica.dtmoney

internal data class BankPurchase(val cents: Long, val description: String)

private val purchaseFormat = Regex(
    """Compra aprovada(?: de|:)\s*R\$\s*(\d{1,3}(?:\.\d{3})+|\d+),(\d{2})\s+em\s+(.{1,160})""",
    RegexOption.IGNORE_CASE
)

internal fun parseBankPurchase(text: String): BankPurchase? {
    val normalized = text.replace('\u00a0', ' ').replace('\u202f', ' ').trim()
    val match = purchaseFormat.matchEntire(normalized) ?: return null
    val (reais, cents, merchant) = match.destructured
    val amount = (reais.replace(".", "") + cents).toLongOrNull() ?: return null
    val description = merchant.trim()
    if (amount <= 0 || description.isBlank()) return null
    return BankPurchase(amount, description)
}
