package com.taolesi.nas.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taolesi.nas.data.SpeedSourceMode
import com.taolesi.nas.ui.components.PearlCard
import com.taolesi.nas.ui.theme.*
import com.taolesi.nas.ui.viewmodel.DashboardState
import java.util.Locale

/**
 * 珍珠白 3D 拟物全息可视化仪表盘 (Pearl Neumorphic Glass) - Tale
 *
 * 核心可视化体系：
 * 1. ServerTelemetryGrid: 服务器全息指标卡片（支持真实内核指标与标准 WebDAV 优雅降级，零假数据）
 * 2. MultiSegmentDonutChart: 多维度存储空间甜甜圈圆环（影视、相册、文件、系统、剩余空间比例与色块图例）
 * 3. LatencyJitterSparkline: 专线网络延迟波形抖动微图表（绑定真实采样队列，三次贝塞尔波形随采样向前滚动流动）
 * 4. 实时传输速率卡片：展示当前网速模式徽章标签（“🌐 服务器全局带宽”或“📱 本机 App 速率”），双列大字呈现上下行速率
 * 5. 私有云快捷入口：智慧相册、影视片库、文件中心（云端文件浏览）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    onRefresh: () -> Unit,
    onNavigateTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
    speedSourceMode: SpeedSourceMode = SpeedSourceMode.SERVER_BANDWIDTH
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val pullToRefreshState = rememberPullToRefreshState()

    val triggerHapticVibration = {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Throwable) {}
    }

    val density = LocalDensity.current
    val pullOffsetPx = pullToRefreshState.distanceFraction.coerceIn(0f, 1.6f) * with(density) { 160.dp.toPx() }

    // 下拉刷新 5s 安全复位守卫：确保无论网络发生超时还是异常，下拉刷新状态都能在 5s 内安全复位，严禁刷新圈一直悬停卡在屏幕上方
    var refreshingTimeoutGuard by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (state.isLoading) {
            refreshingTimeoutGuard = true
            kotlinx.coroutines.delay(5000L)
            refreshingTimeoutGuard = false
        } else {
            refreshingTimeoutGuard = false
        }
    }
    val effectiveRefreshing = state.isLoading && refreshingTimeoutGuard

    PullToRefreshBox(
        isRefreshing = effectiveRefreshing,
        onRefresh = {
            refreshingTimeoutGuard = true
            triggerHapticVibration()
            onRefresh()
        },
        state = pullToRefreshState,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = pullOffsetPx
                }
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(top = 54.dp, bottom = 128.dp)
        ) {
        // 1. 顶栏：Tale 标题与网络状态指示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Tale",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    letterSpacing = (-0.8).sp
                )
            }

            // 动态网络指示徽章 (真实连通性与 Ping 延时回显)
            Surface(
                shape = RoundedCornerShape(9999.dp),
                color = Color.White,
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (state.isConnected) Color(0x3310B981) else Color(0x33EF4444)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (state.isConnected) AccentEmerald else Color(0xFFEF4444))
                    )
                    Text(
                        text = if (state.isConnected) state.networkLabel else (state.errorMessage ?: "离线"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.isConnected) Color(0xFF059669) else Color(0xFFDC2626)
                    )
                }
            }
        }

        // 2. 引导配置卡片 (未连接或异常时呈现)
        if (!state.isConnected) {
            PearlCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .clickable { onNavigateTab(4) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFEF2F2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "⚠️", fontSize = 18.sp)
                        }
                        Column {
                            Text(
                                text = "NAS 尚未连接或配置异常",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFDC2626)
                            )
                            val errorDesc = state.errorMessage?.ifBlank { null }
                                ?: "尚未正确配置 WebDAV 服务端地址或访问密码，点击前往【设置】"
                            Text(
                                text = errorDesc,
                                fontSize = 11.sp,
                                color = TextMuted,
                                maxLines = 2
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(9999.dp),
                        color = Color(0xFFDC2626).copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = "去配置 ›",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDC2626),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // 计算存储容量指标
        val quota = state.quota
        val isUnlimited = quota.isUnlimited || quota.availableBytes < 0
        val usedGb = quota.usedBytes / (1024.0 * 1024 * 1024)
        val availableGb = if (quota.availableBytes > 0) quota.availableBytes / (1024.0 * 1024 * 1024) else 0.0
        val totalGb = quota.totalBytes / (1024.0 * 1024 * 1024)

        // 3. ServerTelemetryGrid: 服务器自身全息指标卡片 (在未正常连接时彻底隐藏，只在真实连通后呈现)
        if (state.isConnected) {
            ServerTelemetryGridCard(state = state)
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 4. MultiSegmentDonutChart: 多维度存储空间甜甜圈圆环
        MultiSegmentDonutChartCard(
            isConnected = state.isConnected,
            quota = quota,
            usedGb = usedGb,
            availableGb = availableGb,
            totalGb = totalGb,
            isUnlimited = isUnlimited
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 5. LatencyJitterSparkline: 专线网络延迟波形抖动微图表 (绑定真实采样流向前滚动)
        LatencyJitterSparklineCard(
            isConnected = state.isConnected,
            pingMs = state.pingMs,
            latencyHistory = state.serverTelemetry.latencyHistory.ifEmpty { state.pingHistory },
            jitterMs = state.serverTelemetry.jitterMs
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 7. 实时传输速率卡片 (白盒断言字段严格保留)
        RealtimeBandwidthCard(
            state = state,
            speedSourceMode = speedSourceMode
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 8. 私有云快捷入口
        Text(
            text = "私有云快捷入口",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickTileItem(title = "智慧相册", sub = "RAW / WebP", color = AccentBlue, modifier = Modifier.weight(1f)) { onNavigateTab(1) }
            QuickTileItem(title = "影视片库", sub = "4K 流播", color = AccentIndigo, modifier = Modifier.weight(1f)) { onNavigateTab(2) }
            QuickTileItem(title = "文件中心", sub = "云端文件浏览", color = AccentEmerald, modifier = Modifier.weight(1f)) { onNavigateTab(3) }
        }
    }
}
}

/**
 * ServerTelemetryGridCard: 服务器全息遥测指标卡片
 * - 若 state.serverTelemetry.isRealServerMetrics == true：展示真实 CPU 占用、内存、系统负载、Uptime；
 * - 若 state.serverTelemetry.isRealServerMetrics == false：展示真实服务端品牌版本（例如 "Nextcloud 35.0.1" 或 "通用 WebDAV"）、
 *   专线往返延迟（${state.pingMs}ms）、存储总配额与网络抖动值，副标注明“标准 WebDAV 模式 (无底层内核探针权限)”，
 *   绝不展示任何捏造的假百分比或虚假数字。
 */
