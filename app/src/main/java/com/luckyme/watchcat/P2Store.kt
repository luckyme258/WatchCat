package com.luckyme.watchcat

import android.content.Context
import android.content.SharedPreferences
import com.luckyme.watchcat.xposed.PkgPolicy
import java.io.File

/**
 * P2（应用列表伪装）的策略仓库：**远程 prefs 为正式通道**，本地 prefs 只兜底。
 *
 * 和剪贴板的 [PolicyStore] 是两套 group：钩子侧读的是 `getRemotePreferences("p2")`，
 * 这里写的必须同一个 group，否则"页面保存了但钩子读不到"这种最难查的错就会出现。
 *
 * 一个 group 里按目标应用存一条：键 `policy.<目标包名>`。不能共用一个 `policy` 键——
 * 一个模块一份 group，共用就等于"给 A 配的名单也套到 B 上"。
 *
 * 文本格式与 [PkgPolicy.parse] 严格一致（由 [text] 生成），并强制写入本次开机 bootId：
 * 换一次开机旧授权自动失效（L10.1）。
 */
object P2Store {

    const val GROUP = "p2"
    private const val LOCAL = "watchcat_p2"
    private const val LEGACY_KEY = "policy"

    fun keyOf(pkg: String) = "policy.$pkg"

    fun prefs(ctx: Context): SharedPreferences {
        val remote = runCatching { FrameworkState.service()?.getRemotePreferences(GROUP) }.getOrNull()
        return remote ?: ctx.getSharedPreferences(LOCAL, Context.MODE_PRIVATE)
    }

    fun isRemote(): Boolean = runCatching {
        FrameworkState.service()?.getRemotePreferences(GROUP) != null
    }.getOrDefault(false)

    /** 页面里一条目标策略。默认 = 未启用、**只看基础（白名单 = 基础可见集）**、ROM 层开。 */
    data class Target(
        val pkg: String,
        val armed: Boolean = false,
        val mode: PkgPolicy.Mode = PkgPolicy.Mode.SHOW,
        val hide: Set<String> = emptySet(),
        val show: Set<String> = emptySet(),
        val rom: Boolean = true,
        val bootId: String? = null,
    )

    fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unknown")

    /** 现读一条策略；没配过返回 null（不要用一个"看起来正常"的空策略骗页面）。 */
    fun read(ctx: Context, pkg: String): Target? {
        val text = runCatching { prefs(ctx).getString(keyOf(pkg), null) }.getOrNull()
            ?: runCatching { prefs(ctx).getString(LEGACY_KEY, null) }.getOrNull()
            ?: return null
        val snap = PkgPolicy.parse(text)
        return Target(
            pkg = pkg,
            armed = snap.armed,
            mode = snap.mode,
            hide = snap.hide,
            show = snap.show,
            rom = snap.rom,
            bootId = snap.bootId,
        )
    }

    /** 已配置的目标包名（列表页打"已配置"标用）。 */
    fun configured(ctx: Context): Set<String> = runCatching {
        prefs(ctx).all.keys
            .mapNotNull { k -> k.takeIf { it.startsWith("policy.") }?.removePrefix("policy.") }
            .toSet()
    }.getOrDefault(emptySet())

    fun text(t: Target, bootId: String): String = buildString {
        append("# WatchCat 应用列表策略（由 App 生成，勿手改）\n")
        append("version=1\n")
        append("armed=").append(if (t.armed) "yes" else "no").append('\n')
        append("bootId=").append(bootId).append('\n')
        append("mode=").append(if (t.mode == PkgPolicy.Mode.SHOW) "show" else "hide").append('\n')
        // 只写当前 mode 用的那份集合：另一份是死数据，留着会和真实意图打架（应用宝那条 `mode=hide` 却残留一长串 `show=`）
        append("hide=").append(if (t.mode == PkgPolicy.Mode.HIDE) t.hide.sorted().joinToString(",") else "").append('\n')
        append("show=").append(if (t.mode == PkgPolicy.Mode.SHOW) t.show.sorted().joinToString(",") else "").append('\n')
        append("rom=").append(if (t.rom) "yes" else "no").append('\n')
    }

