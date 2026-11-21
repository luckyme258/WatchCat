package com.luckyme.watchcat

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自检页：证明 Zig 引擎在真机上「装载 → 解析 → 快照 → 拼写命令 → 写入 → 回读校验」整条链是通的。
 *
 * 三段执行：
 *   1) 主线程：纯计算断言（不碰权限、不碰命令）；
 *   2) 后台线程：root 探测 → 数 uid 段行数 → 真读本应用 appops → 交给 Zig 解析 → 真写一次 → 按作用域回读 → 恢复；
 *   3) 后台线程：能力探测 —— 逐条 op 试写 ignore 并回读，结论交给 Zig 判定，落盘 capability_v1.json。
 *
 * 两条在真机上踩出来的规矩（已写进 appops / writecmd 的注释）：
 *   - `appops get` 里 uid 段与包段在文本上无法区分，必须先用 `appops get --uid <uid>` 数行数；
 *   - 回读必须**按作用域**比对，否则会把 uid 段的同名 op 当成自己的写入结果（自欺）。
 *
 * 写操作只碰**本应用自己**的 VIBRATE（2026-11-21 在本机实测：CAMERA / RECORD_AUDIO 这类
 * 权限型 op，`appops set` 返回 rc=0 却完全不生效，写进去读不回来，拿它验不住），
 * 并在 finally 里恢复 default，不留对外部应用的改动。
 */
class MainActivity : Activity() {

    private lateinit var tv: TextView
    private val lines = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        append("设备 ${Build.MANUFACTURER} ${Build.MODEL}")
        append("系统 Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        append("首选 ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")

        val sample = "Uid mode: COARSE_LOCATION: ignore\nCAMERA: deny; time=+1d2h3m ago"

        try {
            val ver = NativeEngine.nativeVersion()
            val ping = NativeEngine.nativePing()
            val jniVer = NativeEngine.nativeJniVersion()
            val ops = NativeEngine.nativeOpCount()
            // 这份样本：1 条 uid 段 + 1 条包段
            val json = NativeEngine.nativeReadOps(sample, 1)
            val snap = NativeEngine.nativeSnapshot("com.luckyme.probe", sample, "2026-11-21T19:59:00+08:00", 1)
            val cmd = NativeEngine.nativeWriteOp("com.luckyme.probe", "CAMERA", MODE_DENY, SCOPE_PACKAGE)
            val badCmd = NativeEngine.nativeWriteOp("com.x; rm -rf /", "CAMERA", MODE_DENY, SCOPE_PACKAGE)

            val ok = ver == 0x00010000 &&
                ping == 0x5743 &&
                jniVer >= 0x00010006 &&
                ops > 0 &&
                json != null && json.contains("\"count\":2") && json.contains("\"skipped\":0") &&
                json.contains("\"scope\":\"uid\"") && json.contains("\"scope\":\"package\"") &&
                snap != null && snap.startsWith("# watchcat-snapshot v1") && snap.contains("CAMERA\tdeny\tpackage") &&
                cmd == "appops set --user current com.luckyme.probe CAMERA deny" &&
                badCmd == null // 注入尝试必须被拒

            append(if (ok) "LOAD: PASS" else "LOAD: FAIL")
            append(String.format(Locale.US, "version    = 0x%08x", ver))
            append(String.format(Locale.US, "jniVersion = 0x%x", jniVer))
            append("opCount    = $ops")
            append("modeLabel  = ${label(MODE_IGNORE)} / ${label(9)}")
            append("readOps    = ${json ?: "null"}")
            append("writeCmd   = ${cmd ?: "null"}")
            append("injection  = ${if (badCmd == null) "已拦截（返回 null）" else "未拦截！ $badCmd"}")
            append("snapshot   = ${snap ?: "null"}")
        } catch (e: Throwable) {
            append("LOAD: FAIL")
            append("${e.javaClass.name}: ${e.message}")
            e.cause?.let { append("cause: ${it.javaClass.name}: ${it.message}") }
            append("常见原因：ABI 不匹配 / .so 未按 16KB 对齐或被压缩 / 类名与导出符号不一致。")
            Log.e(TAG, "load failed", e)
        }

        render()
        Thread { runRootPhase() }.apply { isDaemon = true; name = "watchcat-selfcheck" }.start()
    }

