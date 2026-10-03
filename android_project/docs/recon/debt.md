# 4D 侦察 - 技术债与风险防线全息图 (Debt & Defense Lines)

> **版本契约**: v1.0.6 (versionCode 6)  
> **更新时间**: 2026-09-28  
> **面向对象**: Hermes 主系统决策层、下游架构师与一线实施研发人员

---

## 1. 历史技术债与暗坑溯源

### 1.1 历史已排暗坑 (Historical Pitfalls Mitigated)
1. **硬编码 Nextcloud 路径死锁**:
   - *问题*: 前期重构 `DavUrlNormalizer` 时假定所有 WebDAV 服务均遵循 `/remote.php/dav/files/$username/`，直接剥离并强行拼接该路径。导致通用 WebDAV、反向代理直接映射 WebDAV 根路径时直接报 404 或解析失败。
   - *治理*: 引入 `DavUrlNormalizer.hasCustomPath` 与自适应多模式探测，用户输入带挂载点 URL（如 `/dav`、`/webdav`、`/alist/dav`）直接以此为根，裸域名则平滑映射标准 Nextcloud 路由。
2. **连接弹窗保存强依赖实时探测**:
   - *问题*: 保存按钮在点击时直接调用 `checkConnectionDetailed()`，若服务端此时响应略慢或网络瞬断，弹窗抛错并拒绝保存，导致用户输入的新配置无法落盘，误以为配置“被内置死锁无法修改”。
   - *治理*: 彻底解耦“保存配置”与“网络探针”，允许用户直接无阻落盘，同时提供非阻塞的就地连通性测试。
3. **主控机习惯污染移动端 UI**:
   - *问题*: 将 Windows 映射的 `T: 盘` 盘符字样写死在 Android 客户端界面与空状态提示中，导致认知混乱。
   - *治理*: 全面清除所有 `T:` 盘符标识，替换为私有云标准“NAS 根目录”与规范云端路径导航。
4. **底层架构术语堆砌**:
   - *问题*: 将 HTTP 206、64MB 零占机、开屏按需、退出即销毁等内部工程设计理念作为产品文案呈现在卡片上，用户体验差。
   - *治理*: 替换为专业简洁的双列实时传输速率指示面板（实时下行/上行速度、延迟）。

---

## 2. v1.0.6 现存技术债盘点 (Identified Technical Debts)

### 2.1 构建工具链与依赖配置技术债
- **Gradle 10 废弃 API 警告**:
  - `app/build.gradle.kts` 中使用 `kotlinOptions { jvmTarget = "21" }`。Kotlin 2.0.21 已废弃该接口，后续版本需平滑迁移至 `compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }`。
  - Gradle 9.3.1 在检查增量输出与 AGP 8.7.3 内部临时 `R.jar` 时存在非阻塞警告。
- **跨平台构建执行环境敏感性**:
  - Windows 环境下若在 MSYS/Git Bash 终端混合执行 Gradle，可能因 POSIX 路径转换将 `JAVA_HOME` 误判为 `D:\c\Program Files`，并诱发多 Daemon 实例竞争。**构建铁律**: 推荐统一使用工程原生 `gradlew.bat` 或纯净 PowerShell 执行构建与测试。

### 2.2 协议与网络端点自适应技术债
- **非标准 WebDAV 实现差异**:
  - 少数自建轻量 WebDAV（如部分 Go/Rust 嵌入式服务）对 RFC 4918 的 Depth 1 查询首项行为不一致（部分不返回查询根自身，部分返回裸相对路径），虽然已有 `selfPatterns` 双重拦截，但面对小众部署需持续监控。
  - RFC 4331 配额响应中，部分私有云服务直接不返回 `quota-available-bytes` 或返回特殊负值标识（如 Nextcloud `-3` 表示无上限）。解析器已做优雅降级（`isUnlimited = true`），严防阻断看板。

### 2.3 硬件与运行期流量统计兼容性技术债
- **TrafficStats 原生计数器硬件差异**:
  - 部分深度定制 Android ROM 或老旧机型内核可能对 `TrafficStats.getUidRxBytes(uid)` 返回 `-1`（UNSUPPORTED）。
  - JVM 单元测试宿主缺少 Android 运行时桩，直接调用抛出 `RuntimeException`。
  - 系统软重启或飞行模式重置时可能出现计数器回退（Delta < 0）。

---

## 3. 明确风险防线清单 (Risk Defense Lines)