    /**
     * 保存并回读校验。只信读回来的内容——remote prefs 的 `apply()` 是异步的，
     * 光看返回值等于自欺。回读不符就当失败，页面不许显示"已启用"。
     */
    fun save(ctx: Context, t: Target): String {
        if (!isRemote()) {
            return "框架未连接：远程 prefs 不可用，策略没有下发（先确认 Vector 在运行、模块已启用）"
        }
        // 白名单模式不许空：`show` 空了就是 fail-open（全可见），等于把「只看基础」悄悄变成「全放行」。
        // 这里直接补基础可见集，并在结果里明说补了多少，不猜用户意图。
        var target = t
        var filled = 0
        if (t.mode == PkgPolicy.Mode.SHOW && t.show.isEmpty()) {
            val base = BaseSet.collect(ctx.packageManager, currentImeId(ctx))
            target = t.copy(show = base)
            filled = base.size
        }
        if (target.armed && target.hide.isEmpty() && target.show.isEmpty() && !target.rom) {
            return "没有可藏的对象：名单为空且 ROM 层关闭，保存了也不会生效"
        }
        val boot = bootId()
        val body = text(target, boot)
        val p = prefs(ctx).edit().putString(keyOf(target.pkg), body)
        if (!p.commit()) return "写入失败：框架拒绝了这次 commit"
        val back = runCatching { prefs(ctx).getString(keyOf(target.pkg), null) }.getOrNull()
        if (back != body) return "写入后回读不符，已放弃（页面不显示为已启用）"
        val note = if (filled > 0) "（白名单为空，已自动填入基础可见集 $filled 个）" else ""
        return "已保存：${target.pkg} · armed=${if (target.armed) "yes" else "no"} · " +
            "mode=${if (target.mode == PkgPolicy.Mode.SHOW) "show" else "hide"} · " +
            "hide=${target.hide.size} show=${target.show.size} " +
            "rom=${if (target.rom) "yes" else "no"}$note"
    }

    /** 当前默认输入法 id（形如 `pkg/.Service`），拿不到就 null。 */
    fun currentImeId(ctx: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
        )
    }.getOrNull()

    fun remove(ctx: Context, pkg: String): String {
        prefs(ctx).edit().remove(keyOf(pkg)).commit()
        val still = runCatching { prefs(ctx).getString(keyOf(pkg), null) }.getOrNull()
        return if (still == null) "已删除：$pkg" else "删除失败：回读仍在"
    }

    /**
     * 开机后一键续期：把所有 bootId 跟当前不一致的策略改写成当前 bootId。
     *
     * 这是"安全设计在 UX 上的代价"——每轮开机旧授权必须失效（L10.1），但让用户逐个目标重进编辑器点保存
     * 是纯手工折磨。续期只改 bootId 这一行，**名单与 armed 一律原样保留**，续期不等于重新授权。
     */
    fun renewBootId(ctx: Context): Pair<Int, Int> {
        if (!isRemote()) return 0 to configured(ctx).size
        val current = bootId()
        var done = 0
        var failed = 0
        for (pkg in configured(ctx)) {
            val t = read(ctx, pkg) ?: continue
            if (t.bootId != null && t.bootId == current) continue
            // 直接重写文本：走 save() 会顺手补基础可见集，违背"只改 bootId 一行"的承诺
            val body = text(t, current)
            val p = prefs(ctx).edit().putString(keyOf(pkg), body)
            if (!p.commit()) { failed++; continue }
            val back = runCatching { prefs(ctx).getString(keyOf(pkg), null) }.getOrNull()
            if (back == body) done++ else failed++
        }
        return done to failed
    }
}
