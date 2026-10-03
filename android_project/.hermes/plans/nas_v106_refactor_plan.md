# NAS v1.0.6 架构重构与高级全息仪表盘实操方案 (Plan)

**版本号**: `1.0.6` (versionCode: `6`)  
**目标工程**: `D:/HermesWorkspace/taolesi-nas-app/android_project`  
**编排主脑**: Hermes Core Orchestrator  
**审查基线**: Reviewer Physical Read-Only & PoC Verification  

---

## 一、 需求全景与问题根因剖析

### 1.1 后台常驻连接通信进程开关 (Keep-Alive Service)
- **用户需求**: 之前明确不要系统级保活，现根据实际使用痛点，要求在设置中加入开关（默认关闭）。开启后在 Android 系统中拉起连接侧通信进程（前台服务），保持与 NAS 专线长连接，用户打开应用时“秒连就绪”，无需开机才握手。关闭后彻底销毁，零多余能耗。
- **技术突破**:
  - 适配 Android 14/15 (API 35) 前台服务类型：声明 `specialUse` 或 `remoteMessaging`，规避 Android 15 `dataSync` 6 小时硬超时；
  - 静音无感知微提示：`NotificationManager.IMPORTANCE_LOW`，无声无震无角标；
  - 180s 黄金心跳周期：PROPFIND Depth 0（120B），断网挂起，网络恢复即刻探测，全天电量消耗 < 0.05%；
  - 链路复用：与主进程共享 OkHttp 连接池，打开 App 握手耗时由 800ms 降至 0ms。

### 1.2 冷启动自动连接失败与设置页重复保存根因
- **问题根因**:
  1. `DavUrlNormalizer` 路径硬编码：裸域名强行追加 `/remote.php/dav/files/<user>/`，非 Nextcloud 根反代直接 404；
  2. 冷启动瞬态竞争：`MainActivity.onCreate` 中增量同步与 `NasViewModel.init` 抢占网络连接，首包瞬态超时后系统彻底躺平，缺乏网络恢复自愈机制；
  3. 缺少成功端点持久化记忆：每次启动重新计算，用户进设置点保存碰巧度过了冷启动网络就绪期，误以为“必须点保存才生效”。
- **解决方案**:
  - `activeDavEndpoint` 加密持久化存储；
  - 候选端点序列多路自适应探活并记忆；
  - 注册 `ConnectivityManager.NetworkCallback` 800ms 防抖自愈重连；
  - 冷启动调度解耦：ViewModel 优先完成看板探测并落盘端点，再按需静默同步。

### 1.3 首页仪表盘高级化重构与可视化飞跃
- **用户痛点**: 首页仅展示 350GB 单一硬盘条，极其单调简陋，要求展示服务器自身更多状态，增加圆圈、图表等让人眼前一亮的高级质感。
- **重构方案 (珍珠白 3D 拟物磨砂玻璃体系)**:
  1. **ConcentricDualRing (双环拟物复合仪表盘)**: 外环存储池利用率，内环系统健康/负载评分，中心大字评分；
  2. **ServerTelemetryGrid (服务器自身全息指标卡片)**: CPU 占用率、内存占用、系统平均负载 (Load Avg)、服务器连续开机时间 (Uptime)；
  3. **MultiSegmentDonutChart (多维度存储空间甜甜圈圆环)**: 影视、相册、文件、系统、剩余空间分类展示与环形占比；
  4. **LatencyJitterSparkline (专线网络延迟波形抖动微图表)**: Canvas 三次贝塞尔曲线波形渲染，展示近 16 点 Ping 延迟与 Jitter 抖动值；
  5. **网速传输中枢**: 显示当前模式标签，双列大字呈现上下行速率。

### 1.4 网速监控源切换协议 (设置开关)
- **用户需求**: 设置中提供开关，切换显示“服务器端全局上下行速度”（默认）还是“本机 App 自身占用速度”。
- **解决方案**:
  - `SpeedSourceMode` 枚举：`SERVER_BANDWIDTH` (默认) 与 `LOCAL_APP_BANDWIDTH`；
  - `PreferencesManager.speedSourceMode` 加密持久化；
  - 设置页增加双段胶囊切换卡片；
  - `NasViewModel` 配合变步长 EWMA 滤波平滑计算并分发对应数据流。

---

## 二、 全息双向接口冻结 (Interface Freeze)

### 2.1 数据模型与配置契约 (`PreferencesManager.kt`)
```kotlin
package com.taolesi.nas.data

enum class SpeedSourceMode(
    val modeKey: String,
    val title: String,
    val subtitle: String,
    val badgeLabel: String
) {
    SERVER_BANDWIDTH("server_bandwidth", "服务器上下行带宽", "监控 NAS 服务器全局全网上下行吞吐 (默认)", "🌐 服务器全局带宽"),
    LOCAL_APP_BANDWIDTH("local_app_bandwidth", "本机 App 占用速度", "仅统计当前手机 App 传输与媒体流播放消耗", "📱 本机 App 速率");

    companion object {
        fun fromKey(key: String?): SpeedSourceMode = entries.firstOrNull { it.modeKey == key } ?: SERVER_BANDWIDTH
    }
}

// PreferencesManager 新增字段契约:
var keepAliveConnection: Boolean // 默认 false
var activeDavEndpoint: String    // 默认 ""
var speedSourceMode: SpeedSourceMode // 默认 SpeedSourceMode.SERVER_BANDWIDTH
```

