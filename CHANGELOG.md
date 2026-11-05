# 更新日志

## [0.5.0] - 2026-11-05

### 重构
- UI 全面重写：XML 布局 + AppCompat/RecyclerView 换成 **Jetpack Compose**，组件全部换成 **Miuix**（compose-miuix-ui）
- 覆盖范围：主页列表、设置页、过滤规则列表、规则编辑、检查更新弹窗、清空确认弹窗
- 工具链随之上移：Gradle 9.8.2 / AGP 9.5.0 / Kotlin 2.4.21 / compileSdk 37（Miuix 的 aar 要求 compileSdk 37）
- 业务逻辑零改动：Hook、数据库、刷新入库、过滤匹配、更新检查、下载安装仍复用原实现

## [0.4.2] - 2026-11-02

### 变更
- `Settings.Global` 键前缀由 `clipboard_watch_*` 改为 `clipboard_keeper_*`，共 6 个键：
  `write_enabled`、`broadcast_throttle_ms`、`read_enabled`、`read_throttle_ms`、`max_records`、`filter_rules`

### 升级须知
- 旧键值不会迁移：升级后设置回到默认（写入监测开、读取监测关、节流 1000ms、上限 10000）
- 过滤规则存在 `clipboard_keeper_filter_rules` 里，需要在应用内重新创建

## [0.4.1] - 2026-11-02

### 变更（重要）
- 应用包名改为 `com.tianxiaodudou.clipboardkeeper`，与更名一致
- 内部文件名同步改为 `clipboard_keeper.db`、`clipboard_keeper.tmp`（导出文件 `clipboard_keeper_export.txt`）

### 升级须知
- 包名变了，这在系统里是**另一个应用**：旧版（`...clipboardpolice`）不会自动覆盖，建议先卸载旧版
- LSPosed 里需要给新包名重新启用模块（作用域仍勾「系统框架」）并重启
- KernelSU 里需要给新应用重新打开 Root 开关
- 旧的剪贴板记录不会迁移（数据库文件名与目录都变了），如有需要请先用旧版导出

## [0.4.0] - 2026-11-02

### 更名
- 应用正式更名为「剪贴板管家」（英文名 ClipboardKeeper），GitHub 仓库同步改名
- 应用内所有显示文案、日志 TAG、主题名、APK 文件名一并更新
- 包名保持不变（`com.tianxiaodudou.clipboardkeeper`），避免 LSPosed 作用域与 KernelSU 授权失效
- 安装包文件名改为 `<versionName>-ClipboardKeeper.apk`

## [0.3.0] - 2026-11-02

### 变更（重要）
- **更换 release 签名密钥**（旧密钥曾以文件形式提交进仓库，历史提交可查，已作废归档）
- 新密钥证书 SHA-256：`18:E6:0A:A9:0B:F6:A3:49:BD:FF:43:2E:FA:61:E5:9D:FB:EF:ED:C3:8F:87:13:87:26:01:8F:33:0B:6E:18:C1`
- 签名的应用身份变了：装有 0.2.2 及更早版本的设备**必须先卸载旧版**再安装 0.3.0，否则系统会拒绝安装（签名不一致）
- 密钥仍只通过 GitHub Secrets 注入，仓库与 CI 产物中不含密钥文件

## [0.2.2] - 2026-11-02

### 新增
- 设置页「检查更新」按钮下方常驻显示当前版本与最近一次检查结果：已是最新 / 发现新版本 x.y.z / 本地版本高于线上

### 改进
- 检查更新按结果分类：本地与线上一致、线上有更高版本、本地更高（测试版）
- 检查失败区分原因：网络问题（DNS 解析失败、连接超时、无法建连、TLS 失败）与仓库问题（404 私有或暂无 Release、401/403 限流、5xx 服务端异常）

## [0.2.1] - 2026-11-02

### 变更
- 发版签名改用 GitHub Secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD），仓库内不再保存任何签名密钥文件
- CI 解码密钥后写到 job 临时目录，job 结束立即删除；缺失 secret 直接失败，不回退 debug 签名

## [0.2.0] - 2026-11-02

### 新增
- 过滤规则：写入剪贴板前按规则匹配，命中直接拦截（口令、验证码、推广文案等不再进入剪贴板）
  - 每条规则可单独启用/停用，支持「满足全部条件 / 满足任一条件」+ 整体反向（非）
  - 条件类型：包含、不包含、开头是、开头不是、结尾是、结尾不是、完全等于、不等于、正则匹配、正则不匹配
  - 命中记录以 [拦] 标记入库，可查看被拦截的内容与命中的规则
- 检查更新：弹出带更新日志的窗口，内置下载器显示实时进度，下载完成自动拉起安装界面
- 更新日志按四路来源依次兜底获取：GitHub Release 正文 → jsDelivr 的 CHANGELOG.md（按 tag）→ raw.githubusercontent.com → releases.atom

### 改进
- 设置页「写入广播节流 / 读取监测节流」随对应的监测开关联动显隐
- 设置页底部新增完整的运作链路说明，讲清每个设置生效的位置
- Toast 全部改为中文人性化文案
- 发版流程：打 tag 触发构建，自动用 CHANGELOG 对应版本段落作为 Release 正文，并在发版后删除旧 Release（只保留最新）

## [0.1.5] - 2026-10-26

- Toast 全部中文化，设置项用中文名与中文值提示

## [0.1.4] - 2026-10-26

- 适配状态栏/导航栏（edge-to-edge）
- 新增「启用写入监测」开关，节流项随监测开关联动显隐
- 设置页补充运作链路说明
- 发版后自动清理旧 Release

## [0.1.3] - 2026-10-26

- 修复读取监测未安装：同时 Hook ClipboardService 与 ClipboardService$ClipboardImpl

## [0.1.2] - 2026-10-26

- 首次发布：写入/读取监测、列表、长按复制、设置项、检查更新
