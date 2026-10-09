package com.smsgateway

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSerializationTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun testBackendConfigMasking() {
        val configShort = BackendConfig(name = "Short", baseUrl = "https://short.com", token = "12345678")
        assertEquals("••••", configShort.maskedToken())

        val configLong = BackendConfig(name = "Long", baseUrl = "https://long.com", token = "SECRET_TOKEN_9999")
        assertEquals("SECR••••9999", configLong.maskedToken())
    }

    @Test
    fun testPendingResponseSerialization() {
        val json = """
            {
                "messages": [
                    {
                        "id": "msg-001",
                        "to": "919876543210",
                        "message": "Your OTP is 1234",
                        "templateId": "DLT-01",
                        "created_at": "2026-10-09T07:00:00Z"
                    }
                ]
            }
        """.trimIndent()

        val adapter = moshi.adapter(PendingResponse::class.java)
        val response = adapter.fromJson(json)

        assertNotNull(response)
        assertEquals(1, response!!.messages.size)
        val msg = response.messages[0]
        assertEquals("msg-001", msg.id)
        assertEquals("919876543210", msg.to)
        assertEquals("Your OTP is 1234", msg.message)
        assertEquals("DLT-01", msg.templateId)
    }

    @Test
    fun testStatusRequestSerialization() {
        val req = StatusRequest(id = "msg-123", status = "sent", error = null)
        val adapter = moshi.adapter(StatusRequest::class.java)
        val json = adapter.toJson(req)

        assertTrue(json.contains("\"id\":\"msg-123\""))
        assertTrue(json.contains("\"status\":\"sent\""))
    }

    @Test
    fun testQueuedStatusMultiBackendIsolation() {
        val type = Types.newParameterizedType(List::class.java, QueuedStatus::class.java)
        val adapter = moshi.adapter<List<QueuedStatus>>(type)

        val list = listOf(
            QueuedStatus("backend-A", StatusRequest("msg-1", "sent")),
            QueuedStatus("backend-B", StatusRequest("msg-2", "failed", "NO_SERVICE")),
            QueuedStatus("backend-A", StatusRequest("msg-3", "delivered"))
        )

        val json = adapter.toJson(list)
        val deserialized = adapter.fromJson(json)

        assertNotNull(deserialized)
        assertEquals(3, deserialized!!.size)

        val backendAEntries = deserialized.filter { it.backendId == "backend-A" }
        val backendBEntries = deserialized.filter { it.backendId == "backend-B" }

        assertEquals(2, backendAEntries.size)
        assertEquals(1, backendBEntries.size)
        assertEquals("msg-2", backendBEntries[0].req.id)
        assertEquals("NO_SERVICE", backendBEntries[0].req.error)
    }
}
