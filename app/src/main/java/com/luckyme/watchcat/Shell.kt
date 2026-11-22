package com.luckyme.watchcat

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 命令执行层：把「要跑什么」和「怎么跑」集中在一处，界面与引擎都不直接碰进程。
 *
 * 两条通路（《安卓开发规范》W4）：
 *   - Magisk / KernelSU 的 `su -c`：本机已确认 `/product/bin/su -> magisk`，走这条；
 *   - Shizuku：等 v0.1 主体功能上来再接（权限申请、binder 调用都在 Kotlin 侧）。
 *
 * 为什么用 `ProcessBuilder("su","-c",cmd)` 而不是 `Runtime.exec("su -c appops set ...")`：
 * 数组传参由内核直接 exec，不经 shell 做二次拆词，注入面更小；
 * 命令里的单引号/空格也不会被外层 shell 吃掉。
 */
object Shell {

    data class Result(val cmd: String, val code: Int, val output: String, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && code == 0
    }

    /**
     * 跑一条 root 命令。
     *
     * @param timeoutMs 首次调用会弹 Magisk 授权框，用户不点就一直等 —— 所以要超时，
     *                  不能把界面线程或后台线程无限挂住。
     *
     * 为什么必须是「边跑边收」而不是「先 waitFor 再读」：
     * pipe 容量只有 64KB，`dumpsys package <pkg>` 这类大输出会把 pipe 写满，
     * 子进程卡在 write 上永不退出 → waitFor 等到超时 → 结果被 destroyForcibly 截断。
     * 2026-11-21 真机表现就是「详情页转 10 秒、权限全显示未读到」，根因在这里。
     */
    fun su(cmd: String, timeoutMs: Long = 10_000): Result =
        exec(listOf("su", "-c", cmd), cmd, timeoutMs)

    /**
     * 在**全局挂载命名空间**里跑 root 命令。
     *
     * 应用进程有自己的 mount namespace：在它眼里 `/data/user/0/<别的应用>` 根本不存在，
     * 于是 `su -c cat /data/user/0/com.taobao.taobao/files/...` 会「命令跑成功、cat 拿 exit=1」，
     * 表现成莫名其妙的「读 marker 失败：exit=1」（2026-11-22 小米 13 实测）。
     * KernelSU/Magisk 的 `su -M`（Magisk 是 `-mm`）切回全局 ns 才看得见。
     */
    fun suMount(cmd: String, timeoutMs: Long = 6_000): Result =
        exec(listOf("su", "-M", "-c", cmd), cmd, timeoutMs)

    private fun exec(argv: List<String>, cmd: String, timeoutMs: Long): Result {
        var p: Process? = null
        return try {
            p = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            val proc = p
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { r ->
                        r.forEachLine { line ->
                            synchronized(out) { out.append(line).append('\n') }
                        }
                    }
                }
            }.apply { isDaemon = true; name = "watchcat-su-reader" }
            reader.start()

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) proc.destroyForcibly()
            reader.join(if (finished) 500 else 2_000)
            val text = synchronized(out) { out.toString() }
            if (!finished) {
                Result(cmd, -1, text, timedOut = true)
            } else {
                Result(cmd, proc.exitValue(), text, timedOut = false)
            }
        } catch (e: Throwable) {
            // 没装 su / 被 root 管理器直接拒，都在这里体现
            Result(cmd, -1, "${e.javaClass.simpleName}: ${e.message}", timedOut = false)
        } finally {
            p?.let { runCatching { it.destroy() } }
        }
    }

    /** `su` 是否真的能拿到 uid=0（只看结果，不看有没有装 su 二进制） */
    fun probeRoot(): Result = su("id", timeoutMs = 10_000)

    /** 该命令在 root 下解析到的可执行文件路径，用于排查 PATH 差异（appops 在 /system/bin） */
    fun which(name: String): Result = su("command -v $name || echo MISSING")

    /**
     * 判断 root 探测结果：Magisk 返回的是 `uid=0(root) gid=0(root) ...`，
     * 被拒时是 `Permission denied` 或抛异常，两者都不能当成功。
     */
    fun isRoot(r: Result): Boolean =
        r.ok && r.output.contains("uid=0")

    /** 进程内是否装着 su（只用于把「没装」和「没授权」区分开） */
    fun hasSuBinary(): Boolean {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/product/bin/su", "/debug_ramdisk/su")
        return paths.any { File(it).exists() }
    }
}
