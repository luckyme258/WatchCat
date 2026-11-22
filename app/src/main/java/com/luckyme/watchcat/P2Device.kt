package com.luckyme.watchcat

/**
 * P2 的设备侧动作：作用域、注入状态、重启目标应用。
 *
 * 全部走"能读就读、读不到就说读不到"的口径（L12/L15.1）：这里没有一个"应该已注入"的猜测，
 * marker 读不到就是没注入（或者目标没起过），要用户去重启目标应用再看。
 */
object P2Device {

    const val SYSTEM_SCOPE = "system"

    /** 框架给的 scope；框架没连上返回 null（未知，不等于空）。 */
    fun scope(): List<String>? = FrameworkState.snapshot()?.scope

    fun inScope(pkg: String): Boolean? = scope()?.contains(pkg)

    /**
     * 请求把目标加入作用域。
     *
     * 优先走框架的 `requestScope`（正式通道，框架自己落库并回调结果）；
     * 框架不支持/服务不在时退回 `vector-cli scope add`（su），并在返回文案里写明走的哪条。
     */
    fun requestScope(pkg: String, done: (String) -> Unit) {
        val svc = FrameworkState.service()
        if (svc != null) {
            try {
                svc.requestScope(listOf(pkg), object : io.github.libxposed.service.XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        done("已加入作用域（框架批准）：" + approved.joinToString(", "))
                    }

                    override fun onScopeRequestFailed(message: String) {
                        cliAdd(pkg) { cli -> done("框架拒绝：$message；已改走 CLI：$cli") }
                    }
                })
                return
            } catch (t: Throwable) {
                // 服务死掉/能力不支持，落到 CLI
            }
        }
        cliAdd(pkg, done)
    }

    private fun cliAdd(pkg: String, done: (String) -> Unit) {
        val module = BuildConfig.APPLICATION_ID
        val r = Shell.su("/data/adb/lspd/cli scope add $module $pkg/0 2>&1", 8_000)
        val text = r.output.trim()
        done(
            when {
                r.ok && text.contains("Successfully", true) -> "已加入作用域（vector-cli）：$pkg"
                text.contains("fixes its scope", true) ->
                    "加入失败：模块被 staticScope 锁死（module.prop 必须 staticScope=false）"
                text.isNotEmpty() -> "加入失败：$text"
                else -> "加入失败：su 无输出（exit=${r.code}）"
            }
        )
    }

    data class Injection(
        val marker: Map<String, String>?,
        val events: List<String>,
        val error: String?,
    ) {
        val injected: Boolean get() = marker != null
    }

    /** 补救回路的一条：目标应用问过这个包，但我们把它藏了（`nnf`=查不存在 / `iae`=不满足）。 */
    data class Remedy(val pkg: String, val count: Int, val act: String)

    /**
     * 从事件文件里算出「它刚才想看谁」。
     *
     * 事件行的形状（钩子里写的）：`ts=… uid=… pid=… pkg=<目标> api=getPackageInfo target=<被查包> act=nnf module=…`。
     * 只统计"被藏掉"的两种 act，`act=allow` 不算——那是本来就该看见的。
     */
    fun remedies(events: List<String>): List<Remedy> {
        val hits = LinkedHashMap<String, Remedy>()
        for (line in events) {
            val m = Regex(" target=([^ ]+) act=([a-z]+)").find(line) ?: continue
            val pkg = m.groupValues[1].trim()
            val act = m.groupValues[2]
            if (pkg.isEmpty() || (act != "nnf" && act != "iae")) continue
            val old = hits[pkg]
            hits[pkg] = Remedy(pkg, (old?.count ?: 0) + 1, act)
        }
        return hits.values.sortedByDescending { it.count }
    }

    /** 读目标进程写下的 marker 与最近事件。文件是目标应用 `0600` 私有，必须 su；且必须走全局挂载 ns。 */
    fun injection(pkg: String): Injection {
        val dir = "/data/user/0/$pkg/files"
        val m = Shell.suMount("cat $dir/watchcat.p2.marker 2>/dev/null")
        val marker = if (m.ok && m.output.isNotBlank()) parseKv(m.output) else null
        val e = Shell.suMount("tail -n 200 $dir/watchcat.p2.events.jsonl 2>/dev/null")
        val events = if (e.ok && e.output.isNotBlank()) e.output.lineSequence().filter { it.isNotBlank() }.toList() else emptyList()
        val err = when {
            !m.ok && m.output.isBlank() -> "读不到目标私有目录（su -M exit=${m.code}）：目标没启动过，或 root 没授权"
            !m.ok -> "读 marker 失败：${m.output.trim()}"
            marker == null -> "还没有 marker：目标应用在本轮开机后没启动过，或没进作用域"
            else -> null
        }
        return Injection(marker, events, err)
    }

    private fun parseKv(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    /** 重启目标应用：force-stop 再拉起。拉不起来就如实报，不假装"已重启"。 */
    fun restart(pkg: String): String {
        val r = Shell.su("am force-stop $pkg; sleep 1; monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1", 12_000)
        val out = r.output.trim()
        return when {
            !r.ok -> "重启失败：${out.ifEmpty { "exit=" + r.code }}"
            out.contains("No activities found", true) -> "已强停，但没有可启动的 Launcher Activity，请手动打开一次"
            else -> "已重启 $pkg（有 Launcher 入口）"
        }
    }
}