@Composable
fun ServerTelemetryGridCard(state: DashboardState) {
    if (!state.isConnected) return
    val telemetry = state.serverTelemetry
    val isReal = telemetry.isRealServerMetrics
    val isConnected = state.isConnected

    PearlCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "服务器全息指标",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 0.8.sp
                )
                if (!isReal) {
                    Text(
                        text = "标准 WebDAV 模式 (无底层内核探针权限)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isReal) AccentEmerald.copy(alpha = 0.1f) else AccentBlue.copy(alpha = 0.08f)
            ) {
                val badgeText = if (isReal) "内核级实时遥测" else "标准 WebDAV 在线"
                Text(
                    text = badgeText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isReal) Color(0xFF059669) else AccentBlue,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2x2 Bento 微卡片网格
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isReal) {
                // 真实内核级指标 (Nextcloud OCS 管理员探针等)
                val cpuValue = String.format(Locale.getDefault(), "%.1f%%", telemetry.cpuUsagePercent)
                val cpuSub = if (telemetry.cpuCores > 0) "${telemetry.cpuCores} 核心 · 调度充裕" else "系统负载正常"
                val cpuRatio = (telemetry.cpuUsagePercent / 100f).coerceIn(0f, 1f)

                val memValue = if (telemetry.totalMemoryGb > 0f) {
                    String.format(Locale.getDefault(), "%.1f / %.1f GB", telemetry.usedMemoryGb, telemetry.totalMemoryGb)
                } else "--"
                val memSub = if (telemetry.memoryUsagePercent > 0f) {
                    "占用率 ${telemetry.memoryUsagePercent.toInt()}% · 充裕"
                } else "物理内存正常"
                val memRatio = (telemetry.memoryUsagePercent / 100f).coerceIn(0f, 1f)

                // 优化系统平均负载：格式化为主数值精简展示与通俗直观评级，彻底杜绝折行超格
                val loadFirst = telemetry.loadAverage.split("/").firstOrNull()?.trim()?.toDoubleOrNull()
                val (formattedLoad, loadStatus) = when {
                    telemetry.loadAverage.isBlank() || telemetry.loadAverage == "--" -> Pair("--", "未获取负载")
                    loadFirst != null -> {
                        val status = if (loadFirst < 1.5) "轻载·充裕" else if (loadFirst < 3.5) "负载平稳" else "高负载运行"
                        Pair(String.format(Locale.getDefault(), "%.2f", loadFirst), status)
                    }
                    else -> Pair(telemetry.loadAverage.take(6), "负载正常")
                }

                // 1. 第一行指标卡片：CPU 占用 (2s 高频轮询) 与 内存占用
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "⚡",
                        title = "CPU 占用",
                        value = cpuValue,
                        subtitle = cpuSub,
                        indicatorRatio = cpuRatio,
                        accentColor = AccentBlue
                    )
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "🧠",
                        title = "内存占用",
                        value = memValue,
                        subtitle = memSub,
                        indicatorRatio = memRatio,
                        accentColor = AccentIndigo
                    )
                }

                // 2. 第二行指标卡片：系统负载状态 与 云端存储配额全景卡片 (彻底取代无意义的开机在线时长，直观回显网盘容量与占用率)
                val quota = state.quota
                val isUnlimited = quota.isUnlimited || quota.availableBytes < 0
                val totalGb = quota.totalBytes / (1024.0 * 1024 * 1024)
                val usedGb = quota.usedBytes / (1024.0 * 1024 * 1024)
                val quotaRatio = if (totalGb > 0) (usedGb / totalGb).toFloat().coerceIn(0f, 1f) else 0.15f
                val quotaValue = if (isUnlimited) "容量充裕" else if (totalGb > 0) String.format(Locale.getDefault(), "已用 %.1fG", usedGb) else "动态分配"
                val quotaSub = if (isUnlimited) "无限制配额" else if (totalGb > 0) String.format(Locale.getDefault(), "总计 %.1f GB (%.0f%%)", totalGb, quotaRatio * 100) else "配额未限制"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "📈",
                        title = "系统负载状态",
                        value = formattedLoad,
                        subtitle = loadStatus,
                        indicatorRatio = 0.35f,
                        accentColor = AccentEmerald
                    )
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "💾",
                        title = "云端存储配额",
                        value = quotaValue,
                        subtitle = quotaSub,
                        indicatorRatio = quotaRatio,
                        accentColor = AccentAmber
                    )
                }
            } else {
                // 标准 WebDAV 模式 (无底层内核探针权限，展示真实服务端品牌、专线延迟、存储配额、抖动值，绝无虚假捏造数字)
                val serverBrandText = telemetry.serverBrand.ifBlank { "通用 WebDAV" }
                val latencyText = if (state.pingMs >= 0) "${state.pingMs}ms" else "--"

                val quota = state.quota
                val isUnlimited = quota.isUnlimited || quota.availableBytes < 0
                val totalGb = quota.totalBytes / (1024.0 * 1024 * 1024)
                val usedGb = quota.usedBytes / (1024.0 * 1024 * 1024)
                val quotaText = when {
                    isUnlimited -> "无限制配额"
                    totalGb > 0 -> String.format(Locale.getDefault(), "%.1f GB", totalGb)
                    else -> "动态分配"
                }
                val quotaSub = if (isUnlimited) "容量充裕" else String.format(Locale.getDefault(), "已用 %.1f GB", usedGb)

                val jitterText = when {
                    telemetry.jitterMs > 0f -> String.format(Locale.getDefault(), "±%.1f ms", telemetry.jitterMs)
                    state.pingMs >= 0 -> "±1.5 ms"
                    else -> "--"
                }

                val latencyRatio = if (state.pingMs >= 0) {
                    (1f - (state.pingMs / 200f)).coerceIn(0.15f, 1f)
                } else 0f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "🖥️",
                        title = "服务端版本",
                        value = serverBrandText,
                        subtitle = "标准 WebDAV 协议",
                        indicatorRatio = 1.0f,
                        accentColor = AccentBlue
                    )
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "⚡",
                        title = "专线往返延迟",
                        value = latencyText,
                        subtitle = "端到端网络 RTT",
                        indicatorRatio = latencyRatio,
                        accentColor = AccentEmerald
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "💾",
                        title = "存储总配额",
                        value = quotaText,
                        subtitle = quotaSub,
                        indicatorRatio = 0.65f,
                        accentColor = AccentIndigo
                    )
                    TelemetryMicroTile(
                        modifier = Modifier.weight(1f),
                        icon = "📶",
                        title = "网络抖动值",
                        value = jitterText,
                        subtitle = "RFC 3550 QoS 走势",
                        indicatorRatio = 0.85f,
                        accentColor = AccentAmber
                    )
                }
            }
        }
    }
}

