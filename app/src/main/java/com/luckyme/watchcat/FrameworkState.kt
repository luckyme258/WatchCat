package com.luckyme.watchcat

import io.github.libxposed.service.XposedService

/**
 * 框架侧只读状态（L12）：能读到的都现读，读不到返回 null 让界面显示“未知”。
 * 服务由 [WatchCatApp] 注册 XposedServiceHelper 后绑定，绑定前一律算未连接。
 */
object FrameworkState {

    @Volatile
    private var bound: XposedService? = null

    fun bind(service: XposedService) {
        bound = service
    }

    fun unbind(service: XposedService) {
        if (bound === service) bound = null
    }

    internal fun service(): XposedService? = bound

    data class Snapshot(
        val api: Int,
        val name: String,
        val version: String,
        val versionCode: Long,
        val properties: Long,
        val scope: List<String>,
    )

    fun snapshot(): Snapshot? {
        val s = bound ?: return null
        return runCatching {
            Snapshot(
                api = s.apiVersion,
                name = s.frameworkName,
                version = s.frameworkVersion,
                versionCode = s.frameworkVersionCode,
                properties = s.frameworkProperties,
                scope = s.scope ?: emptyList(),
            )
        }.getOrNull()
    }
}
