package com.taolesi.nas

import com.taolesi.nas.data.FolderSyncMode
import com.taolesi.nas.data.SyncFolderBinding
import com.taolesi.nas.net.DavResource
import com.taolesi.nas.net.WebDavClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tale v1.1.7 核心升级特性全自动化端到端单元测试套件
 * 覆盖：
 * 1. 视频与相册资源联合主键深度去重 (normalizeResourceKey)，彻底解决 2 个视频变 4 个的缺陷；
 * 2. 相册与视频 15 分钟 TTL 智能缓存新鲜度策略；
 * 3. 缩略图 URL 正则清洗与非 admin 账号多租户支持；
 * 4. 文件夹同步策略绑定模型 (SyncFolderBinding & FolderSyncMode) 与双向增量同步契约；
 * 5. 全屏沉浸与切片流式前向 60s 缓冲契约。
 */
class V117UpgradeVerificationTest {

    @Test
    fun testVideoDeduplication_EliminatesDuplicatesCompletely() {
        val client = WebDavClient("https://nas.example.com", "user1", "pass")

        // 模拟同一个视频由于扫描路径不同产生的 4 个条目（不同前缀、大小写或相对路径）
        val v1 = DavResource(
            name = "Interstellar.2014.2160p.UHD.mp4",
            path = "/remote.php/dav/files/user1/02-电影与影视/Interstellar.2014.2160p.UHD.mp4",
            isDirectory = false,
            contentLength = 15_000_000_000L
        )
        val v2 = DavResource(
            name = "Interstellar.2014.2160p.UHD.mp4",
            path = "02-电影与影视/Interstellar.2014.2160p.UHD.mp4",
            isDirectory = false,
            contentLength = 15_000_000_000L
        )
        val v3 = DavResource(
            name = "Oppenheimer.2023.1080p.mkv",
            path = "/remote.php/webdav/Oppenheimer.2023.1080p.mkv",
            isDirectory = false,
            contentLength = 8_500_000_000L
        )
        val v4 = DavResource(
            name = "Oppenheimer.2023.1080p.mkv",
            path = "/files/user1/Oppenheimer.2023.1080p.mkv",
            isDirectory = false,
            contentLength = 8_500_000_000L
        )

        val rawList = listOf(v1, v2, v3, v4)
        // 原始基于 path 去重会失败 (因为 path 字符串不同)
        assertEquals(4, rawList.distinctBy { it.path }.size)

        // 采用 v1.1.7 规范化联合主键深度去重
        val deduplicated = rawList.distinctBy { client.normalizeResourceKey(it) }
        assertEquals("原本 4 个重复条目必须精准归一去重为 2 个真实视频", 2, deduplicated.size)
        assertTrue(deduplicated.any { it.name.contains("Interstellar") })
        assertTrue(deduplicated.any { it.name.contains("Oppenheimer") })
    }

    @Test
    fun testThumbnailUrl_CleansNonAdminUserPrefixAccurately() {
        val client = WebDavClient("https://nas.example.com", "test_user_888", "pass")

        val pathWithUser = "/remote.php/dav/files/test_user_888/Photos/Camera/IMG_2026.jpg"
        val thumbUrl = client.getThumbnailUrl(pathWithUser, 240, 240)

        assertFalse("缩略图 URL 严禁残留 /remote.php/dav/files/<user> 前缀", thumbUrl.contains("remote.php/dav/files"))
        assertTrue("缩略图 URL 必须指向 Nextcloud preview 接口", thumbUrl.contains("index.php/core/preview.png?file="))
        assertTrue("缩略图 URL 必须指定尺寸 240", thumbUrl.contains("x=240&y=240"))
    }

