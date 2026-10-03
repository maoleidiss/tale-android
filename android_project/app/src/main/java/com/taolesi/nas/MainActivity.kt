package com.taolesi.nas

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.taolesi.nas.data.PreferencesManager
import com.taolesi.nas.data.SyncManager
import com.taolesi.nas.net.DavResource
import com.taolesi.nas.net.DavResult
import com.taolesi.nas.net.DavUrlNormalizer
import com.taolesi.nas.net.WebDavClient
import com.taolesi.nas.service.KeepAliveConnectionService
import com.taolesi.nas.ui.components.DockNavigationItem
import com.taolesi.nas.ui.components.PearlFloatingDock
import com.taolesi.nas.ui.components.PhotoViewerDialog
import com.taolesi.nas.ui.components.VideoPlayerDialog
import com.taolesi.nas.ui.screens.*
import com.taolesi.nas.ui.theme.AccentBlue
import com.taolesi.nas.ui.theme.NASTheme
import com.taolesi.nas.ui.theme.PearlCanvasBg
import com.taolesi.nas.ui.theme.TextPrimary
import com.taolesi.nas.ui.viewmodel.NasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var webDavClient: WebDavClient
    private lateinit var syncManager: SyncManager
    private lateinit var viewModel: NasViewModel

    // 全局硬件/虚拟导航返回拦截回调链（双保险机制，彻底杜绝底层返回键穿透导致直接退出 App）
    var onBackPressedInterceptor: (() -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_DOWN) {
            val consumed = onBackPressedInterceptor?.invoke() ?: false
            if (consumed) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // 前台自愈连通：当应用从后台切回前台，或冷启动就绪后，若尚未连通且配置了密码，立即无感在后台触发自愈握手
        if (::prefs.isInitialized && ::viewModel.isInitialized) {
            if (prefs.password.isNotEmpty() && !viewModel.dashboardState.value.isConnected) {
                viewModel.startColdBootConnectionWithRetry()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = PreferencesManager(this)
        webDavClient = WebDavClient(prefs.serverUrl, prefs.username, prefs.password)
        syncManager = SyncManager(this, prefs, webDavClient)
        viewModel = NasViewModel(this, prefs, webDavClient, syncManager)

        // 冷启动联动：若用户启用了后台专线常驻长连接，立即拉起保活通信服务
        if (prefs.keepAliveConnection && prefs.password.isNotEmpty()) {
            KeepAliveConnectionService.start(this)
        }

        // 消除启动并发争抢：生命周期与网络连通探测全权由 NasViewModel 初始化与两阶段自愈序列驱动

        setContent {
            NASTheme {
                MainAppHost(
                    viewModel = viewModel,
                    registerBackInterceptor = { interceptor ->
                        onBackPressedInterceptor = interceptor
                    },
                    onDirectOpen = { fileName, relativeDir ->
                        directOpenFile(fileName, relativeDir)
                    }
                )
            }
        }
    }

    private fun directOpenFile(fileName: String, relativeDir: String = "") {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "direct_open").apply { mkdirs() }
                val targetFile = File(tempDir, fileName)
                val tempFile = File(tempDir, "$fileName.tmp")

                val fullSubPath = if (relativeDir.isEmpty()) fileName else "${relativeDir.trim('/')}/$fileName"

                if (!targetFile.exists()) {
                    val response = webDavClient.downloadFileStream(fullSubPath)
                    if (!response.isSuccessful) {
                        throw IOException("服务端下载失败，HTTP状态码: ${response.code}")
                    }

                    try {
                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IOException("下载响应体为空")

                        if (tempFile.exists() && tempFile.length() > 0L) {
                            if (targetFile.exists()) {
                                targetFile.delete()
                            }
                            val renamed = tempFile.renameTo(targetFile)
                            if (!renamed) {
                                tempFile.copyTo(targetFile, overwrite = true)
                                tempFile.delete()
                            }
                        } else {
                            tempFile.delete()
                            throw IOException("下载文件大小为0")
                        }
                    } catch (e: Exception) {
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                        throw e
                    }
                }

                // LRU 缓存自动淘汰 (上限 100MB)
                val maxCacheBytes = 100 * 1024 * 1024L
                val cachedFiles = tempDir.listFiles()?.toList() ?: emptyList()
                var currentTotal = cachedFiles.sumOf { it.length() }
                if (currentTotal > maxCacheBytes) {
                    val sortedByLru = cachedFiles
                        .filter { it.name != fileName && !it.name.endsWith(".tmp") }
                        .sortedBy { it.lastModified() }
                    for (file in sortedByLru) {
                        if (currentTotal <= maxCacheBytes) break
                        val size = file.length()
                        if (file.delete()) {
                            currentTotal -= size
                        }
                    }
                }

                targetFile.setLastModified(System.currentTimeMillis())

                if (targetFile.exists()) {
                    val contentUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        targetFile
                    )

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        val ext = fileName.substringAfterLast('.', "").lowercase()
                        val mimeType = when (ext) {
                            "pdf" -> "application/pdf"
                            "doc", "docx" -> "application/msword"
                            "xls", "xlsx" -> "application/vnd.ms-excel"
                            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
                            "txt", "md", "json", "xml", "log" -> "text/plain"
                            else -> "*/*"
                        }
                        setDataAndType(contentUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Tale · 打开文件"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Tale: 打开文件失败 - ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
fun MainAppHost(
    viewModel: NasViewModel,
    registerBackInterceptor: ((() -> Boolean)?) -> Unit = {},
    onDirectOpen: (String, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = viewModel.prefs
    val webDavClient = viewModel.webDavClient
    val syncManager = viewModel.syncManager

    // 动态相册/媒体存储权限请求器 (针对 Android 13+ READ_MEDIA_IMAGES/VIDEO 及 Android 12- READ_EXTERNAL_STORAGE)
    val mediaPermissions = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val granted = permissionsMap.values.any { it }
        if (granted) {
            android.widget.Toast.makeText(context, "已取得相册存储权限，开始增量备份", android.widget.Toast.LENGTH_SHORT).show()
            viewModel.triggerManualSync()
        } else {
            android.widget.Toast.makeText(context, "未授予相册读取权限，无法扫描本地照片进行备份", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val triggerSyncWithPermissionCheck: () -> Unit = {
        val hasPermission = mediaPermissions.any { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (hasPermission) {
            viewModel.triggerManualSync()
        } else {
            permissionLauncher.launch(mediaPermissions)
        }
    }

    var currentTab by remember { mutableIntStateOf(prefs.defaultStartTab) }
    val dashboardState by viewModel.dashboardState.collectAsState()
    val photosState by viewModel.photosState.collectAsState()
    val isPhotosRefreshing by viewModel.isPhotosRefreshing.collectAsState()
    val photoSortOrder by viewModel.photoSortOrder.collectAsState()
    val photoSearchQuery by viewModel.photoSearchQuery.collectAsState()
    val videosState by viewModel.videosState.collectAsState()
    val isVideosRefreshing by viewModel.isVideosRefreshing.collectAsState()
    val filesState by viewModel.filesState.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val previewData by viewModel.previewContent.collectAsState()
    val syncStatus by syncManager.status.collectAsState()

    var showConnectionDialog by remember { mutableStateOf(false) }

    // 全屏查看状态管理
    var activeVideo by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activePhotoViewer by remember { mutableStateOf<Pair<List<DavResource>, Int>?>(null) }
    // 相册当前浏览的具体相册（用于返回键层级拦截）
    var activeAlbumFolder by remember { mutableStateOf<com.taolesi.nas.ui.screens.AlbumFolder?>(null) }

    val authHeader = remember(prefs.username, prefs.password) {
        val creds = "${prefs.username}:${prefs.password}"
        "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
    }

    val dockItems = remember {
        listOf(
            DockNavigationItem("看板", Icons.Default.Home),
            DockNavigationItem("相册", Icons.Default.FavoriteBorder),
            DockNavigationItem("影视", Icons.Default.PlayArrow),
            DockNavigationItem("文件", Icons.Default.MailOutline),
            DockNavigationItem("设置", Icons.Default.Settings)
        )
    }

    // 全层级 BackHandler 拦截（优先级从高到低）
    // 优先级1: 全屏大图查看器
    BackHandler(enabled = activePhotoViewer != null) {
        activePhotoViewer = null
    }
    // 优先级2: 视频播放器
    BackHandler(enabled = activeVideo != null) {
        activeVideo = null
    }
    // 优先级3: 文本/Markdown就地文件预览弹窗
    BackHandler(enabled = previewData != null) {
        viewModel.dismissPreview()
    }
    // 优先级4: 相册页浏览具体相册（双重保险，避免误退应用）
    BackHandler(enabled = currentTab == 1 && activeAlbumFolder != null) {
        activeAlbumFolder = null
    }
    // 优先级5: 文件中心子目录（返回上一级）
    BackHandler(enabled = currentTab == 3 && currentPath.isNotEmpty()) {
        viewModel.navigateUp()
    }
    // 优先级6: 非首页看板 → 返回首页（排除：大图/视频/预览/子相册/文件子目录）
    BackHandler(enabled = currentTab != 0 && activePhotoViewer == null && activeVideo == null && previewData == null && activeAlbumFolder == null && (currentTab != 3 || currentPath.isEmpty())) {
        currentTab = 0
    }
    // 优先级7: 首页看板根页面 → 系统默认退出行为（放通给系统）

    // 联动注册底层物理返回键拦截器，双保险防御原生系统按键吞没/穿透
    DisposableEffect(currentTab, activePhotoViewer, activeVideo, previewData, activeAlbumFolder, currentPath) {
        registerBackInterceptor {
            when {
                activePhotoViewer != null -> {
                    activePhotoViewer = null
                    true
                }
                activeVideo != null -> {
                    activeVideo = null
                    true
                }
                previewData != null -> {
                    viewModel.dismissPreview()
                    true
                }
                currentTab == 1 && activeAlbumFolder != null -> {
                    activeAlbumFolder = null
                    true
                }
                currentTab == 3 && currentPath.isNotEmpty() -> {
                    viewModel.navigateUp()
                    true
                }
                currentTab != 0 -> {
                    currentTab = 0
                    true
                }
                else -> false
            }
        }
        onDispose {
            registerBackInterceptor(null)
        }
    }

    // 页面背景与环境漫反射
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PearlCanvasBg)
    ) {
        // 5 大页面带 Q 弹丝滑淡入切页动效
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220)))
                    .togetherWith(fadeOut(animationSpec = tween(180)))
            },
            label = "tab_switch"
        ) { tabIndex ->
            when (tabIndex) {
                0 -> DashboardScreen(
                    state = dashboardState,
                    onRefresh = { viewModel.refreshDashboard() },
                    onNavigateTab = { currentTab = it },
                    speedSourceMode = prefs.speedSourceMode
                )
                1 -> PhotosScreen(
                    photosState = photosState,
                    syncStatus = syncStatus,
                    serverBaseUrl = prefs.serverUrl,
                    authHeader = authHeader,
                    isRefreshing = isPhotosRefreshing,
                    initialColumns = prefs.albumGridColumns,
                    sortOrder = photoSortOrder,
                    albumDisplayStrategy = prefs.albumDisplayStrategy,
                    searchQuery = photoSearchQuery,
                    onSearchQueryChange = { viewModel.setPhotoSearchQuery(it) },
                    onColumnsChanged = { prefs.albumGridColumns = it },
                    onRefresh = { viewModel.refreshPhotos(forceLoadingState = true, forceRefresh = true) },
                    onTriggerSync = triggerSyncWithPermissionCheck,
                    onSortOrderChange = { viewModel.setPhotoSortOrder(it) },
                    activeFolderOverride = activeAlbumFolder,
                    onFolderEntered = { folder -> activeAlbumFolder = folder },
                    onPhotoClick = { photo ->
                        val list = (photosState as? com.taolesi.nas.ui.viewmodel.ResourceState.Success)?.data ?: listOf(photo)
                        val index = list.indexOfFirst { it.path == photo.path }.coerceAtLeast(0)
                        activePhotoViewer = list to index
                    }
                )
                2 -> VideoScreen(
                    videosState = videosState,
                    serverBaseUrl = prefs.serverUrl,
                    authHeader = authHeader,
                    isRefreshing = isVideosRefreshing,
                    onRefresh = { viewModel.refreshVideos(forceLoadingState = true, forceRefresh = true) },
                    onPlayMovie = { videoResource ->
                        val streamUrl = if (videoResource.path.startsWith("http")) {
                            videoResource.path
                        } else {
                            "${prefs.serverUrl.trimEnd('/')}${videoResource.path}"
                        }
                        activeVideo = streamUrl to videoResource.name
                    }
                )
                3 -> FilesScreen(
                    currentPath = currentPath,
                    filesState = filesState,
                    previewData = previewData,
                    serverBaseUrl = prefs.serverUrl,
                    authHeader = authHeader,
                    onFolderClick = { folderName -> viewModel.navigateToFolder(folderName) },
                    onNavigateToPath = { path -> viewModel.navigateToPath(path) },
                    onNavigateUp = { viewModel.navigateUp() },
                    onRefresh = { viewModel.refreshFiles() },
                    onPreviewFile = { fileName -> viewModel.loadFileContentForPreview(fileName, currentPath) },
                    onPhotoClick = { photo ->
                        val list = (filesState as? com.taolesi.nas.ui.viewmodel.ResourceState.Success)?.data?.filter { viewModel.isImageResource(it) } ?: listOf(photo)
                        val index = list.indexOfFirst { it.path == photo.path }.coerceAtLeast(0)
                        activePhotoViewer = list to index
                    },
                    onDismissPreview = { viewModel.dismissPreview() },
                    onDirectOpenWps = { fileName -> onDirectOpen(fileName, currentPath) },
                    onSaveFile = { fileName, content, cb -> viewModel.saveFileContent(fileName, content, cb) },
                    onCreateFolder = { folderName, cb -> viewModel.createFolder(folderName, cb) },
                    onCreateFile = { fileName, initialBytes, cb -> viewModel.createNewFile(fileName, initialBytes, cb) },
                    onDeleteItem = { item, cb -> viewModel.deleteItem(item, cb) },
                    onRenameItem = { item, newName, cb -> viewModel.renameItem(item, newName, cb) },
                    onUploadFile = { uri, name, cb -> viewModel.uploadLocalFile(uri, name, cb) },
                    onCreateSyncFolder = { folderName, localUri, localName, mode, cb ->
                        viewModel.createFolder(folderName) { ok, msg ->
                            if (ok) {
                                val targetRemote = if (currentPath.isEmpty()) folderName else "${currentPath.trim('/')}/$folderName"
                                val binding = com.taolesi.nas.data.SyncFolderBinding(
                                    remotePath = targetRemote,
                                    localUriString = localUri,
                                    localDisplayName = localName,
                                    syncMode = mode
                                )
                                viewModel.syncManager.addOrUpdateSyncFolderBinding(binding)
                                cb(true, "同步文件夹创建并绑定成功")
                                viewModel.triggerSyncCustomFolder(binding)
                            } else {
                                cb(false, msg)
                            }
                        }
                    },
                    onSyncCustomFolder = { binding ->
                        viewModel.triggerSyncCustomFolder(binding)
                    },
                    syncFolderBindings = viewModel.syncManager.getSyncFolderBindings()
                )
                4 -> SettingsScreen(
                    prefs = prefs,
                    dashboardState = dashboardState,
                    onOpenConnectionDialog = { showConnectionDialog = true },
                    onTriggerSync = triggerSyncWithPermissionCheck,
                    onTriggerTwoWaySync = { viewModel.triggerTwoWaySync(force = true) },
                    onClearCache = {
                        prefs.clearCacheMarkers()
                        viewModel.refreshAll()
                    }
                )
            }
        }

        // 底部悬浮独立胶囊 Dock (Q 弹 Spring 动效)
        PearlFloatingDock(
            items = dockItems,
            selectedIndex = currentTab,
            onItemSelected = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 20.dp)
        )

        // 独立连接配置弹窗 (自用仅填 网址、账号、密码)
        if (showConnectionDialog) {
            ConnectionConfigDialog(
                prefs = prefs,
                webDavClient = webDavClient,
                onDismiss = { showConnectionDialog = false },
                onSave = { url, user, pass ->
                    prefs.activeDavEndpoint = "" // 重置端点记忆，重新触发自适应优选探测
                    webDavClient.updateConfig(url, user, pass)
                    prefs.serverUrl = url
                    prefs.username = user
                    prefs.password = pass
                    if (prefs.keepAliveConnection) {
                        KeepAliveConnectionService.updateState(context)
                    }
                    showConnectionDialog = false
                    viewModel.refreshAll()
                }
            )
        }

        // 4K 影视全屏流播 (流式切片播放)
        activeVideo?.let { (url, title) ->
            VideoPlayerDialog(
                videoUrl = url,
                videoTitle = title,
                authHeader = authHeader,
                onDismiss = { activeVideo = null }
            )
        }

        // 全能多图/单图手势全屏查看器 (集成多端同步删除)
        activePhotoViewer?.let { (photoList, initialIndex) ->
            val resolvedList = photoList.map { item ->
                val fullUrl = com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(prefs.serverUrl, item.path)
                item.copy(path = fullUrl)
            }
            PhotoViewerDialog(
                photos = resolvedList,
                initialIndex = initialIndex,
                authHeader = authHeader,
                onDismiss = { activePhotoViewer = null },
                onDelete = { photoToDelete ->
                    viewModel.deletePhoto(photoToDelete) { ok, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@Composable
fun ConnectionConfigDialog(
    prefs: PreferencesManager,
    webDavClient: WebDavClient,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var username by remember { mutableStateOf(prefs.username) }
    var password by remember { mutableStateOf(prefs.password) }
    var passwordVisible by remember { mutableStateOf(false) }

    var isTesting by remember { mutableStateOf(false) }
    var testSuccessMessage by remember { mutableStateOf<String?>(null) }
    var testErrorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Tale 服务器连接", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        testErrorMessage = null
                        testSuccessMessage = null
                    },
                    label = { Text("服务器网址") },
                    placeholder = { Text("https://nas.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        testErrorMessage = null
                        testSuccessMessage = null
                    },
                    label = { Text("用户账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        testErrorMessage = null
                        testSuccessMessage = null
                    },
                    label = { Text("访问密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 独立就地测试连通性按钮
                OutlinedButton(
                    onClick = {
                        val cleanedUrl = DavUrlNormalizer.cleanBaseUrl(url)
                        val cleanUser = username.trim()
                        val cleanPass = password

                        if (cleanedUrl.isBlank()) {
                            testErrorMessage = "请先填写服务器网址"
                            testSuccessMessage = null
                            return@OutlinedButton
                        }

                        coroutineScope.launch {
                            isTesting = true
                            testSuccessMessage = null
                            testErrorMessage = null

                            // 使用独立临时客户端探活，使用 probeConnectionFast 进行自适应竞速探测，不污染全局配置
                            val testClient = WebDavClient(cleanedUrl, cleanUser, cleanPass)
                            when (val result = testClient.probeConnectionFast()) {
                                is DavResult.Success -> {
                                    val endpoint = testClient.activeDavEndpoint.substringAfter("://").substringAfter('/', "")
                                    testSuccessMessage = "✓ 专线连接成功！延迟: ${result.data}ms (端点: /$endpoint)"
                                    testErrorMessage = null
                                }
                                is DavResult.Failure -> {
                                    testErrorMessage = "✗ " + result.toUserFriendlyMessage() + " (不影响直接保存)"
                                    testSuccessMessage = null
                                }
                            }
                            isTesting = false
                        }
                    },
                    enabled = !isTesting,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    if (isTesting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = AccentBlue,
                                strokeWidth = 2.dp
                            )
                            Text("正在探测网络连通性...", fontSize = 12.sp, color = AccentBlue)
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentBlue)
                            Text("测试当前连通性", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                        }
                    }
                }

                // 原地异步探活状态指示 (绿字成功延迟 / 橙红字失败原因)
                if (testSuccessMessage != null) {
                    Text(
                        text = testSuccessMessage!!,
                        color = Color(0xFF2E7D32),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }

                if (testErrorMessage != null) {
                    Text(
                        text = testErrorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanedUrl = DavUrlNormalizer.cleanBaseUrl(url)
                    val cleanUser = username.trim()
                    val cleanPass = password

                    if (cleanedUrl.isBlank()) {
                        testErrorMessage = "服务器网址不能为空"
                        testSuccessMessage = null
                        return@Button
                    }

                    onSave(cleanedUrl, cleanUser, cleanPass)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text("直接保存", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White
    )
}
