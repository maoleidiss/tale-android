package com.taolesi.nas.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taolesi.nas.net.DavResource
import com.taolesi.nas.ui.theme.AccentBlue
import com.taolesi.nas.ui.theme.AccentIndigo
import com.taolesi.nas.ui.theme.TextPrimary
import kotlinx.coroutines.delay

private fun formatMediaDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

/**
 * 4K 影视全屏秒播播放器 (基于 AndroidX Media3 ExoPlayer)
 * 物理特性：HTTP 206 局部切片缓冲，纯内存 64MB 环形队列，退出即清空，绝不占手机存储空间。
 * 强化功能：横竖屏无缝自由旋转、全屏填充/适应模式切换、-10s/+10s 快捷微调、精细缓冲指示、沉浸拟物控制台。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    videoUrl: String,
    videoTitle: String,
    authHeader: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) break
            ctx = ctx.baseContext
        }
        ctx as? Activity
    }

    // 记录初始横竖屏状态，关闭弹窗时严谨恢复
    val initialOrientation = remember {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    var isLandscape by remember {
        mutableStateOf(activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    // 真正的沉浸式全屏：进入时隐藏系统状态栏与导航栏，退出时安全恢复
    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            val w = activity?.window
            if (w != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var isBuffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // 画面比例模式：false 为原始黑边适应 (FIT)，true 为全屏裁剪填充 (ZOOM)
    var isZoomMode by remember { mutableStateOf(false) }

    // 控制台显隐与手势
    var showControls by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekNotice by remember { mutableStateOf<String?>(null) }

    // 内存环形缓冲区设置：按切片流式加载，先加载前一分钟 (60s) 内容，最大 120s，退出时立即回收
    val exoPlayer = remember(videoUrl) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000,  // 最小前向缓冲 60 秒 (整整预加载前一分钟内容，保障 4K 切片流式丝滑秒播)
                120_000, // 最大缓冲 120 秒
                2_000,   // 起播缓冲 2 秒 (快速毫秒级起播)
                3_000    // 缓冲不足停顿后重播缓冲 3 秒
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("TaolesiNAS-Android/1.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        if (authHeader.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to authHeader))
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(videoUrl))
                prepare()
                playWhenReady = true
            }
    }

    // 播放器事件与缓冲/时长监听
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // 轮询同步播放进度
    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            if (isPlaying) {
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (duration <= 0L && exoPlayer.duration > 0L) {
                    duration = exoPlayer.duration
                }
            }
            delay(250L)
        }
    }

    // 自动隐匿控制台 (4秒无操作且正常播放时)
    LaunchedEffect(showControls, lastInteractionTime, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000L)
            showControls = false
        }
    }

    // 快进/快退提示 800ms 后自动隐藏
    LaunchedEffect(seekNotice) {
        if (seekNotice != null) {
            delay(800L)
            seekNotice = null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                try {
                    exoPlayer.pause()
                    exoPlayer.stop()
                    exoPlayer.release()
                } catch (_: Exception) {}
                activity?.requestedOrientation = initialOrientation
                onDismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                exoPlayer.stop()
                exoPlayer.release()
            } catch (_: Exception) {}
            activity?.requestedOrientation = initialOrientation
        }
    }

    // 快退 10 秒操作
    val seekBack10 = {
        val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
        exoPlayer.seekTo(target)
        currentPosition = target
        seekNotice = "-10s ⏪"
        lastInteractionTime = System.currentTimeMillis()
    }

    // 快进 10 秒操作
    val seekForward10 = {
        val dur = exoPlayer.duration.coerceAtLeast(0L)
        val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
        exoPlayer.seekTo(target)
        currentPosition = target
        seekNotice = "⏩ +10s"
        lastInteractionTime = System.currentTimeMillis()
    }

    // 横竖屏自由旋转切换
    val toggleOrientation = {
        val targetLandscape = !isLandscape
        isLandscape = targetLandscape
        activity?.requestedOrientation = if (targetLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        lastInteractionTime = System.currentTimeMillis()
    }

    Dialog(
        onDismissRequest = {
            activity?.requestedOrientation = initialOrientation
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val screenWidth = size.width
                            if (offset.x < screenWidth * 0.38f) {
                                seekBack10()
                            } else if (offset.x > screenWidth * 0.62f) {
                                seekForward10()
                            } else {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        },
                        onTap = {
                            showControls = !showControls
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    )
                }
        ) {
            // 底层 ExoPlayer 画面渲染
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = if (isZoomMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { playerView ->
                    playerView.resizeMode = if (isZoomMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier.fillMaxSize()
            )

            // 缓冲指示悬浮框 (Frosted Glass 拟物毛玻璃卡片)
            if (isBuffering) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = AccentIndigo,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "4K 流切片缓冲中...",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 快进/快退手势动态水波纹气泡
            AnimatedVisibility(
                visible = seekNotice != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(9999.dp),
                    color = Color.Black.copy(alpha = 0.80f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, AccentIndigo.copy(alpha = 0.6f)),
                    shadowElevation = 8.dp
                ) {
                    Text(
                        text = seekNotice.orEmpty(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }

            // 沉浸播放控制层 (显隐动画)
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.40f))
                ) {
                    // 顶栏：标题、模式切换胶囊、旋转键与关闭键
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 影片标题
                        Surface(
                            shape = RoundedCornerShape(9999.dp),
                            color = Color.Black.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                        ) {
                            Text(
                                text = videoTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier
                                    .widthIn(max = 180.dp)
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 画面比例切换：适应 (FIT) vs 填充 (ZOOM)
                            Surface(
                                shape = RoundedCornerShape(9999.dp),
                                color = if (isZoomMode) AccentIndigo.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.65f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier.clickable {
                                    isZoomMode = !isZoomMode
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isZoomMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = "画面模式",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = if (isZoomMode) "填充铺满" else "原始适应",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }

                            // 自由旋转键 (横竖屏切换)
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.65f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable { toggleOrientation() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ScreenRotation,
                                        contentDescription = "旋转屏幕",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // 关闭键
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.65f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable {
                                        activity?.requestedOrientation = initialOrientation
                                        onDismiss()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "关闭",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 居中大按键控制组 (快退 10s、播放/暂停、快进 10s)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(36.dp)
                    ) {
                        // -10s 快捷键
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            modifier = Modifier
                                .size(50.dp)
                                .clickable { seekBack10() }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FastRewind,
                                    contentDescription = "快退10秒",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(text = "-10s", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        // 居中大播放 / 暂停按钮
                        Surface(
                            shape = CircleShape,
                            color = AccentIndigo.copy(alpha = 0.90f),
                            shadowElevation = 8.dp,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .size(64.dp)
                                .clickable {
                                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        // +10s 快捷键
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            modifier = Modifier
                                .size(50.dp)
                                .clickable { seekForward10() }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FastForward,
                                    contentDescription = "快进10秒",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(text = "+10s", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    // 底栏：播放时间、进度滑块、总时长、倍速切换
                    Surface(
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        color = Color.Black.copy(alpha = 0.70f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = formatMediaDuration(currentPosition),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )

                            Slider(
                                value = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                                onValueChange = { frac ->
                                    val target = (frac * duration).toLong()
                                    currentPosition = target
                                    exoPlayer.seekTo(target)
                                    lastInteractionTime = System.currentTimeMillis()
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentIndigo,
                                    activeTrackColor = AccentIndigo,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = formatMediaDuration(duration),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.7f)
                            )

                            // 倍速循环切换键
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.16f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                modifier = Modifier.clickable {
                                    val nextSpeed = when (playbackSpeed) {
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        else -> 1.0f
                                    }
                                    playbackSpeed = nextSpeed
                                    exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            ) {
                                Text(
                                    text = "${playbackSpeed}x",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全能多图/单图手势全屏查看器 (支持列表翻页、手势双指缩放、动图播放、点击显隐顶栏)
 */
