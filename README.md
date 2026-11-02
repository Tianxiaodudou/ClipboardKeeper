# 剪贴板管家 ClipboardKeeper

LSPosed 模块（libxposed API 102）：记录系统剪贴板的**写入**与**读取**事件，APK 侧提供列表、复制、设置与更新检查。
运行环境：KernelSU + ZygiskNext + LSPosed，Android 14~16。

## 结构

    app/src/main/java/com/tianxiaodudou/clipboardpolice/
    ├── hook/HookEntry.java          # 只做 Hook + 临时日志 + 节流（注入 system_server）
    └── ui/
        ├── MainActivity.java        # 列表 + 刷新/清空/设置
        ├── ClipAdapter.java         # 卡片条目：折叠/展开、长按复制
        ├── ClipUpdateReceiver.java  # 动态注册 CLIP_UPDATED
        ├── UpdateChecker.java       # GitHub Releases + DownloadManager
        ├── SettingsActivity.java    # 4 个设置项（写 Settings.Global）
        ├── ClipSync.java            # 刷新流程：读临时文件 → 解析 → 入库 → 裁剪 → 清空
        ├── ClipDatabase.java        # SQLite / clipboard.db
        ├── ClipRecord.java
        └── RootShell.java           # 全部 su 调用
    app/src/main/resources/META-INF/xposed/
    ├── java_init.list               # HookEntry 全限定名
    ├── scope.list                   # system
    └── module.prop                  # minApiVersion=101, targetApiVersion=102, staticScope=true
    app/src/debug/                   # adb 调试接口，release 不编译

硬约束：`hook/` 包不引用 `ui/`、R 资源或 App SharedPreferences；`io.github.libxposed.*` 只在 `hook/` 内出现；
`io.github.libxposed:api` 必须 `compileOnly`，否则 Hook 静默失效。

## 构建与发版（全部云端）

**打 tag 触发**：`git tag v0.2.0 && git push origin v0.2.0` → 构建、签名、发版（需要验证可用 `workflow_dispatch` 手动跑）。
发版前必须在 `CHANGELOG.md` 写好该版本段落，CI 会用它作为 Release 正文；缺段落会直接失败。
Release 创建成功后自动删除旧 Release，只保留最新一个。
版本号存放在仓库根 `version.properties`（`versionCode` / `versionName`），规则 `a.b.c`：`c` 超 9 进 `b`，`b` 超 9 进 `a`。
CI 用 `GITHUB_TOKEN` 查 `v<versionName>` 对应的 **Release** 是否存在（不看 git tag，删 tag 也不影响）：
已发布则先算出下一个版本再构建（`-PversionCodeOverride/-PversionNameOverride`），查询失败时同样递增，保证版本单调不重复；
构建成功后再提交 `chore(release): vX [skip ci]` 并创建 tag/Release；APK 命名 `<versionName>-ClipboardKeeper.apk`。
Release 创建成功后，workflow 会自动删掉仓库里其它旧 Release（只保留刚发的这一个，tag 保留不动）。
每次构建（成功或失败）都会把 `ci/status.txt`（run/branch/version/status/各步骤 outcome）和 `ci/build.log`
推到 `ci-log` 分支，便于在没有 Actions 权限时查结果。提交信息含 `[skip ci]` 时 workflow 直接跳过。

> 首次发版（v0.1.0）若尚未配置签名 secrets，APK 为 debug 签名，仅用于自测；配置密钥后请重新发版，
> 否则新旧 APK 签名不同，安装新版本需先卸载。

本地构建（可选，需要 JDK 17 + Android SDK 35）：

    gradle assembleRelease

### 签名（GitHub Secrets，仓库内不保存密钥）

release **只使用固定密钥签名**，密钥只存在于 GitHub Actions Secrets 里：

