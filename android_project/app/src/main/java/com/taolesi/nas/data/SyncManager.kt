package com.taolesi.nas.data

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.taolesi.nas.net.DavResource
import com.taolesi.nas.net.WebDavClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 前台相册增量同步状态
 */
data class SyncStatus(
    val isSyncing: Boolean = false,
    val syncedCount: Int = 0,
    val totalPending: Int = 0,
    val currentFileName: String = "",
    val message: String = "就绪"
) {
    /**
     * 剩余未备份照片数 (与相册页专属液态毛玻璃指示胶囊强联动)
     */
    val remainingCount: Int
        get() = (totalPending - syncedCount).coerceAtLeast(0)
}

/**
 * 极简前台相册增量同步管理器 (多线程并发流水线 + 手机信息与相册结构归档)
 *
 * 核心设计守则 (RFC Compliance & Zero Background Resident):
 * 1. 零后台驻留：不使用后台保活服务，仅在前台生命周期内伴随触发；
 * 2. 手机信息独立归档：在 NAS 中按 [手机备份/设备名/相册名/文件名] 自动组织目录；
 * 3. 多线程并发加速：采用 Semaphore 控制 3 路并发并发上传，速度极大提升。
 */
class SyncManager(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val webDavClient: WebDavClient
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * 查询本地相册中未备份的新增项数量
     */
    fun getPendingMediaCount(): Int {
        val lastSyncEpochSec = prefs.lastSyncTimestamp / 1000L
        return queryPendingMedia(lastSyncEpochSec).size
    }

    /**
     * 极速检索本地相册中所有图片 (用于相册双源融合展现)
     */
    fun queryAllLocalPhotos(): List<DavResource> {
        val result = mutableListOf<DavResource>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                val backedHashes = prefs.backedUpHashes
                val lastSyncSec = prefs.lastSyncTimestamp / 1000L

                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "IMG_${id}.jpg"
                    val dateSec = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    val isSynced = (dateSec <= lastSyncSec) || backedHashes.contains(name) || backedHashes.contains("${name}_$size")
                    val dateStr = try { sdf.format(java.util.Date(dateSec * 1000L)) } catch (_: Exception) { "" }

                    result.add(
                        DavResource(
                            name = name,
                            path = uri.toString(),
                            isDirectory = false,
                            contentLength = size,
                            lastModified = dateStr,
                            contentType = mime,
                            isLocal = true,
                            isSynced = isSynced,
                            localUri = uri.toString()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("SyncManager", "读取本地全量相册失败 (可能未授权媒体权限): ${e.message}")
        }
        return result
    }

    /**
     * 协程安全查询本地待备份相册数量
     */
    suspend fun checkPendingMediaCount(): Int = withContext(Dispatchers.IO) {
        getPendingMediaCount()
    }

    /**
     * 获取规范的当前设备名称 (例如: Xiaomi_Redmi_Note_9_4G 或 Android_Device)
     */
    fun getDeviceModelName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim().replace(" ", "_")
        val model = Build.MODEL.orEmpty().trim().replace(" ", "_")
        return when {
            manufacturer.isNotEmpty() && model.isNotEmpty() -> {
                if (model.startsWith(manufacturer, ignoreCase = true)) model else "${manufacturer}_$model"
            }
            model.isNotEmpty() -> model
            else -> "Android_Phone"
        }.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    /**
     * 判断当前是否处于非计费无线网络 (Wi-Fi 或以太网)
     */
    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * 执行前台即时增量同步 (多线程并发流水线)
     * @return 成功同步的文件数量
     */
    suspend fun performForegroundIncrementalSync(): Int = withContext(Dispatchers.IO) {
        if (!prefs.autoBackup) {
            _status.value = SyncStatus(isSyncing = false, message = "自动备份已关闭")
            return@withContext 0
        }

        if (prefs.wifiOnly && !isWifiConnected()) {
            _status.value = SyncStatus(isSyncing = false, message = "仅在 Wi-Fi 下备份 (当前非 Wi-Fi)")
            return@withContext 0
        }

        if (prefs.password.isEmpty()) {
            _status.value = SyncStatus(isSyncing = false, message = "未配置 NAS 访问凭据")
            return@withContext 0
        }

        // 探测本地相册中大于上次同步时间戳的新增项
        val lastSyncEpochSec = prefs.lastSyncTimestamp / 1000L
        val pendingMediaList = queryPendingMedia(lastSyncEpochSec)

        if (pendingMediaList.isEmpty()) {
            _status.value = SyncStatus(isSyncing = false, message = "相册已是最新，无新增照片")
            return@withContext 0
        }

        val total = pendingMediaList.size
        _status.value = SyncStatus(
            isSyncing = true,
            syncedCount = 0,
            totalPending = total,
            message = "检测到 $total 项新增相册，开始多线程备份..."
        )

        val deviceFolder = getDeviceModelName()
        val baseBackupRoot = "手机备份/$deviceFolder"
        
        var successCount = 0
        var highestTimestampMs = prefs.lastSyncTimestamp
        val resolver = context.contentResolver

        // 动态根据用户设置的并发数进行流水线上传 (默认 5 并发)
        val concurrencySemaphore = Semaphore(prefs.maxConcurrentRequests.coerceIn(1, 16))
        val createdDirectories = mutableSetOf<String>()

        val jobs = pendingMediaList.mapIndexed { index, media ->
            async(Dispatchers.IO) {
                if (!currentCoroutineContext().isActive) return@async false
                concurrencySemaphore.withPermit {
                    if (!currentCoroutineContext().isActive) return@withPermit false

                    val cleanBucket = if (media.bucketName.isNotBlank()) media.bucketName else "Camera"
                    val targetRemoteDir = "$baseBackupRoot/$cleanBucket"

                    // 避免重复检测 MKCOL
                    synchronized(createdDirectories) {
                        if (!createdDirectories.contains(targetRemoteDir)) {
                            createdDirectories.add(targetRemoteDir)
                        }
                    }

                    _status.value = SyncStatus(
                        isSyncing = true,
                        syncedCount = successCount,
                        totalPending = total,
                        currentFileName = media.name,
                        message = "正在多线程备份 (${index + 1}/$total): ${media.name}"
                    )

                    var uploadOk = false
                    try {
                        val pfd = try { resolver.openFileDescriptor(media.uri, "r") } catch (_: Exception) { null }
                        val realLength = if (media.size > 0) media.size else (pfd?.statSize ?: -1L)
                        pfd?.close()

                        if (realLength > 0L) {
                            resolver.openInputStream(media.uri)?.use { stream ->
                                uploadOk = webDavClient.uploadFile(
                                    subPath = targetRemoteDir,
                                    fileName = media.name,
                                    inputStream = stream,
                                    contentLength = realLength,
                                    contentType = media.mimeType
                                )
                            }
                        } else {
                            // 长度未知时，安全中转写入临时缓存获取 100% 精确字节大小，彻底避免流被截断
                            val tempFile = File(context.cacheDir, "sync_${System.currentTimeMillis()}_${media.name.filter { it.isLetterOrDigit() || it == '.' }}")
                            try {
                                resolver.openInputStream(media.uri)?.use { inp ->
                                    tempFile.outputStream().use { out -> inp.copyTo(out) }
                                }
                                if (tempFile.exists() && tempFile.length() > 0L) {
                                    val fIn = java.io.FileInputStream(tempFile)
                                    try {
                                        uploadOk = webDavClient.uploadFile(
                                            subPath = targetRemoteDir,
                                            fileName = media.name,
                                            inputStream = fIn,
                                            contentLength = tempFile.length(),
                                            contentType = media.mimeType
                                        )
                                    } finally {
                                        fIn.close()
                                    }
                                }
                            } finally {
                                tempFile.delete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("SyncManager", "多线程上传失败: ${media.name}, 原因: ${e.message}")
                    }

                    if (uploadOk) {
                        synchronized(this@SyncManager) {
                            successCount++
                            if (media.modifiedTimestampMs > highestTimestampMs) {
                                highestTimestampMs = media.modifiedTimestampMs
                                prefs.lastSyncTimestamp = highestTimestampMs
                            }
                        }
                    }

                    _status.value = SyncStatus(
                        isSyncing = true,
                        syncedCount = successCount,
                        totalPending = total,
                        currentFileName = media.name,
                        message = "已备份 $successCount/$total 项"
                    )
                    uploadOk
                }
            }
        }

        jobs.awaitAll()

        val finishMsg = if (successCount == total) {
            "相册备份完成，已成功备份 $successCount 张照片至 [$baseBackupRoot]"
        } else {
            "备份结束: 成功 $successCount 项，跳过 ${total - successCount} 项"
        }

        _status.value = SyncStatus(
            isSyncing = false,
            syncedCount = successCount,
            totalPending = total,
            currentFileName = "",
            message = finishMsg
        )

        return@withContext successCount
    }

    /**
     * 从系统多媒体库检索新增照片与视频记录 (按相册 Bucket 分组提取)
     */
    private fun queryPendingMedia(sinceEpochSec: Long): List<MediaItemToSync> {
        val result = mutableListOf<MediaItemToSync>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val selection = "${MediaStore.MediaColumns.DATE_MODIFIED} > ?"
        val selectionArgs = arrayOf(sinceEpochSec.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC"

        // 1. 查询图片库
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "IMG_${id}.jpg"
                    val dateSec = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val bucket = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "Camera" else "Camera"
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    result.add(
                        MediaItemToSync(
                            uri = uri,
                            name = name,
                            size = size,
                            modifiedTimestampMs = dateSec * 1000L,
                            mimeType = mime,
                            bucketName = bucket
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "读取 MediaStore 图片失败 (请确保已取得媒体权限)", e)
        }

        // 2. 查询视频库
        try {
            val videoProjection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )

            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "VID_${id}.mp4"
                    val dateSec = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: "video/mp4"
                    val bucket = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "Camera" else "Camera"
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    result.add(
                        MediaItemToSync(
                            uri = uri,
                            name = name,
                            size = size,
                            modifiedTimestampMs = dateSec * 1000L,
                            mimeType = mime,
                            bucketName = bucket
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SyncManager", "读取 MediaStore 视频失败", e)
        }

        return result.sortedBy { it.modifiedTimestampMs }
    }

    private data class MediaItemToSync(
        val uri: Uri,
        val name: String,
        val size: Long,
        val modifiedTimestampMs: Long,
        val mimeType: String,
        val bucketName: String = "Camera"
    )

    /**
     * 解析与持久化自定义文件夹同步绑定列表
     */
    fun getSyncFolderBindings(): List<SyncFolderBinding> {
        val jsonStr = prefs.folderSyncBindingsJson
        if (jsonStr.isBlank() || jsonStr == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<SyncFolderBinding>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SyncFolderBinding(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        remotePath = obj.optString("remotePath", ""),
                        localUriString = obj.optString("localUriString", ""),
                        localDisplayName = obj.optString("localDisplayName", ""),
                        syncMode = FolderSyncMode.fromKey(obj.optString("syncMode", "TWO_WAY")),
                        lastSyncTimestamp = obj.optLong("lastSyncTimestamp", 0L),
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveSyncFolderBindings(bindings: List<SyncFolderBinding>) {
        try {
            val arr = org.json.JSONArray()
            for (b in bindings) {
                val obj = org.json.JSONObject()
                obj.put("id", b.id)
                obj.put("remotePath", b.remotePath)
                obj.put("localUriString", b.localUriString)
                obj.put("localDisplayName", b.localDisplayName)
                obj.put("syncMode", b.syncMode.key)
                obj.put("lastSyncTimestamp", b.lastSyncTimestamp)
                obj.put("isEnabled", b.isEnabled)
                arr.put(obj)
            }
            prefs.folderSyncBindingsJson = arr.toString()
        } catch (_: Throwable) {}
    }

    fun addOrUpdateSyncFolderBinding(binding: SyncFolderBinding) {
        val current = getSyncFolderBindings().toMutableList()
        val index = current.indexOfFirst { it.id == binding.id || it.remotePath.trim('/') == binding.remotePath.trim('/') }
        if (index >= 0) {
            current[index] = binding
        } else {
            current.add(binding)
        }
        saveSyncFolderBindings(current)
    }

    fun removeSyncFolderBinding(id: String) {
        val current = getSyncFolderBindings().filter { it.id != id }
        saveSyncFolderBindings(current)
    }

    /**
     * 执行指定自定义文件夹绑定的同步 (双向同步 / 单向上传 / 单向下载)
     */
    suspend fun syncCustomFolder(binding: SyncFolderBinding): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!binding.isEnabled || prefs.password.isEmpty()) return@withContext Pair(0, 0)
        val cleanRemote = binding.remotePath.trim('/')
        if (cleanRemote.isEmpty()) return@withContext Pair(0, 0)

        // 确保远端文件夹存在
        webDavClient.createDirectory(cleanRemote)

        _status.value = SyncStatus(
            isSyncing = true,
            message = "正在同步文件夹 [${binding.localDisplayName} ⇄ $cleanRemote] (${binding.syncMode.title})..."
        )

        var uploaded = 0
        var downloaded = 0

        // 解析本地目标：优先通过 DocumentFile (SAF) 或物理 File
        val isTreeUri = binding.localUriString.startsWith("content://")
        val localDirFile: File? = if (!isTreeUri) {
            val f = File(binding.localUriString)
            if (!f.exists()) f.mkdirs()
            f
        } else null

        val localTreeDoc: androidx.documentfile.provider.DocumentFile? = if (isTreeUri) {
            try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(binding.localUriString))
            } catch (_: Throwable) { null }
        } else null

        // 1. 获取远端文件清单
        val remoteFiles = try {
            webDavClient.listFolder(cleanRemote).filter { !it.isDirectory }
        } catch (_: Throwable) { emptyList() }
        val remoteMap = remoteFiles.associateBy { it.name }

        // 2. 获取本地文件清单
        data class LocalFileInfo(val name: String, val size: Long, val lastModified: Long, val openStream: () -> java.io.InputStream?)
        val localList = mutableListOf<LocalFileInfo>()

        if (localTreeDoc != null && localTreeDoc.exists() && localTreeDoc.isDirectory) {
            localTreeDoc.listFiles().filter { it.isFile && it.name != null && !it.name!!.endsWith(".part") }.forEach { doc ->
                val name = doc.name ?: return@forEach
                localList.add(LocalFileInfo(name, doc.length(), doc.lastModified()) {
                    context.contentResolver.openInputStream(doc.uri)
                })
            }
        } else if (localDirFile != null && localDirFile.exists()) {
            localDirFile.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.startsWith(".") }?.forEach { f ->
                localList.add(LocalFileInfo(f.name, f.length(), f.lastModified()) {
                    java.io.FileInputStream(f)
                })
            }
        }
        val localMap = localList.associateBy { it.name }

        // 3. 根据模式调度
        when (binding.syncMode) {
            FolderSyncMode.TWO_WAY -> {
                // 双向同步：本地新增/更新 -> 上传；远端新增/更新 -> 下载；删除按实时状态双向生效
                // 3.1 本地新增或较新文件上传
                for (local in localList) {
                    val remote = remoteMap[local.name]
                    val remoteTimeMs = if (remote != null) parseDateToMs(remote.lastModified) ?: 0L else 0L
                    val shouldUpload = (remote == null) || (local.size != remote.contentLength && local.lastModified > remoteTimeMs)

                    if (shouldUpload) {
                        try {
                            local.openStream()?.use { stream ->
                                val ok = webDavClient.uploadFile(
                                    subPath = cleanRemote,
                                    fileName = local.name,
                                    inputStream = stream,
                                    contentLength = local.size
                                )
                                if (ok) uploaded++
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 3.2 远端新增或较新文件下载
                for (remote in remoteFiles) {
                    val local = localMap[remote.name]
                    val remoteTimeMs = parseDateToMs(remote.lastModified) ?: 0L
                    val shouldDownload = (local == null) || (local.size != remote.contentLength && remoteTimeMs > local.lastModified)

                    if (shouldDownload) {
                        try {
                            val fullSub = "$cleanRemote/${remote.name}"
                            val resp = webDavClient.downloadFileStream(fullSub)
                            if (resp.isSuccessful) {
                                if (localTreeDoc != null) {
                                    val existingDoc = localTreeDoc.findFile(remote.name)
                                    existingDoc?.delete()
                                    val newDoc = localTreeDoc.createFile("application/octet-stream", remote.name)
                                    if (newDoc != null) {
                                        context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                                            resp.body?.byteStream()?.copyTo(out)
                                        }
                                        downloaded++
                                    }
                                } else if (localDirFile != null) {
                                    val target = File(localDirFile, remote.name)
                                    val temp = File(localDirFile, "${remote.name}.part")
                                    resp.body?.byteStream()?.use { inp ->
                                        temp.outputStream().use { out -> inp.copyTo(out) }
                                    }
                                    if (temp.exists() && temp.length() > 0L) {
                                        if (target.exists()) target.delete()
                                        temp.renameTo(target)
                                        if (remoteTimeMs > 0L) target.setLastModified(remoteTimeMs)
                                        downloaded++
                                    } else {
                                        temp.delete()
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            FolderSyncMode.UPLOAD_ONLY -> {
                // 单向上传：仅把本地推送到云端
                for (local in localList) {
                    val remote = remoteMap[local.name]
                    if (remote == null || remote.contentLength != local.size) {
                        try {
                            local.openStream()?.use { stream ->
                                val ok = webDavClient.uploadFile(
                                    subPath = cleanRemote,
                                    fileName = local.name,
                                    inputStream = stream,
                                    contentLength = local.size
                                )
                                if (ok) uploaded++
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            FolderSyncMode.DOWNLOAD_ONLY -> {
                // 单向下载：仅把云端更新拉到本地
                for (remote in remoteFiles) {
                    val local = localMap[remote.name]
                    if (local == null || local.size != remote.contentLength) {
                        try {
                            val fullSub = "$cleanRemote/${remote.name}"
                            val resp = webDavClient.downloadFileStream(fullSub)
                            if (resp.isSuccessful) {
                                if (localTreeDoc != null) {
                                    val existingDoc = localTreeDoc.findFile(remote.name)
                                    existingDoc?.delete()
                                    val newDoc = localTreeDoc.createFile("application/octet-stream", remote.name)
                                    if (newDoc != null) {
                                        context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                                            resp.body?.byteStream()?.copyTo(out)
                                        }
                                        downloaded++
                                    }
                                } else if (localDirFile != null) {
                                    val target = File(localDirFile, remote.name)
                                    val temp = File(localDirFile, "${remote.name}.part")
                                    resp.body?.byteStream()?.use { inp ->
                                        temp.outputStream().use { out -> inp.copyTo(out) }
                                    }
                                    if (temp.exists() && temp.length() > 0L) {
                                        if (target.exists()) target.delete()
                                        temp.renameTo(target)
                                        downloaded++
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        // 更新上次同步时间戳
        val updatedBinding = binding.copy(lastSyncTimestamp = System.currentTimeMillis())
        addOrUpdateSyncFolderBinding(updatedBinding)
        webDavClient.invalidateFolderCache(cleanRemote)

        _status.value = SyncStatus(
            isSyncing = false,
            message = "同步完成 [${binding.localDisplayName}]: 上传 $uploaded 项，下载 $downloaded 项"
        )
        Pair(uploaded, downloaded)
    }

    /**
     * 执行所有已启用的自定义文件夹同步
     */
    suspend fun syncAllCustomFolders(): Map<String, Pair<Int, Int>> = withContext(Dispatchers.IO) {
        val bindings = getSyncFolderBindings().filter { it.isEnabled }
        val results = mutableMapOf<String, Pair<Int, Int>>()
        for (b in bindings) {
            results[b.id] = syncCustomFolder(b)
        }
        results
    }

    /**
     * 获取双向同步手机本地专属目录 (默认 Downloads/TaleSync)
     */
    fun getLocalSyncDirectory(): File {
        val configured = prefs.twoWaySyncLocalFolder.trim().ifEmpty { PreferencesManager.DEFAULT_TWO_WAY_LOCAL_FOLDER }
        val relativeSub = configured.replace('\\', '/')
            .removePrefix("/")
            .let {
                if (it.startsWith("Downloads/", ignoreCase = true)) {
                    it.substring("Downloads/".length)
                } else {
                    it
                }
            }
            .trim('/')
            .ifEmpty { "TaleSync" }

        val downloadDir = try {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Throwable) {
            null
        }

        val baseDir = if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
            downloadDir
        } else {
            File(context.filesDir, "Downloads")
        }

        val targetDir = File(baseDir, relativeSub)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    /**
     * 获取双向同步 NAS 云端专属目录 (默认 /双向同步)
     */
    fun getRemoteSyncDirectory(): String {
        val remote = prefs.twoWaySyncRemoteFolder.trim().ifEmpty { PreferencesManager.DEFAULT_TWO_WAY_REMOTE_FOLDER }
        return if (remote.startsWith('/')) remote else "/$remote"
    }

    /**
     * 执行安卓本地目录与 NAS 云端专属目录的双向实时增量同步
     * @param force 为 true 时忽略两端同步开关直接执行 (对外显式触发)
     * @return Pair(上传项数, 下载项数)
     */
    suspend fun performTwoWaySync(force: Boolean = false): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!force && !prefs.twoWaySyncEnabled) return@withContext Pair(0, 0)
        if (prefs.password.isEmpty()) return@withContext Pair(0, 0)

        val localDir = getLocalSyncDirectory()
        val remoteDirPath = getRemoteSyncDirectory()
        val cleanRemote = remoteDirPath.trim('/')

        // 确保远程专属目录存在 (RFC 4918 MKCOL 规范)
        webDavClient.createDirectory(cleanRemote)

        _status.value = SyncStatus(
            isSyncing = true,
            syncedCount = 0,
            totalPending = 0,
            currentFileName = "",
            message = "正在初始化双向同步 [$cleanRemote]..."
        )

        var uploaded = 0
        var downloaded = 0

        // 1. 获取远程所有文件列表
        val remoteFiles = try {
            webDavClient.listFolder(cleanRemote).filter { !it.isDirectory }
        } catch (e: Exception) {
            Log.w("SyncManager", "获取双向同步远程列表失败: ${e.message}")
            emptyList()
        }

        val remoteMap = remoteFiles.associateBy { it.name }

        // 2. 本地文件比对 -> 上传新增或更新至 NAS (过滤 .part 临时下载碎片与隐藏文件)
        val localFiles = localDir.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".part") && !it.name.startsWith(".")
        } ?: emptyList()
        val localMap = localFiles.associateBy { it.name }

        for ((index, localFile) in localFiles.withIndex()) {
            val remote = remoteMap[localFile.name]
            val remoteTimeMs = if (remote != null) parseDateToMs(remote.lastModified) ?: 0L else 0L
            val shouldUpload = (remote == null) || (localFile.length() != remote.contentLength && localFile.lastModified() > remoteTimeMs)

            if (shouldUpload) {
                _status.value = SyncStatus(
                    isSyncing = true,
                    syncedCount = uploaded + downloaded,
                    totalPending = localFiles.size,
                    currentFileName = localFile.name,
                    message = "双向同步上传 (${index + 1}/${localFiles.size}): ${localFile.name}"
                )
                try {
                    localFile.inputStream().use { stream ->
                        val ok = webDavClient.uploadFile(
                            subPath = cleanRemote,
                            fileName = localFile.name,
                            inputStream = stream,
                            contentLength = localFile.length(),
                            contentType = "application/octet-stream"
                        )
                        if (ok) uploaded++
                    }
                } catch (e: Exception) {
                    Log.w("SyncManager", "双向同步上传失败: ${localFile.name}, 原因: ${e.message}")
                }
            }
        }

        // 3. 远程文件比对 -> 下载多设备共享新增或更新文件至本地
        for ((index, remoteFile) in remoteFiles.withIndex()) {
            val local = localMap[remoteFile.name]
            val remoteTimeMs = parseDateToMs(remoteFile.lastModified) ?: 0L
            val shouldDownload = (local == null) || (local.length() != remoteFile.contentLength && remoteTimeMs > local.lastModified())

            if (shouldDownload) {
                _status.value = SyncStatus(
                    isSyncing = true,
                    syncedCount = uploaded + downloaded,
                    totalPending = remoteFiles.size,
                    currentFileName = remoteFile.name,
                    message = "双向同步下载 (${index + 1}/${remoteFiles.size}): ${remoteFile.name}"
                )
                try {
                    val fullSub = if (cleanRemote.isEmpty()) remoteFile.name else "$cleanRemote/${remoteFile.name}"
                    val resp = webDavClient.downloadFileStream(fullSub)
                    if (resp.isSuccessful) {
                        val targetLocalFile = File(localDir, remoteFile.name)
                        val tempLocalFile = File(localDir, "${remoteFile.name}.part")
                        resp.body?.byteStream()?.use { input ->
                            tempLocalFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempLocalFile.exists() && tempLocalFile.length() > 0L) {
                            if (targetLocalFile.exists()) targetLocalFile.delete()
                            tempLocalFile.renameTo(targetLocalFile)
                            if (remoteTimeMs > 0L) {
                                targetLocalFile.setLastModified(remoteTimeMs)
                            }
                            downloaded++
                        } else {
                            tempLocalFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SyncManager", "双向同步下载失败: ${remoteFile.name}, 原因: ${e.message}")
                }
            }
        }

        webDavClient.invalidateFolderCache(cleanRemote)

        val finishMsg = "双向同步完成: 成功上传 $uploaded 项，下载 $downloaded 项"
        _status.value = SyncStatus(
            isSyncing = false,
            syncedCount = uploaded + downloaded,
            totalPending = 0,
            currentFileName = "",
            message = finishMsg
        )

        Pair(uploaded, downloaded)
    }

    /**
     * 显式双向同步公开快捷接口
     */
    suspend fun syncTwoWay(force: Boolean = true): Pair<Int, Int> = performTwoWaySync(force)

    /**
     * 显式双向同步接口别名
     */
    suspend fun performTwoWaySyncExplicit(): Pair<Int, Int> = performTwoWaySync(force = true)

    /**
     * 多格式时间戳解析器 (支持 RFC 1123, ISO 8601 等多格式)
     */
    fun parseDateToMs(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        val patterns = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "EEE, dd-MMM-yy HH:mm:ss z"
        )
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                val time = format.parse(dateStr)?.time
                if (time != null) return time
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseIso8601ToMs(dateStr: String): Long? = parseDateToMs(dateStr)
}
