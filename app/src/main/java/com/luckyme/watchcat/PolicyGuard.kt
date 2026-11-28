package com.luckyme.watchcat

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebView

/**
 * 写入侧的保护判定（0.6.0）。
 *
 * 判据**全部取自 Android 自己的标记**，不自建角色清单：
 *
 * | 判据 | 来源 |
 * |---|---|
 * | `uid < 10000` | `Process.FIRST_APPLICATION_UID` —— 系统骨架 |
 * | 当前桌面 | `ACTION_MAIN` + `CATEGORY_HOME` 的 `resolveActivity` 结果 |
 * | 当前输入法 | `Settings.Secure.DEFAULT_INPUT_METHOD` |
 * | 当前 WebView | `WebView.getCurrentWebViewPackage()` |
 *
 * 三个"为什么不用"：
 *   · **不用 `RoleManager`**：它只有 `isRoleHeld(pkg)`（拿包名反查），没有"当前持有者"的查询接口，
 *     拿不到"谁是桌面"这个正向答案；`resolveActivity` 才是系统的判定入口。
 *   · **不用 `FLAG_SYSTEM` 做否决**：Android 没有"系统骨架 vs 普通预装"的界线，厂商商城和 Settings
 *     同为 `FLAG_SYSTEM`。能用 uid 与角色分开的只有后者，所以 flags 只用来打 UI 角标（[isSystem]）。
 *   · **不用平台签名**：分不开上述两者，还要读证书算摘要，收益不如 uid 直接。
 *
 * 这一层只负责"写之前把不该藏的挡掉并给出人话原因"；运行时还有 `PkgPolicy` 的 keep 兜底（第二道闸）。
 */
object PolicyGuard {

    data class Keep(val home: String?, val ime: String?, val web: String?)

    /** 现场探测三件套。任何一项取不到就给 null，由 [rejectReason] 少挡一项，不猜。 */
    fun keep(ctx: Context): Keep {
        val home = runCatching {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        val ime = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        val web = runCatching { WebView.getCurrentWebViewPackage()?.packageName }
            .getOrNull()?.takeIf { it.isNotEmpty() }

        return Keep(home, ime, web)
    }

    /**
     * 这个包能不能进名单。返回 `null` = 可以；否则就是拒绝原因，直接当 UI 文案用。
     *
     * 顺序：自身 → uid<10000 → 当前桌面/输入法/WebView。
     * 注意 `getApplicationInfo` 会抛 `NameNotFoundException`，这里吞成"读不到"（应用可能刚被卸载）。
     */
    fun rejectReason(ctx: Context, pkg: String, selfPkg: String, keep: Keep = keep(ctx)): String? {
        if (pkg == selfPkg) return "不能藏自己"
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: return "读不到这个应用（可能已卸载）"
        if (ai.uid < 10000) return "系统骨架（uid ${ai.uid}）"
        if (keep.home != null && pkg == keep.home) return "当前桌面，藏了会没桌面"
        if (keep.ime != null && pkg == keep.ime) return "当前输入法，藏了会没键盘"
        if (keep.web != null && pkg == keep.web) return "当前 WebView，藏了内嵌浏览器会崩"
        return null
    }

    /**
     * 预装标记，**只用于 UI 角标**（「系统 · 可更新」），不参与否决。
     * `FLAG_UPDATED_SYSTEM_APP` 说明它曾经预装、但已经被更新过，用户视角更像普通应用。
     */
    fun isSystem(ai: ApplicationInfo): Boolean = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    fun isUpdatedSystem(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
