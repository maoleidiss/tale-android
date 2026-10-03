package com.taolesi.nas.ui.utils

import com.taolesi.nas.net.DavResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 相册时间线日期分组模型
 */
data class PhotoDateGroup(
    val title: String,
    val year: Int,
    val date: LocalDate,
    val photos: List<DavResource>
)

/**
 * 极简相册时间轴、时间戳解析与搜索过滤算法工具
 * 核心原则：
 * 1. 彻底根治多源时间字符串 (RFC 1123 vs ISO 8601 vs Local) 导致的乱序 bug；
 * 2. 统一转换为 Epoch Milliseconds (毫秒数) 进行数字排序，保证 100% 绝对时间先后；
 * 3. 仿原生现代相册视觉分组 ("今天 | Tale" / "昨天" / "M月d日" / "yyyy年 M月d日")；
 * 4. 提供右侧快速悬浮年份飞梭索引。
 */
object PhotoTimelineHelper {

    private val rfc1123Formatter = DateTimeFormatter.RFC_1123_DATE_TIME

    /**
     * 将 DavResource 的修改时间安全转化为绝对时间戳 (毫秒)
     * 解决 RFC 1123 (例如 "Thu, 01 Oct 2026 ...") 与其他格式混合时按字母比对导致乱序的问题
     */
    fun parsePhotoEpochMillis(resource: DavResource, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val raw = resource.lastModified.trim()
        if (raw.isNotEmpty()) {
            // 1. 尝试 ISO 8601 (本地同步照片或部分标准 WebDAV)
            try {
                return Instant.parse(raw).toEpochMilli()
            } catch (_: Exception) {}

            // 2. 尝试 RFC 1123 (Nextcloud / Apache 标准 WebDAV, 例如 "Thu, 01 Oct 2026 10:27:00 GMT")
            try {
                return ZonedDateTime.parse(raw, rfc1123Formatter).toInstant().toEpochMilli()
            } catch (_: Exception) {}

            // 3. 尝试匹配常用日期时间字符串格式: yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd
            val dateRegex = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2}):(\d{1,2}))?""")
            val match = dateRegex.find(raw)
            if (match != null) {
                try {
                    val y = match.groupValues[1].toInt()
                    val m = match.groupValues[2].toInt()
                    val d = match.groupValues[3].toInt()
                    val hh = match.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
                    val mm = match.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
                    val ss = match.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
                    return ZonedDateTime.of(y, m, d, hh, mm, ss, 0, zoneId).toInstant().toEpochMilli()
                } catch (_: Exception) {}
            }
        }

        // 4. 若 lastModified 缺失或无法解析，尝试从文件名提取时间戳 (如 IMG_20261001_120000.jpg 或 Screenshot_2026-10-01)
        val nameMatch = Regex("""(20\d{2})[-_]?(\d{2})[-_]?(\d{2})(?:[-_]?(\d{2})(\d{2})(\d{2}))?""").find(resource.name)
        if (nameMatch != null) {
            try {
                val y = nameMatch.groupValues[1].toInt()
                val m = nameMatch.groupValues[2].toInt()
                val d = nameMatch.groupValues[3].toInt()
                val hh = nameMatch.groupValues.getOrNull(4)?.toIntOrNull() ?: 12
                val mm = nameMatch.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
                val ss = nameMatch.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
                return ZonedDateTime.of(y, m, d, hh, mm, ss, 0, zoneId).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }

        return 0L
    }

    /**
     * 解析 DavResource 的最后修改时间为本地日期 LocalDate
     */
    fun parsePhotoDate(resource: DavResource, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
        val epochMs = parsePhotoEpochMillis(resource, zoneId)
        return if (epochMs > 0L) {
            Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate()
        } else {
            LocalDate.now(zoneId)
        }
    }

    /**
     * 将照片列表按绝对时间戳数字降序/升序排列 (最新在前，杜绝字符串乱排)
     */
    fun sortPhotosByDate(photos: List<DavResource>, descending: Boolean = true): List<DavResource> {
        return photos.sortedWith { a, b ->
            val timeA = parsePhotoEpochMillis(a)
            val timeB = parsePhotoEpochMillis(b)
            val cmp = if (descending) timeB.compareTo(timeA) else timeA.compareTo(timeB)
            if (cmp != 0) cmp else com.taolesi.nas.utils.NaturalOrderComparator.compare(a.name, b.name)
        }
    }

    /**
     * 将已排序的照片列表按日期聚合为极简时间线分组 (风格完全对齐用户截图)
     */
    fun groupPhotosByDate(
        photos: List<DavResource>,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault())
    ): List<PhotoDateGroup> {
        if (photos.isEmpty()) return emptyList()

        val yesterday = today.minusDays(1)
        val currentYear = today.year

        // 保持时间顺序线性分桶
        val groupedMap = LinkedHashMap<LocalDate, MutableList<DavResource>>()
        for (photo in photos) {
            val date = parsePhotoDate(photo)
            groupedMap.getOrPut(date) { mutableListOf() }.add(photo)
        }

        return groupedMap.map { (date, photoList) ->
            val title = when {
                date == today -> "今天 | Tale"
                date == yesterday -> "昨天"
                date.year == currentYear -> "${date.monthValue}月${date.dayOfMonth}日"
                else -> "${date.year}年 ${date.monthValue}月${date.dayOfMonth}日"
            }
            PhotoDateGroup(
                title = title,
                year = date.year,
                date = date,
                photos = photoList
            )
        }
    }

    /**
     * 提取照片集合中出现的所有独立年份（倒序排列，用于年份飞梭）
     */
    fun extractDistinctYears(groups: List<PhotoDateGroup>): List<Int> {
        return groups.map { it.year }.distinct().sortedDescending()
    }

    /**
     * 针对相册执行多维度即时搜索过滤 (支持文件名、相册目录、年份/月份关键词)
     */
    fun filterPhotos(photos: List<DavResource>, query: String): List<DavResource> {
        val trimmed = query.trim().lowercase(Locale.getDefault())
        if (trimmed.isEmpty()) return photos

        return photos.filter { photo ->
            photo.name.lowercase(Locale.getDefault()).contains(trimmed) ||
            photo.path.lowercase(Locale.getDefault()).contains(trimmed) ||
            photo.lastModified.lowercase(Locale.getDefault()).contains(trimmed)
        }
    }
}
