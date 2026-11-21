package com.luckyme.watchcat

import android.content.Context
import android.content.SharedPreferences

/**
 * 策略事实源（L5）：框架在线时走 remote preferences；不在线时退回本应用私有 prefs，
 * 保证没有装框架时 UI 仍能打开、也不会把策略写到世界可读的位置。
 */
object PolicyStore {

    const val GROUP = "watchcat"
    private const val LOCAL = "watchcat_policy"

    fun prefs(ctx: Context): SharedPreferences {
        val remote = runCatching {
            FrameworkState.service()?.getRemotePreferences(GROUP)
        }.getOrNull()
        return remote ?: ctx.getSharedPreferences(LOCAL, Context.MODE_PRIVATE)
    }

    fun isRemote(): Boolean = runCatching {
        FrameworkState.service()?.getRemotePreferences(GROUP) != null
    }.getOrDefault(false)
}
