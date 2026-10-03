package com.taolesi.nas.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taolesi.nas.data.PreferencesManager
import com.taolesi.nas.data.SpeedSourceMode
import com.taolesi.nas.service.KeepAliveConnectionService
import com.taolesi.nas.ui.components.PearlCard
import com.taolesi.nas.ui.theme.*
import java.io.File
import java.util.Locale

@Composable
fun SettingsScreen(
    prefs: PreferencesManager,
    onOpenConnectionDialog: () -> Unit,
    onClearCache: () -> Unit,
    onTriggerSync: (() -> Unit)? = null,
    onTriggerTwoWaySync: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    dashboardState: com.taolesi.nas.ui.viewmodel.DashboardState? = null
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var autoBackup by remember { mutableStateOf(prefs.autoBackupOnOpen) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnlyBackup) }
    var deleteProtect by remember { mutableStateOf(prefs.deleteProtection) }
    var photoBackupEnabled by remember { mutableStateOf(prefs.photoBackupEnabled) }
    var photoSyncMode by remember { mutableStateOf(prefs.photoSyncMode) }
    var albumDisplayStrategy by remember { mutableStateOf(prefs.albumDisplayStrategy) }
    var twoWaySyncEnabled by remember { mutableStateOf(prefs.twoWaySyncEnabled) }
    var hwAccel by remember { mutableStateOf(prefs.hardwareAcceleration) }
    var keepAliveConnection by remember { mutableStateOf(prefs.keepAliveConnection) }
    var maxConcurrentRequests by remember { mutableIntStateOf(prefs.maxConcurrentRequests) }
    var speedSourceMode by remember { mutableStateOf(prefs.speedSourceMode) }

    // 本地缓存大小真实统计
    var cacheSizeBytes by remember { mutableLongStateOf(0L) }

    fun calculateCacheSize(): Long {
        fun getFolderSize(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            var size = 0L
            dir.listFiles()?.forEach { file ->
                size += if (file.isDirectory) getFolderSize(file) else file.length()
            }
            return size
        }
        return getFolderSize(context.cacheDir) + getFolderSize(context.externalCacheDir)
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    LaunchedEffect(Unit) {
        cacheSizeBytes = calculateCacheSize()
    }

    val onClearCacheAction = {
        val freedBytes = calculateCacheSize()
        fun deleteDirContent(dir: File?) {
            dir?.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
        }
        deleteDirContent(context.cacheDir)
        deleteDirContent(context.externalCacheDir)
        cacheSizeBytes = calculateCacheSize()
        onClearCache()
        val msg = if (freedBytes > 0) "已成功清空本地缓存，释放 ${formatSize(freedBytes)}" else "本地缓存已清空"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // 强一致连接状态研判 (直接绑定全局 DashboardState，与首页完全统一)
    val (connectionBadgeText, connectionBadgeColor) = remember(prefs.serverUrl, prefs.password, dashboardState) {
        when {
            prefs.serverUrl.isBlank() || !prefs.serverUrl.startsWith("http") -> "配置错误" to Color(0xFFEF4444)
            prefs.password.isBlank() -> "未配置密码" to Color(0xFFF59E0B)
            dashboardState?.isLoading == true -> "检测中..." to AccentBlue
            dashboardState?.isConnected == true -> "专线正常 (${dashboardState.pingMs}ms)" to AccentEmerald
            dashboardState?.isConnected == false -> (dashboardState.errorMessage ?: "连接失败") to Color(0xFFEF4444)
            else -> "未连接" to Color(0xFFEF4444)
        }
    }

    val serverSubtitle = remember(prefs.serverUrl, prefs.username, prefs.password, dashboardState) {
        when {
            prefs.serverUrl.isBlank() -> "尚未配置服务器 URL"
            prefs.password.isBlank() -> "${prefs.serverUrl} · 尚未设置密码"
            dashboardState?.isConnected == false -> "${prefs.serverUrl} · 连接异常: ${dashboardState.errorMessage ?: "离线"}"
            else -> "${prefs.serverUrl} (${prefs.username}) · 永久免登"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 50.dp, bottom = 128.dp)
    ) {
        // 顶栏 (简洁设计，不添加冗余胶囊)
        Text(
            text = "设置",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            letterSpacing = (-0.8).sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        // Group 1: 独立连接入口 (点击直接进入专属弹窗配置)
        Text(
            text = "服务器连接",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        PearlCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenConnectionDialog() }
                .padding(bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "🌐", fontSize = 16.sp)
                    }
                    Column {
                        Text(text = "NAS 服务器连接", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = serverSubtitle, fontSize = 11.sp, color = TextMuted, maxLines = 1)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = connectionBadgeText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = connectionBadgeColor)
                    Text(text = "›", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextSubtle)
                }
            }
        }

        // Group 2: 后台常驻连接与网络保活 (新增开关)
        Text(
            text = "专线网络与保活",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        PearlCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            SettingsToggleRow(
                icon = "⚡",
                title = "保持专线长连接常驻",
                sub = "在系统后台常驻维持专线轻量通信，打开 App 秒连就绪；关闭后彻底销毁，零能耗",
                color = AccentBlue,
                checked = keepAliveConnection
            ) { enabled ->
                keepAliveConnection = enabled
                prefs.keepAliveConnection = enabled
                if (enabled) {
                    KeepAliveConnectionService.start(context)
                    Toast.makeText(context, "后台常驻连接已启动", Toast.LENGTH_SHORT).show()
                } else {
                    KeepAliveConnectionService.stop(context)
                    Toast.makeText(context, "后台常驻连接已彻底销毁", Toast.LENGTH_SHORT).show()
                }
            }

            HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

            // 网络请求并发数调节旋钮
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentEmerald.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🚀", fontSize = 16.sp)
                        }
                        Column {
                            Text(text = "网络请求并发数限制", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(text = "控制同时向 NAS 发起的网络连接数量，默认推荐 5 并发", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AccentEmerald.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${maxConcurrentRequests} 并发",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentEmerald,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 自定义并发数输入与加减微调步进器
                var customInputText by remember(maxConcurrentRequests) { mutableStateOf(maxConcurrentRequests.toString()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "自定义填入数值",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "范围 1~32 并发，即填即生效",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 减号按键
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFF1F5F9),
                            modifier = Modifier
                                .size(36.dp)
                                .clickable {
                                    val current = customInputText.toIntOrNull() ?: maxConcurrentRequests
                                    if (current > 1) {
                                        val newVal = current - 1
                                        customInputText = newVal.toString()
                                        maxConcurrentRequests = newVal
                                        prefs.maxConcurrentRequests = newVal
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "－", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                        }

                        // 数字展示与输入框
                        OutlinedTextField(
                            value = customInputText,
                            onValueChange = { newVal ->
                                val filtered = newVal.filter { it.isDigit() }.take(2)
                                customInputText = filtered
                                val parsed = filtered.toIntOrNull()
                                if (parsed != null && parsed in 1..32) {
                                    maxConcurrentRequests = parsed
                                    prefs.maxConcurrentRequests = parsed
                                }
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentEmerald
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier
                                .width(64.dp)
                                .height(50.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentEmerald,
                                unfocusedBorderColor = Color(0x33CBD5E1),
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            )
                        )

                        // 加号按键
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFF1F5F9),
                            modifier = Modifier
                                .size(36.dp)
                                .clickable {
                                    val current = customInputText.toIntOrNull() ?: maxConcurrentRequests
                                    if (current < 32) {
                                        val newVal = current + 1
                                        customInputText = newVal.toString()
                                        maxConcurrentRequests = newVal
                                        prefs.maxConcurrentRequests = newVal
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "＋", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // Group 3: 网速监控源 (双段胶囊卡片)
        Text(
            text = "网速监控源",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        PearlCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "看板首页网速展示源",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (speedSourceMode == SpeedSourceMode.SERVER_BANDWIDTH) "全网吞吐" else "本机流量",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBlue
                    )
                }

                // 双段胶囊卡片切换容器
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFF1F5F9))
                        .padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 段 1: SERVER_BANDWIDTH
                        val isServerSelected = speedSourceMode == SpeedSourceMode.SERVER_BANDWIDTH
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (isServerSelected) {
                                        Modifier
                                            .shadow(2.dp, RoundedCornerShape(10.dp))
                                            .background(Color.White)
                                            .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier.background(Color.Transparent)
                                    }
                                )
                                .clickable {
                                    speedSourceMode = SpeedSourceMode.SERVER_BANDWIDTH
                                    prefs.speedSourceMode = SpeedSourceMode.SERVER_BANDWIDTH
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "🌐 服务器全局带宽",
                                fontSize = 12.sp,
                                fontWeight = if (isServerSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isServerSelected) AccentBlue else TextMuted
                            )
                        }

                        // 段 2: LOCAL_APP_BANDWIDTH
                        val isLocalSelected = speedSourceMode == SpeedSourceMode.LOCAL_APP_BANDWIDTH
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (isLocalSelected) {
                                        Modifier
                                            .shadow(2.dp, RoundedCornerShape(10.dp))
                                            .background(Color.White)
                                            .border(1.dp, AccentEmerald.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier.background(Color.Transparent)
                                    }
                                )
                                .clickable {
                                    speedSourceMode = SpeedSourceMode.LOCAL_APP_BANDWIDTH
                                    prefs.speedSourceMode = SpeedSourceMode.LOCAL_APP_BANDWIDTH
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📱 本机 App 速率",
                                fontSize = 12.sp,
                                fontWeight = if (isLocalSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isLocalSelected) AccentEmerald else TextMuted
                            )
                        }
                    }
                }

                // 动态模式解读说明
                Text(
                    text = if (speedSourceMode == SpeedSourceMode.SERVER_BANDWIDTH) {
                        "当前模式：监控 NAS 服务端全局全网实时上下行带宽吞吐 (默认推荐)"
                    } else {
                        "当前模式：仅统计当前手机 App 传输与媒体流播放消耗的网络带宽"
                    },
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        // Group 4: 相册设置 (独立相册与同步中心)
        Text(
            text = "相册设置 (备份与同步策略)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        PearlCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. 本地照片备份 / 云备份总开关
                SettingsToggleRow(
                    icon = "📸",
                    title = "本地照片备份 / 云备份",
                    sub = "开启后在 NAS 空间自动建立独立的手机备份照片库",
                    color = AccentBlue,
                    checked = photoBackupEnabled
                ) { isChecked ->
                    photoBackupEnabled = isChecked
                    prefs.photoBackupEnabled = isChecked
                    prefs.autoBackup = isChecked
                    prefs.autoBackupOnOpen = isChecked
                }

                // 显著的"授权并开始备份"入口按钮（权限申请 + 立即触发备份一体化）
                if (onTriggerSync != null) {
                    Button(
                        onClick = { onTriggerSync() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "授权并开始备份",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

                // 2. 同步策略选择器 (默认双向镜像同步)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AccentEmerald.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "🔄", fontSize = 16.sp)
                            }
                            Column {
                                Text(text = "相册同步策略", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                val modeDesc = when (photoSyncMode) {
                                    "TWO_WAY_MIRROR" -> "双向镜像同步 (默认推荐 · 本地与云端双向对齐)"
                                    "ONE_WAY_UPLOAD" -> "单向上传备份 (手机增量上传至 NAS，不拉取云端)"
                                    "ONE_WAY_DOWNLOAD" -> "单向拉取归档 (NAS 照片单向下载至手机)"
                                    else -> "双向镜像同步"
                                }
                                Text(text = modeDesc, fontSize = 11.sp, color = TextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            Triple("TWO_WAY_MIRROR", "双向镜像", "本地云端对齐"),
                            Triple("ONE_WAY_UPLOAD", "单向上传", "只传手机新照"),
                            Triple("ONE_WAY_DOWNLOAD", "单向拉取", "仅拉云端照片")
                        )
                        modes.forEach { (modeKey, title, _) ->
                            val isSelected = photoSyncMode == modeKey
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentEmerald.copy(alpha = 0.12f) else Color(0xFFF1F5F9),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.5f)) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        photoSyncMode = modeKey
                                        prefs.photoSyncMode = modeKey
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AccentEmerald else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

                // 2.5 相册显示排版策略选择器 (默认固定格式 vs 自适应瀑布流)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AccentBlue.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "🖼️", fontSize = 16.sp)
                            }
                            Column {
                                Text(text = "相册缩略图排版策略", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                val stratDesc = if (albumDisplayStrategy == "WATERFALL_FLOW") "自适应瀑布流 (原图比例高低错落，完整呈现构图)" else "默认固定格式 (等高严整规矩网格，整齐划一)"
                                Text(text = stratDesc, fontSize = 11.sp, color = TextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val stratList = listOf(
                            Pair("DEFAULT_GRID", "默认固定格式"),
                            Pair("WATERFALL_FLOW", "自适应瀑布流")
                        )
                        stratList.forEach { (stratKey, title) ->
                            val isSelected = albumDisplayStrategy == stratKey
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentBlue.copy(alpha = 0.12f) else Color(0xFFF1F5F9),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.5f)) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        albumDisplayStrategy = stratKey
                                        prefs.albumDisplayStrategy = stratKey
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AccentBlue else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

                SettingsToggleRow(
                    icon = "⚡",
                    title = "打开应用时自动增量备份",
                    sub = "仅在点开 App 时同步，退出后彻底销毁，绝不驻留后台",
                    color = AccentEmerald,
                    checked = autoBackup
                ) {
                    autoBackup = it
                    prefs.autoBackupOnOpen = it
                }

                HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

                SettingsToggleRow(
                    icon = "📶",
                    title = "仅在 Wi-Fi 网络下备份",
                    sub = "移动蜂窝网络自动暂停",
                    color = AccentEmerald,
                    checked = wifiOnly
                ) {
                    wifiOnly = it
                    prefs.wifiOnlyBackup = it
                }

                HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

                SettingsToggleRow(
                    icon = "🛡️",
                    title = "本地照片防误删保护",
                    sub = "手机本地删照片腾空间，NAS 云端永久安全保留",
                    color = AccentPurple,
                    checked = deleteProtect
                ) {
                    deleteProtect = it
                    prefs.deleteProtection = it
                }
            }
        }

        // Group 4.5: 多端同步与双向传递专属文件夹 (多设备互传)
        Text(
            text = "多端同步与双向传递",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        PearlCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsToggleRow(
                    icon = "🔄",
                    title = "多端专属文件夹双向同步",
                    sub = "本地 Downloads/TaleSync ↔ 云端 /双向同步",
                    color = AccentEmerald,
                    checked = twoWaySyncEnabled
                ) {
                    twoWaySyncEnabled = it
                    prefs.twoWaySyncEnabled = it
                }

                HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "立即执行双向增量同步", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "自动比对本地与云端，增量双向补齐", fontSize = 11.sp, color = TextMuted)
                    }

                    Surface(
                        shape = RoundedCornerShape(9999.dp),
                        color = Color.White,
                        shadowElevation = 2.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable {
                            android.widget.Toast.makeText(context, "正在发起多端双向同步...", android.widget.Toast.LENGTH_SHORT).show()
                            onTriggerTwoWaySync?.invoke()
                        }
                    ) {
                        Text(
                            text = "立即同步",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentEmerald,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Group 5: 播放引擎与流媒体调优 (彻底杜绝 HTTP 206 与 64MB 零占机 敏感词)
        Text(
            text = "影视播放与硬解调优",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        PearlCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsToggleRow(
                    icon = "🎬",
                    title = "硬件解码加速 (MediaCodec)",
                    sub = "芯片硬解 4K HEVC 10-bit，省电流畅",
                    color = AccentIndigo,
                    checked = hwAccel
                ) {
                    hwAccel = it
                    prefs.hardwareAcceleration = it
                }

                HorizontalDivider(color = Color(0x33CBD5E1), thickness = 0.8.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentPurple.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "⚡", fontSize = 14.sp)
                        }
                        Column {
                            Text(text = "流式切片预缓冲大小", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(text = "分片流式内存环形缓存", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                    Text(text = "分片预读缓存 (退出释放)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
                }
            }
        }

        // Group 6: 存储管理
        Text(
            text = "存储管理",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        PearlCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x1F64748B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "📑", fontSize = 14.sp)
                    }
                    Column {
                        Text(text = "本地临时预览缓存", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "当前占用手机 ${formatSize(cacheSizeBytes)}", fontSize = 11.sp, color = TextMuted)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(9999.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33EF4444)),
                    modifier = Modifier.clickable { onClearCacheAction() }
                ) {
                    Text(
                        text = "清空",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsToggleRow(
    icon: String,
    title: String,
    sub: String,
    color: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, fontSize = 14.sp)
            }
            Column {
                Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(text = sub, fontSize = 10.5.sp, color = TextMuted)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentEmerald,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFCBD5E1)
            )
        )
    }
}
