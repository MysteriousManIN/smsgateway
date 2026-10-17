package com.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStatsTest {

    @Test
    fun testSuccessRateCalculation() {
        val stats1 = GatewayStats(sentToday = 18, failedToday = 2)
        assertEquals("90%", stats1.successRate)

        val stats2 = GatewayStats(sentToday = 0, failedToday = 0, totalSent = 95, totalFailed = 5)
        assertEquals("95%", stats2.successRate)

        val statsEmpty = GatewayStats(sentToday = 0, failedToday = 0, totalSent = 0, totalFailed = 0)
        assertEquals("—", statsEmpty.successRate)
    }

    @Test
    fun testAvgLatencyCalculation() {
        val stats = GatewayStats(totalLatencyMs = 600, latencyCount = 5, lastLatencyMs = 120)
        assertEquals(120L, stats.avgLatencyMs)

        val statsNoCount = GatewayStats(totalLatencyMs = 0, latencyCount = 0, lastLatencyMs = 85)
        assertEquals(85L, statsNoCount.avgLatencyMs)
    }

    @Test
    fun testMultiSimRotationRoundRobin() {
        val availableSubIds = listOf(10, 20)
        var rotationIndex = 0

        fun getNext(subIds: List<Int>): Int {
            val idx = Math.abs(rotationIndex) % subIds.size
            val chosen = subIds[idx]
            rotationIndex = (idx + 1) % subIds.size
            return chosen
        }

        assertEquals(10, getNext(availableSubIds))
        assertEquals(20, getNext(availableSubIds))
        assertEquals(10, getNext(availableSubIds))
        assertEquals(20, getNext(availableSubIds))
    }
}

