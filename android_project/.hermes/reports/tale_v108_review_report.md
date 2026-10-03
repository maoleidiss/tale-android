# Tale v1.0.8 架构方案质检审查报告 (Reviewer Report)

**审查对象**: `.hermes/plans/tale_v108_plan.md` & `docs/recon/tale_v108_recon.md`  
**审查基准**: `taolesi-nas-app/android_project` (Tale v1.0.8 / versionCode: 8)  
**审查员**: Hermes Reviewer (质检官)  
**审查时间**: 2025-03-30  
**审查结论**: **PASS (通过并附架构安全加固规范)**

---

## 一、 审查核心门禁评估表

| 门禁维度 | 审查要点 | 评估结论 | 实证与分析摘要 |
|---|---|---|---|
| **1. 需求覆盖度** | 冷启动5次退避 + 持续自愈防假死 | **PASS** | 完整规划 5 次阶梯退避序列 (`0, 1000, 2000, 3500, 5000ms`) 及 15s 持续自愈哨兵，彻底覆盖冷启动假死。 |
| **1. 需求覆盖度** | CPU定时刷新动起来 | **PASS** | 规划在心跳探活循环中以 5s 周期定时驱动 `fetchServerTelemetryDetailed()`，彻底消除“第一帧定格”死数据。 |
| **1. 需求覆盖度** | 真实运行时间剔除“核心系统运行中” | **PASS** | 正则动态提取 OCS uptime 秒数并经 `formatUptimeSeconds()` 转为友好可读文本，彻底拔除静态硬编码敷衍文案。 |
| **1. 需求覆盖度** | 系统平均负载2位精简小数 | **PASS** | 浮点解析并格式化为 `String.format(Locale.US, "%.2f", it)`，统一为 `"0.42 / 0.58 / 0.65"`，杜绝冗长长串。 |
| **2. 架构安全性** | 心跳与遥测的异常隔离机制 | **PASS (附加固要求)** | 遥测在独立协程中异步执行，失败仅静默忽略，绝不翻转 `isConnected`，主 UI 与主心跳不受阻塞。需加固弱网单飞防护。 |
| **3. 契约稳定性** | ServerTelemetry 与 UI/测试兼容性 | **PASS** | 字段与 `DashboardScreen.kt` 消费端及单元测试 100% 契约吻合，保留全部兼容 getter 与伴生对象。 |

---

## 二、 真实工具链实测与 PoC 探针验证

### 1. 独立单测探针构建与运行
质检官就地编写了独立算法与契约探针 `app/src/test/java/com/taolesi/nas/net/TaleV108ReviewerProbeTest.kt`，针对 Plan 中的算法与边界进行了确凿实测。

**实测执行命令**:
```bash
./gradlew testDebugUnitTest --tests com.taolesi.nas.net.TaleV108ReviewerProbeTest
```

**终端真实输出**:
```text
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 11s
22 actionable tasks: 2 executed, 20 up-to-date
```

**探针测试覆盖重点**:
- `testUptimeFormatting`: 0/负数返回 `"--"`；2700s 返回 `"45分钟"`；15300s 返回 `"4小时 15分"`；1571520s 返回 `"18天 4小时"`。全部断言通过。
- `testUptimeRegexExtraction`: 对标准 JSON 数字、带空格 JSON、带引号字符串数字、字段缺失等场景进行了鲁棒性提取实测，全部断言通过。
- `testLoadAverageFormatting`: 对 `"0.4208984375, 0.5830078125, 0.65185546875"` 精准格式化为 `"0.42 / 0.58 / 0.65"`；脏数据容错全部通过。
- `testServerTelemetryMergePreservesJitterAndLatencyHistory`: 模拟后台轮询数据合并，验证了合并时必须保留当前心跳队列中的 `jitterMs` 与 `latencyHistory`。

---

## 三、 确凿缺陷定位与架构安全加固项 (Hardening Requirements)

在执行具体编码前，工兵必须遵照以下 3 项架构安全加固要求，防止潜在运行时隐患：

### 1. 【高优先级】遥测轮询单飞保护（防止弱网下请求雪崩）
- **缺陷隐患位置**: `NasViewModel.kt:372` (`startTrafficMonitoring`)
- **隐患分析**: OkHttp 的 `probeClient` 默认超时时间为 10 秒。若遇到弱网/高时延环境，`fetchServerTelemetryDetailed()` 耗时可能超过 5 秒。若 `trafficJob` 每 5 秒无条件 `launch(Dispatchers.IO)`，会导致上一轮请求未结束即发起新请求，造成 IO 协程与未完成 Socket 堆积，加剧网络拥塞。
- **加固规范**: 必须引入单飞作业防护（Single-flight Guard）：
  ```kotlin
  // 维护单飞 Job 引用
  private var telemetryPollingJob: Job? = null

  // 心跳中触发：
  if (heartbeatTick >= 5) {
      heartbeatTick = 0
      val probeRes = webDavClient.probeConnectionFast()
      // ... 处理 RTT 与 Jitter ...

      // 仅当上一次遥测已完成时才发起新探测，防止弱网雪崩
      if (telemetryPollingJob?.isActive != true) {
          telemetryPollingJob = launch(Dispatchers.IO) {
              val telemRes = webDavClient.fetchServerTelemetryDetailed()
              if (telemRes is DavResult.Success) {
                  updateTelemetryMetrics(telemRes.data)
              }
          }
      }
  }
  ```

### 2. 【高优先级】遥测状态合并防波形图闪烁（State Consistency Guard）
- **缺陷隐患位置**: `NasViewModel.kt:700` (`performPhase2Hydration`) 及新增的 `updateTelemetryMetrics()`
- **隐患分析**: `webDavClient.fetchServerTelemetryDetailed()` 仅从服务端拉取系统硬件指标，返回的模型中 `jitterMs` 为 0f、`latencyHistory` 为空。若直接将最新对象覆写到 `_dashboardState.value.serverTelemetry`，会导致贝塞尔时延曲线和抖动指标归零闪烁。
- **加固规范**: 在更新遥测状态时，严格保留已有心跳波形数据：
  ```kotlin
  private fun updateTelemetryMetrics(fresh: ServerTelemetry) {
      val current = _dashboardState.value.serverTelemetry
      _dashboardState.value = _dashboardState.value.copy(
          serverTelemetry = fresh.copy(
              jitterMs = current.jitterMs,
              latencyHistory = current.latencyHistory
          )
      )
  }
  ```

### 3. 【中优先级】持续自愈哨兵的静默化防护（防 UI 周期性闪烁）
- **缺陷隐患位置**: `NasViewModel.kt:281` (`startColdBootConnectionWithRetry`)
- **隐患分析**: Plan 中的哨兵每 15 秒调用一次 `refreshDashboardInternal()`。而该方法第一行即执行 `isLoading = true`，失败后置为 `false`。若 App 处于长时间断网状态，会导致主界面卡片每 15 秒发生一次 Loading 动画抽搐。
- **加固规范**: 5 次阶梯退避后的 15 秒后台哨兵，建议采用静默探活：先通过 `webDavClient.probeConnectionFast()` 静默探测，仅当探测成功（网络已自愈连通）时，才触发完整的 Dashboard 水合拉取，确保断网放置时界面彻底静默稳定。

---

## 四、 最终审查裁定

**裁定结论**: **PASS**  
本方案对用户痛点定位精准，演化契约与现有工程完全兼容。在工兵实施阶段，请严格落实上述 3 项架构安全加固规范。
