# 剪贴板警察 ClipboardPolice

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

push 到 `main` → GitHub Actions 构建 + 发版；任何分支的 push 只做构建校验（便于先验证再合并）。
版本号存放在仓库根 `version.properties`（`versionCode` / `versionName`），规则 `a.b.c`：`c` 超 9 进 `b`，`b` 超 9 进 `a`。
CI 若发现 `v<versionName>` tag 已存在，则先算出下一个版本、用它构建（`-PversionCodeOverride/-PversionNameOverride`），
构建成功后再提交 `chore(release): vX [skip ci]` 并创建 tag/Release；APK 命名 `<versionName>-ClipboardPolice.apk`。
构建失败时 CI 会把日志推到 `ci-log` 分支（`ci/build-<run>.log`），便于排查。

本地构建（可选，需要 JDK 17 + Android SDK 35）：

    gradle assembleRelease

### 签名（Secrets，绝不入库）

    keytool -genkeypair -v -keystore release.jks -alias clipboardpolice \
      -keyalg RSA -keysize 2048 -validity 36500 \
      -storepass <storePassword> -keypass <keyPassword> \
      -dname "CN=ClipboardPolice"
    base64 -w0 release.jks > release.jks.b64

仓库 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
未配置时 CI 退回 debug 签名（APK 仍可安装，仅用于自测）；每次 job 结束都会删除解码出的 keystore。

## 使用

1. KernelSU 管理器里为本应用**手动开启 Root 开关**（KSU 不会弹授权框）。
2. LSPosed 启用本模块，作用域只勾「系统框架」，重启设备。
3. 打开应用 → 首次自动检测 Root 并刷新列表。

设置项（写入 `Settings.Global`，Hook 用 `Settings.Global.getLong` 读，读不到用默认值）：

| 键 | 可选值 | 默认 |
|---|---|---|
| `clipboard_watch_broadcast_throttle_ms` | 0/500/1000/2000/5000 | 1000 |
| `clipboard_watch_read_enabled` | 0/1 | 0 |
| `clipboard_watch_read_throttle_ms` | 0/500/1000/2000/5000 | 1000 |
| `clipboard_watch_max_records` | 1000/5000/10000/20000/50000 | 10000 |

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
