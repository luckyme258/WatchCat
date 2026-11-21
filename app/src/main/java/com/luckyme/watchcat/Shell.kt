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
     */
    fun su(cmd: String, timeoutMs: Long = 10_000): Result {
        var p: Process? = null
        return try {
            p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            // 先等退出再收输出：首次会弹 Magisk 授权框，若先读 pipe 会一直阻塞在 readLine 上，
            // 超时机制就失效了。appops get 的输出远小于 pipe 容量（64KB），不会互锁。
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val out = StringBuilder()
            runCatching {
                p.inputStream.bufferedReader().use { r -> r.forEachLine { out.append(it).append('\n') } }
            }
            if (!finished) {
                p.destroyForcibly()
                Result(cmd, -1, out.toString(), timedOut = true)
            } else {
                Result(cmd, p.exitValue(), out.toString(), timedOut = false)
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
