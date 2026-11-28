package com.luckyme.watchcat.xposed

/**
 * 旧「每目标一份策略」→ 新「模块一份隐藏名单」的合并规则（0.6.0，纯 JVM，可单测）。
 *
 * 为什么必须无损：0.5.x 里每个目标可能有不同的集合，直接丢掉等于把用户的配置抹了。
 *
 * 规则：
 *   · `mode=hide` 且有名单 → 直接并入；同一个包在多个目标里出现只算一次；
 *   · `mode=show` **且真的在藏东西**（`armed=yes`）→ 黑名单表达不了"只放基础集"这个语义，
 *     记进 paused，由 UI 明说"这几个目标用了旧的白名单设置，已暂停套用"；
 *   · `armed=no` 的 show 模式本来就全放行，等于"什么都没藏"，直接忽略，不进 paused；
 *   · 空文本 / 坏文本解析成空快照 → 忽略。
 *
 * 这一层不碰存储、不引 android.*，只做纯字符串决策，方便在 CI 里直接跑。
 */
object LegacyMerge {

    data class Result(val hide: Set<String>, val paused: List<String>)

    fun merge(policies: Map<String, String?>): Result {
        val hide = LinkedHashSet<String>()
        val paused = ArrayList<String>()
        for ((pkg, text) in policies) {
            if (text.isNullOrBlank()) continue
            val s = PkgPolicy.parse(text)
            when {
                s.mode == PkgPolicy.Mode.SHOW && s.armed && s.show.isNotEmpty() -> paused.add(pkg)
                s.mode == PkgPolicy.Mode.HIDE && s.hide.isNotEmpty() -> hide.addAll(s.hide)
            }
        }
        return Result(hide, paused)
    }
}
