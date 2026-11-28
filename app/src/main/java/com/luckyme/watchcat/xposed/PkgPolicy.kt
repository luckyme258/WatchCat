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
 * rom=yes                         # 内建 ROM 特征包层（org.lineageos.* 等），缺省=关
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

    /**
     * ROM 特征包（当前只认 LineageOS，因为主力机就是 LOS；别的 ROM 没实测过，不写进来假装支持）。
     *
     * 判定用"前缀 + 两个精确名"，不用固定清单：LOS 的包有 `org.lineageos.updater` 这种用户
     * 可见应用，也有 `org.lineageos.settingsoverlay.fuxi` 这种每台设备都不一样的 overlay，
     * 清单维护不过来；而按前缀扫 `org.lineageos.` 是风控最常见的写法，正好一并挡掉。
     */
    private val ROM_NAME_PREFIXES = listOf("org.lineageos.")
    private val ROM_NAME_EXACT = setOf("lineageos.platform", "android.overlay.lineage")

    /** 这个包名是不是 ROM 身份特征。纯字符串判断，不读系统。 */
    fun isRomFeature(pkg: String): Boolean =
        ROM_NAME_EXACT.contains(pkg) || ROM_NAME_PREFIXES.any { pkg.startsWith(it) }

    data class Snapshot(
        val mode: Mode = Mode.HIDE,
        val hide: Set<String> = emptySet(),
        val show: Set<String> = emptySet(),
        val armed: Boolean = false,
        val bootId: String? = null,
        val version: Int = 1,
        /** 内建 ROM 特征包层：**必须显式 `rom=yes` 才开**（与 `armed=yes` 同一条纪律：默认不生效）。 */
        val rom: Boolean = false,
        /**
         * 当前"系统默认实现"三件套（0.6.0）：桌面 / 输入法 / WebView。
         *
         * 这三项由 App 侧用 Android 自己的标记算出来（`RoleManager.ROLE_HOME`、
         * `Settings.Secure.DEFAULT_INPUT_METHOD`、当前 WebView provider），判定层只做字符串比对——
         * L16.2.1 不许这里出现 android.*。
         *
         * 为什么必须有：第三方桌面/输入法 uid 都 >10000，`uid<10000` 那层保护挡不住它们；
         * 藏掉唯一输入法 = 没键盘，藏掉当前桌面 = 没桌面。
         */
        val keepHome: String? = null,
        val keepIme: String? = null,
        val keepWeb: String? = null,
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
        var rom = false
        var keepHome: String? = null
        var keepIme: String? = null
        var keepWeb: String? = null

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
                "rom" -> rom = value.equals("yes", ignoreCase = true) || value == "1" || value.equals("true", ignoreCase = true)
                "keep.home" -> keepHome = value.takeIf { it.isNotEmpty() }
                "keep.ime" -> keepIme = value.takeIf { it.isNotEmpty() }
                "keep.web" -> keepWeb = value.takeIf { it.isNotEmpty() }
            }
        }
        return Snapshot(mode, hide, show, armed, bootId, version, rom, keepHome, keepIme, keepWeb)
    }

    /**
     * 这份策略在当前这次开机上是否真的启用。
     *
     * 四条同时满足才算启用：显式 `armed=yes`、`bootId` 等于当前 boot_id、**保护集完整**、
     * 且**有东西可藏**（用户名单非空，或显式 `rom=yes` 让内建 ROM 层参与）。什么都没得藏时"启用"
     * 没有任何意义，还会让每个被注入进程白挂几十个钩子，所以直接判不启用。
     *
     * **完整性闸**（0.6.0）：新格式（写了 `keep.home` / `keep.ime` / `keep.web` 任意一个）
     * 必须同时带"当前桌面"与"当前输入法"，缺一个就整份不启用。宁可整份不生效（fail-open），
     * 也不带一份算漏的保护集去藏东西——藏掉唯一输入法会让设备没键盘。
     * 旧文本一个 keep 键都没写时跳过这一闸，保持向后兼容（升级不炸旧配置）。
     */
    fun enabled(snapshot: Snapshot, currentBootId: String): Boolean {
        if (!snapshot.armed) return false
        if (snapshot.bootId == null || snapshot.bootId != currentBootId) return false
        if (hasAnyKeep(snapshot) && (snapshot.keepHome == null || snapshot.keepIme == null)) return false
        if (snapshot.rom) return true
        return when (snapshot.mode) {
            Mode.HIDE -> snapshot.hide.isNotEmpty()
            Mode.SHOW -> snapshot.show.isNotEmpty()
        }
    }

    /** 是否写过 0.6.0 的保护集（三个键任意一个非空）。旧文本返回 false。 */
    fun hasAnyKeep(snapshot: Snapshot): Boolean =
        snapshot.keepHome != null || snapshot.keepIme != null || snapshot.keepWeb != null

    /**
     * 是否对该调用者隐藏 [pkg]。纯函数，同样输入永远同样输出。
     *
     * 优先级（顺序即语义，改动必须同步改单测）：
     *   0. **读取方闸**：`selfUid < 10000` —— 注入进系统进程时整份策略不判定，一律放行；
     *   1. 空包名 / `android` —— 放行（框架自身）；
     *   2. 调用者自己的包 —— 放行（自杀保护）；
     *   3. **保护集**（`keep.home` / `keep.ime` / `keep.web`）—— 放行。第三方桌面与输入法 uid 都
     *      >10000，`uid<10000` 那层挡不住，必须靠这一条（0.6.0）；
     *   4. **ROM 特征包层**（`rom=yes` 且命中 [isRomFeature]）—— 隐藏；用户在白名单里显式点名
     *      的包（`mode=show` 且 `show` 含它）除外，用户意图高于内建规则；
     *   5. `pkgUid` 已知时：与自身同 uid、或 uid<10000（系统应用）—— 放行；
     *   6. 按 mode 判：hide=在名单里才隐；show=不在名单里才隐（show 为空则全放行）；
     *   7. 其余一律放行。
     *
     * 第 3 条排在第 4 条前面：保护集里只有桌面/输入法/WebView，永远不会是 ROM 特征包，两者不冲突；
     * 但第 4 条**必须**排在第 5 条前面：ROM 特征包几乎全是 uid<10000 的系统应用
     * （`org.lineageos.updater` 这类），要是先走"系统应用永不隐藏"，这一层就永远不生效。
     * 反过来，**内建 ROM 名单只能收 ROM 专属包，绝不能碰 AOSP 核心**（`android`、`com.android.systemui`、
     * Settings、WebView、媒体/电话 provider）——那些是应用的依赖，藏了就是崩。
     */
    fun isHidden(
        pkg: String?,
        pkgUid: Int?,
        selfPkg: String,
        selfUid: Int,
        snapshot: Snapshot,
    ): Boolean {
        if (selfUid < 10000) return false
        if (pkg == null || pkg.isEmpty() || pkg == "android") return false
        if (pkg == selfPkg) return false
        if (pkg == snapshot.keepHome || pkg == snapshot.keepIme || pkg == snapshot.keepWeb) return false
        val explicitlyShown = snapshot.mode == Mode.SHOW && snapshot.show.contains(pkg)
        if (!explicitlyShown && snapshot.rom && isRomFeature(pkg)) return true
        if (pkgUid != null && (pkgUid == selfUid || pkgUid < 10000)) return false
        return when (snapshot.mode) {
            Mode.HIDE -> snapshot.hide.contains(pkg)
            Mode.SHOW -> snapshot.show.isNotEmpty() && !snapshot.show.contains(pkg)
        }
    }
}
