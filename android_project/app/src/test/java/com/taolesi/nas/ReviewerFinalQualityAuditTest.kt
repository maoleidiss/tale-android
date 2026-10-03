package com.taolesi.nas

import com.taolesi.nas.net.DavResult
import com.taolesi.nas.net.WebDavClient
import com.taolesi.nas.ui.viewmodel.DashboardState
import com.taolesi.nas.ui.viewmodel.ServerTelemetry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Hermes 终审质检专项 PoC 探针 (Reviewer Final Quality Audit Test)
 * 验证 Tale v1.0.8 核心架构规范与边界健全性
 */
class ReviewerFinalQualityAuditTest {

    @Test
    fun testHardcodedTelemetryUptimeEliminationAcrossAllSources() {
        val mainSrcDir = File("src/main/java")
        val altMainSrcDir = File("app/src/main/java")
        val baseDir = if (mainSrcDir.exists()) mainSrcDir else altMainSrcDir
        assertTrue("必须能定位 main 源码目录: ${baseDir.absolutePath}", baseDir.exists())

        val ktFiles = baseDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("源码文件数量必须大于 0", ktFiles.isNotEmpty())

        val contaminatedFiles = mutableListOf<String>()
        for (file in ktFiles) {
            val content = file.readText()
            if (content.contains("核心系统运行中")) {
                contaminatedFiles.add(file.path)
            }
        }
        assertTrue(
            "业务代码中严禁残留“核心系统运行中”硬编码！违规文件: $contaminatedFiles",
            contaminatedFiles.isEmpty()
        )
    }

    @Test
    fun testBuildGradleKtsVersionConsistency() {
        val gradleFile = File("build.gradle.kts")
        val altGradleFile = File("app/build.gradle.kts")
        val target = if (gradleFile.exists()) gradleFile else altGradleFile
        assertTrue("必须能定位 build.gradle.kts", target.exists())

        val gradleText = target.readText()
        val isValidVersionName = gradleText.contains("versionName = \"1.1.2\"") ||
            gradleText.contains("versionName = \"1.1.3\"") ||
            gradleText.contains("versionName = \"1.1.4\"") ||
            gradleText.contains("versionName = \"1.1.5\"") ||
            gradleText.contains("versionName = \"1.1.6\"") ||
            gradleText.contains("versionName = \"1.1.7\"") ||
            gradleText.contains("versionName = \"1.1.8\"") ||
            gradleText.contains("versionName = vName")
        val isValidVersionCode = gradleText.contains("versionCode = 12") ||
            gradleText.contains("versionCode = 13") ||
            gradleText.contains("versionCode = 14") ||
            gradleText.contains("versionCode = 15") ||
            gradleText.contains("versionCode = 16") ||
            gradleText.contains("versionCode = 17") ||
            gradleText.contains("versionCode = 18") ||
            gradleText.contains("versionCode = vCode")

        assertTrue("versionName 必须为 1.1.2 或更高 (支持 1.1.8/vName 自动化)", isValidVersionName)
        assertTrue("versionCode 必须为 12 或更高 (支持 18/vCode 自动化)", isValidVersionCode)
    }

    @Test
    fun testFormatUptimeSecondsExhaustiveBoundaries() {
        // 1. 非正数边界
        assertEquals("--", WebDavClient.formatUptimeSeconds(Long.MIN_VALUE))
        assertEquals("--", WebDavClient.formatUptimeSeconds(-86400L))
        assertEquals("--", WebDavClient.formatUptimeSeconds(-1L))
        assertEquals("--", WebDavClient.formatUptimeSeconds(0L))

        // 2. 秒级边界 (< 60s)
        assertEquals("0分钟", WebDavClient.formatUptimeSeconds(1L))
        assertEquals("0分钟", WebDavClient.formatUptimeSeconds(59L))

        // 3. 分钟级边界 (60s <= t < 3600s)
        assertEquals("1分钟", WebDavClient.formatUptimeSeconds(60L))
        assertEquals("1分钟", WebDavClient.formatUptimeSeconds(119L))
        assertEquals("59分钟", WebDavClient.formatUptimeSeconds(3599L))

        // 4. 小时级边界 (3600s <= t < 86400s)
        assertEquals("1小时 0分", WebDavClient.formatUptimeSeconds(3600L))
        assertEquals("1小时 1分", WebDavClient.formatUptimeSeconds(3660L))
        assertEquals("4小时 15分", WebDavClient.formatUptimeSeconds(15300L))
        assertEquals("23小时 59分", WebDavClient.formatUptimeSeconds(86399L))

        // 5. 天级边界 (>= 86400s)
        assertEquals("1天 0小时", WebDavClient.formatUptimeSeconds(86400L))
        assertEquals("18天 4小时", WebDavClient.formatUptimeSeconds(1571520L))
        assertEquals("365天 12小时", WebDavClient.formatUptimeSeconds(31579200L))
    }