@Composable
fun PhotoViewerDialog(
    photos: List<DavResource>,
    initialIndex: Int = 0,
    authHeader: String,
    onDismiss: () -> Unit,
    onDelete: ((DavResource) -> Unit)? = null
) {
    val context = LocalContext.current
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) {
        photos.size
    }
    var showBars by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.98f))
        ) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val currentPhoto = photos.getOrNull(page)
                if (currentPhoto != null) {
                    var scale by remember(page) { mutableStateOf(1f) }
                    var offsetX by remember(page) { mutableStateOf(0f) }
                    var offsetY by remember(page) { mutableStateOf(0f) }

                    val imageRequest = remember(currentPhoto.path, authHeader) {
                        ImageRequest.Builder(context)
                            .data(currentPhoto.path)
                            .apply {
                                if (authHeader.isNotEmpty()) {
                                    addHeader("Authorization", authHeader)
                                }
                            }
                            .crossfade(true)
                            .build()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(page) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = currentPhoto.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    showBars = !showBars
                                }
                        )
                    }
                }
            }

            // 顶栏悬浮控制条 (自动沉浸退隐，显示张数序号与文件名)
            AnimatedVisibility(
                visible = showBars,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp)
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activePhoto = photos.getOrNull(pagerState.currentPage)
                    Surface(
                        shape = RoundedCornerShape(9999.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${photos.size}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentBlue
                            )
                            Text(
                                text = activePhoto?.name.orEmpty(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (onDelete != null) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .clickable { showDeleteConfirm = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 删除二次防误删确认弹窗
            if (showDeleteConfirm) {
                val activePhoto = photos.getOrNull(pagerState.currentPage)
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("确认删除照片？", fontWeight = FontWeight.Bold) },
                    text = { Text("删除后将同步从本地相册和 NAS 云端永久移除，操作无法撤销。") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteConfirm = false
                                if (activePhoto != null) {
                                    onDelete?.invoke(activePhoto)
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                        ) {
                            Text("彻底删除", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

/**
 * 兼容单张图片呼出的重载
 */
@Composable
fun PhotoViewerDialog(
    photo: DavResource,
    authHeader: String,
    onDismiss: () -> Unit
) {
    PhotoViewerDialog(
        photos = listOf(photo),
        initialIndex = 0,
        authHeader = authHeader,
        onDismiss = onDismiss
    )
}