    /** 后台段：root → 数 uid 段 → 真读 → 快照 → 真写 → 按作用域回读 → 恢复 */
    private fun runRootPhase() {
        if (!Shell.hasSuBinary()) {
            appendAsync("root       = 没装 su 二进制，只能走 Shizuku（未接入）")
            return
        }

        val root = Shell.probeRoot()
        appendAsync("root       = ${if (Shell.isRoot(root)) "OK（uid=0）" else "不可用：${root.output.trim().take(120)}"}")
        if (!Shell.isRoot(root)) return

        val uid = Process.myUid()

        // 真读：先数 uid 段，再读合并输出——顺序不能反，解析要靠这个行数划边界
        val uidRaw = Shell.su("appops get --uid $uid")
        val uidLines = countNonEmpty(uidRaw.output)
        val all = Shell.su("appops get $SELF_PKG")
        val allLines = countNonEmpty(all.output)
        val realJson = NativeEngine.nativeReadOps(all.output, uidLines)
        val parsed = countOf(realJson)
        appendAsync("realRead   = exit=${all.code} uid段=${uidLines} 包段=${allLines - uidLines} 解析=${parsed}")
        appendAsync("boundary   = ${if (parsed == allLines) "对齐（一行不丢）" else "错位！行数 $allLines vs 解析 $parsed"}")

        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val before = NativeEngine.nativeSnapshot(SELF_PKG, all.output, stamp, uidLines)
        appendAsync("snapshot   = ${if (before == null) "失败（按规范此时不许写）" else "已生成 ${before.length} 字符"}")
        if (before == null) return

        // 真写：VIBRATE（包作用域）。权限型 op（CAMERA / RECORD_AUDIO）在本机实测「rc=0 但不生效」，
        // 用它做写入验证只会得到假阳性，所以选一条确定可写、可读回的普通 op。
        val setCmd = NativeEngine.nativeWriteOp(SELF_PKG, WRITE_OP, MODE_DENY, SCOPE_PACKAGE)
        if (setCmd == null) {
            appendAsync("writeOp    = 命令生成失败，已放弃写入")
            return
        }
        try {
            val w = Shell.su(setCmd)
            appendAsync("write      = $setCmd -> exit=${w.code}")
            val got = readScopedMode(uid, WRITE_OP, "package")
            appendAsync("readback   = ${WRITE_OP}(package) modeId=$got ${if (got == MODE_DENY) "（与期望一致）" else "（不一致！）"}")
        } finally {
            val restore = NativeEngine.nativeWriteOp(SELF_PKG, WRITE_OP, MODE_DEFAULT, SCOPE_PACKAGE)
            if (restore != null) {
                val r = Shell.su(restore)
                val got = readScopedMode(uid, WRITE_OP, "package")
                appendAsync("restore    = ${WRITE_OP} -> default, exit=${r.code}, 回读 modeId=$got")
            }
        }

        runCapabilityPhase(uid)
    }

    /**
     * 能力探测段：只探本应用自己，每探一条立刻恢复原值。
     * 结论由 Zig 侧（nativeCapability）判定，这里只负责画出来。
     */
    private fun runCapabilityPhase(uid: Int) {
        val report = runCatching { CapabilityProbe.run(this, uid, SELF_PKG) }.getOrNull()
        if (report == null) {
            appendAsync("capability = 探测失败（异常或引擎返回 null）")
            return
        }

        val rows = report.rows
        val writable = rows.count { it.verdict == "writable" }
        val notWritable = rows.count { it.verdict == "not_writable" }
        val appop = rows.count { it.channel == "appop" }
        val perm = rows.count { it.channel == "permission" }
        appendAsync("capability = ${rows.size} 条：可写 $writable / 写不进 $notWritable；通道 appop $appop / permission $perm")

        // 可写的挑出来给人看（这才是界面能画开关的那批）
        rows.filter { it.verdict == "writable" }.take(6).forEach {
            appendAsync("cap-ok     = ${it.op}(${it.scope}) ${label(it.before)}→${label(it.after)}")
        }
        // 权限型且写不进的，必须明确指向权限通道，别让人以为是坏开关
        rows.firstOrNull { it.verdict == "not_writable" && it.channel == "permission" }?.let {
            appendAsync("cap-note   = ${it.op}(${it.scope}) 写不进 → ${adviceText(it.advice)}")
        }
        // 只读的非权限型（本机就是不认这次写入）
        rows.firstOrNull { it.verdict == "not_writable" && it.channel == "appop" }?.let {
            appendAsync("cap-note   = ${it.op}(${it.scope}) 写不进 → ${adviceText(it.advice)}")
        }
        appendAsync("cap-file   = ${report.savedPath ?: "未落盘"}")
    }