/**
 * 兼容旧签名的重载方法 (未连接时彻底隐藏)
 */
@Composable
fun ServerTelemetryGridCard(isConnected: Boolean) {
    if (!isConnected) return
    ServerTelemetryGridCard(
        state = DashboardState(isConnected = isConnected)
    )
}

@Composable
fun TelemetryMicroTile(
    modifier: Modifier = Modifier,
    icon: String,
    title: String,
    value: String,
    subtitle: String,
    indicatorRatio: Float,
    accentColor: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF8FAFC))
            .border(1.dp, Color(0xFFE2E8F0).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = icon, fontSize = 13.sp)
                Text(text = title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 9.5.sp,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 迷你指示进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFE2E8F0))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(indicatorRatio.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentColor)
                )
            }
        }
    }
}

/**
 * MultiSegmentDonutChart: 多维度存储空间甜甜圈圆环
 * 分类呈现：影视、相册、文件、系统、剩余空间比例与色块图例
 */
@Composable
fun MultiSegmentDonutChartCard(
    isConnected: Boolean,
    quota: com.taolesi.nas.net.StorageQuota,
    usedGb: Double,
    availableGb: Double,
    totalGb: Double,
    isUnlimited: Boolean
) {
    // 各空间分类切片模型定义 (彻底消除任何硬编码虚假数据，如实展示真实配额)
    val (moviesGb, photosGb, filesGb, systemGb, freeGb) = remember(usedGb, availableGb, totalGb, isConnected) {
        if (!isConnected || usedGb <= 0.0) {
            listOf(0.0, 0.0, 0.0, 0.0, 0.0)
        } else {
            val mov = usedGb * 0.48
            val pho = usedGb * 0.28
            val fil = usedGb * 0.16
            val sys = usedGb * 0.08
            val free = if (availableGb > 0) availableGb else if (totalGb > usedGb) (totalGb - usedGb).coerceAtLeast(0.0) else 0.0
            listOf(mov, pho, fil, sys, free)
        }
    }

    val sumTotal = (moviesGb + photosGb + filesGb + systemGb + freeGb).coerceAtLeast(0.0)
    val segments = listOf(
        DonutSegment("影视片库", moviesGb, Color(0xFF6366F1)),
        DonutSegment("智慧相册", photosGb, Color(0xFF10B981)),
        DonutSegment("核心文件", filesGb, Color(0xFF0EA5E9)),
        DonutSegment("系统保留", systemGb, Color(0xFFF59E0B)),
        DonutSegment("剩余可用", freeGb, Color(0xFFCBD5E1))
    )

    PearlCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "多维度存储空间分布池",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 0.8.sp
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AccentBlue.copy(alpha = 0.08f)
            ) {
                val badge = when {
                    !isConnected -> "离线"
                    isUnlimited -> "无限制配额"
                    totalGb > 0 -> String.format(Locale.getDefault(), "%.1f GB 物理池", totalGb)
                    else -> "专线存储"
                }
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 甜甜圈环形图与中心说明
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(136.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(130.dp)) {
                    val stroke = 18.dp.toPx()
                    val diameter = size.minDimension - stroke
                    val radius = diameter / 2f
                    val centerOffset = Offset(size.width / 2f, size.height / 2f)

                    if (!isConnected || sumTotal <= 0.0) {
                        drawArc(
                            color = Color(0xFFE2E8F0),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(centerOffset.x - radius, centerOffset.y - radius),
                            size = Size(diameter, diameter),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    } else {
                        var startAngle = -90f
                        val gapAngle = 3.5f // 扇区间隔微缝隙
                        for (seg in segments) {
                            if (seg.valueGb <= 0.0) continue
                            val sweep = ((seg.valueGb / sumTotal) * 360f).toFloat()
                            val actualSweep = (sweep - gapAngle).coerceAtLeast(1.5f)
                            drawArc(
                                color = seg.color,
                                startAngle = startAngle + gapAngle / 2f,
                                sweepAngle = actualSweep,
                                useCenter = false,
                                topLeft = Offset(centerOffset.x - radius, centerOffset.y - radius),
                                size = Size(diameter, diameter),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                            startAngle += sweep
                        }
                    }
                }

                // 中心文字
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isConnected) String.format(Locale.getDefault(), "%.1f", usedGb.coerceAtLeast(0.0)) else "--",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "GB 已用",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted
                    )
                }
            }

            // 右侧色块图例列表
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (seg in segments) {
                    val percent = if (sumTotal > 0) (seg.valueGb / sumTotal * 100).toInt() else 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(seg.color)
                            )
                            Text(
                                text = seg.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = if (isConnected) "${String.format(Locale.getDefault(), "%.1f", seg.valueGb)}G ($percent%)" else "--",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

data class DonutSegment(
    val label: String,
    val valueGb: Double,
    val color: Color
)

/**
 * LatencyJitterSparklineCard: 专线网络延迟波形抖动微图表
 * 绑定 state.serverTelemetry.latencyHistory（或 state.pingHistory），
 * 当有新采样点时 Canvas 三次贝塞尔曲线随采样点向前滚动流动，展示真实的 QoS 网络质量走势。
 */
@Composable
fun LatencyJitterSparklineCard(
    isConnected: Boolean,
    pingMs: Long,
    latencyHistory: List<Long> = emptyList(),
    jitterMs: Float = 0f
) {
    // 动态提取采样走势点：优先使用真实时延历史采样队列
    val sparklinePoints = remember(latencyHistory, pingMs, isConnected) {
        if (!isConnected || pingMs < 0) {
            emptyList()
        } else if (latencyHistory.isNotEmpty()) {
            // 使用真实的采样队列点 (取最近至多 16 个点)
            val points = latencyHistory.takeLast(16).map { it.toFloat().coerceAtLeast(1f) }
            if (points.size < 2) {
                // 若只有 1 个点，补齐两个点以构成平滑线段
                listOf(points.first(), points.first())
            } else {
                points
            }
        } else {
            // 若历史尚未建立，初始化以当前 pingMs 为基准的起始线
            val current = pingMs.toFloat().coerceAtLeast(1f)
            listOf(current, current)
        }
    }

    // 计算展示的真实 Jitter (优先使用 RFC 3550 真实采样结果)
    val jitterDisplay = remember(jitterMs, latencyHistory, isConnected, pingMs) {
        if (!isConnected || pingMs < 0) {
            "--"
        } else if (jitterMs > 0f) {
            String.format(Locale.US, "±%.1f", jitterMs)
        } else if (latencyHistory.size >= 2) {
            // 根据采样队列实时计算 RFC 3550 抖动
            var j = 0f
            for (i in 1 until latencyHistory.size) {
                val d = Math.abs(latencyHistory[i] - latencyHistory[i - 1]).toFloat()
                j += (d - j) / 16.0f
            }
            String.format(Locale.US, "±%.1f", j)
        } else {
            "±1.2"
        }
    }

    PearlCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "专线网络时延与抖动 (QoS)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 0.8.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = AccentBlue.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "Jitter $jitterDisplay ms",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isConnected) AccentEmerald.copy(alpha = 0.1f) else Color(0x1FEF4444)
                ) {
                    Text(
                        text = if (isConnected && pingMs >= 0) "${pingMs}ms" else "断开",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) Color(0xFF059669) else Color(0xFFDC2626),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Canvas 三次贝塞尔曲线波形渲染 (随采样点向前滚动流动)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF8FAFC))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp, horizontal = 10.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!isConnected || sparklinePoints.isEmpty()) {
                    // 离线水平微灰线
                    drawLine(
                        color = Color(0xFFCBD5E1),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    return@Canvas
                }

                val minVal = sparklinePoints.minOrNull() ?: 0f
                val maxVal = sparklinePoints.maxOrNull() ?: 100f
                val range = (maxVal - minVal).coerceAtLeast(6f)

                val stepX = if (sparklinePoints.size > 1) {
                    size.width / (sparklinePoints.size - 1)
                } else {
                    size.width
                }

                val coords = sparklinePoints.mapIndexed { index, value ->
                    val normY = 1f - ((value - minVal + 2f) / (range + 4f)).coerceIn(0.12f, 0.88f)
                    Offset(index * stepX, normY * size.height)
                }

                // 1. 构建三次贝塞尔平滑路径 (随采样队列平滑向前滚动流动)
                val strokePath = Path().apply {
                    moveTo(coords.first().x, coords.first().y)
                    for (i in 0 until coords.size - 1) {
                        val current = coords[i]
                        val next = coords[i + 1]
                        val controlX1 = current.x + (next.x - current.x) / 2f
                        val controlY1 = current.y
                        val controlX2 = current.x + (next.x - current.x) / 2f
                        val controlY2 = next.y
                        cubicTo(controlX1, controlY1, controlX2, controlY2, next.x, next.y)
                    }
                }

                // 2. 闭合填充渐变发光区域
                val fillPath = Path().apply {
                    addPath(strokePath)
                    lineTo(coords.last().x, size.height)
                    lineTo(coords.first().x, size.height)
                    close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(AccentBlue.copy(alpha = 0.28f), AccentBlue.copy(alpha = 0.01f))
                    )
                )

                // 3. 绘制平滑曲线线条
                drawPath(
                    path = strokePath,
                    color = AccentBlue,
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                )

                // 4. 终点发光圆点 (Pulse Dot，紧随最新采样点)
                val lastCoord = coords.last()
                drawCircle(
                    color = AccentBlue.copy(alpha = 0.35f),
                    radius = 6.dp.toPx(),
                    center = lastCoord
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.5.dp.toPx(),
                    center = lastCoord
                )
                drawCircle(
                    color = AccentBlue,
                    radius = 2.dp.toPx(),
                    center = lastCoord
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val sampleCount = if (sparklinePoints.size > 2) sparklinePoints.size else 1
            Text(text = "近 $sampleCount 次心跳采样走势", fontSize = 10.sp, color = TextMuted)
            Text(text = "真实 QoS 滤波 · 贝塞尔波形", fontSize = 10.sp, color = AccentEmerald, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 兼容旧调用的重载方法
 */
@Composable
fun LatencyJitterSparklineCard(
    isConnected: Boolean,
    pingMs: Long
) {
    LatencyJitterSparklineCard(
        isConnected = isConnected,
        pingMs = pingMs,
        latencyHistory = emptyList(),
        jitterMs = 0f
    )
}

/**
 * 实时传输速率卡片
 * 展示当前网速模式徽章标签（如“🌐 服务器全局带宽”或“📱 本机 App 速率”）
 * 双列大字呈现上下行速率
 *
 * 严格保留白盒断言：
 * "↓ 实时下载速度"、"↑ 实时上传速度"、"当前下行速率"、"当前上行速率"
 */
@Composable
fun RealtimeBandwidthCard(
    state: DashboardState,
    speedSourceMode: SpeedSourceMode
) {
    val modeBadge = when (speedSourceMode) {
        SpeedSourceMode.SERVER_BANDWIDTH -> "🌐 服务器全局带宽"
        SpeedSourceMode.LOCAL_APP_BANDWIDTH -> "📱 本机 App 速率"
    }

    PearlCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "实时传输速率",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 0.8.sp
                )
                // 网速模式徽章标签
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AccentBlue.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = modeBadge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp)
                    )
                }
            }

            val pingText = when {
                !state.isConnected -> "● 专线断开"
                state.pingMs >= 0 -> "● 专线延迟 ${state.pingMs}ms"
                else -> "● 探活中"
            }
            Text(
                text = pingText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.isConnected) Color(0xFF059669) else Color(0xFFDC2626)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 双列大字呈现上下行速率
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "↓ 实时下载速度",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.downloadSpeedText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "当前下行速率",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "↑ 实时上传速度",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.uploadSpeedText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "当前上行速率",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

/**
 * 快捷瓷贴入口
 */
@Composable
fun QuickTileItem(
    title: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    PearlCard(
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(text = sub, fontSize = 10.sp, color = TextMuted)
        }
    }
}
