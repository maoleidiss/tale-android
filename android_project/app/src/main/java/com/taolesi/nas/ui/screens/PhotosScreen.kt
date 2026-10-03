package com.taolesi.nas.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import com.taolesi.nas.data.SyncStatus
import com.taolesi.nas.net.DavResource
import com.taolesi.nas.ui.components.PearlCard
import com.taolesi.nas.ui.theme.*
import com.taolesi.nas.ui.utils.PhotoTimelineHelper
import com.taolesi.nas.ui.utils.SystemBarsController
import com.taolesi.nas.ui.utils.pinchToZoomColumns
import com.taolesi.nas.ui.viewmodel.PhotoSortOrder
import com.taolesi.nas.ui.viewmodel.ResourceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 相册文件夹模型 (聚类目录与相册封面)
 */
data class AlbumFolder(
    val name: String,
    val folderPath: String,
    val photos: List<DavResource>,
    val coverPhoto: DavResource
)

enum class PhotoTabMode {
    ALL_PHOTOS,  // 全部照片时间轴流
    ALBUMS       // 相册文件夹网格
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    photosState: ResourceState<List<DavResource>>,
    syncStatus: SyncStatus,
    serverBaseUrl: String,
    authHeader: String,
    isRefreshing: Boolean = false,
    initialColumns: Int = 3,
    sortOrder: PhotoSortOrder = PhotoSortOrder.DATE_DESC,
    albumDisplayStrategy: String = "DEFAULT_GRID",
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onColumnsChanged: (Int) -> Unit = {},
    onRefresh: () -> Unit = {},
    onTriggerSync: () -> Unit = {},
    onSortOrderChange: (PhotoSortOrder) -> Unit = {},
    onPhotoClick: (DavResource) -> Unit,
    onPhotoDelete: ((DavResource) -> Unit)? = null,
    activeFolderOverride: AlbumFolder? = null,
    onFolderEntered: ((AlbumFolder?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var columns by remember { mutableIntStateOf(initialColumns) }
    var tabMode by remember { mutableStateOf(PhotoTabMode.ALL_PHOTOS) }
    var activeFolder by remember { mutableStateOf<AlbumFolder?>(activeFolderOverride) }
    var isSearchActive by remember { mutableStateOf(false) }

    LaunchedEffect(activeFolderOverride) {
        if (activeFolderOverride != activeFolder) {
            activeFolder = activeFolderOverride
        }
    }
    var showSortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 严格物理隔离：相册中纯净展示照片，绝对不显示视频
    val rawPhotos = remember((photosState as? ResourceState.Success)?.data) {
        (photosState as? ResourceState.Success)?.data.orEmpty().filter { res ->
            if (res.isDirectory) return@filter false
            val ext = res.name.substringAfterLast('.', "").lowercase()
            val isKnownImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "raw", "dng") ||
                    res.contentType.startsWith("image/")
            val isKnownVideo = ext in listOf("mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "m4v", "ts") ||
                    res.contentType.startsWith("video/")
            isKnownImage && !isKnownVideo
        }
    }
    
    // 即时搜索过滤
    val allPhotos = remember(rawPhotos, searchQuery) {
        if (searchQuery.isBlank()) rawPhotos else PhotoTimelineHelper.filterPhotos(rawPhotos, searchQuery)
    }

    // 独立持久化网格状态
    val gridStateAll = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val gridStateAlbums = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    // 内存预聚类相册目录
    val albumFolders = remember(allPhotos) {
        allPhotos.groupBy { photo ->
            val clean = photo.path.trim('/')
            val parent = clean.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) parent.substringAfterLast('/') else "根目录"
        }.map { (folderName, photosInFolder) ->
            AlbumFolder(
                name = folderName,
                folderPath = photosInFolder.firstOrNull()?.path?.substringBeforeLast('/') ?: "",
                photos = photosInFolder,
                coverPhoto = photosInFolder.first()
            )
        }.sortedByDescending { it.photos.size }
    }

    // 按时间排序时生成严格数字时间戳日期分桶；按名称排序时不分组
    val isDateSorting = (sortOrder == PhotoSortOrder.DATE_DESC || sortOrder == PhotoSortOrder.DATE_ASC)
    val dateGroups = remember(allPhotos, isDateSorting) {
        if (isDateSorting) {
            PhotoTimelineHelper.groupPhotosByDate(allPhotos)
        } else {
            emptyList()
        }
    }

    val distinctYears = remember(dateGroups) {
        PhotoTimelineHelper.extractDistinctYears(dateGroups)
    }

    // 双重检测门禁年份控件状态：
    // 条件 1: 用户已滑到较旧的照片（例如第一可见项日期距今天 >= 7 天，或者当前项不在第一组近期照片）
    // 条件 2: 正在高速滑动 (高 velocity/delta)
    var isFastScrolling by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }

    val hasScrolledToOlderPhotos by remember {
        derivedStateOf {
            val firstIdx = gridStateAll.firstVisibleItemIndex
            if (firstIdx <= 3) return@derivedStateOf false
            
            // 查找第一可见项对应的日期
            var accumulated = 0
            for (group in dateGroups) {
                accumulated += 1 + group.photos.size
                if (firstIdx < accumulated) {
                    val days = ChronoUnit.DAYS.between(group.date, today)
                    return@derivedStateOf days >= 7 || group.year < today.year
                }
            }
            firstIdx > 12
        }
    }

    // 预计算 URL 映射表：本地直接直通 URI，云端使用 preview 缩略图
    val photoThumbUrlMap = remember(allPhotos, serverBaseUrl) {
        val cleanBase = serverBaseUrl.trimEnd('/')
        val map = HashMap<String, String>(allPhotos.size)
        val prefixRegex = Regex("^/remote\\.php/dav/files/[^/]+", RegexOption.IGNORE_CASE)
        val webdavPrefixRegex = Regex("^/remote\\.php/webdav", RegexOption.IGNORE_CASE)
        val filesPrefixRegex = Regex("^/files/[^/]+", RegexOption.IGNORE_CASE)

        allPhotos.forEach { photo ->
            val url = if (photo.isLocal || photo.path.startsWith("content://") || photo.path.startsWith("file://")) {
                photo.localUri ?: photo.path
            } else if (photo.path.startsWith("http")) {
                com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(cleanBase, photo.path)
            } else {
                var rel = photo.path
                rel = rel.replaceFirst(prefixRegex, "")
                rel = rel.replaceFirst(webdavPrefixRegex, "")
                rel = rel.replaceFirst(filesPrefixRegex, "")
                if (!rel.startsWith('/')) rel = "/$rel"
                val encoded = try { java.net.URLEncoder.encode(rel, "UTF-8").replace("+", "%20") } catch (_: Exception) { rel }
                "$cleanBase/index.php/core/preview.png?file=$encoded&x=360&y=360"
            }
            map[photo.path] = url
        }
        map
    }

