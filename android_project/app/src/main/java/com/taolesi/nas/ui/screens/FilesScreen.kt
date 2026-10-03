package com.taolesi.nas.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taolesi.nas.net.DavResource
import com.taolesi.nas.ui.components.PearlCard
import com.taolesi.nas.ui.theme.*
import com.taolesi.nas.ui.viewmodel.ResourceState
import com.taolesi.nas.ui.utils.pinchToZoomColumns
import android.util.Base64
import java.util.Locale

// 空 DOCX 与 XLSX 最小合法模板 Base64
private const val EMPTY_DOCX_B64 = "UEsDBBQAAAAIAI2rQV0XmADX7wAAALIBAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbH2Qy07DMBBF9/0Ka7YodmCBEKrTBY8lsCgfYNmTxMIeWx43pH+P0kIWqLC+j3Nntrs5BjFhYZ9Iw7VsQSDZ5DwNGt73z80dCK6GnAmJUMMRGXbdZrs/ZmQxx0CsYaw13yvFdsRoWKaMNMfQpxJNZZnKoLKxH2ZAddO2t8omqki1qUsHdJvtI/bmEKp4mivSeUnBwCAezs4FpsHkHLw11SdSE7lfmOYbIQuGk4dHn/lqjgHUZcQi/U34Cb5OWIp3KN5MqS8mogb1mYpTLtlDRKry/54LS1Pfe4trfmnLJVlk9jTEIFclGk/rBer08u4LUEsDBBQAAAAIAI2rQV0/rf76swAAACwBAAALAAAAX3JlbHMvLnJlbHONz7GOwjAQBNCer7C2vzhQIITi0CAk2lP4AMveJBb2ruX1HeHvaSgAXXHtaPRG0x2WFNUvFglMBtZNCwrJsQ80GbgMp68dKKmWvI1MaOCOAod+1X1jtDUwyRyyqCVFEgNzrXmvtbgZk5WGM9KS4sgl2SoNl0ln6652Qr1p260urwZ8oOrsDZSzX4Ma7hn/g/M4BodHdj8Jqf6x8dEANdgyYTVw4+K1f8bNkiLoftXpt5P9A1BLAwQUAAAACACNq0FdAl/GwpoAAADMAAAAEQAAAHdvcmQvZG9jdW1lbnQueG1sNc5BDsIgFATQvacg7C3VhTGEtjtPoAdAwJYE/id8lPb2piTdvMxiMhk1rTGwn8vkEQZ+6XrOHBi0HuaBv56P850zKhqsDghu4JsjPo0nVaVF840OCltjAJJ14EspSQpBZnFRU4fJwRrDB3PUhTrMs6iYbcpoHJGHOQZx7fubiNoDb5tvtNuoqkw7eaeMShzmZmq25mlPx4/xD1BLAQIUABQAAAAIAI2rQV0XmADX7wAAALIBAAATAAAAAAAAAAAAAACAAQAAAABbQ29udGVudF9UeXBlc10ueG1sUEsBAhQAFAAAAAgAjatBXT+t/vqzAAAALAEAAAsAAAAAAAAAAAAAAIABIAEAAF9yZWxzLy5yZWxzUEsBAhQAFAAAAAgAjatBXQJfxsKaAAAAzAAAABEAAAAAAAAAAAAAAIAB/AEAAHdvcmQvZG9jdW1lbnQueG1sUEsFBgAAAAADAAMAuQAAAMUCAAAAAA=="
private const val EMPTY_XLSX_B64 = "UEsDBBQAAAAIAI2rQV2wXVXTAgEAADMCAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbK2RPU8DMQyG9/6KKCtq0jIghHrtwMcIDOUHmJyvFzWxo9gt13+PeoUOqDAxefDr93kkL1ZDTmaPVSJTY+duZg1S4DbSprFv66fprTWiQC0kJmzsAcWulpPF+lBQzJATSWN71XLnvYQeM4jjgjTk1HHNoOK4bnyBsIUN+uvZ7MYHJkXSqR477HKyeMAOdknN46BIJ5OKSay5PyWPsMZCKSkG0Mjk99T+wEy/EK5iGjPSxyJXQ07WX0YcV78Tvg9f9lhrbNG8QtVnyNhYPyT/wXX7zrx1f7dc8OSuiwFbDruMpE5KRWilR9Sc3Dhdhkhn8z8ExrT4ccz/2eTcfxbx48+Xn1BLAwQUAAAACACNq0Fdfm/AhbQAAAAqAQAACwAAAF9yZWxzLy5yZWxzjc+xbsIwEMbxnaewbicODKiq4rAgJFYUHsA4l8SKfWf5TGvevmuLGLp/+n36d8cag/rCLJ7JwK5pQSE5Hj3NBm7DefsBSoql0QYmNPBEgWO/6a4YbPFMsvgkqsZAYmApJX1qLW7BaKXhhFRjmDhHW6ThPOtk3Wpn1Pu2Pej824AXVF1GA/ky7kANz4T/wXmavMMTu0dEKm8+XhagBptnLAZq0N+c1zvz2tQYQPebTv9J7H8AUEsDBBQAAAAIAI2rQV1vJc8guQAAACsBAAAaAAAAeGwvX3JlbHMvd29ya2Jvb2sueG1sLnJlbHONzzFrwzAQBeA9v0LcXp/dIZRiOUspZC3ODxDy2RaR7oRObZ1/H8hQktKh04M3fI/XH7YUzRcVDcIWuqYFQ+xlCrxYOI3vTy9gtDqeXBQmCxdSOAy7/oOiq0FY15DVbCmyWlhrza+I6ldKThvJxFuKs5TkqjZSFszOn91C+Ny2eyz3BvxCzXGyUI5TB2a8ZPoPLvMcPL2J/0zE9Y8N/JZy1pWoghldWaha+KkUb9E1W4qAw67Hh4/DFVBLAwQUAAAACACNq0FddPlqlsEAAAAeAQAADwAAAHhsL3dvcmtib29rLnhtbI2PwWrDMBBE7/4KsfdYdg+hGEm5hELObT5AtdaxiLRrtErr/H1o3N57mmFg3jDmsOakvrBIZLLQtx0opJFDpIuF88fb7hWUVE/BJya0cEeBg2vMN5frJ/NVrTmRWJhrXQatZZwxe2l5QVpzmrhkX6XlctGyFPRBZsSak37pur3OPhJshKH8h8HTFEc88njLSHWDFEy+RiaZ4yLgGvOcELepIp/RwvuP70E9s1Ow0IMqQwwWyin0oJ3Rv7XG6L9v7gFQSwMEFAAAAAgAjatBXQAiKm6GAAAAnwAAABgAAAB4bC93b3Jrc2hlZXRzL3NoZWV0MS54bWw9zDEOwjAMQNG9p4i80xQGhFCSLogTwAGs1tCK2K7iCMrtkTqwfun90K+c3ZuKzSoR9m0HjmTQcZZnhPvtujuBs4oyYlahCF8y6FMTPlpeNhFVt3IWizDVupy9t2EiRmt1IVk5P7QwVmu1PL0thXDcEGd/6LqjZ5wFUhO2eMGKPjXB/9/pB1BLAQIUABQAAAAIAI2rQV2wXVXTAgEAADMCAAATAAAAAAAAAAAAAACAAQAAAABbQ29udGVudF9UeXBlc10ueG1sUEsBAhQAFAAAAAgAjatBXX5vwIW0AAAAKgEAAAsAAAAAAAAAAAAAAIABMwEAAF9yZWxzLy5yZWxzUEsBAhQAFAAAAAgAjatBXW8lzyC5AAAAKwEAABoAAAAAAAAAAAAAAIABEAIAAHhsL19yZWxzL3dvcmtib29rLnhtbC5yZWxzUEsBAhQAFAAAAAgAjatBXXT5apbBAAAAHgEAAA8AAAAAAAAAAAAAAIABAQMAAHhsL3dvcmtib29rLnhtbFBLAQIUABQAAAAIAI2rQV0AIipuhgAAAJ8AAAAYAAAAAAAAAAAAAACAAe8DAAB4bC93b3Jrc2hlZXRzL3NoZWV0MS54bWxQSwUGAAAAAAUABQBFAQAAqwQAAAAA"

