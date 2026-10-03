package com.taolesi.nas

import com.taolesi.nas.net.DavResource
import com.taolesi.nas.ui.utils.PhotoTimelineHelper
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TimelineRealDateSortingTest {

    @Test
    fun testRealDateSortingFixesStringComparisonBug() {
        // 用户反馈的真实场景：
        // 7月21日: "Tue, 21 Jul 2026 10:00:00 GMT" (如果字符串比较，'T' 开头)
        // 10月1日: "Thu, 01 Oct 2026 10:00:00 GMT" (如果字符串比较，'T' 开头且 Tue 在 Thu 之后，导致 7月21日 莫名排在 10月1日 前面！)
        // 8月8日:  "Sat, 08 Aug 2026 10:00:00 GMT" (如果字符串比较，'S' 开头排在最后！)
        val pJuly = DavResource(name = "photo_july.jpg", path = "/1.jpg", isDirectory = false, lastModified = "Tue, 21 Jul 2026 10:00:00 GMT")
        val pOct = DavResource(name = "photo_oct.jpg", path = "/2.jpg", isDirectory = false, lastModified = "Thu, 01 Oct 2026 10:00:00 GMT")
        val pAug = DavResource(name = "photo_aug.jpg", path = "/3.jpg", isDirectory = false, lastModified = "Sat, 08 Aug 2026 10:00:00 GMT")

        val rawList = listOf(pJuly, pOct, pAug)
        val sortedDesc = PhotoTimelineHelper.sortPhotosByDate(rawList, descending = true)

        // 正确的时间顺序必须是: 10月1日 -> 8月8日 -> 7月21日
        assertEquals("第1张必须是10月1日", "photo_oct.jpg", sortedDesc[0].name)
        assertEquals("第2张必须是8月8日", "photo_aug.jpg", sortedDesc[1].name)
        assertEquals("第3张必须是7月21日", "photo_july.jpg", sortedDesc[2].name)

        // 分组标题测试
        val today = LocalDate.of(2026, 10, 1)
        val groups = PhotoTimelineHelper.groupPhotosByDate(sortedDesc, today)
        assertEquals(3, groups.size)
        assertEquals("今天 | Tale", groups[0].title)
        assertEquals("8月8日", groups[1].title)
        assertEquals("7月21日", groups[2].title)
    }
}
