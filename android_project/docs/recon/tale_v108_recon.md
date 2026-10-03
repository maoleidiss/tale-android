# Tale v1.0.8 深度架构侦察与核心缺陷归因报告 (Deep Recon)

> **工程基线**: `D:/HermesWorkspace/taolesi-nas-app/android_project`  
> **版本规划**: `1.0.8` (versionCode: `8`)  
> **目标**: 彻底修复重试机制、实现全息遥测定时真实轮询、规范化系统负载展示与真实运行时间计算。

---

## 一、 缺陷归因与运行时溯源 (Root Cause Analysis)

### 1. 冷启动与后台自动重连机制缺陷
- **现状代码**: `NasViewModel.kt` 中的 `startColdBootConnectionWithRetry()`：
  ```kotlin
  val retryDelays = listOf(0L, 1500L, 3000L) // 仅重试 3 次！
  ```
- **病灶分析**:
  1. 仅有 3 次重试。当用户杀掉 App（冷启动）重新打开时，若遇到移动网络切换、DNS 握手慢或 Cloudflare 专线瞬态抖动，3 次重试在 4.5 秒内即消耗完毕。
  2. 3 次耗尽后，协程直接退出，系统保持 `isConnected = false`，且**没有任何后续后台常驻重试或定时自愈机制**，导致界面“一直放着”，必须用户手动进设置点击保存。
  3. 即使开启了后台常驻服务 `KeepAliveConnectionService`，App 进程被划掉重启时，ViewModel 未优先同步 Service 的保活活跃 Socket，造成冷启动依然走全量探活可能遇阻。

### 2. CPU 占用与系统指标“一直不变、不会动”缺陷
- **现状代码**: `NasViewModel.kt` 的 `startTrafficMonitoring()`：
  ```kotlin
  // 每 5 秒心跳仅调用轻量探针测 ping：
  val probeRes = webDavClient.probeConnectionFast()
  // 没有任何 fetchServerTelemetryDetailed() 的调用！
  ```
- **病灶分析**:
  - `webDavClient.fetchServerTelemetryDetailed()` 仅在连通成功那一瞬间的 `performPhase2Hydration()` 触发过一次。
  - 在 App 正常运行过程中，从未定时更新 `serverTelemetry`，导致 CPU 使用率、内存、系统负载永久定格在冷启动时的第一帧数据，完全没有实时动态性。

### 3. 运行时间（Uptime）显示为静态字符串“核心系统运行中”
- **现状代码**: `WebDavClient.kt` 第 528 行：
  ```kotlin
  uptimeText = "核心系统运行中"
  ```
- **病灶分析**:
  - 开发者偷懒硬编码了字符串 `"核心系统运行中"`，完全没有提取服务端返回的真正 uptime 或秒数格式化。
  - Nextcloud OCS API 或 Linux 系统的 `/proc/uptime` 以秒为单位返回运行时间；在标准 WebDAV 降级模式下，应基于网络请求真实动态时钟和会话在线时长计算，严禁使用敷衍的静态文本。

### 4. 系统平均负载格式粗暴与静态未更新
- **现状代码**:
  ```kotlin
  val loadStr = loadMatch?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.take(3)?.joinToString(" / ") ?: "--"
  ```
- **病灶分析**:
  - Nextcloud 返回的 load 是高精度浮点数（如 `[0.4208984375, 0.5830078125, 0.65185546875]`）。
  - 未进行任何精度截断，直接拼接产生冗长难看的一长串数字（`0.4208984375 / 0.5830078125 / 0.65185546875`），严重破坏珍珠白卡片的视觉高级感。
  - 由于未轮询更新，数值永远不变。

---

## 二、 修复方案与演化契约 (Remediation Architecture)

1. **5 次阶梯退避 + 后台持续巡检自愈重连**:
   - `retryDelays = listOf(0L, 1000L, 2000L, 3500L, 5000L)`（至少 5 次重试，覆盖 11.5s 启动窗口）；
   - 若 5 次用尽仍未连接，启动后台低频自愈循环（每 15s 探活一次直到连通），绝不出现“一直放着”的断联假死；
   - `onResume()` 和前台常驻服务状态主动对齐。
2. **遥测指标定时轮询驱动**:
   - 在心跳循环中，每 5 秒在后台异步触发 `webDavClient.fetchServerTelemetryDetailed()`；
   - 保证 CPU 占用率、负载、内存、时延真正随服务器实时状态动态刷新。
3. **真实运行时间动态格式化**:
   - 解析 Nextcloud OCS 提供的真实 uptime 秒数或字符串；
   - 转换为专业友好格式：如 `"18天 4小时 32分"` 或 `"4小时 15分"`，无数据时为 `"--"`，彻底删除 `"核心系统运行中"` 敷衍文本。
4. **系统负载规范化 (2 位精简浮点)**:
   - 解析 load 浮点数组，统一格式化为 `String.format(Locale.US, "%.2f", it)`，如 `"0.42 / 0.58 / 0.65"`，清爽专业。