enum class NewItemType {
    FOLDER, SYNC_FOLDER, TXT, MD, JSON, DOCX, XLSX
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    currentPath: String,
    filesState: ResourceState<List<DavResource>>,
    previewData: Pair<String, String>?,
    serverBaseUrl: String = "",
    authHeader: String = "",
    onFolderClick: (String) -> Unit,
    onNavigateToPath: (String) -> Unit = {},
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onPreviewFile: (String) -> Unit,
    onPhotoClick: (DavResource) -> Unit = {},
    onDismissPreview: () -> Unit,
    onDirectOpenWps: (String) -> Unit,
    onSaveFile: ((String, String, (Boolean, String) -> Unit) -> Unit)? = null,
    onCreateFolder: ((String, (Boolean, String) -> Unit) -> Unit)? = null,
    onCreateFile: ((String, ByteArray, (Boolean, String) -> Unit) -> Unit)? = null,
    onDeleteItem: ((DavResource, (Boolean, String) -> Unit) -> Unit)? = null,
    onRenameItem: ((DavResource, String, (Boolean, String) -> Unit) -> Unit)? = null,
    onUploadFile: ((android.net.Uri, String, (Boolean, String) -> Unit) -> Unit)? = null,
    onCreateSyncFolder: ((String, String, String, com.taolesi.nas.data.FolderSyncMode, (Boolean, String) -> Unit) -> Unit)? = null,
    onSyncCustomFolder: ((com.taolesi.nas.data.SyncFolderBinding) -> Unit)? = null,
    syncFolderBindings: List<com.taolesi.nas.data.SyncFolderBinding> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var columns by remember { mutableIntStateOf(1) } // 1: 单列详细大列表, 2~5: 网格平铺缩放
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshingTimedOut by remember { mutableStateOf(false) }

