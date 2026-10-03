package com.taolesi.nas.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.taolesi.nas.data.PreferencesManager
import com.taolesi.nas.net.DavResource
import com.taolesi.nas.ui.components.PearlCard
import com.taolesi.nas.ui.theme.*
import com.taolesi.nas.ui.viewmodel.ResourceState
import java.net.URLEncoder
import java.util.Locale

/**
 * 影视片库界面
 * 特性：接入 Nextcloud 480x270 视频缩略图预览接口、高品质拟物毛玻璃降级封面、
 * 智能清晰度与编码标签识别、跟手弹性下拉刷新（5s 优雅复位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    videosState: ResourceState<List<DavResource>>,
    serverBaseUrl: String = "",
    authHeader: String = "",
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onPlayMovie: (DavResource) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember(context) { PreferencesManager(context) }

    // 动态回退保证预览 URL 及认证 100% 可用
    val effectiveBaseUrl = remember(serverBaseUrl, prefs.serverUrl) {
        serverBaseUrl.ifBlank { prefs.serverUrl }
    }
    val effectiveAuthHeader = remember(authHeader, prefs.username, prefs.password) {
        if (authHeader.isNotBlank()) authHeader
        else {
            val creds = "${prefs.username}:${prefs.password}"
            "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
        }
    }

    val isRefreshingTimedOutState = remember { mutableStateOf(false) }

    // 10 秒超时安全复位机制：避免弱网下拉刷新长时间悬停
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            isRefreshingTimedOutState.value = false
            kotlinx.coroutines.delay(10000L)
            isRefreshingTimedOutState.value = true
        } else {
            isRefreshingTimedOutState.value = false
        }
    }

    val effectiveRefreshing = isRefreshing && !isRefreshingTimedOutState.value
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
    val pullOffsetPx by animateFloatAsState(
        targetValue = if (pullToRefreshState.distanceFraction > 0f) {
            pullToRefreshState.distanceFraction.coerceIn(0f, 1.2f) * with(density) { 68.dp.toPx() }
        } else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "videoPullOffset"
    )

    PullToRefreshBox(
        isRefreshing = effectiveRefreshing,
        onRefresh = {
            triggerHapticVibration()
            isRefreshingTimedOutState.value = false
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
                .padding(horizontal = 20.dp)
                .padding(top = 54.dp)
        ) {
            // 顶栏：影视片库标题与流播技术规格
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "影视片库",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        letterSpacing = (-0.8).sp
                    )

                    val countText = when (videosState) {
                        is ResourceState.Success -> "云端片库共 ${videosState.data.size} 部影视 · 4K 切片秒播"
                        is ResourceState.Loading -> "正在连接 NAS 扫描电影目录..."
                        is ResourceState.Error -> "加载失败: ${videosState.message}"
                        else -> "就绪"
                    }
                    Text(
                        text = countText,
                        fontSize = 12.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 影视网格视图，直接呈现影片卡片列表
            when (videosState) {
                is ResourceState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 128.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AccentIndigo)
                    }
                }
                is ResourceState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 128.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PearlCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFEF2F2)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⚠️", fontSize = 22.sp)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(text = "影视片库加载失败", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = videosState.message.ifBlank { "无法获取云端电影目录，请检查连接" },
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = onRefresh,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                                    shape = RoundedCornerShape(9999.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("重试扫描片库", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                is ResourceState.Success -> {
                    val videoList = videosState.data
                    if (videoList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 128.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            PearlCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(AccentIndigo.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🍿", fontSize = 26.sp)
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(text = "云端片库暂无视频", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "将视频拷贝到 NAS 的【02-电影与影视】目录即可在此直接秒播",
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = onRefresh,
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                                        shape = RoundedCornerShape(9999.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("重新扫描片库", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 128.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(videoList, key = { it.path }) { video ->
                                val sizeMb = video.contentLength / (1024.0 * 1024)
                                val sizeStr = if (sizeMb > 1024) {
                                    String.format(Locale.getDefault(), "%.2f GB", sizeMb / 1024)
                                } else {
                                    String.format(Locale.getDefault(), "%.1f MB", sizeMb)
                                }
                                val ext = video.name.substringAfterLast('.', "").uppercase()
                                val lowerName = video.name.lowercase()

                                // 智能解析视频清晰度
                                val resolutionLabel = when {
                                    "4k" in lowerName || "2160" in lowerName || "uhd" in lowerName -> "4K 超清"
                                    "1080" in lowerName || "fhd" in lowerName -> "1080P 全高清"
                                    "720" in lowerName || "hd" in lowerName -> "720P 高清"
                                    else -> "HD 高清"
                                }

                                // 智能解析视频编解码格式
                                val codecLabel = when {
                                    "265" in lowerName || "hevc" in lowerName -> "HEVC (H.265)"
                                    "264" in lowerName || "avc" in lowerName -> "AVC (H.264)"
                                    "av01" in lowerName || "av1" in lowerName -> "AV1"
                                    else -> ext
                                }

                                // Nextcloud 视频缩略图预览接口 (480x270 黄金 16:9 比例)
                                val thumbUrl = remember(video.path, effectiveBaseUrl, prefs.username) {
                                    val cleanBase = effectiveBaseUrl.trimEnd('/')
                                    val prefix = "/remote.php/dav/files/${prefs.username}"
                                    val rel = if (video.path.startsWith(prefix)) {
                                        video.path.substring(prefix.length)
                                    } else {
                                        video.path.replaceFirst("^/remote\\.php/dav/files/[^/]+".toRegex(), "")
                                    }
                                    val encoded = try {
                                        URLEncoder.encode(rel, "UTF-8").replace("+", "%20")
                                    } catch (_: Exception) {
                                        rel
                                    }
                                    "$cleanBase/index.php/core/preview.png?file=$encoded&x=480&y=270"
                                }

                                PearlCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPlayMovie(video) }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(6.dp)
                                    ) {
                                        // 16:9 视频海报/缩略图容器 (带高品质拟物毛玻璃降级卡片)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(
                                                            Color(0xFF1E1B4B),
                                                            Color(0xFF312E81),
                                                            Color(0xFF0F172A)
                                                        )
                                                    )
                                                )
                                        ) {
                                            // 底层拟物毛玻璃降级背景 (在未生成预览图或加载中优雅垫底)
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Movie,
                                                        contentDescription = null,
                                                        tint = Color.White.copy(alpha = 0.20f),
                                                        modifier = Modifier.size(54.dp)
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "TAOLESI CINEMA",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        letterSpacing = 2.sp,
                                                        color = Color.White.copy(alpha = 0.25f)
                                                    )
                                                }
                                            }

                                            // Nextcloud 视频缩略图预览
                                            AsyncImage(
                                                model = ImageRequest.Builder(context)
                                                    .data(thumbUrl)
                                                    .addHeader("Authorization", effectiveAuthHeader)
                                                    .scale(Scale.FILL)
                                                    .crossfade(true)
                                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                                    .diskCachePolicy(CachePolicy.ENABLED)
                                                    .build(),
                                                contentDescription = video.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            // 渐变暗影层 (增强文字对比度)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(
                                                        Brush.verticalGradient(
                                                            0.0f to Color.Black.copy(alpha = 0.35f),
                                                            0.4f to Color.Transparent,
                                                            1.0f to Color.Black.copy(alpha = 0.75f)
                                                        )
                                                    )
                                            )

                                            // 左上角清晰度标签
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (resolutionLabel.contains("4K")) Color(0xFFD97706) else AccentIndigo,
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    text = resolutionLabel,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            // 右上角编码格式与封装格式标签
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color.Black.copy(alpha = 0.65f),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    text = codecLabel,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            // 居中半透明毛玻璃播放微动按键
                                            Surface(
                                                shape = CircleShape,
                                                color = Color.Black.copy(alpha = 0.45f),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.30f)),
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .size(46.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "播放",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(26.dp)
                                                    )
                                                }
                                            }

                                            // 左下角文件大小
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color.Black.copy(alpha = 0.65f),
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    text = sizeStr,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // 卡片底部：影片标题与元数据
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = video.name,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "${video.lastModified.take(16)} · $ext 封装",
                                                    fontSize = 11.sp,
                                                    color = TextMuted
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(9999.dp),
                                                color = AccentIndigo.copy(alpha = 0.12f),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.25f))
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        tint = AccentIndigo,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Text(
                                                        text = "秒播",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = AccentIndigo
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
