# 更新日志

## [0.6.0] - 2026-11-05

### 变更
- 更新功能**完全照搬 android-updater v1.5.1**：直接内嵌 `updater` + `updater-compose` 源码，设置页「检查更新」只是调用 `showUpdateDialog(activity)`
- 弹窗、下载进度、安装、PAT 七步教程、「访问令牌」入口、401/403/404 错误分类全部由库负责
- 删除自研的 UpdateChecker / ChangelogFetcher / UpdatePrefs / 更新相关 UI 状态与弹窗
- 设置页只保留「当前版本 x.y.z」一行文本

## [0.5.9] - 2026-11-05

### 新增/修复
- PAT 弹窗内加入**一步一步获取教程**（Generate new token → classic → 勾 repo → 复制 ghp_ 开头），并提供「打开 GitHub Token 页面」直达按钮
- 新增「限流」错误类型：403 且有 `X-RateLimit-Remaining: 0` 或响应体含 rate limit 时，直接提示「匿名 60 次/小时，填 PAT 提到 5000 次/小时」，不再笼统报「私有仓库」
- 401 明确提示「PAT 无效或已过期」，404 仍是「仓库不存在、为私有或暂无 Release」

## [0.5.8] - 2026-11-05

### 新增
- 设置页新增「GitHub 访问令牌（PAT）」：私有仓库检查更新/下载 Release 资产必须带 PAT
- 令牌保存在应用私有 SharedPreferences（不走 Settings.Global），界面只显示掩码；支持一键清除
- `Updater.init` 会带上该令牌，出错诊断请求同样带 `Authorization`，私有仓库也能正确区分「无更新 / 401 令牌无效 / 404 仓库不存在」

## [0.5.7] - 2026-11-05

### 变更
- 检查更新继续使用 [android-updater](https://github.com/Tianxiaodudou/android-updater) v1.2.1，但改为**内嵌同源代码**（`com.tianxiaodudou.updater`，Apache-2.0）：
  该库发布在私有 GitHub Packages，CI 里拉不到包（Could not find com.tianxiaodudou:updater:1.2.1），内嵌可去掉私有仓库凭据依赖
- 依赖改为直接使用 okhttp 4.12.0；FileProvider 换成库内置的 `.updater.files`，删除本地 file_paths
- 检查/下载/安装逻辑与 v1.2.1 一致；更新日志优先 Release 正文，为空时四路兜底

## [0.5.6] - 2026-11-05

### 变更
- 检查更新接入自研 [android-updater](https://github.com/Tianxiaodudou/android-updater)（`com.tianxiaodudou:updater`）：版本检查、APK 下载（带进度）、安装全部交给库
- 弹窗仍为 Miuix 风格：更新日志优先用 Release 正文，为空时再用四路兜底（jsDelivr / raw / atom）
- 库返回 null 时用原有诊断逻辑区分「已是最新 / 网络问题 / 仓库问题」
- 删除自研的 UpdateDownloader（下载与安装改由库负责）
- CI 增加 `packages: read` 权限与 GitHub Packages 凭据注入（库发布在私有 Packages）

## [0.5.5] - 2026-11-05

### 优化
- 整体布局更紧凑：页面内边距 8dp、块间距 6dp、卡片内边距 8dp（统一走 AppDims 常量）
- 顶栏改为自定义紧凑样式：标题单行省略 + 底部 1dp 分隔线，标题与操作按钮不再互相压盖
- 卡片去掉阴影（避免相邻卡片视觉压盖），改为 1dp 描边 + 更深一档的页面底色，提高对比度
- 列表行的包名/时间、设置行的数值统一单行省略，长文本不再挤占相邻控件
- 条件编辑卡：按钮行与输入框之间留 6dp，删除按钮文案缩短为「删除」

## [0.5.4] - 2026-11-05

### 修复
- 按钮圆角收敛到 8dp，配合四边 4dp 内边距，按钮大小刚好包住文字，不再是虚胖的胶囊

## [0.5.3] - 2026-11-05

### 优化
- 提高控件与背景的对比度：页面底色比卡片深一档（浅色 #E9ECF0 / 深色 #15161A），卡片统一加 1dp 描边 + 轻阴影
- 所有卡片改走 AppCard 统一封装，浅色/深色模式分别给描边颜色

## [0.5.2] - 2026-11-05

### 修复
- 所有 Miuix 按钮统一收紧：内留白改为 4dp（insideMargin），最小高度 28dp、最小宽度 0 —— 按钮不再虚胖
- 覆盖范围：顶部栏操作、弹窗按钮、列表编辑/删除、保存、检查更新等全部按钮

## [0.5.1] - 2026-11-05

### 优化
- 开启 R8 代码混淆 + 资源压缩（minifyEnabled / shrinkResources），APK 体积明显下降
- 保留规则：Hook 入口（java_init.list 反射加载）、libsu 反射实现类；libxposed API 为 compileOnly，仅忽略缺失告警

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