    private fun adviceText(advice: String): String = when (advice) {
        "use_permission_channel" -> "走 pm revoke/grant（权限通道）"
        "readonly" -> "本机只读，界面上灰掉"
        "unsupported" -> "本机没这条 op"
        "recheck" -> "回读与期望不符，要人工复核"
        else -> advice
    }

    /** 重新读一遍，按作用域取某个 op 的 modeId；-1 表示没找到（可能压根没这条记录） */
    private fun readScopedMode(uid: Int, op: String, scope: String): Int {
        val uidLines = countNonEmpty(Shell.su("appops get --uid $uid").output)
        val all = Shell.su("appops get $SELF_PKG")
        val json = runCatching { NativeEngine.nativeReadOps(all.output, uidLines) }.getOrNull()
        return modeIdOf(json, op, scope)
    }

    /** 在 JSON 的 ops 数组里按 op + scope 精确取 modeId：同名 op 在两段里可以并存，只认指定作用域 */
    private fun modeIdOf(json: String?, op: String, scope: String): Int {
        if (json == null) return -1
        val needleOp = "\"op\":\"$op\""
        val needleScope = "\"scope\":\"$scope\""
        var from = 0
        while (true) {
            val i = json.indexOf(needleOp, from)
            if (i < 0) return -1
            // 这一个对象的范围：从上一个 '{' 到下一个 '}'
            val objStart = json.lastIndexOf('{', i).coerceAtLeast(0)
            val objEnd = json.indexOf('}', i).let { if (it < 0) json.length else it }
            val obj = json.substring(objStart, objEnd)
            if (obj.contains(needleScope)) {
                val k = obj.indexOf("\"modeId\":")
                if (k < 0) return -1
                val start = k + "\"modeId\":".length
                val end = obj.indexOfFirst(start) { it == ',' || it == '}' }
                return obj.substring(start, end).trim().toIntOrNull() ?: -1
            }
            from = objEnd + 1
        }
    }

    private fun countOf(json: String?): Int {
        if (json == null) return -1
        val key = "\"count\":"
        val i = json.indexOf(key)
        if (i < 0) return -1
        val start = i + key.length
        val end = json.indexOfFirst(start) { it == ',' || it == '}' }
        return json.substring(start, end).trim().toIntOrNull() ?: -1
    }

    /** 非空行数：与 Zig 侧「按非空行序号划分作用域」的口径保持一致 */
    private fun countNonEmpty(text: String): Int = text.lines().count { it.isNotBlank() }

    private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (i in from until length) if (pred(this[i])) return i
        return length
    }

    private fun label(mode: Int): String = MODE_LABELS[mode] ?: "?"

    private fun append(s: String) {
        lines.append(s).append('\n')
    }

    private fun appendAsync(s: String) {
        Log.i(TAG, s)
        runOnUiThread {
            lines.append(s).append('\n')
            render()
        }
    }

    private fun render() {
        if (!::tv.isInitialized) {
            tv = TextView(this).apply {
                textSize = 13f
                setPadding(40, 80, 40, 40)
            }
            setContentView(ScrollView(this).apply { addView(tv) })
        }
        tv.text = lines.toString()
        Log.i(TAG, "render: ${lines.length} chars")
    }

    companion object {
        private const val TAG = "WatchCat"
        private const val SELF_PKG = "com.luckyme.watchcat"

        // 自检用哪条 op 写：必须是本机确认「写得进去、读得回来」的
        private const val WRITE_OP = "VIBRATE"

        // 五态文案从 Zig 侧搬到界面层：nativeModeName 已下线（导出名额 8/8）
        private const val MODE_DEFAULT = 0
        private const val MODE_ALLOW = 1
        private const val MODE_IGNORE = 2
        private const val MODE_DENY = 3
        private const val MODE_FOREGROUND = 4
        private const val SCOPE_PACKAGE = 0
        private const val SCOPE_UID = 1

        private val MODE_LABELS = mapOf(
            0 to "默认",
            1 to "允许",
            2 to "忽略（给空数据）",
            3 to "拒绝（抛异常）",
            4 to "仅前台允许",
        )
    }
}
