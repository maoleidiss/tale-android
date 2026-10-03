package com.taolesi.nas

import com.taolesi.nas.net.DavResource
import com.taolesi.nas.ui.utils.PhotoTimelineHelper
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PhotoTimelineHelperTest {

    @Test
    fun testDateGroupingLabels() {
        val today = LocalDate.of(2026, 10, 3)
        val pToday = DavResource(name = "photo_today.jpg", path = "/photos/1.jpg", isDirectory = false, lastModified = "2026-10-03T10:00:00Z")
        val pYesterday = DavResource(name = "photo_yesterday.jpg", path = "/photos/2.jpg", isDirectory = false, lastModified = "2026-10-02T15:00:00Z")
        val pThisYear = DavResource(name = "photo_summer.jpg", path = "/photos/3.jpg", isDirectory = false, lastModified = "2026-07-15T08:00:00Z")
        val pLastYear = DavResource(name = "photo_2025.jpg", path = "/photos/4.jpg", isDirectory = false, lastModified = "2025-12-25T12:00:00Z")

        val list = listOf(pToday, pYesterday, pThisYear, pLastYear)
        val groups = PhotoTimelineHelper.groupPhotosByDate(list, today)

        assertEquals(4, groups.size)
        assertEquals("今天 | Tale", groups[0].title)
        assertEquals("昨天", groups[1].title)
        assertEquals("7月15日", groups[2].title)
        assertEquals("2025年 12月25日", groups[3].title)

        val years = PhotoTimelineHelper.extractDistinctYears(groups)
        assertEquals(listOf(2026, 2025), years)
    }

    @Test
    fun testInstantSearchFilter() {
        val p1 = DavResource(name = "Screenshot_2026-10-01.jpg", path = "/photos/Screenshot_2026-10-01.jpg", isDirectory = false)
        val p2 = DavResource(name = "Family_Trip_01.jpg", path = "/photos/Family_Trip_01.jpg", isDirectory = false)
        val p3 = DavResource(name = "Invoice_WeChat.jpg", path = "/photos/Invoice_WeChat.jpg", isDirectory = false)

        val list = listOf(p1, p2, p3)

        // 搜微信/发票
        val res1 = PhotoTimelineHelper.filterPhotos(list, "invoice")
        assertEquals(1, res1.size)
        assertEquals("Invoice_WeChat.jpg", res1[0].name)

        // 搜截图
        val res2 = PhotoTimelineHelper.filterPhotos(list, "screenshot")
        assertEquals(1, res2.size)
        assertEquals("Screenshot_2026-10-01.jpg", res2[0].name)

        // 空搜索应返回全量
        val res3 = PhotoTimelineHelper.filterPhotos(list, "")
        assertEquals(3, res3.size)
    }
}