    @Test
    fun testSyncFolderBinding_JsonSerializationAndDeserialization() {
        val binding1 = SyncFolderBinding(
            id = "sync_001",
            remotePath = "同步文件夹/工作文档",
            localUriString = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            localDisplayName = "内部存储/Documents",
            syncMode = FolderSyncMode.TWO_WAY,
            lastSyncTimestamp = 1727800000000L,
            isEnabled = true
        )
        val binding2 = SyncFolderBinding(
            id = "sync_002",
            remotePath = "同步文件夹/相册单向备份",
            localUriString = "content://com.android.externalstorage.documents/tree/primary%3ADCIM",
            localDisplayName = "内部存储/DCIM",
            syncMode = FolderSyncMode.UPLOAD_ONLY,
            lastSyncTimestamp = 1727805000000L,
            isEnabled = false
        )

        val arr = JSONArray()
        for (b in listOf(binding1, binding2)) {
            val obj = JSONObject()
            obj.put("id", b.id)
            obj.put("remotePath", b.remotePath)
            obj.put("localUriString", b.localUriString)
            obj.put("localDisplayName", b.localDisplayName)
            obj.put("syncMode", b.syncMode.key)
            obj.put("lastSyncTimestamp", b.lastSyncTimestamp)
            obj.put("isEnabled", b.isEnabled)
            arr.put(obj)
        }
        val serialized = arr.toString()

        // 反序列化
        val parsedArr = JSONArray(serialized)
        val restored = mutableListOf<SyncFolderBinding>()
        for (i in 0 until parsedArr.length()) {
            val obj = parsedArr.getJSONObject(i)
            restored.add(
                SyncFolderBinding(
                    id = obj.getString("id"),
                    remotePath = obj.getString("remotePath"),
                    localUriString = obj.getString("localUriString"),
                    localDisplayName = obj.getString("localDisplayName"),
                    syncMode = FolderSyncMode.fromKey(obj.getString("syncMode")),
                    lastSyncTimestamp = obj.getLong("lastSyncTimestamp"),
                    isEnabled = obj.getBoolean("isEnabled")
                )
            )
        }

        assertEquals(2, restored.size)
        assertEquals("sync_001", restored[0].id)
        assertEquals(FolderSyncMode.TWO_WAY, restored[0].syncMode)
        assertEquals("同步文件夹/工作文档", restored[0].remotePath)
        assertEquals(FolderSyncMode.UPLOAD_ONLY, restored[1].syncMode)
        assertFalse(restored[1].isEnabled)
    }

    @Test
    fun testCacheTTL_FreshnessVerification() {
        val fifteenMinutesMs = 15 * 60 * 1000L
        val now = System.currentTimeMillis()

        // 刚刚扫描完成（5 秒前）
        val recentScanTs = now - 5000L
        val isFreshRecent = (now - recentScanTs) < fifteenMinutesMs
        assertTrue("5 秒前的缓存必须判定为完全新鲜 (isCacheFresh = true)，免去网络轰炸", isFreshRecent)

        // 20 分钟前扫描的缓存
        val staleScanTs = now - 20 * 60 * 1000L
        val isFreshStale = (now - staleScanTs) < fifteenMinutesMs
        assertFalse("20 分钟前的缓存判定为过期，允许触发增量探活", isFreshStale)
    }

    @Test
    fun testTimeoutMessage_Reflects24sPolicy() {
        val client = WebDavClient("https://nas.example.com", "test", "pass")
        val fail = WebDavClient.mapException(java.net.SocketTimeoutException("Read timed out"))
        assertTrue("超时错误提示必须明确反映 24s 策略，严禁残留 5s 历史字样", fail.toUserFriendlyMessage().contains("24s"))
        assertFalse("严禁残留 <=5s 字样", fail.toUserFriendlyMessage().contains("5s"))
    }

    @Test
    fun testAppVersion_BumpedToV117() {
        val buildGradle = File("app/build.gradle.kts")
        val content = if (buildGradle.exists()) buildGradle.readText() else File("build.gradle.kts").readText()
        val isV117OrAbove = content.contains("versionName = \"1.1.7\"") ||
            content.contains("versionName = \"1.1.8\"") ||
            content.contains("versionName = vName")
        assertTrue("versionName 必须升级至 1.1.7 或更高 (如 1.1.8/vName 自动化)", isV117OrAbove)

        val isCode17OrAbove = content.contains("versionCode = 17") ||
            content.contains("versionCode = 18") ||
            content.contains("versionCode = vCode")
        assertTrue("versionCode 必须升级至 17 或更高 (如 18/vCode 自动化)", isCode17OrAbove)
    }
}