    val isRefreshingTimedOutState = remember { mutableStateOf(false) }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            isRefreshingTimedOutState.value = false
            delay(10000L)
            isRefreshingTimedOutState.value = true
        } else {
            isRefreshingTimedOutState.value = false
        }
    }

    val effectiveRefreshing = isRefreshing && !isRefreshingTimedOutState.value

    // 自适应折叠收纳 + 联动收纳安卓系统状态栏（时间/电池/信号等）
    var isHeaderVisible by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            // 离开页面时确保安全恢复系统状态栏
            SystemBarsController.showSystemBars(context)
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val deltaY = available.y

                // 速度检测：单次事件位移绝对值 > 30f 判定为快速滑动
                if (abs(deltaY) > 30f) {
                    isFastScrolling = true
                }

                if (deltaY < -15f && isHeaderVisible) {
                    isHeaderVisible = false
                    // 向上滑全面收纳：收纳 App 顶栏，并将安卓系统状态栏一起收纳隐藏 (全沉浸)
                    SystemBarsController.hideSystemBars(context)
                } else if (deltaY > 15f && !isHeaderVisible) {
                    isHeaderVisible = true
                    // 向下滑动：恢复 App 顶栏与系统状态栏
                    SystemBarsController.showSystemBars(context)
                }
                return Offset.Zero
            }
        }
    }

    // 滚动停止后自动延迟退隐年份控件
    LaunchedEffect(gridStateAll.isScrollInProgress) {
        if (!gridStateAll.isScrollInProgress) {
            delay(1500L)
            isFastScrolling = false
        }
    }

    BackHandler(enabled = activeFolder != null || isSearchActive) {
        if (isSearchActive) {
            isSearchActive = false
            onSearchQueryChange("")
        } else if (activeFolder != null) {
            activeFolder = null
        }
    }

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

    LaunchedEffect(activeFolder) {
        onFolderEntered?.invoke(activeFolder)
    }

    val density = LocalDensity.current
    val pullOffsetPx by animateFloatAsState(
        targetValue = if (pullToRefreshState.distanceFraction > 0f) {
            pullToRefreshState.distanceFraction.coerceIn(0f, 1.2f) * with(density) { 72.dp.toPx() }
        } else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "photosPullOffset"
    )

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = effectiveRefreshing,
            onRefresh = {
                triggerHapticVibration()
                isRefreshingTimedOutState.value = false
                onRefresh()
            },
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
        ) {
            // 仅在向下拉动时，在顶部露出的空白呼吸区域内展示“相册总张数与同步进度”
            val isPullingDown = pullToRefreshState.distanceFraction > 0.05f || effectiveRefreshing
            val pullAlpha = (pullToRefreshState.distanceFraction * 2.2f).coerceIn(0f, 1f)

            if (isPullingDown || effectiveRefreshing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .graphicsLayer { alpha = if (effectiveRefreshing) 1f else pullAlpha },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            text = "${allPhotos.size}张照片",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            letterSpacing = (-0.3).sp
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (syncStatus.isSyncing) {
                                val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
                                val rotation by infiniteTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "syncRotation"
                                )
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(13.dp).rotate(rotation)
                                )
                                Text(
                                    text = "正在同步${syncStatus.remainingCount}张照片",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            } else if (syncStatus.remainingCount > 0) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "${syncStatus.remainingCount}张待备份",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = AccentEmerald,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "已同步",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = pullOffsetPx }
                    .padding(horizontal = 16.dp)
                    .padding(top = if (isHeaderVisible) 46.dp else 12.dp)
            ) {
                // 顶栏：大标题 "照片" + 搜索 / 排序 / 切换按钮 (常态绝不堆积同步信息条，保持干脆纯粹)
                AnimatedVisibility(
                    visible = isHeaderVisible,
                    enter = expandVertically(animationSpec = tween(220, easing = EaseOutCubic)) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(animationSpec = tween(200, easing = EaseInCubic)) + fadeOut(animationSpec = tween(150))
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (activeFolder != null) {
                                    IconButton(
                                        onClick = { activeFolder = null },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "返回",
                                            tint = TextPrimary
                                        )
                                    }
                                }

                                Text(
                                    text = activeFolder?.name ?: "照片",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    letterSpacing = (-0.6).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 右侧操作图标组：搜索、排序/调节、相册切换
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        triggerHapticVibration()
                                        isSearchActive = !isSearchActive
                                        if (!isSearchActive) onSearchQueryChange("")
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                        contentDescription = "搜索",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Box {
                                    IconButton(
                                        onClick = {
                                            triggerHapticVibration()
                                            showSortMenu = true
                                        },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "排序与过滤",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false }
                                    ) {
                                        val options = listOf(
                                            PhotoSortOrder.DATE_DESC to "最新在前 (时间)",
                                            PhotoSortOrder.DATE_ASC  to "最早在前 (时间)",
                                            PhotoSortOrder.NAME_ASC  to "名称升序 (A → Z)",
                                            PhotoSortOrder.NAME_DESC to "名称降序 (Z → A)"
                                        )
                                        options.forEach { (order, label) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            text = if (sortOrder == order) "✓" else "  ",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = AccentBlue
                                                        )
                                                        Text(
                                                            text = label,
                                                            fontSize = 13.sp,
                                                            fontWeight = if (sortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (sortOrder == order) AccentBlue else TextPrimary
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    triggerHapticVibration()
                                                    onSortOrderChange(order)
                                                    showSortMenu = false
                                                }
                                            )
                                        }
                                    }
                                }

                                if (activeFolder == null) {
                                    IconButton(
                                        onClick = {
                                            triggerHapticVibration()
                                            tabMode = if (tabMode == PhotoTabMode.ALL_PHOTOS) PhotoTabMode.ALBUMS else PhotoTabMode.ALL_PHOTOS
                                        },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "相册视图切换",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 搜索输入框
                        AnimatedVisibility(
                            visible = isSearchActive,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFF1F5F9),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = onSearchQueryChange,
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
                                        cursorBrush = SolidColor(AccentBlue),
                                        decorationBox = { innerTextField ->
                                            if (searchQuery.isEmpty()) {
                                                Text("搜索照片名、日期...", fontSize = 13.sp, color = TextMuted)
                                            }
                                            innerTextField()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清空",
                                            tint = TextMuted,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { onSearchQueryChange("") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 内容瀑布流与相册网格
                when (photosState) {
                    is ResourceState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AccentBlue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        }
                    }
                    is ResourceState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            PearlCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                                ) {
                                    Text("⚠️", fontSize = 20.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("相册加载失败", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = onRefresh,
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                        shape = RoundedCornerShape(9999.dp)
                                    ) {
                                        Text("重试", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    is ResourceState.Success -> {
                        if (allPhotos.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                PearlCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                                    ) {
                                        Text("📷", fontSize = 24.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (searchQuery.isNotEmpty()) "未找到相关照片" else "暂无照片",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = onTriggerSync,
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                            shape = RoundedCornerShape(9999.dp)
                                        ) {
                                            Text("检测本地备份", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        } else if (activeFolder != null) {
                            // 子相册视图
                            val folderPhotos = activeFolder?.photos.orEmpty()
                            val gridSpacing = 3.dp

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                state = gridStateFolderOrAlbums(activeFolder != null, gridStateAlbums),
                                horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                                verticalArrangement = Arrangement.spacedBy(gridSpacing),
                                contentPadding = PaddingValues(bottom = 128.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(folderPhotos, key = { it.path }) { photo ->
                                    PhotoItemCard(
                                        photo = photo,
                                        thumbUrl = photoThumbUrlMap[photo.path] ?: com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(serverBaseUrl, photo.path),
                                        authHeader = authHeader,
                                        onClick = { onPhotoClick(photo) }
                                    )
                                }
                            }
                        } else if (tabMode == PhotoTabMode.ALBUMS) {
                            // 相册文件夹视图
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                state = gridStateAlbums,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 128.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(albumFolders, key = { it.name }) { album ->
                                    val coverUrl = photoThumbUrlMap[album.coverPhoto.path] ?: com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(serverBaseUrl, album.coverPhoto.path)

                                    PearlCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeFolder = album }
                                    ) {
                                        Column(modifier = Modifier.padding(4.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(120.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFFE2E8F0))
                                            ) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(coverUrl)
                                                        .addHeader("Authorization", authHeader)
                                                        .size(Size(320, 240))
                                                        .scale(Scale.FILL)
                                                        .crossfade(100)
                                                        .allowHardware(true)
                                                        .allowRgb565(true)
                                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                                        .diskCachePolicy(CachePolicy.ENABLED)
                                                        .networkCachePolicy(CachePolicy.ENABLED)
                                                        .build(),
                                                    contentDescription = album.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )

                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color.Black.copy(alpha = 0.65f),
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(5.dp)
                                                ) {
                                                    Text(
                                                        text = "${album.photos.size}",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Text(
                                                text = album.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // 仿截图现代时间轴布局 (紧凑网格 + 原生粗体大字标题: "今天 | Tale" / "昨天" / "10月1日")
                            val gridSpacing = 3.dp

                            if (isDateSorting) {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    state = gridStateAll,
                                    horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                                    verticalArrangement = Arrangement.spacedBy(gridSpacing),
                                    contentPadding = PaddingValues(bottom = 128.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pinchToZoomColumns(
                                            currentColumns = columns,
                                            onColumnsChange = { newCol ->
                                                columns = newCol
                                                onColumnsChanged(newCol)
                                            },
                                            minColumns = 2,
                                            maxColumns = 5
                                        )
                                ) {
                                    dateGroups.forEach { group ->
                                        // 类似截图的大字号纯净日期 Header
                                        item(
                                            span = { GridItemSpan(maxLineSpan) },
                                            key = "header_${group.title}_${group.photos.firstOrNull()?.path.orEmpty()}"
                                        ) {
                                            Text(
                                                text = group.title,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 18.dp, bottom = 8.dp, start = 2.dp)
                                            )
                                        }

                                        itemsIndexed(
                                            items = group.photos,
                                            key = { _, photo -> photo.path }
                                        ) { _, photo ->
                                            PhotoItemCard(
                                                photo = photo,
                                                thumbUrl = photoThumbUrlMap[photo.path] ?: com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(serverBaseUrl, photo.path),
                                                authHeader = authHeader,
                                                onClick = { onPhotoClick(photo) }
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 按名称排序：不展示时间 Header，纯紧凑等比网格
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    state = gridStateAll,
                                    horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                                    verticalArrangement = Arrangement.spacedBy(gridSpacing),
                                    contentPadding = PaddingValues(bottom = 128.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pinchToZoomColumns(
                                            currentColumns = columns,
                                            onColumnsChange = { newCol ->
                                                columns = newCol
                                                onColumnsChanged(newCol)
                                            },
                                            minColumns = 2,
                                            maxColumns = 5
                                        )
                                ) {
                                    itemsIndexed(allPhotos, key = { _, it -> it.path }) { _, photo ->
                                        PhotoItemCard(
                                            photo = photo,
                                            thumbUrl = photoThumbUrlMap[photo.path] ?: com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(serverBaseUrl, photo.path),
                                            authHeader = authHeader,
                                            onClick = { onPhotoClick(photo) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // 右侧中部偏下悬浮年份选择飞梭：
        // 1. 双重检测：用户已滑动到旧照片（>= 7天）+ 快速滑动
        // 2. 样式升级：半透明磨砂毛玻璃效果（非黑色），高光微边，优雅清透
        // 3. 位置下移：垂直居中偏下 (offset 80dp)
        AnimatedVisibility(
            visible = isDateSorting && distinctYears.size > 1 && hasScrolledToOlderPhotos && isFastScrolling && activeFolder == null && tabMode == PhotoTabMode.ALL_PHOTOS,
            enter = fadeIn(tween(180)) + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut(tween(300)) + slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = 80.dp)
                .padding(end = 8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.85f),
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0x66CBD5E1)),
                modifier = Modifier.wrapContentSize()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 7.dp)
                ) {
                    distinctYears.forEach { yr ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    triggerHapticVibration()
                                    var targetIdx = 0
                                    for (grp in dateGroups) {
                                        if (grp.year == yr) break
                                        targetIdx += 1 + grp.photos.size
                                    }
                                    coroutineScope.launch {
                                        gridStateAll.animateScrollToItem(targetIdx)
                                    }
                                }
                                .padding(vertical = 5.dp, horizontal = 5.dp)
                        ) {
                            Text(
                                text = yr.toString(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AccentBlue
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun gridStateFolderOrAlbums(isFolder: Boolean, fallback: androidx.compose.foundation.lazy.grid.LazyGridState): androidx.compose.foundation.lazy.grid.LazyGridState {
    return if (isFolder) androidx.compose.foundation.lazy.grid.rememberLazyGridState() else fallback
}

/**
 * 现代相册紧凑网格照片卡片 (极简设计，待备份带微型角标，已同步完全纯净)
 */
@Composable
private fun PhotoItemCard(
    photo: DavResource,
    thumbUrl: String,
    authHeader: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isPending = photo.isLocal && !photo.isSynced

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFFE2E8F0))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(thumbUrl)
                .apply {
                    if (!photo.isLocal && authHeader.isNotEmpty()) {
                        addHeader("Authorization", authHeader)
                    }
                }
                .size(Size(360, 360))
                .scale(Scale.FILL)
                .crossfade(100)
                .allowHardware(true)
                .allowRgb565(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = photo.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 仅对“本地待备份”的照片在右上角显示极其克制的微型半透明小图标
        if (isPending) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = "待备份",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
    }
}
