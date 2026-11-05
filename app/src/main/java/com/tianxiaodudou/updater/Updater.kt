package com.tianxiaodudou.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 可复用的「检查更新」组件（Apache-2.0）。
 *
 * 典型用法：
 *   Updater.init(context, Updater.Config(owner = "you", repo = "your-app"))
 *   val result = Updater.checkDetailed()             // Update / UpToDate / Failure
 *   val apk = Updater.download(context, release) { p -> }// 进度 0..100
 *   Updater.install(context, apk)                    // 唤起系统安装器
 *
 * 依赖 GitHub Releases：版本号取 tag（可去 v 前缀），APK 取该 release 里第一个 .apk 资产。
 * 私有仓库 / 限流需要 PAT：可以用 [saveToken] 保存，或换 updater-compose 里的
 * showUpdateDialog()，它自带「一步一步获取 PAT」的二级弹窗。
 */
object Updater {

    data class Config(
        val owner: String,
        val repo: String,
        val currentVersion: String,
        /** 私有仓库需要：repo 权限的 PAT；公开仓库可留空 */
        val token: String? = null,
        /** 自定义 API 基址（企业版/镜像） */
        val apiBase: String = "https://api.github.com",
        /** 是否把 v1.2.3 与 1.2.3 视为同一版本 */
        val stripPrefixV: Boolean = true,
    ) {
        internal fun readableToken(): String? = token?.takeIf { it.isNotBlank() }
    }

    data class Release(
        val version: String,
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long,
        val publishedAt: String,
    )

    /**
     * 检查结果。`check()` 仍然只返回 Release?（兼容旧代码）；
     * 需要区分「已是最新 / 限流 / 私有仓库 / 网络错误」时用 [checkDetailed]。
     */
    sealed interface CheckResult {
        data class Update(val release: Release) : CheckResult
        data class UpToDate(val current: String, val latest: String) : CheckResult

        data class Failure(
            val reason: FailureReason,
            val httpCode: Int = 0,
            val detail: String = "",
        ) : CheckResult {
            /** 补一个 PAT 通常就能解决（私有仓库、限流、权限不足、404 掩码）。 */
            val needsToken: Boolean
                get() = reason == FailureReason.UNAUTHORIZED ||
                        reason == FailureReason.RATE_LIMITED ||
                        reason == FailureReason.FORBIDDEN ||
                        reason == FailureReason.NOT_FOUND

            /** 可直接展示给用户的中文说明；[hasToken] 决定是否提示「去填令牌」。 */
            fun message(hasToken: Boolean): String = when (reason) {
                FailureReason.UNAUTHORIZED ->
                    "访问令牌无效或已过期，请重新填写。"

                FailureReason.RATE_LIMITED ->
                    if (hasToken) "GitHub API 配额已用尽，请稍后再试。"
                    else "匿名访问 GitHub API 已被限流（每小时 60 次）。填写访问令牌可提升到 5000 次/小时。"

                FailureReason.FORBIDDEN ->
                    "令牌权限不足：经典 PAT 需要勾选 repo；细粒度 PAT 需要 Contents 的 Read-only 权限。"

                FailureReason.NOT_FOUND ->
                    if (hasToken) "仓库或 Release 不存在，或令牌没有该仓库的访问权限。"
                    else "仓库或 Release 不存在。如果这是私有仓库，需要先填写访问令牌。"

                FailureReason.NETWORK ->
                    "连不上 api.github.com，请检查网络或代理后重试。"

                FailureReason.NO_APK ->
                    "最新 Release 里没有 .apk 安装包，请先上传 APK 资产。"

                FailureReason.OTHER ->
                    "出了点问题，请稍后重试。"
            }
        }

        enum class FailureReason {
            /** 401：PAT 无效或已过期 */
            UNAUTHORIZED,

            /** 403/429：GitHub API 配额耗尽（匿名 60 次/小时） */
            RATE_LIMITED,

            /** 403：PAT 权限不足（没有 repo / Contents 权限） */
            FORBIDDEN,

