package com.taolesi.nas.net

import com.taolesi.nas.ui.viewmodel.ServerTelemetry
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Tale v1.0.8 架构方案算法探针 (Reviewer 独立实测)
 */
class TaleV108ReviewerProbeTest {

    private fun formatUptimeSeconds(seconds: Long): String {
        if (seconds <= 0) return "--"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}天 ${hours}小时"
            hours > 0 -> "${hours}小时 ${minutes}分"
            else -> "${minutes}分钟"
        }
    }

    private fun formatLoadAverage(rawCpuloadArrayStr: String?): String {
        if (rawCpuloadArrayStr.isNullOrBlank()) return "--"
        val parsed = rawCpuloadArrayStr.split(",").mapNotNull {
            it.trim().toFloatOrNull()
        }.take(3).map {
            String.format(Locale.US, "%.2f", it)
        }
        return if (parsed.isEmpty()) "--" else parsed.joinToString(" / ")
    }

    @Test
    fun testUptimeFormatting() {
        // 边界用例 1: 0 或负数
        assertEquals("--", formatUptimeSeconds(0))
        assertEquals("--", formatUptimeSeconds(-100))

        // 边界用例 2: 小于 1 小时 (例如 45 分钟 = 2700 秒)
        assertEquals("45分钟", formatUptimeSeconds(2700))

        // 边界用例 3: 4 小时 15 分 (4 * 3600 + 15 * 60 = 15300 秒)
        assertEquals("4小时 15分", formatUptimeSeconds(15300))

        // 边界用例 4: 18 天 4 小时 (18 * 86400 + 4 * 3600 + 32 * 60 = 1571520 秒)
        assertEquals("18天 4小时", formatUptimeSeconds(1571520))
    }

    @Test
    fun testUptimeRegexExtraction() {
        val regex = Regex("\"uptime\"\\s*:\\s*\"?(\\d+)\"?")

        val jsonStandard = """{"server":{"uptime":1572480}}"""
        val sec1 = regex.find(jsonStandard)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        assertEquals(1572480L, sec1)
        assertEquals("18天 4小时", formatUptimeSeconds(sec1))

        val jsonSpaced = """{"server": { "uptime" : 7200 }}"""
        val sec2 = regex.find(jsonSpaced)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        assertEquals(7200L, sec2)
        assertEquals("2小时 0分", formatUptimeSeconds(sec2))

        val jsonStringNum = """{"server": { "uptime" : "15300" }}"""
        val sec3 = regex.find(jsonStringNum)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        assertEquals(15300L, sec3)
        assertEquals("4小时 15分", formatUptimeSeconds(sec3))

        val jsonMissing = """{"server": {}}"""
        val sec4 = regex.find(jsonMissing)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        assertEquals(0L, sec4)
        assertEquals("--", formatUptimeSeconds(sec4))
    }

    @Test
    fun testServerTelemetryMergePreservesJitterAndLatencyHistory() {
        val currentTelemetry = ServerTelemetry(
            isRealServerMetrics = true,
            serverBrand = "Nextcloud 27.1.0",
            cpuUsagePercent = 15f,
            cpuCores = 4,
            usedMemoryGb = 2.0f,
            totalMemoryGb = 8.0f,
            memoryUsagePercent = 25f,
            loadAverage = "0.20 / 0.30 / 0.40",
            uptimeText = "10天 5小时",
            jitterMs = 2.8f,
            latencyHistory = listOf(25L, 26L, 27L, 28L)
        )

        // 模拟轮询获取的最新全息指标 (OkHttp fetchServerTelemetryDetailed 返回)
        val freshTelemetry = ServerTelemetry(
            isRealServerMetrics = true,
            serverBrand = "Nextcloud 27.1.0",
            cpuUsagePercent = 45f,
            cpuCores = 4,
            usedMemoryGb = 3.5f,
            totalMemoryGb = 8.0f,
            memoryUsagePercent = 43.75f,
            loadAverage = "0.85 / 0.60 / 0.45",
            uptimeText = "10天 6小时",
            jitterMs = 0f, // 网络探针尚未填充
            latencyHistory = emptyList() // 网络探针尚未填充
        )

        // Reviewer 加固契约合并逻辑：
        val merged = freshTelemetry.copy(
            jitterMs = currentTelemetry.jitterMs,
            latencyHistory = currentTelemetry.latencyHistory
        )

        assertEquals(45f, merged.cpuUsagePercent, 0.01f)
        assertEquals("0.85 / 0.60 / 0.45", merged.loadAverage)
        assertEquals("10天 6小时", merged.uptimeText)
        // 关键断言：心跳维护的抖动与时延历史绝不丢失
        assertEquals(2.8f, merged.jitterMs, 0.01f)
        assertEquals(listOf(25L, 26L, 27L, 28L), merged.latencyHistory)
    }
}
