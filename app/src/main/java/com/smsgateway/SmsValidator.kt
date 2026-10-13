package com.smsgateway

/** Pure validation/normalization helpers — unit-tested, no Android deps. */
object SmsValidator {

    /**
     * Accepts "+91 98765 43210", "919876543210", "09876543210", dashes/spaces/parens/dots,
     * and "tel:" URIs. Preserves international '+' prefix when present.
     * Returns valid phone format (10–15 digits) or null when invalid.
     */
    fun normalizePhone(raw: String): String? {
        var s = raw.trim()
        if (s.startsWith("tel:", ignoreCase = true)) s = s.substring(4).trim()
        val hasPlus = s.contains("+")
        var digits = s.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        // Strip national trunk prefix '0' (e.g. 09876543210 -> 9876543210)
        if (!hasPlus && digits.length == 11 && digits.startsWith("0")) {
            digits = digits.substring(1)
        }
        if (digits.length !in 10..15) return null
        return if (hasPlus) "+$digits" else digits
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
