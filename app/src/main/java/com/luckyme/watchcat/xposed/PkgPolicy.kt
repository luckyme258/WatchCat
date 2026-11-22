package com.luckyme.watchcat.xposed

/**
 * 应用列表判定层（P2）。
 *
 * 与 [ClipPolicy] 同一套纪律：**这个文件不许出现任何 android.* / java.io 引用**，
 * 判定必须能在 CI 的纯 JVM 单测里直接跑（L16.2.1）。
 *
 * 输入是"策略文本"，输出是"这个包该不该对这个调用者隐藏"。策略文本有两个来源
 * （见 [PkgHook]）：框架 remote preferences 的 `policy` 键（正式通道，L6 口径），
 * 以及目标应用私有目录里的 `watchcat.p2.policy`（仅调试/联调用，root 用 su 写）。
 *
 * 格式（一行一条，`#` 注释，未知键忽略）：
 *
 * ```
 * version=1
 * armed=yes                       # 必须显式写 yes；缺省=不启用（L10.1 默认不生效）
 * bootId=<本次开机 boot_id>       # 与当前 boot_id 不一致=不启用（重启后旧授权失效）
 * mode=hide                       # hide=黑名单隐藏 | show=白名单可见
 * hide=com.a,com.b                # mode=hide 时生效
 * show=com.c,com.d                # mode=show 时生效（空集合=不隐藏任何东西，fail-open）
 * ```
 *
 * 设计取舍：
 *   · **默认全可见**：没有策略、策略坏掉、bootId 对不上，一律不隐藏（L10.4 fail-open）。
 *   · **白名单模式不允许空**：`show` 为空时按"不隐藏"处理。真按"除了列表全隐藏"跑，
 *     一次写错就把目标应用的所有依赖都藏了，等于把应用搞崩。
 *   · **系统身份与自身永不隐藏**：调用者自己的包、自身 uid 下的包、uid<10000 的系统应用、
 *     `android` 包，一律放行——这四条是"隐藏"功能的自杀保护。
 */
object PkgPolicy {

    enum class Mode { HIDE, SHOW }

    data class Snapshot(
        val mode: Mode = Mode.HIDE,
        val hide: Set<String> = emptySet(),
        val show: Set<String> = emptySet(),
        val armed: Boolean = false,
        val bootId: String? = null,
        val version: Int = 1,
    )

    /** 解析策略文本；任何一行坏掉只丢那一行，不让整份策略失效（与 ClipPolicy 同口径）。 */
    fun parse(text: String?): Snapshot {
        if (text.isNullOrBlank()) return Snapshot()
        var mode = Mode.HIDE
        val hide = HashSet<String>()
        val show = HashSet<String>()
        var armed = false
        var bootId: String? = null
        var version = 1

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "version" -> version = value.toIntOrNull() ?: version
                "armed" -> armed = value.equals("yes", ignoreCase = true) || value == "1" || value.equals("true", ignoreCase = true)
                "bootId" -> if (value.isNotEmpty()) bootId = value
                "mode" -> when (value.lowercase()) {
                    "hide" -> mode = Mode.HIDE
                    "show" -> mode = Mode.SHOW
                }
                "hide" -> value.split(',').forEach { tok -> tok.trim().takeIf { it.isNotEmpty() }?.let { hide.add(it) } }
                "show" -> value.split(',').forEach { tok -> tok.trim().takeIf { it.isNotEmpty() }?.let { show.add(it) } }
            }
        }
        return Snapshot(mode, hide, show, armed, bootId, version)
    }

    /**
     * 这份策略在当前这次开机上是否真的启用。
     *
     * 三条同时满足才算启用：显式 `armed=yes`、`bootId` 等于当前 boot_id、名单非空。
     * 名单为空时"启用"没有任何意义，还会让每个被注入进程白挂几十个钩子，所以直接判不启用。
     */
    fun enabled(snapshot: Snapshot, currentBootId: String): Boolean {
        if (!snapshot.armed) return false
        if (snapshot.bootId == null || snapshot.bootId != currentBootId) return false
        return when (snapshot.mode) {
            Mode.HIDE -> snapshot.hide.isNotEmpty()
            Mode.SHOW -> snapshot.show.isNotEmpty()
        }
    }

    /**
     * 是否对该调用者隐藏 [pkg]。纯函数，同样输入永远同样输出。
     *
     * 优先级（顺序即语义，改动必须同步改单测）：
     *   1. 空包名 / `android` —— 放行（框架自身）；
     *   2. 调用者自己的包 —— 放行（自杀保护）；
     *   3. `pkgUid` 已知时：与自身同 uid、或 uid<10000（系统应用）—— 放行；
     *   4. 按 mode 判：hide=在名单里才隐；show=不在名单里才隐（show 为空则全放行）；
     *   5. 其余一律放行。
     */
    fun isHidden(
        pkg: String?,
        pkgUid: Int?,
        selfPkg: String,
        selfUid: Int,
        snapshot: Snapshot,
    ): Boolean {
        if (pkg == null || pkg.isEmpty() || pkg == "android") return false
        if (pkg == selfPkg) return false
        if (pkgUid != null && (pkgUid == selfUid || pkgUid < 10000)) return false
        return when (snapshot.mode) {
            Mode.HIDE -> snapshot.hide.contains(pkg)
            Mode.SHOW -> snapshot.show.isNotEmpty() && !snapshot.show.contains(pkg)
        }
    }
}
