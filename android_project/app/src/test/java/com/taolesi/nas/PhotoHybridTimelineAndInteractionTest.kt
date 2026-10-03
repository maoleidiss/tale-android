package com.taolesi.nas

import com.taolesi.nas.net.DavResource
import com.taolesi.nas.ui.utils.PhotoTimelineHelper
import com.taolesi.nas.ui.viewmodel.PhotoSortOrder
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PhotoHybridTimelineAndInteractionTest {

    @Test
    fun testHybridMediaAssetDeduplicationAndState() {
        // 模拟本地照片与云端照片混合
        val localPhoto1 = DavResource(
            name = "IMG_20261003_120000.jpg",
            path = "content://media/external/images/media/1001",
            isDirectory = false,
            contentLength = 2048000L,
            lastModified = "2026-10-03T12:00:00Z",
            isLocal = true,
            isSynced = false,
            localUri = "content://media/external/images/media/1001"
        )

        val localPhoto2 = DavResource(
            name = "Screenshot_2026-10-01.jpg",
            path = "content://media/external/images/media/1002",
            isDirectory = false,
            contentLength = 1024000L,
            lastModified = "2026-10-01T15:00:00Z",
            isLocal = true,
            isSynced = true,
            localUri = "content://media/external/images/media/1002"
        )

        val remotePhoto = DavResource(
            name = "IMG_20250601_090000.jpg",
            path = "/remote.php/webdav/01-照片与相册/IMG_20250601_090000.jpg",
            isDirectory = false,
            contentLength = 3054000L,
            lastModified = "2025-06-01T09:00:00Z",
            isLocal = false,
            isSynced = true
        )

        val list = listOf(localPhoto1, localPhoto2, remotePhoto)

        // 验证混合列表包含本地未备份、本地已备份和云端照片
        val pendingCount = list.count { it.isLocal && !it.isSynced }
        assertEquals("待备份照片应仅有 1 张", 1, pendingCount)

        val syncedCount = list.count { it.isSynced }
        assertEquals("已备份照片应有 2 张", 2, syncedCount)
    }

    @Test
    fun testDateGroupingVsNameSortingContract() {
        val today = LocalDate.of(2026, 10, 3)
        val p1 = DavResource(name = "B_photo.jpg", path = "/1.jpg", isDirectory = false, lastModified = "2026-10-03T09:00:00Z")
        val p2 = DavResource(name = "A_photo.jpg", path = "/2.jpg", isDirectory = false, lastModified = "2026-10-02T10:00:00Z")
        val p3 = DavResource(name = "C_photo.jpg", path = "/3.jpg", isDirectory = false, lastModified = "2025-05-01T10:00:00Z")

        val list = listOf(p1, p2, p3)

        // 1. 按时间排序契约：必须具备日期分组
        val dateGroups = PhotoTimelineHelper.groupPhotosByDate(list, today)
        assertEquals(3, dateGroups.size)
        assertEquals("今天 | Tale", dateGroups[0].title)
        assertEquals("昨天", dateGroups[1].title)
        assertEquals("2025年 5月1日", dateGroups[2].title)

        // 2. 年份提取
        val years = PhotoTimelineHelper.extractDistinctYears(dateGroups)
        assertEquals(listOf(2026, 2025), years)

        // 3. 搜索契约
        val searchA = PhotoTimelineHelper.filterPhotos(list, "A_photo")
        assertEquals(1, searchA.size)
        assertEquals("A_photo.jpg", searchA[0].name)
    }
}
