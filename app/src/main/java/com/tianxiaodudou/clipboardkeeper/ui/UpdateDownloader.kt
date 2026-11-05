package com.tianxiaodudou.clipboardkeeper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 内置下载器：下载 APK 到应用缓存，完成后交给系统安装器。 */
object UpdateDownloader {

    fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit,
        onFinished: (File?, String?) -> Unit
    ) {
        Thread {
            val dir = File(context.cacheDir, "update")
            if (!dir.exists() && !dir.mkdirs()) {
                onFinished(null, "无法创建下载目录")
                return@Thread
            }
            dir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
            val target = File(dir, "$version-ClipboardKeeper.apk")
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ClipboardKeeper-Updater")
                }
                val code = connection.responseCode
                if (code != 200) throw IllegalStateException("HTTP $code")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPercent = -1
                        while (true) {
                            val len = input.read(buffer)
                            if (len <= 0) break
                            output.write(buffer, 0, len)
                            read += len
                            if (total > 0) {
                                val percent = (read * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                onFinished(target, null)
            } catch (t: Throwable) {
                onFinished(null, t.javaClass.simpleName + ": " + t.message)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    /** 拉起系统安装器；未授予「安装未知应用」时跳到授权页 */
    fun install(activity: Activity, apk: File) {
        try {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
                return
            }
            val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                apk
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {
            // 安装失败由页面提示
        }
    }
}
