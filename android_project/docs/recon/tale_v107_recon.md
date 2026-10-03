# Tale (v1.0.7) 架构重构与深度侦察物化规范 (Recon)

> **版本契约**: v1.0.7 (versionCode 7)  
> **目标工程**: `D:/HermesWorkspace/taolesi-nas-app/android_project`  
> **核心任务**:
> 1. 彻底删除首页双环复合评分卡片及检测评分垃圾设计；
> 2. 服务器信息真实请求与动态解析（对接 Nextcloud OCS `/ocs/v2.php/apps/serverinfo/api/v1/info` / `/status.php` / WebDAV RFC 4331，绝不硬编码假数字）；
> 3. 网络显示真实上下行吞吐，专线波形图动态随真实 RTT 采样滚动；
> 4. 冷启动自动连接彻底自愈（两阶段握手、移除并发竞争、指数退避重试，根治必须进设置点保存才连接的 Bug）；
> 5. 应用全面更名为 `Tale`。

---

## 1. 现场代码缺陷分析与证据链

### 1.1 缺陷 1: 首页双环评分与虚假检测指标
- **现状**: `DashboardScreen.kt:289-499` 存在 `ConcentricDualRingGaugeCard`，硬编码 98 分、极佳徽章、双环复合等无意义虚假视觉元素。
- **治理**: 物理彻底删除 `ConcentricDualRingGaugeCard`，从 `DashboardScreen` 与 `DashboardState` 中彻底移除虚假评分字段。

### 1.2 缺陷 2: 服务器信息硬编码与非实时性
- **现状**: `NasViewModel.kt:48-80` 的 `ServerTelemetry` 硬编码 `cpuUsagePercent = 18.5f`, `cpuCores = 4`, `usedMemoryGb = 2.4f`, `totalMemoryGb = 8.0f`, `loadAverage = "0.42 / 0.58 / 0.65"`, `uptimeText = "42天 18时"`。连接任何服务器都显示一模一样的假数字。
- **治理**:
  - `WebDavClient` 增加原生服务端探针 `fetchServerTelemetry()`：
    1. 优先请求 `/ocs/v2.php/apps/serverinfo/api/v1/info?format=json`（带 `OCS-APIREQUEST: true`），解析真实的 CPU 使用率、CPU 核心数、实际已用/总内存、系统负载、Uptime；
    2. 若返回 401/403/404（普通用户无管理员权限或通用 WebDAV），自动降级请求 `/status.php` 获取系统品牌与版本（例如 `Nextcloud 35.0.1`），并结合 WebDAV 真实存储配额与真实网络 RTT，在卡片上真实清晰呈现“标准 WebDAV 模式 (无底层内核探针权限)”以及真实的服务器版本与配额，**100% 杜绝捏造假数字**；
  - 启动定时周期轮询与手动刷新联动，数据动态实时变化。

### 1.3 缺陷 3: 网速与波形图静态失灵
- **现状**:
  - `LatencyJitterSparklineCard`: 使用 `sin(i * 0.8)` 虚构静态波形，完全不会随时间滚动；
  - `startTrafficMonitoring`: 在 `SERVER_BANDWIDTH` 模式下直接给无流量状态填充 128KB/s 假常数。
- **治理**:
  - 在 `NasViewModel` 中维护真实的 RTT 采样滚动窗口 `latencyHistory: List<Long>`（近 16 次轻量探活真实时延）；
  - 引入定时或心跳驱动的采样流，每次新采样向队列尾部追加并弹出头部，Canvas 曲线基于真实实时采样点平滑滚动重绘；
  - 网速在无数据传输时严格真实显示 `0 B/s`，绝不注入假常数。

### 1.4 缺陷 4: 冷启动不自动连接、必须手动保存才生效根因
- **现状**:
  1. `NasViewModel.init` 并发调用 `refreshAll()`，同时 `MainActivity.onCreate` 在 `lifecycleScope` 重复调用 `refreshDashboard()`，并发发起网络请求导致连接 Job 竞争被取消；
  2. `refreshDashboardInternal` 将 `isConnected` 与 `listFolder("")`（拉取完整根目录树）强绑定，冷启动瞬态网络抖动一旦抛出异常直接置为 `isConnected = false` 并彻底躺平，不再重试；
  3. `ConnectionConfigDialog` 点击保存时重置了端点并单独调用 `refreshAll()`，此时网络已稳定故能连上。
- **治理**:
  - 实施**两阶段分层握手 (Two-Phase Handshake)**：
    - Phase 1 (轻量快速握手): 仅发一个 100 字节的 PROPFIND Depth 0 探活。只要服务端响应 207 或 200，立即判定专线连通，毫秒级点亮 `isConnected = true`，回显真实网络延迟；
    - Phase 2 (平滑异步水合): 在连通后异步拉取配额、目录与服务器遥测，子项异常绝不推翻 Phase 1 的连通状态；
  - 冷启动自动重试自愈：首次握手若遇弱网抖动，自动执行 1.5s、3s 指数退避重试（最多 3 次），无需用户手动进设置保存；
  - 移除 `MainActivity.onCreate` 中与 ViewModel 重复的并发刷新调用，收敛为统一启动序列。

### 1.5 缺陷 5: 应用改名 Tale
- 将包名、应用名称（`AndroidManifest.xml`、Compose 顶栏标题、通知栏标题、字符串资源）全面更新为 `Tale`。
