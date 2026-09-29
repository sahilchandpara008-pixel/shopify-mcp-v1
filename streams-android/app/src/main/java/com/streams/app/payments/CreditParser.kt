package com.streams.app.payments

/**
 * Reads "money received" messages — bank SMS ("INR 149.37 credited to A/c XX12 ... UPI Ref 426912345678")
 * and UPI app notifications ("Rahul paid you ₹149.37") — and pulls out the amount and the
 * 12-digit UPI reference (UTR) when present. Anything that looks like a debit, a request or a
 * failure is ignored.
 */
object CreditParser {
    data class Credit(val amount: Double, val ref: String?)

    private val amountRe = Regex("""(?:₹|\brs\.?|\binr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val creditRe = Regex("""credited|received|deposited|paid you|sent you|added to your""", RegexOption.IGNORE_CASE)
    private val notCreditRe = Regex(
        """debited|withdrawn|spent|paid to|sent to|you paid|you sent|requested|request from|collect request|failed|declined|reversed|refund|\bdue\b|reminder|\botp\b""",
        RegexOption.IGNORE_CASE,
    )
    private val refRe = Regex("""(?<![0-9])([0-9]{12})(?![0-9])""")

    fun parse(text: String?): Credit? {
        if (text.isNullOrBlank()) return null
        if (!creditRe.containsMatchIn(text) || notCreditRe.containsMatchIn(text)) return null
        val amount = amountRe.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null
        return Credit(amount, refRe.find(text)?.groupValues?.get(1))
    }
}
