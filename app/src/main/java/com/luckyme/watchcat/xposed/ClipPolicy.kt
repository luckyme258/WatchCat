package com.luckyme.watchcat.xposed

/**
 * 剪贴板判定层（L16.2.1：业务判定只在 Kotlin/JVM 的纯函数里）。
 *
 * 约束：**这个文件不许出现任何 android.* / java.io 引用**。
 * 它既要能在 system_server 里跑，也要能在 CI 的 JVM 单测里直接跑；
 * 一旦引入 Android 类型，单测就得靠 Robolectric，等于把「改一行 20 秒见结果」变成几分钟。
 *
 * 策略来源是 `/data/system/watchcat.clip.policy`（由 App 通过 su 写入，root 可写、
 * system_server 可读，2026-11-22 实测标签为 system_data_file）。格式见 [parse]。
 */
object ClipPolicy {

    enum class Mode { ALLOW, EMPTY, FAKE, BLOCK }

    /** 无论策略怎么写都放行的 uid：root(0)、system(1000)、shell(2000)。写错策略也不会把系统读坏。 */
    val BUILTIN_ALLOW_UIDS: Set<Int> = setOf(0, 1000, 2000)

    data class Snapshot(
        val defaultMode: Mode = Mode.EMPTY,
        val allowUids: Set<Int> = emptySet(),
        val uidModes: Map<Int, Mode> = emptyMap(),
        val imeUid: Int? = null,
        val fakePool: String? = null,
        val version: Int = 1,
    )

    /**
     * 解析策略文本。格式（一行一条，`#` 开头为注释，未知键一律忽略）：
     *
     * ```
     * version=1
     * default=empty            # allow | empty | fake | block
     * imeuid=10321             # 当前输入法的 uid，永远放行（否则用户自己的粘贴会坏）
     * allow=10123,10124        # 逗号分隔的放行 uid
     * mode.10125=allow         # 按 uid 覆盖默认模式
     * pool=社交                 # 替身词库池（缺省按 uid 取模）
     * ```
     *
     * 解析口径刻意宽松：任何一行解析失败都只丢那一行，不让整份策略失效——
     * 策略坏掉时宁可退回「默认空 + 只放行系统 uid」，也不能因为一个逗号让钩子全挂。
     */
    fun parse(text: String?): Snapshot {
        if (text.isNullOrBlank()) return Snapshot()
        var def = Mode.EMPTY
        var version = 1
        val allow = HashSet<Int>()
        val uidModes = HashMap<Int, Mode>()
        var ime: Int? = null
        var pool: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when {
                key == "version" -> version = value.toIntOrNull() ?: version
                key == "default" -> modeOf(value)?.let { def = it }
                key == "imeuid" -> ime = value.toIntOrNull()
                key == "allow" -> value.split(',').forEach { tok ->
                    tok.trim().toIntOrNull()?.let { allow.add(it) }
                }
                key == "pool" -> if (value.isNotEmpty()) pool = value
                key.startsWith("mode.") -> {
                    val uid = key.substring(5).toIntOrNull()
                    val mode = modeOf(value)
                    if (uid != null && mode != null) uidModes[uid] = mode
                }
            }
        }
        return Snapshot(def, allow, uidModes, ime, pool, version)
    }

    /**
     * 判定这一次读取该给什么。纯函数：同样的输入永远同样的输出。
     *
     * 优先级（顺序即语义，改动必须同步改单测）：
     *   1. `mode.<uid>` 显式覆盖 —— 用户逐应用选过的东西最优先；
     *   2. 系统身份（root/system/shell）—— 兜底，别把自己读坏；
     *   3. 输入法 —— 用户自己的「粘贴」链路；
     *   4. `allow` 名单；
     *   5. `default`。
     */
    fun decide(uid: Int, snapshot: Snapshot): Mode {
        snapshot.uidModes[uid]?.let { return it }
        if (BUILTIN_ALLOW_UIDS.contains(uid)) return Mode.ALLOW
        if (snapshot.imeUid != null && snapshot.imeUid == uid) return Mode.ALLOW
        if (snapshot.allowUids.contains(uid)) return Mode.ALLOW
        return snapshot.defaultMode
    }

    private fun modeOf(s: String): Mode? = when (s.lowercase()) {
        "allow" -> Mode.ALLOW
        "empty" -> Mode.EMPTY
        "fake" -> Mode.FAKE
        "block" -> Mode.BLOCK
        else -> null
    }
}
