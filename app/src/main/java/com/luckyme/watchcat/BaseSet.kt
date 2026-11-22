package com.luckyme.watchcat

import android.content.Intent
import android.content.pm.PackageManager

/**
 * 「基础可见集」：最小暴露模式下，每个读取方默认允许看到的那一小撮应用。
 *
 * 为什么必须有这份东西：
 *   1. `PkgPolicy.isHidden` 第 5 条在 `show` 为空时是 fail-open（全可见）。真按"空集隐藏"
 *      就是把应用的依赖全藏了 —— 桌面点不开、输入法不出、登录页跳不回来，直接崩。
 *   2. 用户不该为"哪些包是基础设施"做功课。所以默认姿态 = 只看基础，基础集由本机探测，
 *      不写死机型（Chromebook/小米/vivo 的 launcher 包名各不相同）。
 *
 * 这份集合只影响**第三方**可见性：`uid<10000` 的系统应用在判定里本来永不隐藏（自杀保护），
 * 这里列系统关键包是为了"暴露集合"语义完整、并在文档/诊断里能直接展示给人看。
 */
object BaseSet {

    /**
     * 系统面关键包：WebView、GMS/账号、设置、权限控制器、文件选择器、分享面板、安装器、媒体 provider。
     * 只收"应用一定有依赖"的，不收任何厂商定制/娱乐类。
     */
    val SYSTEM_CRITICAL: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.permissioncontroller",
        "com.android.webview",
        "com.google.android.webview",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.android.documentsui",
        "com.android.intentresolver",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.providers.media",
        "com.android.providers.downloads",
        "com.android.externalstorage",
        "com.android.defcontainer",
    )

    /** 探测到的桌面（可能有多个：第三方桌面 + 系统桌面都在跑）。 */
    fun launchers(pm: PackageManager): Set<String> = runCatching {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(i, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** 所有已安装输入法（含第三方）：名单要按"装了什么"算，不能只看当前默认那一个。 */
    fun imes(pm: PackageManager): Set<String> = runCatching {
        pm.queryIntentServices(
            Intent("android.view.inputmethod.InputMethod"), PackageManager.GET_META_DATA,
        ).mapNotNull { it.serviceInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** 当前默认输入法（有的 ROM 不把它算进上面的 service 列表）。 */
    fun currentIme(pm: PackageManager, defaultImeId: String?): String? =
        defaultImeId?.substringBefore('/')?.takeIf { it.isNotEmpty() }

    /** 组装：系统关键包 + 桌面 + 输入法。去重、按包名排序（写进策略时稳定，便于 diff）。 */
    fun collect(pm: PackageManager, defaultImeId: String? = null): Set<String> {
        val out = LinkedHashSet<String>()
        out.addAll(SYSTEM_CRITICAL)
        out.addAll(launchers(pm))
        out.addAll(imes(pm))
        currentIme(pm, defaultImeId)?.let { out.add(it) }
        return out
    }

    /** 给 UI 用的一句话摘要。 */
    fun describe(pm: PackageManager, defaultImeId: String? = null): String {
        val l = launchers(pm).size
        val i = imes(pm).size + if (currentIme(pm, defaultImeId) != null) 1 else 0
        return "系统关键包 ${SYSTEM_CRITICAL.size} 个 · 桌面 $l 个 · 输入法 $i 个"
    }
}
