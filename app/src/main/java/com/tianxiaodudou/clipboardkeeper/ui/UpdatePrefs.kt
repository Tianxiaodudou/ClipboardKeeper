package com.tianxiaodudou.clipboardkeeper.ui

import android.content.Context

/** 检查更新用的 GitHub PAT（只用于读 Release，存在应用私有 SP，不走 Settings.Global） */
object UpdatePrefs {

    private const val FILE = "updater"
    private const val KEY_PAT = "github_pat"

    fun token(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PAT, "")?.trim().orEmpty()

    fun save(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAT, value.trim()).apply()
    }

    /** 掩码显示：ghp_abcd…wxyz */
    fun masked(token: String): String {
        if (token.isEmpty()) return ""
        if (token.length <= 10) return token.take(3) + "…" + token.takeLast(2)
        return token.take(8) + "…" + token.takeLast(4)
    }
}
