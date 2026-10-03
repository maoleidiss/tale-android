package com.taolesi.nas

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Worker 4 专项自测守护套件：
 * 验证工程配置、版本升级至 1.0.7、全量更名 Tale 与生命周期对齐
 */
class Worker4TaleConfigVerificationTest {

    private fun findProjectFile(relativePathFromApp: String): File {
        val candidate1 = File(relativePathFromApp)
        if (candidate1.exists()) return candidate1
        val candidate2 = File("app/$relativePathFromApp")
        if (candidate2.exists()) return candidate2
        val candidate3 = File("../$relativePathFromApp")
        if (candidate3.exists()) return candidate3
        throw AssertionError("未能找到目标文件: $relativePathFromApp (当前路径: ${File(".").absolutePath})")
    }

    @Test
    fun testAndroidManifest_ApplicationLabelIsTale() {
        val manifestFile = findProjectFile("src/main/AndroidManifest.xml")
        val content = manifestFile.readText()

        assertTrue("AndroidManifest.xml 必须配置 android:label=\"Tale\"", content.contains("android:label=\"Tale\""))
        assertFalse("AndroidManifest.xml 严禁残留“桃乐丝云盘”", content.contains("桃乐丝云盘"))
    }

    @Test
    fun testBuildGradle_VersionCodeAndNameAndJsonDependency() {
        val gradleFile = findProjectFile("build.gradle.kts")
        val content = gradleFile.readText()

        val hasValidVersionCode = content.contains("versionCode = 7") || content.contains("versionCode = 8") || content.contains("versionCode = 9") || content.contains("versionCode = 10") || content.contains("versionCode = 11") || content.contains("versionCode = 12") || content.contains("versionCode = 13") || content.contains("versionCode = 14") || content.contains("versionCode = 15") || content.contains("versionCode = 16") || content.contains("versionCode = 17") || content.contains("versionCode = 18") || content.contains("versionCode = vCode")
        val hasValidVersionName = content.contains("versionName = \"1.0.7\"") || content.contains("versionName = \"1.0.8\"") || content.contains("versionName = \"1.0.9\"") || content.contains("versionName = \"1.1.0\"") || content.contains("versionName = \"1.1.1\"") || content.contains("versionName = \"1.1.2\"") || content.contains("versionName = \"1.1.3\"") || content.contains("versionName = \"1.1.4\"") || content.contains("versionName = \"1.1.5\"") || content.contains("versionName = \"1.1.6\"") || content.contains("versionName = \"1.1.7\"") || content.contains("versionName = \"1.1.8\"") || content.contains("versionName = vName")

        assertTrue("versionCode 必须升级为 7 或更高版本 (当前已演进至 8)", hasValidVersionCode)
        assertTrue("versionName 必须升级为 \"1.0.7\" 或更高版本 (当前已演进至 1.0.8)", hasValidVersionName)
        assertTrue(
            "必须引入 org.json:json:20240303 确保 JVM 单元测试安全",
            content.contains("testImplementation(\"org.json:json:20240303\")")
        )
    }

    @Test
    fun testKeepAliveConnectionService_NotificationAndNameAlignedToTale() {
        val serviceFile = findProjectFile("src/main/java/com/taolesi/nas/service/KeepAliveConnectionService.kt")
        val content = serviceFile.readText()

        assertTrue("通知栏标题必须为“Tale 专线保活”", content.contains(".setContentTitle(\"Tale 专线保活\")"))
        assertFalse("严禁残留“桃乐丝专线保活”", content.contains("桃乐丝专线保活"))
        assertTrue("通知渠道名称对齐为“Tale 专线保活”", content.contains("\"Tale 专线保活\""))
    }

    @Test
    fun testMainActivity_ConcurrentTimeoutsAndTextAligned() {
        val mainActivityFile = findProjectFile("src/main/java/com/taolesi/nas/MainActivity.kt")
        val content = mainActivityFile.readText()

        // 验证 onCreate 中没有 lifecycleScope.launch 调用 viewModel.refreshDashboard()
        val onCreateSlice = content.substringAfter("override fun onCreate").substringBefore("setContent {")
        assertFalse(
            "MainActivity.onCreate 必须清理与 ViewModel 重复并发的 viewModel.refreshDashboard()",
            onCreateSlice.contains("refreshDashboard()")
        )

        // 验证文件打开选择器标题对齐 Tale
        assertTrue("文件打开选择器标题必须对齐 Tale", content.contains("Tale · 打开文件"))
        // 验证 Toast 提示文案对齐 Tale
        assertTrue("文件打开异常提示必须对齐 Tale", content.contains("Tale: 打开文件失败"))
    }
}
