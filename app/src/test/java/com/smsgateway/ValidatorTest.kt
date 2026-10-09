package com.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidatorTest {

    @Test
    fun normalizePhone_plainDigits() {
        assertEquals("919876543210", SmsValidator.normalizePhone("919876543210"))
    }

    @Test
    fun normalizePhone_internationalFormat() {
        assertEquals("919876543210", SmsValidator.normalizePhone("+91 98765 43210"))
        assertEquals("919876543210", SmsValidator.normalizePhone("+91-98765-43210"))
        assertEquals("919876543210", SmsValidator.normalizePhone("(+91) 9876543210"))
    }

    @Test
    fun normalizePhone_rejectsGarbage() {
        assertNull(SmsValidator.normalizePhone(""))
        assertNull(SmsValidator.normalizePhone("12345"))
        assertNull(SmsValidator.normalizePhone("1234567890123456"))
        assertNull(SmsValidator.normalizePhone("98ab543210"))
        assertNull(SmsValidator.normalizePhone("+"))
    }

    @Test
    fun messageValidation() {
        assertEquals(true, SmsValidator.isValidMessage("Hello"))
        assertEquals(false, SmsValidator.isValidMessage("   "))
        assertEquals(false, SmsValidator.isValidMessage("x".repeat(1001)))
        assertEquals(true, SmsValidator.isValidMessage("x".repeat(1000)))
    }

    @Test
    fun partCountEstimate_gsm() {
        assertEquals(1, SmsValidator.partCountEstimate("x".repeat(160)))
        assertEquals(2, SmsValidator.partCountEstimate("x".repeat(161)))
        assertEquals(2, SmsValidator.partCountEstimate("x".repeat(306)))
        assertEquals(2, SmsValidator.partCountEstimate("x".repeat(307)))
        assertEquals(3, SmsValidator.partCountEstimate("x".repeat(314)))
    }

    @Test
    fun partCountEstimate_unicode() {
        assertEquals(1, SmsValidator.partCountEstimate("न".repeat(70)))
        assertEquals(2, SmsValidator.partCountEstimate("न".repeat(71)))
    }

    @Test
    fun backendUrlNormalization() {
        assertEquals(
            "https://api.example.com/api/v1/sms/",
            BackendConfig(name = "t", baseUrl = "https://api.example.com", token = "x").normalizedBaseUrl()
        )
        assertEquals(
            "https://api.example.com/api/v1/sms/",
            BackendConfig(name = "t", baseUrl = "https://api.example.com/", token = "x").normalizedBaseUrl()
        )
        assertEquals(
            "https://api.example.com/api/v1/sms/",
            BackendConfig(name = "t", baseUrl = "https://api.example.com/api/v1/sms", token = "x").normalizedBaseUrl()
        )
        assertNull(
            BackendConfig(name = "t", baseUrl = "http://api.example.com", token = "x").normalizedBaseUrl()
        )
    }
}