            /** 404：仓库不存在，或私有仓库未带令牌（GitHub 用 404 掩码） */
            NOT_FOUND,

            /** 连不上 api.github.com */
            NETWORK,

            /** Release 里没有 .apk 资产 */
            NO_APK,

            OTHER,
        }
    }

    private const val PREFS = "com.tianxiaodudou.updater.prefs"
    private const val KEY_TOKEN = "github_pat"
    private const val TOKEN_PAGE = "https://github.com/settings/tokens"
    private const val PAT_STEPS = "获取步骤：\n" +
            "1. 点「打开令牌页」并登录 GitHub\n" +
            "2. Generate new token → Generate new token (classic)\n" +
            "3. Note 随便写；Expiration 选 90 days\n" +
            "4. 勾选 repo（私有仓库必须；公开仓库也建议勾）\n" +
            "5. 拉到页面最底部，点 Generate token\n" +
            "6. 复制 ghp_ 或 github_pat_ 开头的令牌\n" +
            "7. 粘贴到下面 → 保存并重试\n\n" +
            "匿名访问每小时只有 60 次，填了令牌是 5000 次/小时。"

    private var cfg: Config? = null
    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 初始化；重复调用以最后一次为准。 */
    fun init(config: Config) {
        cfg = config
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 初始化，并自动读取上次通过弹窗保存的 PAT（[saveToken]）。
     * 显式传入的 `Config.token` 优先；公开仓库取不到已保存令牌时照常匿名访问。
     */
    fun init(context: Context, config: Config) {
        val stored = storedToken(context)
        init(if (config.readableToken() == null && stored.isNotBlank()) config.copy(token = stored) else config)
    }

    /** 读取本机保存的 PAT；没有则返回空串。 */
    fun storedToken(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "")
            .orEmpty()

    /** 保存 PAT（应用私有 SharedPreferences），并立即用于后续请求。 */
    fun saveToken(context: Context, token: String) {
        val value = token.trim()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, value)
            .apply()
        cfg = cfg?.copy(token = value.ifBlank { null })
    }

    /** 清除已保存的 PAT，之后的请求回到匿名访问。 */
    fun clearToken(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .apply()
        cfg = cfg?.copy(token = null)
    }

    /** 是否已有可用令牌（Config 里的或本机保存的）。 */
    fun hasToken(context: Context): Boolean =
        currentToken(context).isNotBlank()

    /** 当前生效的令牌：Config 里的优先，其次本机保存的；没有则空串。 */
    fun currentToken(context: Context): String =
        cfg?.readableToken() ?: storedToken(context)

    /** 打码显示，例如 `ghp_…a1b2`；用于界面提示。 */
    fun maskToken(token: String): String = when {
        token.isBlank() -> ""
        token.length <= 9 -> "••••••"
        else -> token.take(4) + "…" + token.takeLast(4)
    }

    fun isReady(): Boolean = cfg != null

    /** 检查最新 release；无更新或失败返回 null（不抛异常）。 */
    suspend fun check(currentVersion: String? = null): Release? =
        (checkDetailed(currentVersion) as? CheckResult.Update)?.release

