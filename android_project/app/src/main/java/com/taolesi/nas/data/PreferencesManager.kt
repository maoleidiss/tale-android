package com.taolesi.nas.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 仪表盘与传输测速数据源模式
 */
enum class SpeedSourceMode(val key: String) {
    SERVER_BANDWIDTH("server_bandwidth"),
    LOCAL_APP_BANDWIDTH("local_app_bandwidth");

    companion object {
        fun fromKey(key: String?): SpeedSourceMode {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SERVER_BANDWIDTH
        }
    }
}

/**
 * 通用文件夹同步模式枚举
 */
enum class FolderSyncMode(val key: String, val title: String, val description: String) {
    TWO_WAY("TWO_WAY", "双向同步", "本地与云端实时双向互相同步，任何更改与删除双向生效"),
    UPLOAD_ONLY("UPLOAD_ONLY", "单向上传", "仅将本地增删改同步至 NAS 云端，云端更改不拉取"),
    DOWNLOAD_ONLY("DOWNLOAD_ONLY", "单向下载", "仅从 NAS 云端同步更新至手机本地，本地更改不推送到云端");

    companion object {
        fun fromKey(key: String?): FolderSyncMode {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: TWO_WAY
        }
    }
}

/**
 * 自定义文件夹同步绑定实体模型
 */
data class SyncFolderBinding(
    val id: String = java.util.UUID.randomUUID().toString(),
    val remotePath: String,           // NAS 相对路径 (例如 "同步文件夹/工作资料")
    val localUriString: String,       // 本地通过 SAF 授权的 DocumentTree URI 或绝对路径
    val localDisplayName: String,     // 界面展示的本地名称 (例如 "内部存储/Documents/Work")
    val syncMode: FolderSyncMode = FolderSyncMode.TWO_WAY,
    val lastSyncTimestamp: Long = 0L,
    val isEnabled: Boolean = true
)

/**
 * 基于 Android 硬件安全密钥库 (Keystore) 与 AES-256-GCM 加密的安全凭据与全局配置管理器

 * 具备对 Keystore 异常的防御性回退机制，确保在任何测试环境或模拟器下 100% 稳定运行
 */
class PreferencesManager(private val context: Context) {

    private val sharedPrefs: SharedPreferences = try {
        createEncryptedPrefs(context)
    } catch (e: Throwable) {
        Log.e("PreferencesManager", "Keystore/EncryptedSharedPreferences 异常，尝试清理损坏文件重试", e)
        try {
            // 如果是因为覆盖安装导致旧版 Keystore 密钥失效 (AEADBadTagException / Corrupted XML)，清理损坏文件后重新创建
            deleteDamagedPrefsFile(context, "nas_secure_prefs")
            createEncryptedPrefs(context)
        } catch (e2: Throwable) {
            Log.e("PreferencesManager", "二次初始化仍受限，切换至安全沙箱私有存储", e2)
            context.getSharedPreferences("nas_private_prefs", Context.MODE_PRIVATE)
        }
    }

