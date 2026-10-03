package com.taolesi.nas.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.taolesi.nas.MainActivity
import com.taolesi.nas.R
import com.taolesi.nas.data.PreferencesManager
import com.taolesi.nas.net.DavUrlNormalizer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 专线连接状态快照
 */
data class KeepAliveState(
    val isRunning: Boolean = false,
    val isConnected: Boolean = false,
    val isNetworkAvailable: Boolean = true,
    val latencyMs: Long = -1L,
    val lastHeartbeatTs: Long = 0L,
    val endpointUrl: String = "",
    val errorMessage: String? = null
)

/**
 * WebDAV 专线长连接保活与状态同步前台服务 (Android 14/15 specialUse)
 *
 * 核心机制：
 * 1. 前台服务常驻，通知渠道 IMPORTANCE_LOW（静音无震动无角标）
 * 2. 180s 轻量心跳探测 (PROPFIND Depth 0)，精准捕获专线时延与连通性
 * 3. NetworkCallback 监听：断网即挂起探测循环，网络恢复立即唤醒并快速重连
 * 4. 彻底的资源生命周期闭环：onDestroy 时销毁 Socket、释放 ConnectionPool 与通知
 */
class KeepAliveConnectionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager

    private var heartbeatJob: Job? = null
    private val heartbeatTrigger = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var isNetworkConnected = true

    private var okHttpClient: OkHttpClient? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(TAG, "检测到物理网络连通，唤醒快速重连")
            val wasDisconnected = !isNetworkConnected
            isNetworkConnected = true
            _serviceState.update { it.copy(isNetworkAvailable = true) }
            if (wasDisconnected) {
                updateNotificationText("网络已恢复，正在验证专线长连接...")
                triggerHeartbeat()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(TAG, "物理网络已断开，专线心跳挂起")
            isNetworkConnected = false
            _serviceState.update {
                it.copy(
                    isNetworkAvailable = false,
                    isConnected = false,
                    errorMessage = "网络连接已中断"
                )
            }
            updateNotificationText("网络已断开，专线保活已挂起")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KeepAliveConnectionService 正在初始化...")

        preferencesManager = PreferencesManager(applicationContext)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 创建并配置独立的专用探针连接池与轻量客户端 (同步接入双栈 IPv4 优先 DNS 与防死锁设置)
        val dualStackDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val addresses = try {
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    try {
                        java.net.InetAddress.getAllByName(hostname).toList()
                    } catch (_: Exception) {
                        throw java.net.UnknownHostException("无法解析主机名: $hostname (${e.message})")
                    }
                }
                if (addresses.isEmpty()) {
                    throw java.net.UnknownHostException("主机名解析列表为空: $hostname")
                }
                return addresses.sortedWith(Comparator { a, b ->
                    when {
                        a is java.net.Inet4Address && b !is java.net.Inet4Address -> -1
                        a !is java.net.Inet4Address && b is java.net.Inet4Address -> 1
                        else -> 0
                    }
                })
            }
        }

        okHttpClient = OkHttpClient.Builder()
            .dns(dualStackDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()

        createNotificationChannel()

        // 注册网络监听
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "注册 NetworkCallback 异常", e)
        }

        _serviceState.update {
            it.copy(
                isRunning = true,
                isNetworkAvailable = true,
                endpointUrl = resolveProbeUrl()
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand 收到 action: $action")

        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATE -> {
                _serviceState.update { it.copy(endpointUrl = resolveProbeUrl()) }
                triggerHeartbeat()
                return START_STICKY
            }
            else -> {
                // 启动或保持前台运行
                startForegroundCompat()
                ensureHeartbeatRunning()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 适配 Android 14/15 的 specialUse 前台服务启动
     */
    private fun startForegroundCompat() {
        val notification = buildNotification("专线长连接保活服务正在运行")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务 startForeground 异常", e)
        }
    }

    /**
     * 创建 IMPORTANCE_LOW（静音、无震动、无角标）通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tale 专线保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维持与 Tale WebDAV 服务器的长连接与状态探测"
                setShowBadge(false)
                enableVibration(false)
                vibrationPattern = null
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建服务常驻通知
     */
    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Tale 专线保活")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 更新当前通知文本
     */
    private fun updateNotificationText(text: String) {
        if (!_serviceState.value.isRunning) return
        try {
            val notification = buildNotification(text)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    /**
     * 触发立即探测（网络恢复或外部请求状态更新时调用）
     */
    private fun triggerHeartbeat() {
        heartbeatTrigger.trySend(Unit)
    }

    /**
     * 启动或守护 180s 心跳轮询主循环
     */
    private fun ensureHeartbeatRunning() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = serviceScope.launch {
            Log.i(TAG, "专线保活心跳调度器已启动 (周期: ${HEARTBEAT_INTERVAL_MS / 1000}s)")
            while (isActive) {
                // 仅在网络物理畅通时执行 WebDAV 探针
                if (isNetworkConnected) {
                    performProbe()
                }

                // 等待 180s 周期或被立即唤醒（例如断网恢复、凭据热更新）
                withTimeoutOrNull(HEARTBEAT_INTERVAL_MS) {
                    heartbeatTrigger.receive()
                }

                // 若网络仍处于断网状态，进入挂起等待网络连通信号唤醒
                while (isActive && !isNetworkConnected) {
                    heartbeatTrigger.receive()
                }
            }
        }
    }

    /**
     * 解析目标探测 URL (优先使用 activeDavEndpoint，降级使用 serverUrl)
     */
    private fun resolveProbeUrl(): String {
        val activeEndpoint = preferencesManager.activeDavEndpoint.trim()
        if (activeEndpoint.isNotEmpty()) {
            return if (activeEndpoint.endsWith('/')) activeEndpoint else "$activeEndpoint/"
        }
        val server = preferencesManager.serverUrl.trim()
        val user = preferencesManager.username.trim()
        if (server.isEmpty()) return ""
        return DavUrlNormalizer.buildDavUrl(server, user, isCollection = true)
    }

    /**
     * 执行单次 PROPFIND Depth 0 轻量心跳探测
     */
    private suspend fun performProbe() = withContext(Dispatchers.IO) {
        val targetUrl = resolveProbeUrl()
        val client = okHttpClient

        if (targetUrl.isEmpty() || client == null) {
            _serviceState.update {
                it.copy(
                    isConnected = false,
                    errorMessage = "未配置有效的 Tale WebDAV 服务器地址"
                )
            }
            updateNotificationText("未配置 Tale 服务器地址")
            return@withContext
        }

        val username = preferencesManager.username.trim()
        val password = preferencesManager.password
        val authHeader = if (username.isNotEmpty() && password.isNotEmpty()) {
            Credentials.basic(username, password, Charsets.UTF_8)
        } else {
            ""
        }

        val propfindXml = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:resourcetype/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
            .header("Depth", "0")
            .header("User-Agent", "Tale-KeepAlive/1.0")

        if (authHeader.isNotEmpty()) {
            requestBuilder.header("Authorization", authHeader)
        }

        val startTimeMs = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val latency = System.currentTimeMillis() - startTimeMs
                val isSuccess = response.code == 207 || response.isSuccessful

                if (isSuccess) {
                    _serviceState.update {
                        it.copy(
                            isConnected = true,
                            isNetworkAvailable = true,
                            latencyMs = latency,
                            lastHeartbeatTs = System.currentTimeMillis(),
                            endpointUrl = targetUrl,
                            errorMessage = null
                        )
                    }
                    updateNotificationText("专线畅通 · 延迟 ${latency}ms")
                    Log.d(TAG, "心跳成功: $targetUrl, 延迟: ${latency}ms, HTTP: ${response.code}")
                } else {
                    val errMsg = "HTTP ${response.code}"
                    _serviceState.update {
                        it.copy(
                            isConnected = false,
                            latencyMs = -1L,
                            lastHeartbeatTs = System.currentTimeMillis(),
                            endpointUrl = targetUrl,
                            errorMessage = errMsg
                        )
                    }
                    updateNotificationText("专线异常 ($errMsg)")
                    Log.w(TAG, "心跳服务端响应异常: HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            val errMsg = e.localizedMessage ?: "连接超时或失败"
            _serviceState.update {
                it.copy(
                    isConnected = false,
                    latencyMs = -1L,
                    lastHeartbeatTs = System.currentTimeMillis(),
                    endpointUrl = targetUrl,
                    errorMessage = errMsg
                )
            }
            updateNotificationText("专线连接超时或受阻")
            Log.w(TAG, "心跳网络异常: $errMsg")
        } catch (e: Throwable) {
            Log.e(TAG, "心跳探测未捕获异常", e)
        }
    }

    /**
     * 彻底释放所有资源、关闭通知、销毁 Socket 连接
     */
    override fun onDestroy() {
        Log.i(TAG, "KeepAliveConnectionService 正在彻底销毁并释放资源...")
        super.onDestroy()

        // 1. 取消并终止协程与心跳循环
        serviceScope.cancel()
        heartbeatJob = null

        // 2. 注销网络监听
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "注销 NetworkCallback 异常", e)
        }

        // 3. 彻底销毁 OkHttp Dispatcher 与连接池 Socket
        try {
            okHttpClient?.dispatcher?.cancelAll()
            okHttpClient?.dispatcher?.executorService?.shutdown()
            okHttpClient?.connectionPool?.evictAll()
            okHttpClient = null
        } catch (e: Exception) {
            Log.w(TAG, "销毁 OkHttpClient 资源异常", e)
        }

        // 4. 彻底移除前台服务通知
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "关闭前台服务通知异常", e)
        }

        // 5. 重置静态状态单例
        _serviceState.value = KeepAliveState(isRunning = false)
        Log.i(TAG, "KeepAliveConnectionService 资源已完全释放完毕")
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_ID = 10086
        private const val CHANNEL_ID = "taolesi_nas_keep_alive_channel"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L // 30 秒高敏心跳周期，彻底死锁 Cloudflare Tunnel 与运营商 NAT 映射保活

        const val ACTION_START = "com.taolesi.nas.service.action.START"
        const val ACTION_STOP = "com.taolesi.nas.service.action.STOP"
        const val ACTION_UPDATE_STATE = "com.taolesi.nas.service.action.UPDATE_STATE"

        private val _serviceState = MutableStateFlow(KeepAliveState())
        val serviceState: StateFlow<KeepAliveState> = _serviceState.asStateFlow()

        /**
         * 启动专线长连接保活服务
         */
        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveConnectionService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "静态启动 KeepAliveConnectionService 失败", e)
            }
        }

        /**
         * 停止专线长连接保活服务
         */
        @JvmStatic
        fun stop(context: Context) {
            try {
                val intent = Intent(context, KeepAliveConnectionService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "静态停止 KeepAliveConnectionService 失败", e)
                try {
                    context.stopService(Intent(context, KeepAliveConnectionService::class.java))
                } catch (_: Exception) {}
            }
        }

        /**
         * 触发即时状态刷新与心跳唤醒
         */
        @JvmStatic
        fun updateState(context: Context) {
            try {
                val intent = Intent(context, KeepAliveConnectionService::class.java).apply {
                    action = ACTION_UPDATE_STATE
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "静态更新 KeepAliveConnectionService 状态失败", e)
            }
        }
    }
}
