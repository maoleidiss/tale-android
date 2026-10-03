# NAS App v1.0.5 架构初审与实证准入报告 (Reviewer Verdict)

- **审阅时间**: 2026-09-28
- **审查员**: Hermes Reviewer
- **目标分支/版本**: v1.0.5 (versionCode 5)
- **审查结论**: **PASS (准入执行，附关键红线约束)**

---

## 1. 业务范围与边界审阅 (in_scope)
- **范围匹配度**: 100% 紧扣用户 4 点诉求，零越权发散。
  1. 首页传输卡片：消除 HTTP 206 / 64MB / 开屏按需等内部术语，替换为原生实时上/下行速率；
  2. 智慧相册顶栏：完全剔除右侧“栏数”与“已连接”冗余胶囊；
  3. 文件中心：彻底纠正 Windows 盘符“T: 盘”为标准私有云路径规范；
  4. 设置与连接：剔除右上角胶囊，解耦连接弹窗保存与网络探测阻塞，支持通用 WebDAV 路径自适应。
- **正交性验证**: Worker 1、Worker 2、Worker 3 涉及文件完全正交互斥，无代码重叠冲突。

---

## 2. 接口与契约冻结核查 (diff_anchored)
- `DashboardState`:
  ```kotlin
  data class DashboardState(
      // ... 保持既有字段完全不变 ...
      val downloadSpeedText: String = "0 B/s",
      val uploadSpeedText: String = "0 B/s"
  )
  ```
  带默认参数，确保原有调用方和测试代码 100% 向后兼容。

---

## 3. 实机/JVM 探针实测与暗坑预警 (Empirical Evidence)
经 Reviewer 编写单元测试探针（`TrafficStatsProbeTest.kt`）在 Gradle `testDebugUnitTest` 真实运行验证，抓取到关键暗坑：

### 暗坑 A: JVM 单测环境下 `Process.myUid()` 报未 Mock 异常
- **实测报错**:
  `java.lang.RuntimeException: Method myUid in android.os.Process not mocked.`
- **规避指令 (Worker 1 必遵)**:
  `TrafficStats` 采样协程必须使用全局 `try { ... } catch (_: Throwable) { ... }` 彻底兜底，无论在 JVM 单测还是特定不支持该 API 的 Android 驱动上，崩溃捕获后均平滑降级为 `"0 B/s"`，绝不可让 ViewModel 初始化或后台协程抛出未捕获异常。

### 暗坑 B: 设备端 `TrafficStats.UNSUPPORTED (-1L)` 与差值负数
- **实测验证**:
  当返回值小于 0 或上次基准为负时，差值不可用于速度计算，必须重置基准并呈现 `"0 B/s"`，严防出现负数速率文本（如 `-1 B/s`）。

### 暗坑 C: `ConnectionConfigDialog` 原有死锁逻辑定位
- **缺陷位置**: `MainActivity.kt:472-479`
  原代码在 `webDavClient.checkConnectionDetailed()` 失败时直接弹错并恢复旧配置，拒绝调用 `onSaveAndTest`。
- **规避指令 (Worker 3 必遵)**:
  提供解耦的“直接保存”或“无论探测成功与否均允许用户强行保存配置”通道，赋予用户 100% 控制权，彻底消除“被内置锁定”的误解。

### 暗坑 D: `WebDavClient` 通用路径兼容与既有单测守护
- **缺陷位置**: `WebDavClient.kt:174` `DavUrlNormalizer.buildDavUrl`
  原代码硬编码拼接 `/remote.php/dav/files/$encodedUser/`。
- **规避指令 (Worker 1 必遵)**:
  对自定义 path（如 `/dav`、`/webdav`、`/`）进行智能根路径保留；对裸域名默认保留兼容，同时保持 `WebDavClientTest.kt` 全部现有单测 100% 通过。