    /** 检查最新 release，并区分失败原因，供「填写 PAT」等交互使用。 */
    suspend fun checkDetailed(currentVersion: String? = null): CheckResult =
        withContext(Dispatchers.IO) {
            val c = cfg
                ?: return@withContext CheckResult.Failure(
                    CheckResult.FailureReason.OTHER,
                    detail = "Updater.init() 未调用",
                )
            val version = currentVersion ?: c.currentVersion
            val json = try {
                fetchLatestRelease(c)
            } catch (e: HttpStatusException) {
                return@withContext CheckResult.Failure(e.reason, e.code, e.message.orEmpty())
            } catch (e: IOException) {
                return@withContext CheckResult.Failure(
                    CheckResult.FailureReason.NETWORK,
                    detail = e.message.orEmpty(),
                )
            } catch (e: Exception) {
                return@withContext CheckResult.Failure(
                    CheckResult.FailureReason.OTHER,
                    detail = e.message.orEmpty(),
                )
            }

            val tag = json.optString("tag_name")
            if (tag.isEmpty()) {
                return@withContext CheckResult.Failure(
                    CheckResult.FailureReason.OTHER,
                    detail = "响应里没有 tag_name",
                )
            }

            val latest = if (c.stripPrefixV) tag.removePrefix("v").removePrefix("V") else tag
            val current = if (c.stripPrefixV) version.removePrefix("v").removePrefix("V") else version
            if (!isNewer(latest, current)) {
                return@withContext CheckResult.UpToDate(current = current, latest = latest)
            }

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (!name.endsWith(".apk", true)) continue
                    apkUrl = a.optString("browser_download_url")
                    apkName = name
                    size = a.optLong("size", 0L)
                    break
                }
            }
            if (apkUrl.isEmpty()) {
                return@withContext CheckResult.Failure(
                    CheckResult.FailureReason.NO_APK,
                    detail = "Release $tag 里没有 .apk 资产",
                )
            }

            CheckResult.Update(
                Release(
                    version = latest,
                    tag = tag,
                    notes = json.optString("body").trim(),
                    apkUrl = apkUrl,
                    apkName = apkName,
                    sizeBytes = size,
                    publishedAt = json.optString("published_at"),
                ),
            )
        }

    /** 下载 APK 到 cacheDir/updates；失败返回 null 并删除半成品。 */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val c = cfg ?: return@withContext null
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name != release.apkName) it.delete() }
            val out = File(dir, release.apkName)
            if (out.exists()) out.delete()

            val req = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", "android-updater")
                .apply { c.readableToken()?.let { header("Authorization", "token " + it) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val total = if (body.contentLength() > 0) body.contentLength() else release.sizeBytes
                var read = 0L
                var last = -1
                body.byteStream().use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != last) { last = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                if (out.length() < 1024) { out.delete(); null } else out
            }
        }.getOrNull()
    }

    /**
     * 唤起系统安装器；没有「安装未知应用」权限时先跳授权页并返回 false。
     * 注意：provider authority 固定为 宿主applicationId + ".updater.files"。
     */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".updater.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 清掉已下载的安装包缓存。 */
    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, "updates").deleteRecursively() }
    }

    // ---------------- 内部 ----------------

    private class HttpStatusException(
        val reason: CheckResult.FailureReason,
        val code: Int,
        message: String,
    ) : IOException(message)

    private fun fetchLatestRelease(c: Config): org.json.JSONObject {
        val url = c.apiBase.trimEnd('/') + "/repos/" + c.owner + "/" + c.repo + "/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "android-updater")
            .apply { c.readableToken()?.let { header("Authorization", "token " + it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw httpFailure(resp)
            return org.json.JSONObject(resp.body?.string().orEmpty())
        }
    }

    /** 把 GitHub 的 HTTP 错误翻译成可操作的失败原因。 */
    private fun httpFailure(resp: Response): HttpStatusException {
        val code = resp.code
        val remaining = resp.header("X-RateLimit-Remaining")
        val body = runCatching { resp.peekBody(1024).string() }.getOrDefault("")
        val rateLimited = remaining == "0" ||
                resp.header("Retry-After") != null ||
                body.contains("rate limit", ignoreCase = true)
        val reason = when {
            code == 401 -> CheckResult.FailureReason.UNAUTHORIZED
            code == 403 && rateLimited -> CheckResult.FailureReason.RATE_LIMITED
            code == 403 -> CheckResult.FailureReason.FORBIDDEN
            code == 404 -> CheckResult.FailureReason.NOT_FOUND
            code == 429 -> CheckResult.FailureReason.RATE_LIMITED
            else -> CheckResult.FailureReason.OTHER
        }
        val detail = buildString {
            append("HTTP ").append(code)
            if (remaining != null && reason == CheckResult.FailureReason.RATE_LIMITED) {
                append("，剩余配额 ").append(remaining)
            }
        }
        return HttpStatusException(reason, code, detail)
    }

    /** 语义化比较：latest 是否比 current 新。 */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.split('.', '-', '+').mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    /**
     * 一键式：检查 → 弹窗展示版本说明 → 内置下载器显示进度 → 拉起安装。
     * 只用框架 AlertDialog / ProgressBar，不引额外 UI 依赖，Compose 与 XML 项目都能直接用。
     */
    fun prompt(
        activity: android.app.Activity,
        currentVersion: String? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        scope.launch {
            val result = checkDetailed(currentVersion)
            val info = (result as? CheckResult.Update)?.release
            if (info == null) {
                if (result is CheckResult.Failure) {
                    showFailure(activity, result) { prompt(activity, currentVersion, onResult) }
                }
                onResult(false)
                return@launch
            }

            val dialog = android.app.AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + info.tag)
                .setMessage(info.notes.ifBlank { "有新版本可用，建议立即更新。" })
                .setNegativeButton("以后再说") { d, _ -> d.dismiss(); onResult(false) }
                .setPositiveButton("立即更新", null)
                .create()

            dialog.setOnShowListener {
                val btn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                btn?.setOnClickListener {
                    btn.isEnabled = false
                    val bar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
                    bar.max = 100
                    bar.progress = 0
                    val label = android.widget.TextView(activity).apply {
                        text = progressText(0, info.sizeBytes)
                        setPadding(0, 24, 0, 8)
                    }
                    val box = android.widget.LinearLayout(activity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(64, 40, 64, 16)
                        addView(
                            bar,
                            android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        addView(label)
                    }
                    dialog.setContentView(box)

                    scope.launch {
                        val apk = download(activity, info) { pct ->
                            activity.runOnUiThread {
                                bar.progress = pct
                                label.text = progressText(pct, info.sizeBytes)
                            }
                        }
                        activity.runOnUiThread { dialog.dismiss() }
                        if (apk == null) {
                            onResult(false)
                            return@launch
                        }
                        onResult(install(activity, apk))
                    }
                }
            }
            dialog.show()
        }
    }

    /** 非 Compose 版弹窗里的失败提示：能补令牌的给出「填写令牌」入口。 */
    private fun showFailure(
        activity: android.app.Activity,
        failure: CheckResult.Failure,
        retry: () -> Unit,
    ) {
        val builder = android.app.AlertDialog.Builder(activity)
            .setTitle("检查更新失败")
            .setMessage(failure.message(hasToken(activity)))
            .setNegativeButton("关闭", null)
            .setPositiveButton("重试") { _, _ -> retry() }
        if (failure.needsToken) {
            builder.setNeutralButton("填写令牌") { _, _ -> showPatEditor(activity, retry) }
        }
        builder.show()
    }

    /** 框架 Dialog 版的 PAT 输入（一步步说明 + 粘贴框），不依赖 Compose。 */
    private fun showPatEditor(activity: android.app.Activity, retry: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val input = android.widget.EditText(activity).apply {
            hint = "粘贴 GitHub 访问令牌"
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(currentToken(activity))
            setSelection(text.length)
        }
        val guide = android.widget.TextView(activity).apply {
            text = PAT_STEPS
            textSize = 12f
        }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(guide)
            addView(
                input,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(box) }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("GitHub 访问令牌（PAT）")
            .setView(scroll)
            .setNeutralButton("打开令牌页") { _, _ -> openTokenPage(activity) }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并重试", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val token = input.text.toString().trim()
                if (token.isEmpty()) {
                    input.error = "请先粘贴令牌"
                } else {
                    saveToken(activity, token)
                    dialog.dismiss()
                    retry()
                }
            }
        }
        dialog.show()
    }

    private fun openTokenPage(activity: android.app.Activity) {
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            android.widget.Toast.makeText(activity, TOKEN_PAGE, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun progressText(pct: Int, total: Long): String =
        if (total > 0) "正在下载…   " + pct + "%   (" + fmtSize(total * pct / 100) + " / " + fmtSize(total) + ")"
        else "正在下载…   " + pct + "%"

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format("%.0f KB", kb) else String.format("%.1f MB", kb / 1024)
    }
}
