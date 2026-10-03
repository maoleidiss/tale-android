package com.taolesi.nas

import android.net.TrafficStats
import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TrafficStatsProbeTest {

    @Test
    fun testTrafficStatsAvailabilityInJvm() {
        try {
            val uid = Process.myUid()
            println("Process.myUid() returned: $uid")
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            println("TrafficStats rx: $rx, tx: $tx")
        } catch (t: Throwable) {
            println("TrafficStats threw in JVM environment: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 速率格式化数学算法验证
     */
    @Test
    fun testSpeedFormattingAlgorithm() {
        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "0 B/s"
            val b = bytesPerSec.toDouble()
            return when {
                b >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB/s", b / (1024 * 1024 * 1024))
                b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", b / (1024 * 1024))
                b >= 1024 -> String.format(Locale.US, "%.1f KB/s", b / 1024)
                else -> "${bytesPerSec} B/s"
            }
        }

        assertEquals("0 B/s", formatSpeed(0))
        assertEquals("0 B/s", formatSpeed(-100))
        assertEquals("500 B/s", formatSpeed(500))
        assertEquals("1.5 KB/s", formatSpeed(1536))
        assertEquals("10.0 MB/s", formatSpeed(10 * 1024 * 1024))
        assertEquals("1.2 GB/s", formatSpeed((1.2 * 1024 * 1024 * 1024).toLong()))

        // 验证 NasViewModel.Companion.formatSpeed 产出完全一致
        assertEquals("0 B/s", com.taolesi.nas.ui.viewmodel.NasViewModel.formatSpeed(0))
        assertEquals("0 B/s", com.taolesi.nas.ui.viewmodel.NasViewModel.formatSpeed(-100))
        assertEquals("500 B/s", com.taolesi.nas.ui.viewmodel.NasViewModel.formatSpeed(500))
        assertEquals("1.5 KB/s", com.taolesi.nas.ui.viewmodel.NasViewModel.formatSpeed(1536))
        assertEquals("10.0 MB/s", com.taolesi.nas.ui.viewmodel.NasViewModel.formatSpeed(10 * 1024 * 1024))
        assertEquals("1.2 GB/s", com.taolesi.nas.ui.viewmodel.NasViewModel.formatSpeed((1.2 * 1024 * 1024 * 1024).toLong()))
    }

    /**
     * 验证防御性 TrafficStats 采样逻辑（即便在 JVM 测试或不支持设备上也绝不抛出任何异常崩溃）
     */
    @Test
    fun testDefensiveTrafficSampling() {
        var lastRxBytes = -1L
        var lastTxBytes = -1L
        var downloadSpeedText = "0 B/s"
        var uploadSpeedText = "0 B/s"

        fun sampleSpeeds(getRx: () -> Long, getTx: () -> Long) {
            try {
                val currentRx = getRx()
                val currentTx = getTx()

                if (currentRx >= 0 && lastRxBytes >= 0) {
                    val rxDelta = currentRx - lastRxBytes
                    downloadSpeedText = if (rxDelta > 0) "${rxDelta} B/s" else "0 B/s"
                }
                if (currentTx >= 0 && lastTxBytes >= 0) {
                    val txDelta = currentTx - lastTxBytes
                    uploadSpeedText = if (txDelta > 0) "${txDelta} B/s" else "0 B/s"
                }

                if (currentRx >= 0) lastRxBytes = currentRx
                if (currentTx >= 0) lastTxBytes = currentTx
            } catch (_: Throwable) {
                downloadSpeedText = "0 B/s"
                uploadSpeedText = "0 B/s"
            }
        }

        // 第一次采样 (初始化基准)
        sampleSpeeds({ 1000L }, { 500L })
        assertEquals("0 B/s", downloadSpeedText)
        assertEquals("0 B/s", uploadSpeedText)

        // 第二次采样 (增量计算)
        sampleSpeeds({ 3000L }, { 1500L })
        assertEquals("2000 B/s", downloadSpeedText)
        assertEquals("1000 B/s", uploadSpeedText)

        // 模拟 TrafficStats.UNSUPPORTED (-1)
        sampleSpeeds({ -1L }, { -1L })
        // 遇到 -1 时不更新，保持或降级，绝不报负数
        assertFalse(downloadSpeedText.contains("-"))

        // 模拟 JVM 抛出 NotMocked 异常
        sampleSpeeds({ throw RuntimeException("Method not mocked") }, { throw RuntimeException() })
        assertEquals("0 B/s", downloadSpeedText)
        assertEquals("0 B/s", uploadSpeedText)
    }

    @Test
    fun testDashboardStateSpeedDefaults() {
        val state = com.taolesi.nas.ui.viewmodel.DashboardState()
        assertEquals("0 B/s", state.downloadSpeedText)
        assertEquals("0 B/s", state.uploadSpeedText)

        val updated = state.copy(downloadSpeedText = "1.5 MB/s", uploadSpeedText = "320 KB/s")
        assertEquals("1.5 MB/s", updated.downloadSpeedText)
        assertEquals("320 KB/s", updated.uploadSpeedText)
    }
}
