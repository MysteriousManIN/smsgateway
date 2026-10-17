package com.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogStoreTest {

    @Before
    fun setUp() {
        LogStore.clear()
    }

    @Test
    fun testLogEntryModel() {
        val entry = LogEntry(
            id = 1,
            timestamp = 1000L,
            message = "SMS sent successfully",
            formatted = "[00:00:01] SMS sent successfully"
        )
        assertEquals(1L, entry.id)
        assertEquals(1000L, entry.timestamp)
        assertEquals("SMS sent successfully", entry.message)
        assertEquals("[00:00:01] SMS sent successfully", entry.formatted)
    }

    @Test
    fun testLogStoreAddAndClear() {
        LogStore.add("Test log entry 1")
        LogStore.add("Test log entry 2")

        val logs = LogStore.getAll()
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("Test log entry 2"))
        assertTrue(logs[1].contains("Test log entry 1"))

        LogStore.clear()
        assertEquals(0, LogStore.getAll().size)
    }

    @Test
    fun testLogStoreRemoveLogsContaining() {
        LogStore.add("Backend Alpha - Poll OK")
        LogStore.add("Backend Beta - Poll OK")
        LogStore.add("Backend Alpha - 1 sent")

        assertEquals(3, LogStore.getAll().size)

        LogStore.removeLogsContaining("Alpha")
        val remaining = LogStore.getAll()

        assertEquals(1, remaining.size)
        assertTrue(remaining[0].contains("Beta"))
        assertFalse(remaining[0].contains("Alpha"))
    }

    @Test
    fun testHeartbeatUpdateInPlace() {
        LogStore.setHeartbeat("Poll Backend - 0 pending")
        assertEquals(1, LogStore.getAll().size)
        assertTrue(LogStore.getAll()[0].contains("0 pending"))

        LogStore.setHeartbeat("Poll Backend - 2 pending")
        // Should update the existing poll line, not append a second one
        assertEquals(1, LogStore.getAll().size)
        assertTrue(LogStore.getAll()[0].contains("2 pending"))
    }
}

