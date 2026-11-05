// Vendored from https://github.com/Tianxiaodudou/android-updater (v1.2.1, Apache-2.0)
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 可复用的「检查更新」组件（Apache-2.0）。
 *
 * 典型用法：
 *   Updater.init(context, Updater.Config(owner = "you", repo = "your-app"))
 *   val info = Updater.check()                       // 有新版返回 Release，否则 null
 *   val apk = Updater.download(context, info) { p -> }// 进度 0..100
 *   Updater.install(context, apk)                    // 唤起系统安装器
 *
 * 依赖 GitHub Releases：版本号取 tag（可去 v 前缀），APK 取该 release 里第一个 .apk 资产。
 * 私有仓库可传 token（PAT，需 repo 权限）。
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

    fun isReady(): Boolean = cfg != null

    /** 检查最新 release；无更新或失败返回 null（不抛异常）。 */
    suspend fun check(currentVersion: String? = null): Release? = withContext(Dispatchers.IO) {
        val c = cfg ?: return@withContext null
        val version = currentVersion ?: c.currentVersion
        val json = runCatching { fetchLatestRelease(c) }.getOrNull() ?: return@withContext null
        val tag = json.optString("tag_name")
        if (tag.isEmpty()) return@withContext null

        val latest = if (c.stripPrefixV) tag.removePrefix("v").removePrefix("V") else tag
        val current = if (c.stripPrefixV) version.removePrefix("v").removePrefix("V") else version
        if (!isNewer(latest, current)) return@withContext null

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
        if (apkUrl.isEmpty()) return@withContext null

        Release(
            version = latest,
            tag = tag,
            notes = json.optString("body").trim(),
            apkUrl = apkUrl,
            apkName = apkName,
            sizeBytes = size,
            publishedAt = json.optString("published_at"),
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

    private fun fetchLatestRelease(c: Config): org.json.JSONObject {
        val url = c.apiBase.trimEnd('/') + "/repos/" + c.owner + "/" + c.repo + "/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "android-updater")
            .apply { c.readableToken()?.let { header("Authorization", "token " + it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            return org.json.JSONObject(resp.body?.string().orEmpty())
        }
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
            val info = check(currentVersion)
            if (info == null) { onResult(false); return@launch }

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

    private fun progressText(pct: Int, total: Long): String =
        if (total > 0) "正在下载…   " + pct + "%   (" + fmtSize(total * pct / 100) + " / " + fmtSize(total) + ")"
        else "正在下载…   " + pct + "%"

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format("%.0f KB", kb) else String.format("%.1f MB", kb / 1024)
    }
}