    init {
        try {
            com.taolesi.nas.net.WebDavClient.defaultCacheDir = context.cacheDir
        } catch (_: Throwable) {}
    }

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            ctx,
            "nas_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun deleteDamagedPrefsFile(ctx: Context, name: String) {
        try {
            val prefsFile = java.io.File(ctx.filesDir.parent, "shared_prefs/$name.xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
            }
        } catch (_: Throwable) {}
    }

    /**
     * WebDAV 服务端根地址 (如 https://nas.example.com)
     */
    var serverUrl: String
        get() = sharedPrefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = sharedPrefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    /**
     * WebDAV 用户名
     */
    var username: String
        get() = sharedPrefs.getString(KEY_USERNAME, "admin") ?: "admin"
        set(value) = sharedPrefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    /**
     * WebDAV 访问密码
     */
    var password: String
        get() = sharedPrefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = sharedPrefs.edit().putString(KEY_PASSWORD, value).apply()

    /**
     * 自动备份开关 (仅在前台打开 App 时增量检测)
     */
    var autoBackup: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_BACKUP, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_AUTO_BACKUP, value).apply()

    /** 兼容历史别名 */
    var autoBackupOnOpen: Boolean
        get() = autoBackup
        set(value) { autoBackup = value }

    /**
     * 仅在 Wi-Fi 网络下执行自动备份
     */
    var wifiOnly: Boolean
        get() = sharedPrefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** 兼容历史别名 */
    var wifiOnlyBackup: Boolean
        get() = wifiOnly
        set(value) { wifiOnly = value }

    /**
     * 防误删保护 (开启后云端文件删除需要二次确认阻断)
     */
    var preventAccidentalDelete: Boolean
        get() = sharedPrefs.getBoolean(KEY_PREVENT_DELETE, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_PREVENT_DELETE, value).apply()

    /** 兼容历史别名 */
    var deleteProtection: Boolean
        get() = preventAccidentalDelete
        set(value) { preventAccidentalDelete = value }

    /**
     * 视频硬解加速
     */
    var hardwareAcceleration: Boolean
        get() = sharedPrefs.getBoolean(KEY_HW_ACCELERATION, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_HW_ACCELERATION, value).apply()

    /**
     * 视频流式缓冲大小 (MB)
     */
    var videoBufferSizeMb: Int
        get() = sharedPrefs.getInt(KEY_VIDEO_BUFFER_MB, 64)
        set(value) = sharedPrefs.edit().putInt(KEY_VIDEO_BUFFER_MB, value).apply()

    /**
     * 默认倍速播放
     */
    var defaultPlaySpeed: Float
        get() = sharedPrefs.getFloat(KEY_PLAY_SPEED, 1.25f)
        set(value) = sharedPrefs.edit().putFloat(KEY_PLAY_SPEED, value).apply()

    /**
     * 相册网格排布列数记忆 (默认 2 栏，支持手势 1~5 栏无缝滑动调节)
     */
    var albumGridColumns: Int
        get() = sharedPrefs.getInt(KEY_ALBUM_COLUMNS, 2)
        set(value) = sharedPrefs.edit().putInt(KEY_ALBUM_COLUMNS, value.coerceIn(1, 5)).apply()

    /**
     * 启动时默认 Tab 索引 (0: 看板, 1: 相册, 2: 影视, 3: 文件, 4: 设置)
     */
    var defaultStartTab: Int
        get() = sharedPrefs.getInt(KEY_START_TAB, 0)
        set(value) = sharedPrefs.edit().putInt(KEY_START_TAB, value).apply()

    /**
     * 已备份完成的照片哈希集合 (防漏传与防重复备份双重保险)
     */
    var backedUpHashes: Set<String>
        get() = sharedPrefs.getStringSet("backed_up_photo_hashes", emptySet()) ?: emptySet()
        set(value) = sharedPrefs.edit().putStringSet("backed_up_photo_hashes", value).apply()

    fun addBackedUpHash(hash: String) {
        val current = backedUpHashes.toMutableSet()
        current.add(hash)
        backedUpHashes = current
    }

    /**
     * 上次增量同步成功时间戳 (毫秒)
     */
    var lastSyncTimestamp: Long
        get() = sharedPrefs.getLong(KEY_LAST_SYNC_TS, 0L)
        set(value) = sharedPrefs.edit().putLong(KEY_LAST_SYNC_TS, value).apply()

    /**
     * 后台专线连接保活开关 (默认 true，配置密码后自动启动专线长连接保活)
     */
    var keepAliveConnection: Boolean
        get() = sharedPrefs.getBoolean(KEY_KEEP_ALIVE_CONNECTION, true)
        set(value) = sharedPrefs.edit().putBoolean(KEY_KEEP_ALIVE_CONNECTION, value).apply()

    /**
     * 当前激活/探测优选的 WebDAV 端点地址 (默认 "")
     */
    var activeDavEndpoint: String
        get() = sharedPrefs.getString(KEY_ACTIVE_DAV_ENDPOINT, "") ?: ""
        set(value) = sharedPrefs.edit().putString(KEY_ACTIVE_DAV_ENDPOINT, value.trim()).apply()

    /**
     * 网速数据源模式 (默认 SERVER_BANDWIDTH)
     */
    var speedSourceMode: SpeedSourceMode
        get() {
            val raw = sharedPrefs.getString(KEY_SPEED_SOURCE_MODE, SpeedSourceMode.SERVER_BANDWIDTH.key)
            return SpeedSourceMode.fromKey(raw)
        }
        set(value) = sharedPrefs.edit().putString(KEY_SPEED_SOURCE_MODE, value.key).apply()

    /**
     * 相册独立备份开关 (默认 false，需用户显式开启并授权)
     */
    var photoBackupEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_PHOTO_BACKUP_ENABLED, false)
        set(value) = sharedPrefs.edit().putBoolean(KEY_PHOTO_BACKUP_ENABLED, value).apply()

    /**
     * 相册同步策略: TWO_WAY_MIRROR(默认双向镜像), ONE_WAY_UPLOAD(单向备份到云端), ONE_WAY_DOWNLOAD(仅云端拉取)
     */
    var photoSyncMode: String
        get() = sharedPrefs.getString(KEY_PHOTO_SYNC_MODE, SYNC_MODE_TWO_WAY_MIRROR) ?: SYNC_MODE_TWO_WAY_MIRROR
        set(value) = sharedPrefs.edit().putString(KEY_PHOTO_SYNC_MODE, value).apply()

    /**
     * 相册云端备份独立落盘目录
     */
    var photoRemoteFolder: String
        get() = sharedPrefs.getString(KEY_PHOTO_REMOTE_FOLDER, "/Photos/PhoneBackup") ?: "/Photos/PhoneBackup"
        set(value) = sharedPrefs.edit().putString(KEY_PHOTO_REMOTE_FOLDER, value).apply()

    /**
     * 自定义文件夹同步绑定列表 (JSON 序列化存储)
     */
    var folderSyncBindingsJson: String
        get() = sharedPrefs.getString(KEY_FOLDER_SYNC_BINDINGS, "[]") ?: "[]"
        set(value) = sharedPrefs.edit().putString(KEY_FOLDER_SYNC_BINDINGS, value).apply()

    /**
     * 相册缓存上次完全扫描成功时间戳 (毫秒，用于 15 分钟 TTL 缓存判定)
     */
    var photosLastFullScanTs: Long
        get() = sharedPrefs.getLong(KEY_PHOTOS_LAST_SCAN_TS, 0L)
        set(value) = sharedPrefs.edit().putLong(KEY_PHOTOS_LAST_SCAN_TS, value).apply()

    /**
     * 视频缓存上次完全扫描成功时间戳 (毫秒，用于 15 分钟 TTL 缓存判定)
     */
    var videosLastFullScanTs: Long
        get() = sharedPrefs.getLong(KEY_VIDEOS_LAST_SCAN_TS, 0L)
        set(value) = sharedPrefs.edit().putLong(KEY_VIDEOS_LAST_SCAN_TS, value).apply()

    /**
     * 相册显示排版策略: DEFAULT_GRID(默认固定网格), WATERFALL_FLOW(自适应瀑布流)
     */
    var albumDisplayStrategy: String
        get() = sharedPrefs.getString(KEY_ALBUM_DISPLAY_STRATEGY, STRATEGY_DEFAULT_GRID) ?: STRATEGY_DEFAULT_GRID
        set(value) = sharedPrefs.edit().putString(KEY_ALBUM_DISPLAY_STRATEGY, value).apply()

    /**
     * 网络请求最大并发数 (支持用户自定义填入，默认 5，范围 1..32)
     */
    var maxConcurrentRequests: Int
        get() = sharedPrefs.getInt(KEY_MAX_CONCURRENT_REQUESTS, 5)
        set(value) = sharedPrefs.edit().putInt(KEY_MAX_CONCURRENT_REQUESTS, value.coerceIn(1, 32)).apply()

    /**
     * 双向同步开关
     */
    var twoWaySyncEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_TWO_WAY_SYNC_ENABLED, false)
        set(value) = sharedPrefs.edit().putBoolean(KEY_TWO_WAY_SYNC_ENABLED, value).apply()

    /**
     * 双向同步本地目标相对路径 (默认 Downloads/TaleSync)
     */
    var twoWaySyncLocalFolder: String
        get() {
            val v = sharedPrefs.getString(KEY_TWO_WAY_SYNC_LOCAL_FOLDER, DEFAULT_TWO_WAY_LOCAL_FOLDER)
            return if (v.isNullOrBlank()) DEFAULT_TWO_WAY_LOCAL_FOLDER else v
        }
        set(value) = sharedPrefs.edit().putString(KEY_TWO_WAY_SYNC_LOCAL_FOLDER, value).apply()

    /**
     * 双向同步 NAS 云端相对路径 (默认 /双向同步)
     */
    var twoWaySyncRemoteFolder: String
        get() {
            val v = sharedPrefs.getString(KEY_TWO_WAY_SYNC_REMOTE_FOLDER, DEFAULT_TWO_WAY_REMOTE_FOLDER)
            return if (v.isNullOrBlank() || v == "/06-设备自动备份/多设备同步共享") DEFAULT_TWO_WAY_REMOTE_FOLDER else v
        }
        set(value) = sharedPrefs.edit().putString(KEY_TWO_WAY_SYNC_REMOTE_FOLDER, value).apply()

    /**
     * 清空除连接凭据外的所有本地缓存标记与本地持久化缓存文件
     */
    fun clearCacheMarkers() {
        sharedPrefs.edit()
            .remove(KEY_LAST_SYNC_TS)
            .apply()
        try {
            com.taolesi.nas.net.WebDavClient.clearDefaultDiskCache(context.cacheDir)
        } catch (_: Throwable) {}
    }

    companion object {
        const val DEFAULT_TWO_WAY_REMOTE_FOLDER = "/双向同步"
        const val DEFAULT_TWO_WAY_LOCAL_FOLDER = "Downloads/TaleSync"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_AUTO_BACKUP = "auto_backup_on_open"
        private const val KEY_WIFI_ONLY = "wifi_only_backup"
        private const val KEY_PREVENT_DELETE = "delete_protection"
        private const val KEY_HW_ACCELERATION = "hardware_acceleration"
        private const val KEY_VIDEO_BUFFER_MB = "video_buffer_size_mb"
        private const val KEY_PLAY_SPEED = "default_play_speed"
        private const val KEY_START_TAB = "default_start_tab"
        private const val KEY_ALBUM_COLUMNS = "album_grid_columns"
        private const val KEY_MAX_CONCURRENT_REQUESTS = "max_concurrent_requests"
        private const val KEY_LAST_SYNC_TS = "last_sync_timestamp"
        private const val KEY_KEEP_ALIVE_CONNECTION = "keep_alive_connection"
        private const val KEY_ACTIVE_DAV_ENDPOINT = "active_dav_endpoint"
        private const val KEY_SPEED_SOURCE_MODE = "speed_source_mode"
        private const val KEY_PHOTO_BACKUP_ENABLED = "photo_backup_enabled"
        private const val KEY_PHOTO_SYNC_MODE = "photo_sync_mode"
        private const val KEY_PHOTO_REMOTE_FOLDER = "photo_remote_folder"
        private const val KEY_FOLDER_SYNC_BINDINGS = "folder_sync_bindings"
        private const val KEY_PHOTOS_LAST_SCAN_TS = "photos_last_scan_ts"
        private const val KEY_VIDEOS_LAST_SCAN_TS = "videos_last_scan_ts"
        private const val KEY_ALBUM_DISPLAY_STRATEGY = "album_display_strategy"
        private const val KEY_TWO_WAY_SYNC_ENABLED = "two_way_sync_enabled"
        private const val KEY_TWO_WAY_SYNC_LOCAL_FOLDER = "two_way_sync_local_folder"
        private const val KEY_TWO_WAY_SYNC_REMOTE_FOLDER = "two_way_sync_remote_folder"

        const val SYNC_MODE_TWO_WAY_MIRROR = "TWO_WAY_MIRROR"
        const val SYNC_MODE_ONE_WAY_UPLOAD = "ONE_WAY_UPLOAD"
        const val SYNC_MODE_ONE_WAY_DOWNLOAD = "ONE_WAY_DOWNLOAD"

        const val STRATEGY_DEFAULT_GRID = "DEFAULT_GRID"
        const val STRATEGY_WATERFALL_FLOW = "WATERFALL_FLOW"
    }
}
