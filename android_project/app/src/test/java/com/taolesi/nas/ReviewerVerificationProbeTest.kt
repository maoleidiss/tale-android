package com.taolesi.nas

import com.taolesi.nas.net.DavUrlNormalizer
import com.taolesi.nas.ui.viewmodel.DashboardState
import com.taolesi.nas.ui.viewmodel.NasViewModel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 【Hermes 质检官与审查员 (Reviewer) 专属终审验收探针】
 * 针对 v1.0.5 用户 4 大核心诉求进行全自动化端到端白盒与黑盒双重实证复核
 */
class ReviewerVerificationProbeTest {

    // =========================================================================
    // 诉求 1 验收：首页卡片上下行实时网速与状态展示
    // =========================================================================
    @Test
    fun testRequirement1_TrafficSpeedDisplayAndFormatting() {
        // 1. DashboardState 默认字段契约校验
        val defaultState = DashboardState()
        assertEquals("默认下载速率必须为 0 B/s", "0 B/s", defaultState.downloadSpeedText)
        assertEquals("默认上传速率必须为 0 B/s", "0 B/s", defaultState.uploadSpeedText)

        // 2. formatSpeed 各种极限边界数学测试
        assertEquals("0 B/s", NasViewModel.formatSpeed(-9999L))
        assertEquals("0 B/s", NasViewModel.formatSpeed(0L))
        assertEquals("1 B/s", NasViewModel.formatSpeed(1L))
        assertEquals("1023 B/s", NasViewModel.formatSpeed(1023L))
        assertEquals("1.0 KB/s", NasViewModel.formatSpeed(1024L))
        assertEquals("1.5 KB/s", NasViewModel.formatSpeed(1536L))
        assertEquals("1.0 MB/s", NasViewModel.formatSpeed(1024 * 1024L))
        assertEquals("2.4 MB/s", NasViewModel.formatSpeed((2.4 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB/s", NasViewModel.formatSpeed(1024 * 1024 * 1024L))
        assertEquals("10.5 GB/s", NasViewModel.formatSpeed((10.5 * 1024 * 1024 * 1024).toLong()))

        // 3. 源码白盒审查：DashboardScreen 中实时速率卡片已就位，程序员内部术语已彻底清除
        val dashboardSource = File("src/main/java/com/taolesi/nas/ui/screens/DashboardScreen.kt").readText()
        assertTrue("必须包含实时下载速度显示", dashboardSource.contains("↓ 实时下载速度"))
        assertTrue("必须包含实时上传速度显示", dashboardSource.contains("↑ 实时上传速度"))
        assertTrue("必须包含当前下行速率副标", dashboardSource.contains("当前下行速率"))
        assertTrue("必须包含当前上行速率副标", dashboardSource.contains("当前上行速率"))
        assertTrue("文件中心入口副标必须为云端文件浏览", dashboardSource.contains("云端文件浏览"))
        assertFalse("首页卡片绝不能包含 HTTP 206 术语", dashboardSource.contains("HTTP 206"))
        assertFalse("首页卡片绝不能包含 64MB 零占机术语", dashboardSource.contains("64MB 零占机") || dashboardSource.contains("64 MB 零占机"))
        assertFalse("首页卡片绝不能包含 T: 盘", dashboardSource.contains("T:"))
        assertFalse("首页卡片绝不能包含 T盘", dashboardSource.contains("T盘"))
    }

    // =========================================================================
    // 诉求 2 验收：相册顶栏多余胶囊删除
    // =========================================================================
    @Test
    fun testRequirement2_PhotosScreenCapsuleRemoval() {
        val photosSource = File("src/main/java/com/taolesi/nas/ui/screens/PhotosScreen.kt").readText()

        // 顶栏右侧多余胶囊彻底移除
        assertFalse("必须移除 '栏排布' 药丸胶囊", photosSource.contains("栏排布"))
        assertFalse("必须移除 '已连接 NAS' 顶栏冗余胶囊", photosSource.contains("已连接 NAS") || photosSource.contains("已连接"))

        // 手势缩放功能完好保留；双指缩放调栏数提示已依最新需求从界面移除以保持视觉极简
        assertTrue("必须保留 pinchToZoomColumns 手势扩展", photosSource.contains("pinchToZoomColumns"))
    }

    // =========================================================================
    // 诉求 3 验收：文件中心 T 盘彻底清除
    // =========================================================================
    @Test
    fun testRequirement3_FilesScreenTDriveEliminated() {
        val filesSource = File("src/main/java/com/taolesi/nas/ui/screens/FilesScreen.kt").readText()

        // 绝不包含任何 T: 或 T 盘
        assertFalse("文件中心绝不能包含 T: 字符", filesSource.contains("T:"))
        assertFalse("文件中心绝不能包含 T 盘字符", filesSource.contains("T 盘") || filesSource.contains("T盘"))

        // 根目录面包屑规范
        assertTrue("根目录面包屑必须显示 NAS 根目录", filesSource.contains("NAS 根目录"))
        assertTrue("空状态文案必须规范为私有云上传", filesSource.contains("可通过电脑或手机将文件上传至私有云"))
    }

    // =========================================================================
    // 诉求 4 验收：设置页连接解锁与自适应
    // =========================================================================
    @Test
    fun testRequirement4_SettingsAndWebDavAdaptation() {
        // 1. 设置页顶栏冗余胶囊清除
        val settingsSource = File("src/main/java/com/taolesi/nas/ui/screens/SettingsScreen.kt").readText()
        assertFalse("设置页顶栏绝不能包含 '零后台驻留' 胶囊", settingsSource.contains("零后台驻留"))

        // 2. 连接弹窗非阻塞保存审查
        val mainSource = File("src/main/java/com/taolesi/nas/MainActivity.kt").readText()
        assertTrue("弹窗保存按钮必须文案为 '直接保存'", mainSource.contains("\"直接保存\""))
        assertTrue("必须提供独立的测试当前连通性按钮", mainSource.contains("测试当前连通性"))
        assertTrue("测试失败信息需明确标注不影响直接保存", mainSource.contains("(不影响直接保存)"))

        // 3. 通用 WebDAV 与 Nextcloud 自适应路由测试
        // 3.1 用户自定义根路径 (如 Alist / Apache WebDAV / Nginx) 绝对不强行拼 remote.php
        val genericUrl1 = "https://nas.example.com/dav"
        assertTrue("应该识别为带有自定义路径", DavUrlNormalizer.hasCustomPath(genericUrl1))
        val built1 = DavUrlNormalizer.buildDavUrl(genericUrl1, "user1", "docs/test.pdf", isCollection = false)
        assertEquals("https://nas.example.com/dav/docs/test.pdf", built1)

        val genericUrl2 = "http://192.168.1.100:5005/webdav"
        assertTrue("应该识别为带有自定义路径", DavUrlNormalizer.hasCustomPath(genericUrl2))
        val built2 = DavUrlNormalizer.buildDavUrl(genericUrl2, "user1", "Photos/1.jpg", isCollection = false)
        assertEquals("http://192.168.1.100:5005/webdav/Photos/1.jpg", built2)

        // 3.2 裸域名情况自适应 Nextcloud 标准路径
        val nakedUrl = "https://nas.example.com"
        assertFalse("裸域名不包含自定义路径", DavUrlNormalizer.hasCustomPath(nakedUrl))
        val builtNaked = DavUrlNormalizer.buildDavUrl(nakedUrl, "admin", "folder", isCollection = true)
        assertEquals("https://nas.example.com/remote.php/dav/files/admin/folder/", builtNaked)

        // 3.3 误粘贴 Nextcloud 完整路径时防御性剥离
        val pastedUrl = "https://nas.example.com/remote.php/dav/files/admin/"
        val cleaned = DavUrlNormalizer.cleanBaseUrl(pastedUrl)
        assertEquals("https://nas.example.com", cleaned)
    }
}
