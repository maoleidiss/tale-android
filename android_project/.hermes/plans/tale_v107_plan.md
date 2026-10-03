# Tale (v1.0.7) 架构重构与双向接口冻结方案 (Plan & Interface Freeze)

> **版本契约**: v1.0.7 (versionCode 7)  
> **工作目录**: `D:/HermesWorkspace/taolesi-nas-app/android_project`  
> **执行角色**: 主脑 (Architect & Orchestrator)

---

## 一、 核心重构目标与范围界定 (In Scope)

1. **首页双环评分与垃圾检测组件物理移除**:
   - 彻底删除 `ConcentricDualRingGaugeCard` 及相关虚构评分字段 (`healthScore`)；
   - 首页顶栏直出实时网络专线状态，直接呈现真实核心控制台。
2. **服务器信息真实请求与动态解析 (绝不硬编码)**:
   - `WebDavClient` 实现真实探针 `fetchServerTelemetryDetailed()`：
     - 首选探测 Nextcloud OCS 监控接口: `/ocs/v2.php/apps/serverinfo/api/v1/info?format=json` (Header: `OCS-APIREQUEST: true`)；
     - 备选探测服务端系统状态: `/status.php` (解析版本、产品名、安装状态)；
     - 结合 WebDAV RFC 4331 真实配额与网络往返时延；
   - 在管理员模式下展示真实 CPU %、RAM、Load、Uptime；在普通 WebDAV 模式下展示真实服务端软件版本（如 `Nextcloud 35.0.1` / `AList`）、真实配额与真实时延，严禁输出任何虚假数字。
3. **网络与上下行网速真实性 + 波动图动态滚动**:
   - 剔除 `startTrafficMonitoring` 在 `SERVER_BANDWIDTH` 模式下填充的 128KB/s 假常数；
   - 建立 16 点真实时延滚动采样环形队列 (`latencyHistory`)，每次轻量心跳追加新数据，驱动 `LatencyJitterSparklineCard` 的 Canvas 贝塞尔波形图随真实数据流动态平滑向前滚动；
   - Jitter 严格按 RFC 3550 真实时延差值实时计算。
4. **冷启动自动连接根除与两阶段分层握手 (Two-Phase Handshake)**:
   - **Phase 1 轻量快速握手**: PROPFIND Depth 0 仅 100 字节，100ms 内判定连通，立即点亮 `isConnected = true` 并呈现时延；
   - **Phase 2 异步平滑水合**: 连通后异步拉取配额、目录与服务器遥测，子项异常绝不推翻 Phase 1 的连通状态；
   - **冷启动并发解耦**: 彻底移除 `MainActivity.onCreate` 中与 ViewModel 重复的并发探测；
   - **弱网退避自愈**: 启动首次握手若遇网络抖动，自动执行 1.5s/3s 指数退避自愈重试（最多 3 次）。
5. **App 全面更名 Tale**:
   - `AndroidManifest.xml` 应用名修改为 `Tale`；
   - 页面顶栏、通知栏、字符串全面对齐 `Tale`。

---

## 二、 全息双向接口冻结 (Interface Freeze)

### 2.1 遥测数据模型冻结 (`ServerTelemetry`)
```kotlin
data class ServerTelemetry(
    val isRealServerMetrics: Boolean = false, // 是否具备底层 OCS 内核探针权限
    val serverBrand: String = "WebDAV Server", // 例如 "Nextcloud 35.0.1" / "通用 WebDAV"
    val cpuUsagePercent: Float = 0f,
    val cpuCores: Int = 0,
    val usedMemoryGb: Float = 0f,
    val totalMemoryGb: Float = 0f,
    val memoryUsagePercent: Float = 0f,
    val loadAverage: String = "--",
    val uptimeText: String = "--",
    val jitterMs: Float = 0f,
    val latencyHistory: List<Long> = emptyList()
) {
    companion object {
        val EMPTY = ServerTelemetry()
        val OFFLINE = ServerTelemetry(
            isRealServerMetrics = false,
            serverBrand = "离线",
            cpuUsagePercent = 0f,
            cpuCores = 0,
            usedMemoryGb = 0f,
            totalMemoryGb = 0f,
            memoryUsagePercent = 0f,
            loadAverage = "--",
            uptimeText = "--",
            jitterMs = 0f,
            latencyHistory = emptyList()
        )
    }
}
```

