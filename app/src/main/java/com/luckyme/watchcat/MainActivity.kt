package com.luckyme.watchcat

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自检页：证明 Zig 引擎在真机上「装载 → 解析 → 快照 → 拼写命令」整条链是通的。
 *
 * 两段执行：
 *   1) 主线程：纯计算断言（不碰权限、不碰命令）；
 *   2) 后台线程：root 探测 → 真读本应用自己的 appops → 读出文本交给 Zig 解析 → 真写一次再回读。
 *
 * 写操作只碰**本应用自己**的 CAMERA 这一个 op，并在 finally 里恢复成 default；
 * 不留任何对外部应用的改动，root 弹窗只弹一次。
 */
class MainActivity : Activity() {

    private lateinit var tv: TextView
    private val lines = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        append("设备 ${Build.MANUFACTURER} ${Build.MODEL}")
        append("系统 Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        append("首选 ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        append("全部 ABI ${Build.SUPPORTED_ABIS.joinToString(", ")}")

        val sample = "Uid mode: COARSE_LOCATION: ignore\nCAMERA: deny; time=+1d2h3m ago"

        try {
            val ver = NativeEngine.nativeVersion()
            val ping = NativeEngine.nativePing()
            val tag = NativeEngine.nativeTag()
            val jniVer = NativeEngine.nativeJniVersion()
            val ops = NativeEngine.nativeOpCount()
            val json = NativeEngine.nativeReadOps(sample)
            val snap = NativeEngine.nativeSnapshot("com.luckyme.probe", sample, "2026-11-21T19:59:00+08:00")
            val cmd = NativeEngine.nativeWriteOp("com.luckyme.probe", "CAMERA", MODE_DENY, SCOPE_PACKAGE)
            val badCmd = NativeEngine.nativeWriteOp("com.x; rm -rf /", "CAMERA", MODE_DENY, SCOPE_PACKAGE)

            val ok = ver == 0x00010000 &&
                ping == 0x5743 &&
                "watchcat/0.1.0" == tag &&
                jniVer >= 0x00010006 &&
                ops > 0 &&
                json != null && json.contains("\"count\":2") && json.contains("\"skipped\":0") &&
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

    /**
     * 后台段：root → 真读 → 真写 → 回读。
     * 所有异常都在这里吃掉并转成文字，绝不让线程崩掉把界面留空。
     */
    private fun runRootPhase() {
        if (!Shell.hasSuBinary()) {
            appendAsync("root       = 没装 su 二进制，只能走 Shizuku（未接入）")
            return
        }

        val root = Shell.probeRoot()
        appendAsync("root       = ${if (Shell.isRoot(root)) "OK（uid=0）" else "不可用：${root.output.trim().take(120)}"}")
        if (!Shell.isRoot(root)) {
            appendAsync("           首次会弹 Magisk 授权框；若已拒绝，去 Magisk「超级用户」里给本应用放行")
            return
        }

        // 真读：解析器第一次吃「非样本」的真实输出
        val read = Shell.su("appops get $SELF_PKG")
        val realJson = runCatching { NativeEngine.nativeReadOps(read.output) }.getOrNull()
        appendAsync("realRead   = exit=${read.code} ops=${countOf(realJson)} json=${realJson?.take(200) ?: "null"}")

        // 真写：只动自己的 CAMERA，写完立刻回读，最后一定恢复 default
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val before = NativeEngine.nativeSnapshot(SELF_PKG, read.output, stamp)
        appendAsync("snapshot   = ${if (before == null) "失败（按规范此时不许写）" else "已生成 ${before.length} 字符"}")
        if (before == null) return

        val setCmd = NativeEngine.nativeWriteOp(SELF_PKG, "CAMERA", MODE_IGNORE, SCOPE_PACKAGE)
        if (setCmd == null) {
            appendAsync("writeOp    = 命令生成失败，已放弃写入")
            return
        }
        try {
            val w = Shell.su(setCmd)
            appendAsync("write      = $setCmd -> exit=${w.code} ${w.output.trim().take(80)}")
            val after = Shell.su("appops get $SELF_PKG")
            val afterJson = runCatching { NativeEngine.nativeReadOps(after.output) }.getOrNull()
            val got = modeIdOf(afterJson, "CAMERA")
            appendAsync("readback   = CAMERA modeId=$got ${if (got == MODE_IGNORE) "（与期望一致）" else "（不一致！）"}")
        } finally {
            val restore = NativeEngine.nativeWriteOp(SELF_PKG, "CAMERA", MODE_DEFAULT, SCOPE_PACKAGE)
            if (restore != null) {
                val r = Shell.su(restore)
                appendAsync("restore    = CAMERA -> default, exit=${r.code}")
            }
        }
    }

    /** 从 JSON 里取某个 op 的 modeId，取不到返回 -1（不抛异常，自检页不能因为解析崩） */
    private fun modeIdOf(json: String?, op: String): Int {
        if (json == null) return -1
        val i = json.indexOf("\"op\":\"$op\"")
        if (i < 0) return -1
        val j = json.indexOf("\"modeId\":", i)
        if (j < 0) return -1
        val start = j + "\"modeId\":".length
        val end = json.indexOfFirst(start) { it == ',' || it == '}' }
        return json.substring(start, end).trim().toIntOrNull() ?: -1
    }

    private fun countOf(json: String?): Int {
        if (json == null) return -1
        val k = "\"count\":"
        val i = json.indexOf(k)
        if (i < 0) return -1
        val start = i + k.length
        val end = json.indexOfFirst(start) { it == ',' || it == '}' }
        return json.substring(start, end).trim().toIntOrNull() ?: -1
    }

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
