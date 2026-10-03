# NAS App v1.0.5 体验与连接全方位重构 Plan

## 1. 业务目标与用户痛点对齐
- **首页传输卡片**：彻底移除 HTTP 206、64MB 零占机、开屏按需、退出即销毁等内部底层术语；改为以醒目大字展示 APP 原生实时下行下载速度（`downloadSpeedText`）与上行上传速度（`uploadSpeedText`），保留延迟测速。
- **相册顶栏**：彻底剔除顶栏右侧的“一栏/两栏排布”胶囊和“已连接”胶囊，页面顶部重回极致纯净。
- **文件中心**：彻底清除任何关于 Windows“T 盘”的混淆描述，统一按照云端标准路径规范（`/` 与 `/ 路径`）展示。
- **设置页面与连接**：
  - 剔除右上角“零后台驻留 · 纯按需”多余胶囊。
  - 核心原理梳理与解释：解析 WebDAV 连接机制，支持通用根路径与 Nextcloud 路径自适应，消除强制拼路径导致的 404/无法连接。
  - 允许用户自由编辑、自由保存任何服务器 URL，彻底破除“内置锁定”感。

## 2. 接口与数据契约冻结 (Interface Freeze)
- `DashboardState`:
  - 新增 `downloadSpeedText: String = "0 B/s"`
  - 新增 `uploadSpeedText: String = "0 B/s"`
- `WebDavClient`:
  - 引入通用端点智能识别机制，优先使用用户填写的完整 URL/根路径，并支持自适应探测。
- `NasViewModel`:
  - 启动 UID 级别 `TrafficStats` 周期采样（1000ms），免去任何额外网络开销，精确计算 APP 实时进出速率。

## 3. 波次编排流水线 (Stage DAG)
- **Stage 1 (审查初审门禁)**:
  - 派发 `pi_task(role='reviewer')`，对重构 Plan、修改边界与变更契约进行实战审查与防死锁过滤。
- **Stage 2 (正交并发执行 - pi_swarm)**:
  - Worker 1: 核心网络层与 ViewModel (`WebDavClient.kt`, `NasViewModel.kt`) 速率采样与自适应路径。
  - Worker 2: 首页卡片与相册重构 (`DashboardScreen.kt`, `PhotosScreen.kt`) 速率呈现与胶囊精简。
  - Worker 3: 文件中心与设置弹窗重构 (`FilesScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt`) 消除T盘与自由保存连接。
- **Stage 3 (门禁与测试构建)**:
  - 运行单元测试 `gradlew testDebugUnitTest`；
  - 编译全新版本 APK（v1.0.5，versionCode 5）。
- **Stage 4 (终审与交付)**:
  - 派发 `pi_task(role='reviewer')` 终审；
  - 投递 APK 至微信与更新 Wiki。