### 2.2 仪表盘状态模型冻结 (`DashboardState`)
```kotlin
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
    val speedSourceMode: SpeedSourceMode = SpeedSourceMode.SERVER_BANDWIDTH,
    val serverTelemetry: ServerTelemetry = ServerTelemetry.EMPTY,
    val storageDistribution: StorageDistribution = StorageDistribution.EMPTY,
    val pingHistory: List<Long> = emptyList()
)
```

### 2.3 `WebDavClient` 新增接口冻结
- `suspend fun fetchServerTelemetryDetailed(): DavResult<ServerTelemetry>`
- `suspend fun probeConnectionFast(): DavResult<Long>` (仅 PROPFIND Depth 0 极轻握手，用于冷启动毫秒连通判定)

---

## 三、 波次编排与执行分工

- **Worker 1 (底层通信与遥测探针)**:
  - 目标文件: `app/src/main/java/com/taolesi/nas/net/WebDavClient.kt`
  - 任务: 实现 `probeConnectionFast()`，实现 `fetchServerTelemetryDetailed()` 解析 Nextcloud OCS `/ocs/v2.php/apps/serverinfo/api/v1/info` 及 `/status.php`，无权限或普通 WebDAV 优雅降级回显真实服务端软件版本与真实 RTT。
- **Worker 2 (状态中枢与冷启动两阶段握手自愈)**:
  - 目标文件: `app/src/main/java/com/taolesi/nas/ui/viewmodel/NasViewModel.kt`
  - 任务: 
    1. 升级 `ServerTelemetry` 与 `DashboardState`；
    2. 实现两阶段分层握手 (Phase 1 极速探活点亮 `isConnected = true`，Phase 2 异步水合)；
    3. 加入冷启动 1.5s/3s 指数退避自愈；
    4. 修正流量监控算法（剔除假常数，维护动态 16 点 `latencyHistory` 真实滚动采样流）。
- **Worker 3 (UI 重构与垃圾评分物理删除)**:
  - 目标文件: `app/src/main/java/com/taolesi/nas/ui/screens/DashboardScreen.kt`, `app/src/main/java/com/taolesi/nas/ui/screens/SettingsScreen.kt`
  - 任务:
    1. 物理删除 `ConcentricDualRingGaugeCard` 双环评分；
    2. 重构 `ServerTelemetryGridCard`：当具备内核指标时展示真实 CPU/RAM/负载/Uptime；当为普通 WebDAV 时展示真实服务端品牌版本、专线时延与存储配额，杜绝硬编码假数字；
    3. 升级 `LatencyJitterSparklineCard` 绑定实时动态滚动的 `latencyHistory`；
    4. 顶栏标题从 "NAS" 改为 "Tale"。
- **Worker 4 (工程配置与全量改名 Tale)**:
  - 目标文件: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/taolesi/nas/MainActivity.kt`, `app/src/main/java/com/taolesi/nas/service/KeepAliveConnectionService.kt`, `app/build.gradle.kts`
  - 任务:
    1. `label="Tale"`，通知标题改为 "Tale 专线保活"；
    2. 清理 `MainActivity.onCreate` 重复并发请求，对齐启动序列；
    3. 版本号升级至 `v1.0.7` (versionCode 7)。
- **Worker 5 (单元测试守护与回归验证)**:
  - 目标文件: `app/src/test/java/com/taolesi/nas/V107TaleVerificationTest.kt`
  - 任务: 编写并跑通 100% 针对 v1.0.7 真实遥测、两阶段握手、动态波形、Tale 应用名及移除双环评分的单测套件。