    val isLoading = filesState is ResourceState.Loading

    // 5 秒超时安全复位机制：若网络慢或卡顿，下拉刷新在 5s 内必定优雅复位，绝不永久转圈
    LaunchedEffect(isLoading) {
        if (isLoading) {
            isRefreshingTimedOut = false
            kotlinx.coroutines.delay(5000L)
            isRefreshingTimedOut = true
        } else {
            isRefreshingTimedOut = false
        }
    }

    val isRefreshing = isLoading && !isRefreshingTimedOut

    // 交互弹窗状态
    var showNewItemMenu by remember { mutableStateOf(false) }
    var currentNewItemType by remember { mutableStateOf<NewItemType?>(null) }
    var newItemNameInput by remember { mutableStateOf("") }

    var itemToDelete by remember { mutableStateOf<DavResource?>(null) }
    var itemToRename by remember { mutableStateOf<DavResource?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // 本地文件夹绑定选择器 (SAF OpenDocumentTree)
    var selectedLocalFolderUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedLocalFolderName by remember { mutableStateOf("") }
    var selectedSyncMode by remember { mutableStateOf(com.taolesi.nas.data.FolderSyncMode.TWO_WAY) }

    val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {}
            selectedLocalFolderUri = uri
            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            selectedLocalFolderName = doc?.name ?: uri.lastPathSegment ?: "本地文件夹"
        }
    }

    // 本地文件上传选择器
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && onUploadFile != null) {
            val fileName = run {
                var name: String? = null
                if (uri.scheme == "content") {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val col = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (col >= 0) name = it.getString(col)
                        }
                    }
                }
                name ?: uri.path?.substringAfterLast('/') ?: "uploaded_file"
            }
            android.widget.Toast.makeText(context, "正在上传: $fileName", android.widget.Toast.LENGTH_SHORT).show()
            onUploadFile(uri, fileName) { ok, msg ->
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 内部 BackHandler 拦截（处于子目录时返回上一级，处于就地预览时关闭预览）
    BackHandler(enabled = previewData != null) {
        onDismissPreview()
    }
    BackHandler(enabled = previewData == null && currentPath.isNotEmpty()) {
        onNavigateUp()
    }

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
    // 阻尼跟手计算：限制下拉拉伸位移，配合 spring 回弹提供丝滑舒适的手感
    val pullOffsetPx by animateFloatAsState(
        targetValue = if (pullToRefreshState.distanceFraction > 0f) {
            pullToRefreshState.distanceFraction.coerceIn(0f, 1.2f) * with(density) { 68.dp.toPx() }
        } else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pullOffset"
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            triggerHapticVibration()
            isRefreshingTimedOut = false
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
            // 顶栏：标题与「新建」+「上传」功能组（彻底移除右侧冗余的 1列/2栏 网格胶囊指示，捏合手势即可自由缩放）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "文件中心",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    letterSpacing = (-0.8).sp
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 1. Windows 风格「新建」按键与下拉菜单
                    Box {
                        Surface(
                            onClick = { showNewItemMenu = true },
                            shape = RoundedCornerShape(9999.dp),
                            color = Color.White,
                            shadowElevation = 2.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(text = "＋", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                                Text(text = "新建", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                                Text(text = "▾", fontSize = 10.sp, color = AccentBlue)
                            }
                        }

                        DropdownMenu(
                            expanded = showNewItemMenu,
                            onDismissRequest = { showNewItemMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("📁 新建文件夹", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    showNewItemMenu = false
                                    newItemNameInput = ""
                                    currentNewItemType = NewItemType.FOLDER
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🔄 新建同步文件夹", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentBlue) },
                                onClick = {
                                    showNewItemMenu = false
                                    newItemNameInput = "同步文件夹"
                                    currentNewItemType = NewItemType.SYNC_FOLDER
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📄 新建文本文档 (.txt)", fontSize = 13.sp) },
                                onClick = {
                                    showNewItemMenu = false
                                    newItemNameInput = "新建文本文档.txt"
                                    currentNewItemType = NewItemType.TXT
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📝 新建 Markdown 笔记 (.md)", fontSize = 13.sp) },
                                onClick = {
                                    showNewItemMenu = false
                                    newItemNameInput = "新建笔记.md"
                                    currentNewItemType = NewItemType.MD
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🔧 新建 JSON 配置 (.json)", fontSize = 13.sp) },
                                onClick = {
                                    showNewItemMenu = false
                                    newItemNameInput = "config.json"
                                    currentNewItemType = NewItemType.JSON
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📘 新建 Word 文档 (.docx)", fontSize = 13.sp) },
                                onClick = {
                                    showNewItemMenu = false
                                    newItemNameInput = "新建文档.docx"
                                    currentNewItemType = NewItemType.DOCX
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📊 新建 Excel 表格 (.xlsx)", fontSize = 13.sp) },
                                onClick = {
                                    showNewItemMenu = false
                                    newItemNameInput = "新建工作表.xlsx"
                                    currentNewItemType = NewItemType.XLSX
                                }
                            )
                        }
                    }

                    // 2. Windows 风格「上传」按键
                    Surface(
                        onClick = { filePickerLauncher.launch("*/*") },
                        shape = RoundedCornerShape(9999.dp),
                        color = Color.White,
                        shadowElevation = 2.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = "⬆️", fontSize = 12.sp)
                            Text(text = "上传", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentEmerald)
                        }
                    }

                    // 3. 返回上一级按钮 (仅在非根目录下呈现)
                    if (currentPath.isNotEmpty()) {
                        Surface(
                            onClick = { onNavigateUp() },
                            shape = RoundedCornerShape(9999.dp),
                            color = Color.White,
                            shadowElevation = 2.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33CBD5E1))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary, modifier = Modifier.size(13.dp))
                                Text(text = "上级", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                            }
                        }
                    }
                }
            }

        // 可点击的面包屑路径胶囊导航行 (支持直接点击任意目录层级跳转)
        val breadcrumbScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(breadcrumbScroll)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val isRoot = currentPath.isEmpty()
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isRoot) AccentBlue.copy(alpha = 0.12f) else Color(0xFFF1F5F9),
                modifier = Modifier.clickable { onNavigateToPath("") }
            ) {
                Text(
                    text = "NAS 根目录",
                    fontSize = 11.sp,
                    fontWeight = if (isRoot) FontWeight.Bold else FontWeight.Medium,
                    color = if (isRoot) AccentBlue else TextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (currentPath.isNotEmpty()) {
                val segments = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                var accumulated = ""
                segments.forEachIndexed { index, seg ->
                    accumulated = if (accumulated.isEmpty()) seg else "$accumulated/$seg"
                    val targetPath = accumulated
                    val isLast = index == segments.size - 1

                    Text(text = ">", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Bold)

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isLast) AccentBlue.copy(alpha = 0.12f) else Color(0xFFF1F5F9),
                        modifier = Modifier.clickable { onNavigateToPath(targetPath) }
                    ) {
                        Text(
                            text = seg,
                            fontSize = 11.sp,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                            color = if (isLast) AccentBlue else TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 文件目录列表 / 图标网格
        when (filesState) {
            is ResourceState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 128.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentBlue)
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
                            Text(text = "文件目录拉取失败", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = filesState.message.ifBlank { "无法获取当前目录内容，请检查连接" },
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = onRefresh,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                shape = RoundedCornerShape(9999.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("重新拉取目录", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            is ResourceState.Success -> {
                val fileList = filesState.data
                if (fileList.isEmpty()) {
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
                                        .background(AccentBlue.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📂", fontSize = 26.sp)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = "当前目录为空", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "可通过电脑或手机将文件上传至私有云，此处将实时呈现",
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = onRefresh,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                    shape = RoundedCornerShape(9999.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("刷新当前目录", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    val handleItemClick: (DavResource) -> Unit = { item ->
                        val ext = item.name.substringAfterLast('.', "").uppercase()
                        val isDocOrText = ext in listOf("MD", "TXT", "JSON", "XML", "YAML", "LOG", "PY", "SH", "CONF")
                        val isImage = ext in listOf("JPG", "JPEG", "PNG", "WEBP", "GIF", "HEIC", "BMP", "SVG")
                        if (item.isDirectory) {
                            onFolderClick(item.name)
                        } else if (isImage) {
                            onPhotoClick(item)
                        } else if (isDocOrText) {
                            onPreviewFile(item.name)
                        } else {
                            onDirectOpenWps(item.name)
                        }
                    }

                    // 动态自适应网格/列表布局 (支持双指手势无缝缩放 1~5 列)
                    if (columns == 1) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 128.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .pinchToZoomColumns(
                                    currentColumns = columns,
                                    onColumnsChange = { newCol ->
                                        triggerHapticVibration()
                                        columns = newCol
                                    },
                                    minColumns = 1,
                                    maxColumns = 5
                                )
                        ) {
                            items(fileList, key = { it.path }) { item ->
                                val ext = item.name.substringAfterLast('.', "").uppercase()
                                val isDocOrText = ext in listOf("MD", "TXT", "JSON", "XML", "YAML", "LOG", "PY", "SH", "CONF")
                                val isImage = ext in listOf("JPG", "JPEG", "PNG", "WEBP", "GIF", "HEIC", "BMP", "SVG")

                                PearlCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { handleItemClick(item) }
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
                                            if (isImage && serverBaseUrl.isNotBlank()) {
                                                val imageUrl = com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(serverBaseUrl, item.path)
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(imageUrl)
                                                        .addHeader("Authorization", authHeader)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = item.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(Color(0xFFE2E8F0))
                                                )
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (item.isDirectory) AccentBlue.copy(alpha = 0.12f) else AccentEmerald.copy(alpha = 0.12f),
                                                    modifier = Modifier.size(52.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = if (item.isDirectory) "📁" else (if (isDocOrText) "📑" else "📦"),
                                                            fontSize = 22.sp
                                                        )
                                                    }
                                                }
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val subText = if (item.isDirectory) {
                                                    "文件夹 · 点击进入"
                                                } else {
                                                    val sizeMb = item.contentLength / (1024.0 * 1024)
                                                    val sizeStr = if (sizeMb >= 1) String.format(Locale.getDefault(), "%.1f MB", sizeMb) else "${item.contentLength / 1024} KB"
                                                    "$sizeStr · $ext · ${item.lastModified.take(16)}"
                                                }
                                                Text(text = subText, fontSize = 11.sp, color = TextMuted)
                                            }
                                        }

                                        // 操作菜单按键 (重命名与删除)
                                        var itemMenuExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(
                                                onClick = { itemMenuExpanded = true },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Text(text = "⋮", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                            }
                                            DropdownMenu(
                                                expanded = itemMenuExpanded,
                                                onDismissRequest = { itemMenuExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("✏️ 重命名", fontSize = 13.sp) },
                                                    onClick = {
                                                        itemMenuExpanded = false
                                                        renameInput = item.name
                                                        itemToRename = item
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("🗑️ 删除", fontSize = 13.sp, color = Color(0xFFEF4444)) },
                                                    onClick = {
                                                        itemMenuExpanded = false
                                                        itemToDelete = item
                                                    }
                                                )
                                            }
                                        }

                                        if (item.isDirectory) {
                                            Text(text = "›", fontSize = 18.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // 2~5 列多栏网格布局 (手势自由放大缩小)
                        val gridSpacing = if (columns >= 4) 6.dp else 8.dp
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                            verticalArrangement = Arrangement.spacedBy(gridSpacing),
                            contentPadding = PaddingValues(bottom = 128.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .pinchToZoomColumns(
                                    currentColumns = columns,
                                    onColumnsChange = { newCol ->
                                        triggerHapticVibration()
                                        columns = newCol
                                    },
                                    minColumns = 1,
                                    maxColumns = 5
                                )
                        ) {
                            items(fileList, key = { it.path }) { item ->
                                val ext = item.name.substringAfterLast('.', "").uppercase()
                                val isDocOrText = ext in listOf("MD", "TXT", "JSON", "XML", "YAML", "LOG", "PY", "SH", "CONF")
                                val isImage = ext in listOf("JPG", "JPEG", "PNG", "WEBP", "GIF", "HEIC", "BMP", "SVG")

                                PearlCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { handleItemClick(item) }
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(if (columns >= 4) 4.dp else 6.dp)
                                    ) {
                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            if (isImage && serverBaseUrl.isNotBlank()) {
                                                val imageUrl = com.taolesi.nas.net.DavUrlNormalizer.toSafeHttpUrl(serverBaseUrl, item.path)
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(imageUrl)
                                                        .addHeader("Authorization", authHeader)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = item.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Color(0xFFE2E8F0))
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (item.isDirectory) AccentBlue.copy(alpha = 0.12f) else AccentEmerald.copy(alpha = 0.12f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (item.isDirectory) "📁" else (if (isDocOrText) "📑" else "📦"),
                                                        fontSize = if (columns >= 4) 20.sp else 28.sp
                                                    )
                                                }
                                            }

                                            // 右上角微型菜单按键
                                            var itemMenuExpanded by remember { mutableStateOf(false) }
                                            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                                                IconButton(
                                                    onClick = { itemMenuExpanded = true },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Text(text = "⋮", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                }
                                                DropdownMenu(
                                                    expanded = itemMenuExpanded,
                                                    onDismissRequest = { itemMenuExpanded = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("✏️ 重命名", fontSize = 13.sp) },
                                                        onClick = {
                                                            itemMenuExpanded = false
                                                            renameInput = item.name
                                                            itemToRename = item
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("🗑️ 删除", fontSize = 13.sp, color = Color(0xFFEF4444)) },
                                                        onClick = {
                                                            itemMenuExpanded = false
                                                            itemToDelete = item
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = item.name,
                                            fontSize = if (columns >= 4) 10.sp else 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {}
        }

        // 就地文本 / Markdown 全屏预览与轻量就地编辑保存弹窗
        previewData?.let { (fileName, textContent) ->
            var isEditing by remember(fileName) { mutableStateOf(false) }
            var editBuffer by remember(fileName, textContent) { mutableStateOf(textContent) }
            var saveStatusMessage by remember(fileName) { mutableStateOf<String?>(null) }
            var isSaving by remember(fileName) { mutableStateOf(false) }

            Dialog(onDismissRequest = onDismissPreview) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.88f)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fileName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isEditing) "正在就地编辑中 · 修改后可直接保存回 NAS" else "安全预览模式 (0 依赖轻量渲染)",
                                    fontSize = 10.sp,
                                    color = if (isEditing) AccentAmber else TextMuted
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // 编辑/查看切换胶囊
                                Surface(
                                    shape = RoundedCornerShape(9999.dp),
                                    color = if (isEditing) AccentBlue.copy(alpha = 0.12f) else Color(0xFFF1F5F9),
                                    modifier = Modifier.clickable {
                                        isEditing = !isEditing
                                        saveStatusMessage = null
                                    }
                                ) {
                                    Text(
                                        text = if (isEditing) "退出编辑" else "✏️ 编辑",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isEditing) AccentBlue else TextSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                                IconButton(onClick = onDismissPreview, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextMuted)
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0x33CBD5E1))

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFF8FAFC))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (isEditing) {
                                BasicTextField(
                                    value = editBuffer,
                                    onValueChange = { editBuffer = it },
                                    textStyle = TextStyle(
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                                )
                            } else {
                                Text(
                                    text = editBuffer,
                                    fontSize = 12.sp,
                                    color = TextPrimary
                                )
                            }
                        }

                        saveStatusMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = msg,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (msg.contains("成功")) AccentEmerald else Color(0xFFEF4444)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isEditing) {
                            Button(
                                onClick = {
                                    isSaving = true
                                    onSaveFile?.invoke(fileName, editBuffer) { success, msg ->
                                        isSaving = false
                                        saveStatusMessage = msg
                                        if (success) {
                                            isEditing = false
                                        }
                                    }
                                },
                                enabled = !isSaving,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                                shape = RoundedCornerShape(9999.dp),
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            ) {
                                Text(if (isSaving) "正在上传回 NAS..." else "保存修改并同步到 NAS", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    onDismissPreview()
                                    onDirectOpenWps(fileName)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                shape = RoundedCornerShape(9999.dp),
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            ) {
                                Text("调用外部专业应用打开 (↗ WPS / 编辑器)", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Windows 风格新建条目弹窗 (支持文件夹、同步文件夹、TXT、MD、JSON、Word、Excel，脱离预览范围全局可用)
            currentNewItemType?.let { itemType ->
                if (itemType == NewItemType.SYNC_FOLDER) {
                    AlertDialog(
                        onDismissRequest = { currentNewItemType = null },
                        title = { Text("🔄 新建文件夹同步绑定", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = newItemNameInput,
                                    onValueChange = { newItemNameInput = it },
                                    label = { Text("NAS 云端文件夹名称") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Surface(
                                    onClick = { folderPickerLauncher.launch(null) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFF1F5F9),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33CBD5E1)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (selectedLocalFolderName.isNotBlank()) "已选本地: $selectedLocalFolderName" else "点击选择手机本地绑定文件夹",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selectedLocalFolderName.isNotBlank()) AccentBlue else TextPrimary
                                            )
                                            Text(
                                                text = "通过 SAF 授权，支持多端按目录对应",
                                                fontSize = 10.sp,
                                                color = TextMuted
                                            )
                                        }
                                        Text(text = "📂 选择", fontSize = 12.sp, color = AccentBlue, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Text(text = "同步模式选择:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        com.taolesi.nas.data.FolderSyncMode.TWO_WAY to "双向同步",
                                        com.taolesi.nas.data.FolderSyncMode.UPLOAD_ONLY to "单向上传",
                                        com.taolesi.nas.data.FolderSyncMode.DOWNLOAD_ONLY to "单向下载"
                                    ).forEach { (mode, title) ->
                                        val isSel = selectedSyncMode == mode
                                        Surface(
                                            onClick = { selectedSyncMode = mode },
                                            shape = RoundedCornerShape(9999.dp),
                                            color = if (isSel) AccentBlue.copy(alpha = 0.15f) else Color(0xFFF8FAFC),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) AccentBlue else Color(0x33CBD5E1)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = title,
                                                    fontSize = 10.sp,
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSel) AccentBlue else TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                                Text(
                                    text = selectedSyncMode.description,
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val name = newItemNameInput.trim().trim('/')
                                    val uriStr = selectedLocalFolderUri?.toString().orEmpty()
                                    if (name.isNotBlank()) {
                                        onCreateSyncFolder?.invoke(
                                            name,
                                            uriStr,
                                            selectedLocalFolderName.ifBlank { name },
                                            selectedSyncMode
                                        ) { ok, msg ->
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            if (ok) currentNewItemType = null
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Text("确认创建并绑定", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { currentNewItemType = null }) {
                                Text("取消", color = TextMuted)
                            }
                        }
                    )
                } else {
                    val typeTitle = when (itemType) {
                        NewItemType.FOLDER -> "新建文件夹"
                        NewItemType.SYNC_FOLDER -> "新建同步文件夹"
                        NewItemType.TXT -> "新建文本文档"
                        NewItemType.MD -> "新建 Markdown 笔记"
                        NewItemType.JSON -> "新建 JSON 配置文件"
                        NewItemType.DOCX -> "新建 Word 文档"
                        NewItemType.XLSX -> "新建 Excel 工作表"
                    }

                    AlertDialog(
                        onDismissRequest = { currentNewItemType = null },
                        title = { Text(typeTitle, fontWeight = FontWeight.Bold) },
                        text = {
                            OutlinedTextField(
                                value = newItemNameInput,
                                onValueChange = { newItemNameInput = it },
                                label = { Text(if (itemType == NewItemType.FOLDER) "文件夹名称" else "文件名称 (含扩展名)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val name = newItemNameInput.trim()
                                    if (name.isNotBlank()) {
                                        if (itemType == NewItemType.FOLDER) {
                                            onCreateFolder?.invoke(name) { ok, msg ->
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                if (ok) currentNewItemType = null
                                            }
                                        } else {
                                            val initialBytes = when (itemType) {
                                                NewItemType.DOCX -> Base64.decode(EMPTY_DOCX_B64, Base64.DEFAULT)
                                                NewItemType.XLSX -> Base64.decode(EMPTY_XLSX_B64, Base64.DEFAULT)
                                                NewItemType.JSON -> "{\n  \"name\": \"new_config\"\n}\n".toByteArray(Charsets.UTF_8)
                                                NewItemType.MD -> "# ${name.substringBeforeLast('.')}\n\n".toByteArray(Charsets.UTF_8)
                                                NewItemType.TXT -> "".toByteArray(Charsets.UTF_8)
                                                else -> ByteArray(0)
                                            }
                                            onCreateFile?.invoke(name, initialBytes) { ok, msg ->
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                if (ok) currentNewItemType = null
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Text("创建", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { currentNewItemType = null }) {
                                Text("取消", color = TextMuted)
                            }
                        }
                    )
                }
            }

            // 重命名弹窗 (全局可用)
            itemToRename?.let { targetItem ->
                AlertDialog(
                    onDismissRequest = { itemToRename = null },
                    title = { Text("重命名", fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = renameInput,
                            onValueChange = { renameInput = it },
                            label = { Text("新名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (renameInput.isNotBlank()) {
                                    onRenameItem?.invoke(targetItem, renameInput) { ok, msg ->
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        if (ok) itemToRename = null
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Text("保存", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { itemToRename = null }) {
                            Text("取消", color = TextMuted)
                        }
                    }
                )
            }

            // 删除确认弹窗 (全局可用)
            itemToDelete?.let { targetItem ->
                AlertDialog(
                    onDismissRequest = { itemToDelete = null },
                    title = { Text("确认删除", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444)) },
                    text = { Text("确定要从 NAS 云端彻底删除「${targetItem.name}」吗？此操作不可逆。") },
                    confirmButton = {
                        Button(
                            onClick = {
                                onDeleteItem?.invoke(targetItem) { ok, msg ->
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    itemToDelete = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                        ) {
                            Text("彻底删除", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { itemToDelete = null }) {
                            Text("取消", color = TextMuted)
                        }
                    }
                )
            }
        }
    }
}
