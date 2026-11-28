package com.luckyme.watchcat

import android.content.Context
import android.content.SharedPreferences
import com.luckyme.watchcat.xposed.LegacyMerge
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

    /** 0.6.0 的单份隐藏名单：钩子侧优先读这个键（见 `PkgHook.Source`）。 */
    const val KEY_LIST = "hideList"
    private const val KEY_MIGRATED = "hideList.migrated"

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

    // ---- 0.6.0：单份隐藏名单（ListScheme） ----------------------------------------
    //
    // 与上面的 per-target `Target` 并存一段时间：P2 阶段只落存储与迁移，UI 还是旧的
    // （旧 UI 继续写 `policy.<pkg>`，钩子读不到 `hideList` 时按 `policy.<pkg>` 兜底，行为不变）；
    // 等 P3 换成「隐藏名单」页之后，`Target`、`renewBootId`、`configured` 才删。

    /**
     * 一份名单。**没有 `armed`**——名单非空即生效，这是"配置了但没启用"在数据模型上消失的关键。
     * `bootId` 仍然保留：它只表示"本轮开机签发的授权"，重启后失效，由列表页一键续期。
     */
    data class Scheme(
        val hide: Set<String> = emptySet(),
        val keepHome: String? = null,
        val keepIme: String? = null,
        val keepWeb: String? = null,
        val bootId: String? = null,
        val rom: Boolean = true,
    )

    /** 没配过返回 null（不拿一份"看起来正常"的空名单骗页面）。 */
    fun readScheme(ctx: Context): Scheme? {
        val text = runCatching { prefs(ctx).getString(KEY_LIST, null) }.getOrNull() ?: return null
        val snap = PkgPolicy.parse(text)
        return Scheme(
            hide = snap.hide,
            keepHome = snap.keepHome,
            keepIme = snap.keepIme,
            keepWeb = snap.keepWeb,
            bootId = snap.bootId,
            rom = snap.rom,
        )
    }

    /** 名单为空时写 `armed=no`：空名单不该让每个被注入进程白挂几十个钩子，也不该让 ROM 层单独生效。 */
    fun textScheme(s: Scheme, bootId: String): String = buildString {
        append("# WatchCat 隐藏名单（由 App 生成，勿手改）\n")
        append("version=1\n")
        append("armed=").append(if (s.hide.isEmpty()) "no" else "yes").append('\n')
        append("bootId=").append(bootId).append('\n')
        append("mode=hide\n")
        append("hide=").append(s.hide.sorted().joinToString(",")).append('\n')
        s.keepHome?.let { append("keep.home=").append(it).append('\n') }
        s.keepIme?.let { append("keep.ime=").append(it).append('\n') }
        s.keepWeb?.let { append("keep.web=").append(it).append('\n') }
        append("rom=").append(if (s.rom) "yes" else "no").append('\n')
    }

    /**
     * 保存 + 回读校验（同 [save] 的纪律：只信读回来的）。
     *
     * **保护集必须完整**：缺当前桌面或当前输入法时直接拒写。判定层还有一道完整性闸会整份禁用，
     * 但那时用户会看到"保存成功却不生效"，所以必须在写入前就说清楚。
     */
    fun saveScheme(ctx: Context, s: Scheme): String {
        if (!isRemote()) {
            return "框架未连接：远程 prefs 不可用，名单没有下发（先确认 Vector 在运行、模块已启用）"
        }
        if (s.keepHome.isNullOrEmpty() || s.keepIme.isNullOrEmpty()) {
            return "保护集不完整（读不到当前桌面或当前输入法）：整份名单不会被启用，已拒绝保存"
        }
        val boot = bootId()
        val body = textScheme(s, boot)
        val p = prefs(ctx).edit().putString(KEY_LIST, body)
        if (!p.commit()) return "写入失败：框架拒绝了这次 commit"
        val back = runCatching { prefs(ctx).getString(KEY_LIST, null) }.getOrNull()
        if (back != body) return "写入后回读不符，已放弃（页面不显示为已生效）"
        return "已保存：藏 ${s.hide.size} 个" + if (s.rom) " + ROM 特征包" else ""
    }

    /** 开机后一键续期：只重写 bootId 这一行，名单与保护集原样保留。 */
    fun renewSchemeBootId(ctx: Context): String {
        if (!isRemote()) return "框架未连接，续期没有下发"
        val s = readScheme(ctx) ?: return "还没有名单"
        if (s.bootId == bootId()) return "名单没过期，不用续期"
        val body = textScheme(s, bootId())
        val p = prefs(ctx).edit().putString(KEY_LIST, body)
        if (!p.commit()) return "续期失败：commit 被拒"
        val back = runCatching { prefs(ctx).getString(KEY_LIST, null) }.getOrNull()
        return if (back == body) "已续期（目标应用仍需冷启动）" else "续期后回读不符，已放弃"
    }

    /**
     * 0.5.x → 0.6.0 的一次性迁移。
     *
     * 1. 把 `policy.<pkg>` 里 `mode=hide` 的集合**取并集**成一份名单（规则见 [LegacyMerge]）；
     * 2. `armed=yes` 的白名单目标无法用黑名单表达 → 记进 paused，UI 提示"已暂停套用"；
     * 3. 迁移时**必须**带一份现场探测的保护集，否则新文本会被完整性闸判死；
     * 4. 原键改名 `legacy.policy.<pkg>` 而不是删除——留给回滚，也不再被旧 UI 当成"已配置"。
     *
     * 幂等：`hideList` 已存在或 `hideList.migrated` 已置位就直接返回 `done=false`，跑两次不会重复合并。
     */
    data class Migrate(val merged: Int, val paused: List<String>, val done: Boolean)

    fun migrateLegacy(ctx: Context): Migrate {
        if (!isRemote()) return Migrate(0, emptyList(), false)
        val p = prefs(ctx)
        if (p.getString(KEY_LIST, null) != null || p.getBoolean(KEY_MIGRATED, false)) {
            return Migrate(0, emptyList(), false)
        }
        val all = p.all
        val map = LinkedHashMap<String, String?>()
        for ((k, v) in all) {
            if (!k.startsWith("policy.")) continue
            map[k.removePrefix("policy.")] = v as? String
        }
        val r = LegacyMerge.merge(map)
        val guard = PolicyGuard.keep(ctx)
        val body = textScheme(
            Scheme(
                hide = r.hide,
                keepHome = guard.home,
                keepIme = guard.ime,
                keepWeb = guard.web,
                rom = true,
            ),
            bootId(),
        )
        val ed = p.edit().putString(KEY_LIST, body).putBoolean(KEY_MIGRATED, true)
        for ((legacyPkg, text) in map) {
            ed.remove("policy.$legacyPkg")
            if (!text.isNullOrBlank()) ed.putString("legacy.policy.$legacyPkg", text)
        }
        if (!ed.commit()) return Migrate(0, emptyList(), false)
        val back = runCatching { p.getString(KEY_LIST, null) }.getOrNull()
        return if (back == body) Migrate(r.hide.size, r.paused, true) else Migrate(0, emptyList(), false)
    }
}
