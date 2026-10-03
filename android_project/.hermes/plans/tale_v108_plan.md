# Tale v1.0.8 架构重构与双向接口冻结方案 (Plan)

> **目标版本**: `v1.0.8` (versionCode: `8`)  
> **核心使命**:  
> 1. 彻底解决冷启动重连“仅重试3次就一直放着”的问题，实装至少 5 次退避重试 + 持续后台自愈机制；  
> 2. 彻底解决 CPU、负载、运行时间“一直不变、不会动”的问题，在心跳循环中实装实时服务器网络遥测轮询；  
> 3. 彻底删除“核心系统运行中”敷衍文案，对接真实 Uptime 与动态运行时间格式化；  
> 4. 规范化系统平均负载为 `0.42 / 0.58 / 0.65`（2位精简小数），杜绝冗长浮点数。

---

## 一、 权威双向接口冻结 (Interface Freeze)

### 1. `ServerTelemetry` 模型更新 (`com.taolesi.nas.ui.viewmodel.ServerTelemetry`)
```kotlin
data class ServerTelemetry(
    val isRealServerMetrics: Boolean = false, // 是否具备底层 OCS 内核探针权限
    val serverBrand: String = "WebDAV Server",
    val cpuUsagePercent: Float = 0f,
    val cpuCores: Int = 0,
    val usedMemoryGb: Float = 0f,
    val totalMemoryGb: Float = 0f,
    val memoryUsagePercent: Float = 0f,
    val loadAverage: String = "--",     // 冻结格式: "0.42 / 0.58 / 0.65" (保留 2 位小数)
    val uptimeText: String = "--",      // 冻结格式: "18天 4小时" / "4小时 15分" 或基于会话时长的计算
    val jitterMs: Float = 0f,
    val latencyHistory: List<Long> = emptyList()
)
```

### 2. 真实 Uptime 动态解析契约 (`WebDavClient.kt`)
- 探测 Nextcloud OCS 响应中的 `uptime`（以秒为单位的正整数，如 `1572480`）；
- 若具备 `uptime` 秒数，转换为人类可读字符串：
  ```kotlin
  fun formatUptimeSeconds(seconds: Long): String {
      if (seconds <= 0) return "--"
      val days = seconds / 86400
      val hours = (seconds % 86400) / 3600
      val minutes = (seconds % 3600) / 60
      return when {
          days > 0 -> "${days}天 ${hours}小时"
          hours > 0 -> "${hours}小时 ${minutes}分"
          else -> "${minutes}分钟"
      }
  }
  ```
- 严禁出现 `"核心系统运行中"` 等静态硬编码敷衍文案！无数据时严格显示 `"--"`。

### 3. 系统平均负载格式化契约 (`WebDavClient.kt`)
- 对 OCS 返回的 `cpuload: [0.4208984375, 0.5830078125, 0.65185546875]` 进行规范化：
  ```kotlin
  val loadStr = loadMatch?.groupValues?.get(1)?.split(",")?.mapNotNull {
      it.trim().toFloatOrNull()
  }?.take(3)?.map {
      String.format(Locale.US, "%.2f", it)
  }?.joinToString(" / ") ?: "--"
  ```
- 输出严格为 `"0.42 / 0.58 / 0.65"`，清爽精炼。

### 4. 至少 5 次退避重试 + 永久后台自愈机制 (`NasViewModel.kt`)
- **阶梯退避重试序列**:
  ```kotlin
  val retryDelays = listOf(0L, 1000L, 2000L, 3500L, 5000L) // 严格 5 次阶梯退避
  ```
- **持续自愈哨兵 (Background Self-Healing Sentinel)**:
  若 5 次退避结束依然因外部网络原因未连通，不直接中断退出，而是启动后台低频守护任务：
  ```kotlin
  while (isActive && prefs.password.isNotEmpty() && !_dashboardState.value.isConnected) {
      delay(15000L) // 每 15 秒静默探活一次
      refreshDashboardInternal()
      if (_dashboardState.value.isConnected) {
          launch { refreshPhotosInternal() }
          launch { refreshVideosInternal() }
          launch { refreshFilesInternal(_currentPath.value) }
          break
      }
  }
  ```
- 保证只要 App 打开着，无论是冷启动、断网恢复还是弱网闪断，100% 自动自愈连通，绝不再发生“一直放着”的假死。

### 5. 遥测指标定时轮询驱动 (`NasViewModel.kt`)
- 在 `trafficJob` 循环中，结合心跳探活（每 5 秒）：
  ```kotlin
  if (heartbeatTick >= 5) {
      heartbeatTick = 0
      // 1. 轻量 ping 测算
      val probeRes = webDavClient.probeConnectionFast()
      // 2. 实时异步更新全息遥测指标 (CPU、负载、Uptime 随真实服务器状态动态刷新)
      launch(Dispatchers.IO) {
          val telemRes = webDavClient.fetchServerTelemetryDetailed()
          if (telemRes is DavResult.Success) {
              updateTelemetryMetrics(telemRes.data)
          }
      }
  }
  ```

---

## 二、 责任工兵与改动切口 (Target Paths)

| 目标文件 | 责任切口 |
|---|---|
| `WebDavClient.kt` | 解析 OCS uptime 秒数并格式化；规范化 loadAverage 为 2 位小数；彻底剔除硬编码静态文案 |
| `NasViewModel.kt` | 5 次退避序列 + 15s 后台持续自愈哨兵；`trafficJob` 循环中定时异步抓取 `fetchServerTelemetryDetailed()` |
| `build.gradle.kts` | 升级 `versionCode = 8`, `versionName = "1.0.8"` |
| `unified_manifest.json` | 更新版本元数据与测试断言记录 |
