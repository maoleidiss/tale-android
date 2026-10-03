package com.taolesi.nas

import com.taolesi.nas.net.StorageQuota
import com.taolesi.nas.ui.viewmodel.DashboardState
import com.taolesi.nas.ui.viewmodel.NasViewModel
import com.taolesi.nas.ui.viewmodel.ServerTelemetry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DashboardScreenRefactorTest {

    @Test
    fun testDashboardStateDefaultsAndSpeedFields() {
        val state = DashboardState(
            quota = StorageQuota(0, 0, 0),
            downloadSpeedText = "1.2 MB/s",
            uploadSpeedText = "340 KB/s"
        )
        assertEquals("1.2 MB/s", state.downloadSpeedText)
        assertEquals("340 KB/s", state.uploadSpeedText)
    }

    @Test
    fun testFormatSpeedAlgorithm() {
        assertEquals("0 B/s", NasViewModel.formatSpeed(-1L))
        assertEquals("0 B/s", NasViewModel.formatSpeed(0L))
        assertEquals("512 B/s", NasViewModel.formatSpeed(512L))
        assertEquals("2.0 KB/s", NasViewModel.formatSpeed(2048L))
        assertEquals("5.0 MB/s", NasViewModel.formatSpeed(5 * 1024 * 1024L))
        assertEquals("1.5 GB/s", NasViewModel.formatSpeed((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testV107DashboardScreenSourceContractVerification() {
        val screenFile = File("src/main/java/com/taolesi/nas/ui/screens/DashboardScreen.kt")
        assertTrue("DashboardScreen.kt 文件必须存在", screenFile.exists())
        val content = screenFile.readText()

        // 1. 彻底移除 ConcentricDualRingGaugeCard 及其所有虚假评分
        assertFalse("严禁出现 ConcentricDualRingGaugeCard", content.contains("ConcentricDualRingGaugeCard"))
        assertFalse("严禁在 DashboardScreen.kt 出现 healthScore 假评分", content.contains("healthScore"))
        assertFalse("严禁出现 '极佳' 虚假徽章", content.contains("极佳"))

        // 2. 顶栏标题从 NAS 改为 Tale
        assertTrue("顶栏标题必须修改为 Tale", content.contains("text = \"Tale\""))

        // 3. 严格保留白盒断言关键词
        assertTrue("必须保留白盒断言：↓ 实时下载速度", content.contains("↓ 实时下载速度"))
        assertTrue("必须保留白盒断言：↑ 实时上传速度", content.contains("↑ 实时上传速度"))
        assertTrue("必须保留白盒断言：当前下行速率", content.contains("当前下行速率"))
        assertTrue("必须保留白盒断言：当前上行速率", content.contains("当前上行速率"))
        assertTrue("必须保留白盒断言：云端文件浏览", content.contains("云端文件浏览"))

        // 4. 重构 ServerTelemetryGridCard 降级副标与无假数据规范
        assertTrue("必须包含副标注明：标准 WebDAV 模式 (无底层内核探针权限)", content.contains("标准 WebDAV 模式 (无底层内核探针权限)"))

        // 5. LatencyJitterSparklineCard 绑定真实 latencyHistory
        assertTrue("波形图必须绑定 latencyHistory", content.contains("latencyHistory"))
    }
}