为确保工程 100% 稳定运行、零崩溃、零退化，建立如下六大纵深防御阵线：

| 防线编号 | 防线名称 | 风险场景与后果 | 防护机制与工程实现 | 验证用例 |
| :--- | :--- | :--- | :--- | :--- |
| **DEF-01** | **空密码 Fail-Fast 防爆破防线** | 用户未填密码或首次安装，后台发起并发探测，触发 NAS 端的 Fail2Ban 策略导致客户端公网 IP 被封禁 | 在 `NasViewModel` 初始化与所有网络拉取入口前置拦截空密码，直接置为离线与友好提示，0 次网络开销 | `SystemReliabilityTest.testEmptyPasswordFailFastGuard`<br>`V106UpgradeVerificationTest.testDashboardState_StateMachineTransitions` |
| **DEF-02** | **自适应端点分级回退防线** | 用户配置各类反代挂载、通用 WebDAV 或误粘贴完整路径导致 404/405 | 1. `hasCustomPath` 精准判定自定义前缀；<br>2. 裸域名自动补齐 Nextcloud 标准路由；<br>3. 误粘贴路径智能剥离；<br>4. 支持 `customRootPrefix` 动态覆盖；<br>5. 井号转义 `%23`、空格转义 `%20` | `WebDavClientTest.testCleanBaseUrl`<br>`V106UpgradeVerificationTest.testAdaptiveEndpoint_*` (共 6 个专项测试) |
| **DEF-03** | **网速统计模式切换与健壮降级防线** | 离线时幽灵网速误导用户；特定设备或 JVM 测试中 TrafficStats 崩溃 | 1. 离线状态（`!isConnected`）强制清零为 `0 B/s`；<br>2. `getTrafficBytes()` 全包裹 try-catch；<br>3. Delta 差值负数保护；<br>4. formatSpeed 全频谱与负数保护 | `V106UpgradeVerificationTest.testTrafficSpeed_*`<br>`TrafficStatsProbeTest.testDefensiveTrafficSampling` |
| **DEF-04** | **零后台驻留架构与单向同步防线** | 违规引入后台常驻服务导致电池耗尽、被应用商店下架；误删远端重要数据 | 1. `AndroidManifest.xml` 白盒断言 0 Service、0 Receiver、0 保活权限；<br>2. `SyncManager` 完全绑定前台协程生命周期，协作式取消；<br>3. 单向 PUT 备份，严禁包含任何远端 DELETE 指令 | `V106UpgradeVerificationTest.testKeepAlivePolicy_ManifestZeroBackgroundResidentEnforcement`<br>`V106UpgradeVerificationTest.testKeepAlivePolicy_SyncManagerForegroundLifecycleAndSafetyRules` |
| **DEF-05** | **RFC 4918 递归套娃与 XXE 防护防线** | Depth 1 解析包含自身导致目录套娃；恶意 XML 实体注入攻击 | 1. 忽略 Multi-Status 首个自身项；<br>2. `selfPatterns` 集合比对兜底过滤；<br>3. XML 解析器禁用 DOCTYPE 声明（disallow-doctype-decl）；<br>4. 配额解析异常降级无限配额 | `WebDavClientTest.testParseFolderXmlExcludesSelfFirstItem`<br>`WebDavClientTest.testParseFolderXmlSelfPatternDualProtection`<br>`V106UpgradeVerificationTest.testDashboardState_StorageQuotaModelVariants` |
| **DEF-06** | **全自动化单元测试防护网** | 代码迭代改动引发回归故障 | 46 个自动化单元测试构筑全网格防护网，涵盖边界算法、状态机、白盒架构规则，确保 CI/CD 100% 编译通过且全绿通过 | `app/src/test/java/com/taolesi/nas/` 全量执行 (46/46 PASS) |

---

## 4. 后续版本 (v1.0.7+) 建议与改进规划

1. **Gradle DSL 现代化**:
   将 `kotlinOptions` 迁移至 `compilerOptions`，消除 Gradle 构建警告。
2. **端点探活性能优化**:
   在网络探活时引入 OkHttp 并发多路探测（通用根路径 `/` 与 Nextcloud 路径并行探测，谁快用谁并记住选择），进一步将冷启动探测延迟降低 50%。
3. **分卷压缩包与超大文件直开体验**:
   增加分段预读取与本地 LRU 缓存进度指示，增强离线访问体验。
