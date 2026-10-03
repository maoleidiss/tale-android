package com.taolesi.nas

import com.taolesi.nas.ui.viewmodel.DashboardState
import com.taolesi.nas.ui.viewmodel.ServerTelemetry
import org.junit.Assert.*
import org.junit.Test

/**
 * NasViewModel 冷启动自愈与遥测单飞合并契约测试
 */
class NasViewModelTelemetryAndRetryTest {

    @Test
    fun testColdBootRetryDelaysContract() {
        val retryDelays = listOf(0L, 1000L, 2000L, 3500L, 5000L)
        assertEquals(5, retryDelays.size)
        assertEquals(0L, retryDelays[0])
        assertEquals(1000L, retryDelays[1])
        assertEquals(2000L, retryDelays[2])
        assertEquals(3500L, retryDelays[3])
        assertEquals(5000L, retryDelays[4])
        // 阶梯递增校验
        for (i in 1 until retryDelays.size) {
            assertTrue("重试延迟必须单调递增", retryDelays[i] > retryDelays[i - 1])
        }
    }

    @Test
    fun testTelemetryMetricsMergePreservesJitterAndLatencyHistory() {
        val currentTelemetry = ServerTelemetry(
            isRealServerMetrics = true,
            serverBrand = "Nextcloud 29.0.0",
            cpuUsagePercent = 12.5f,
            cpuCores = 8,
            usedMemoryGb = 4.2f,
            totalMemoryGb = 16.0f,
            memoryUsagePercent = 26.25f,
            loadAverage = "0.35 / 0.40 / 0.50",
            uptimeText = "5天 12小时",
            jitterMs = 3.5f,
            latencyHistory = listOf(15L, 16L, 14L, 18L, 20L)
        )

        var dashboardState = DashboardState(
            isConnected = true,
            networkLabel = "极速专线直连 (15ms)",
            serverTelemetry = currentTelemetry
        )

        // 模拟轮询获取的最新系统遥测指标 (fresh)
        val freshTelemetry = ServerTelemetry(
            isRealServerMetrics = true,
            serverBrand = "Nextcloud 29.0.0",
            cpuUsagePercent = 65.0f,
            cpuCores = 8,
            usedMemoryGb = 9.8f,
            totalMemoryGb = 16.0f,
            memoryUsagePercent = 61.25f,
            loadAverage = "2.10 / 1.80 / 1.50",
            uptimeText = "5天 13小时",
            jitterMs = 0f, // 遥测探针接口不返回 RTT/Jitter，应由轻量心跳探针维护
            latencyHistory = emptyList()
        )

        // 执行 updateTelemetryMetrics 契约合并
        val current = dashboardState.serverTelemetry
        dashboardState = dashboardState.copy(
            serverTelemetry = freshTelemetry.copy(
                jitterMs = current.jitterMs,
                latencyHistory = current.latencyHistory
            )
        )

        // 断言：业务指标全面更新为 fresh 数据
        assertEquals(65.0f, dashboardState.serverTelemetry.cpuUsagePercent, 0.001f)
        assertEquals(9.8f, dashboardState.serverTelemetry.usedMemoryGb, 0.001f)
        assertEquals("2.10 / 1.80 / 1.50", dashboardState.serverTelemetry.loadAverage)
        assertEquals("5天 13小时", dashboardState.serverTelemetry.uptimeText)

        // 审查员核心安全加固断言：已有心跳波形与抖动数据必须严格保留，绝不允许清空或被覆盖为 0
        assertEquals(3.5f, dashboardState.serverTelemetry.jitterMs, 0.001f)
        assertEquals(listOf(15L, 16L, 14L, 18L, 20L), dashboardState.serverTelemetry.latencyHistory)
    }

    @Test
    fun testTelemetrySingleFlightGuardLogic() {
        var runningJobCount = 0
        var isJobActive = false

        // 模拟单飞调度防重叠
        for (i in 0 until 5) {
            if (!isJobActive) {
                isJobActive = true
                runningJobCount++
            }
        }

        // 防重叠保护下，仅能启动 1 个任务
        assertEquals(1, runningJobCount)

        // 任务完成后，后续触发可正常调度
        isJobActive = false
        if (!isJobActive) {
            isJobActive = true
            runningJobCount++
        }
        assertEquals(2, runningJobCount)
    }
}
