package com.smsgateway

/** Pure validation/normalization helpers — unit-tested, no Android deps. */
object SmsValidator {

    /**
     * Accepts "+91 98765 43210", "919876543210", dashes/spaces/parens.
     * Returns digits-only (10–15) or null when invalid.
     */
    fun normalizePhone(raw: String): String? {
        var digits = raw.trim().replace(Regex("[\\s\\-().]"), "")
        if (digits.startsWith("+")) digits = digits.substring(1)
        if (digits.length !in 10..15 || digits.isEmpty() || !digits.all { it.isDigit() }) return null
        return digits
    }

    fun isValidMessage(message: String): Boolean =
        message.isNotBlank() && message.length <= 1000

    /** Rough SMS part estimate (GSM-7 160/153, UCS-2 70/67). */
    fun partCountEstimate(message: String): Int {
        if (message.isEmpty()) return 0
        val unicode = message.any { it.code > 127 }
        return if (unicode) {
            if (message.length <= 70) 1 else 1 + (message.length - 70 + 66) / 67
        } else {
            if (message.length <= 160) 1 else 1 + (message.length - 160 + 152) / 153
        }
    }
}