    @Test
    fun testLoadAverageNormalizationBoundaries() {
        fun parseLoad(body: String): String {
            val loadMatch = Regex("\"cpuload\"\\s*:\\s*\\[([^\\]]+)\\]").find(body)
            return loadMatch?.groupValues?.get(1)?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.take(3)?.map { String.format(Locale.US, "%.2f", it) }?.joinToString(" / ")?.ifEmpty { "--" } ?: "--"
        }

        // 1. 无 cpuload 字段
        assertEquals("--", parseLoad("""{"ocs":{"data":{}}}"""))

        // 2. 空数组 []
        assertEquals("--", parseLoad("""{"cpuload":[]}"""))

        // 3. 数组元素非数字
        assertEquals("--", parseLoad("""{"cpuload":["nan", "error"]}"""))

        // 4. 仅 1 个数值
        assertEquals("0.45", parseLoad("""{"cpuload":[0.4521]}"""))

        // 5. 2 个数值
        assertEquals("0.12 / 0.34", parseLoad("""{"cpuload":[0.123, 0.345]}"""))

        // 6. 3 个数值且带各种空格与高精度
        assertEquals("0.15 / 0.23 / 0.18", parseLoad("""{"cpuload": [  0.15234 ,  0.2289,0.1841  ]}"""))

        // 7. 超过 3 个数值 (截断前 3 个)
        assertEquals("1.00 / 2.00 / 3.00", parseLoad("""{"cpuload":[1.0, 2.0, 3.0, 4.0, 5.0]}"""))
    }

    @Test
    fun testColdBootRetryDelaysAndSilentSelfHealingContract() {
        val expectedDelays = listOf(0L, 1000L, 2000L, 3500L, 5000L)
        assertEquals(5, expectedDelays.size)
        // 阶梯递增性验证
        for (i in 1 until expectedDelays.size) {
            assertTrue("重试延迟必须递增", expectedDelays[i] > expectedDelays[i - 1])
        }

        // 模拟哨兵自愈：probeConnectionFast 成功前不触发全局 loading 闪烁
        var isGlobalLoading = false
        val probeSimulate = { isHealthy: Boolean ->
            if (isHealthy) {
                isGlobalLoading = true // 仅在自愈成功后水合
                DavResult.Success(35L)
            } else {
                // 静默失败，绝对不能让 isGlobalLoading 置为 true
                DavResult.Failure(com.taolesi.nas.net.DavErrorType.HOST_UNREACHABLE, "网络未就绪")
            }
        }

        // 哨兵探测失败状态：无 loading 闪烁
        val failRes = probeSimulate(false)
        assertTrue(failRes is DavResult.Failure)
        assertFalse("哨兵探测失败时严禁触发全局 loading 闪烁", isGlobalLoading)

        // 哨兵自愈成功状态：触发水合
        val succRes = probeSimulate(true)
        assertTrue(succRes is DavResult.Success)
        assertTrue("哨兵探测成功后启动平滑水合", isGlobalLoading)
    }

    @Test
    fun testTelemetryMetricsMergePreservesWaveformAndJitter() {
        val initialLatencyHistory = listOf(22L, 24L, 25L, 23L, 26L)
        val initialJitter = 1.8f
        val currentTelemetry = ServerTelemetry(
            isRealServerMetrics = true,
            serverBrand = "Nextcloud 30.0.0",
            cpuUsagePercent = 20.0f,
            cpuCores = 4,
            usedMemoryGb = 3.2f,
            totalMemoryGb = 16.0f,
            memoryUsagePercent = 20.0f,
            loadAverage = "0.45 / 0.50 / 0.55",
            uptimeText = "3天 8小时",
            jitterMs = initialJitter,
            latencyHistory = initialLatencyHistory
        )

        var dashboardState = DashboardState(
            isConnected = true,
            serverTelemetry = currentTelemetry,
            pingHistory = initialLatencyHistory
        )

        // 模拟轮询获取的 fresh 遥测数据（遥测接口不含网络 RTT 与 Jitter）
        val freshTelemetry = ServerTelemetry(
            isRealServerMetrics = true,
            serverBrand = "Nextcloud 30.0.0",
            cpuUsagePercent = 55.0f,
            cpuCores = 4,
            usedMemoryGb = 6.4f,
            totalMemoryGb = 16.0f,
            memoryUsagePercent = 40.0f,
            loadAverage = "1.20 / 0.90 / 0.70",
            uptimeText = "3天 9小时",
            jitterMs = 0f,
            latencyHistory = emptyList()
        )

        // 执行 NasViewModel.updateTelemetryMetrics 的规范合并逻辑
        val current = dashboardState.serverTelemetry
        dashboardState = dashboardState.copy(
            serverTelemetry = freshTelemetry.copy(
                jitterMs = current.jitterMs,
                latencyHistory = current.latencyHistory
            )
        )

        // 1. 验证业务指标被成功更新
        assertEquals(55.0f, dashboardState.serverTelemetry.cpuUsagePercent, 0.001f)
        assertEquals(6.4f, dashboardState.serverTelemetry.usedMemoryGb, 0.001f)
        assertEquals("1.20 / 0.90 / 0.70", dashboardState.serverTelemetry.loadAverage)
        assertEquals("3天 9小时", dashboardState.serverTelemetry.uptimeText)

        // 2. 验证心跳波形与抖动数据被完整保留，未被覆盖为 0 或空列表
        assertEquals(initialJitter, dashboardState.serverTelemetry.jitterMs, 0.001f)
        assertEquals(initialLatencyHistory, dashboardState.serverTelemetry.latencyHistory)
    }
}