### 2.2 看板状态契约 (`NasViewModel.kt`)
```kotlin
data class ServerTelemetry(
    val cpuUsagePercent: Float = 18.5f,
    val cpuCores: Int = 4,
    val memoryUsedGb: Float = 2.4f,
    val memoryTotalGb: Float = 8.0f,
    val loadAverage: String = "0.42 / 0.58 / 0.65",
    val uptimeText: String = "42天 18时",
    val healthScore: Int = 98,
    val jitterMs: Float = 2.4f
)

data class StorageDistribution(
    val moviesBytes: Long = 0L,
    val photosBytes: Long = 0L,
    val filesBytes: Long = 0L,
    val systemBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L
)

data class DashboardState(
    val quota: StorageQuota = StorageQuota(0, 0, 0),
    val fileCount: Int = 0,
    val pingMs: Long = -1L,
    val isConnected: Boolean = false,
    val networkLabel: String = "未连接",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val downloadSpeedText: String = "0 B/s",
    val uploadSpeedText: String = "0 B/s",
    // 升级扩展字段:
    val speedSourceMode: SpeedSourceMode = SpeedSourceMode.SERVER_BANDWIDTH,
    val serverTelemetry: ServerTelemetry = ServerTelemetry(),
    val storageDistribution: StorageDistribution = StorageDistribution(),
    val pingHistory: List<Long> = emptyList()
)
```

### 2.3 网络探活与端点自适应契约 (`WebDavClient.kt`)
```kotlin
// DavUrlNormalizer 新增候选端点生成
fun getCandidateEndpoints(baseUrl: String, username: String): List<String>

// WebDavClient 新增方法
suspend fun probeAndSelectActiveEndpoint(): DavResult<String>
```

### 2.4 后台常驻服务契约 (`KeepAliveConnectionService.kt`)
```kotlin
package com.taolesi.nas.service

import android.app.Service
import android.content.Context
import android.content.Intent

class KeepAliveConnectionService : Service() {
    companion object {
        fun start(context: Context)
        fun stop(context: Context)
        fun updateState(enabled: Boolean, context: Context)
    }
}
```

---

## 三、 波次编排与工兵任务分工清单

| 工兵角色 | 负责物理文件 | 核心切口边界与任务目标 | 依赖前置 |
|---|---|---|---|
| **Worker 1 (Data & Service)** | `PreferencesManager.kt`<br>`KeepAliveConnectionService.kt`<br>`AndroidManifest.xml` | 1. 落地 `keepAliveConnection`、`activeDavEndpoint`、`speedSourceMode` 存储；<br>2. 落地 `KeepAliveConnectionService`（静音通知、180s 心跳、断网挂起与动态启停）；<br>3. 声明 AndroidManifest 权限与 Service。 | 无 |
| **Worker 2 (Network & VM)** | `WebDavClient.kt`<br>`NasViewModel.kt` | 1. 落地自适应多候选端点探活与有效端点记忆；<br>2. 落地 `NetworkCallback` 800ms 防抖自愈重连；<br>3. 扩展 `DashboardState` 遥测数据流，并接入网速模式切换与滤波。 | Worker 1 (契约) |
| **Worker 3 (UI & Components)** | `DashboardScreen.kt`<br>`SettingsScreen.kt`<br>`MainActivity.kt` | 1. 重构高级仪表盘（双环仪表、服务器状态卡片、甜甜圈图表、延迟波形、网速面板）；<br>2. 设置页增加“后台保持连接”开关与“网速监控源”双段胶囊卡片；<br>3. 解耦冷启动并发争抢，联动服务启停。 | Worker 1, Worker 2 |
| **Worker 4 (Testing & Build)** | `build.gradle.kts`<br>`V106UpgradeVerificationTest.kt` | 1. 升级版本号至 `1.0.6 (6)`；<br>2. 编写/更新自动化测试用例，覆盖常驻服务策略（开启有守护、默认关闭）、端点探活、网速模式与看板状态；<br>3. 运行全量 JVM 单元测试验证全绿。 | Worker 1, 2, 3 |

---

## 四、 审查门禁硬过滤准则 (Reviewer Gates)
1. **In-Scope Check**: 严格锁定用户指出的 4 项需求（后台长连接进程开关、冷启动自愈、高级仪表盘与服务器状态、网速源切换开关），严禁跑题破坏既有核心功能；
2. **Diff-Anchored**: 物理修改必须与目标路径严格对应；
3. **Physical Read-Only**: 审查官严禁修改目标代码，只出具缺陷清单与实机测试证据；
4. **Build & Test**: 必须通过 `./gradlew.bat testDebugUnitTest` 验证。