- `KEYSTORE_BASE64`：keystore 文件的 Base64（单行）
- `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
- CI 在 job 内解码到 `$RUNNER_TEMP/signing/`，类型按魔数自动识别（JKS `feedfeed` / 其它按 PKCS12），job 结束（含失败）立即删除
- 缺 `KEYSTORE_BASE64` 直接 `::error::` 失败，不回退 debug 签名
- 每次构建用 `apksigner verify --print-certs` 校验，证书 SHA-256 写进 `ci-log` 分支

生成密钥（一次性，产物别入库）：

    openssl req -x509 -newkey rsa:4096 -nodes -keyout key.pem -out cert.pem \
      -days 36500 -subj "/CN=ClipboardKeeper/O=ClipboardKeeper"
    openssl pkcs12 -export -in cert.pem -inkey key.pem -name clipboardpolice \
      -out release.p12 -passout pass:<密码>
    base64 -w0 release.p12 > keystore.b64

把 `keystore.b64` 的单行内容填进 `KEYSTORE_BASE64`；本地构建同样通过环境变量传入。

## 使用

1. KernelSU 管理器里为本应用**手动开启 Root 开关**（KSU 不会弹授权框）。
2. LSPosed 启用本模块，作用域只勾「系统框架」，重启设备。
3. 打开应用 → 首次自动检测 Root 并刷新列表。

设置项（写入 `Settings.Global`，Hook 用 `Settings.Global.getLong` 读，读不到用默认值）；
界面里「写入广播节流」只在启用写入监测时显示，「读取监测节流」只在启用读取监测时显示：

| 键 | 可选值 | 默认 |
|---|---|---|
| `clipboard_watch_write_enabled` | 0/1 | 1 |
| `clipboard_watch_broadcast_throttle_ms` | 0/500/1000/2000/5000 | 1000 |
| `clipboard_watch_read_enabled` | 0/1 | 0 |
| `clipboard_watch_read_throttle_ms` | 0/500/1000/2000/5000 | 1000 |
| `clipboard_watch_max_records` | 1000/5000/10000/20000/50000 | 10000 |
| `clipboard_watch_filter_rules` | JSON 字符串（由界面维护） | 空 |

## 过滤规则

界面：设置 → 过滤规则，可新建多条规则，每条独立启用/停用。

- 条件之间可选「满足全部（与）」或「满足任一（或）」，整条规则可再取反（非）
- 条件类型：包含、不包含、开头是、开头不是、结尾是、结尾不是、完全等于、不等于、正则匹配、正则不匹配
- 命中时 Hook 直接短路这次写入：内容不会进入剪贴板，记录以 [拦] 入库
- 规则以 JSON 存在 `Settings.Global` 的 `clipboard_watch_filter_rules`，Hook 侧按原始串变化重新解析

## 检查更新

- 查 GitHub Releases 最新版，弹窗内含**更新日志**（四路依次兜底：Release 正文 → jsDelivr 的 `CHANGELOG.md@vX.Y.Z` → raw.githubusercontent.com 同名文件 → `releases.atom` 正文）
- 点「下载更新」后走应用内置下载器，弹窗内显示百分比进度，下载完成直接拉起系统安装界面（需允许「安装未知应用」）

## adb 调试接口（仅 debug 构建，只接受 uid 0/2000）

    adb shell am broadcast -a com.tianxiaodudou.clipboardpolice.DEBUG.REFRESH
    adb shell am broadcast -a com.tianxiaodudou.clipboardpolice.DEBUG.CLEAR
    adb shell am broadcast -a com.tianxiaodudou.clipboardpolice.DEBUG.EXPORT   # /sdcard/Download/clipboard_export.txt

## 验证要点

1. `adb shell cmd clipboard set-text "TEST_MARKER_12345"` → `tail -5 /data/local/tmp/clipboard_watch.tmp` 有四列制表符、操作=`write`。
2. `DEBUG.REFRESH` 后数据库含该记录（或 `EXPORT` 后比对），临时文件已清空。
3. 纯写入不产生 `read` 记录；模块内长按复制会以模块包名被记录。
4. 节流：`settings put global clipboard_watch_broadcast_throttle_ms 5000` 后 1 秒内多次同包名写入 → 多行日志、一次广播。
5. 上限：设 1000 填 1200 → 留 1000；连续插 200 条 → 裁剪只在第 100、200 条后触发。
6. 重启后记录仍在；单条 >100KB 被截断并标记。

## 已知事项

- **临时文件路径回退**：Hook 优先写 `/data/local/tmp/clipboard_watch.tmp`；若 DAC/SELinux 拒绝（system_server 写 shell 数据目录可能被拒），自动回退 `/data/system/clipboard_watch.tmp`。APK 刷新时两个文件都会读，并按 `su` 预创建 + `chmod 666` 尽量保证主路径可写。
- **检查更新**：调用 GitHub API 未带 token，仓库为私有或暂无 Release 时返回 404，界面会提示「仓库可能为私有或暂无 Release」。要让应用内更新可用，需把仓库设为公开。
- **动态广播**：`CLIP_UPDATED` 由 system_server 发出，App 以 `RECEIVER_EXPORTED` 注册（非同 uid 必须导出），因此其它应用也能触发一次刷新——它只会读取 root 临时文件，风险可控。
