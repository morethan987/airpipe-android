package com.morethan.airpipe

import android.content.Context

/** 轻量偏好：轮询总开关（磁贴 / App 按钮 / 各自启入口共用，持久化跨重启）。 */
object Prefs {
    private const val FILE = "prefs"
    private const val KEY_SYNC_ENABLED = "sync_enabled"

    /** 默认 true：保持既有行为（装好即轮询）。 */
    fun syncEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_ENABLED, true)

    fun setSyncEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYNC_ENABLED, v).apply()
    }
}
