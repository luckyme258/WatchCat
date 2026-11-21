package com.luckyme.watchcat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 能力探测：把「本机到底能把哪条 op 改成什么」变成一张可存、可看的表。
 *
 * 为什么必须探测，而不是照抄一张 op 表（规范 W8.3）：
 * 同一条 op 在不同 ROM / API 上有三种完全不同的下场 ——
 *   1. 写得进：`appops set` 落到存储，按作用域回读与期望一致；
 *   2. 写不进：命令返回 0，但回读纹丝不动。Android 15/16 上运行时权限型 op
 *      （CAMERA、RECORD_AUDIO…）属于这一类：状态由权限系统持有，
 *      `com.android.server.permission.access.appop.AppOpService.setPackageMode`
 *      直接判为「should not be set directly」后丢弃（2026-11-21 在本机 logcat 实测）；
 *      这类要改只能走 `pm revoke/grant` 那条权限通道；
 *   3. 压根没有这条 op：本机不认这个名字。
 * 界面若把 2 当 1 画成开关，用户点了没反应，还以为应用坏了。
 *
 * 探测纪律：
 *   - 只探**本应用自己**（com.luckyme.watchcat），不去动别人的权限；
 *   - 每探一条：读 → 写 `ignore` → 按同一作用域回读 → **立即恢复原值**；
 *   - 结论一律以「写入 + 回读」为准，命令的 rc 不参与判定；
 *   - 结果落盘 `files/capability_v1.json`，换 ROM / 换 API 要重探（表里带设备指纹）。
 */
object CapabilityProbe {

    /** 探测试写用的目标档：`ignore`（给空数据）——比 `deny` 温和，误写也不至于让应用崩 */
    private const val WANT_MODE = 2

    /** 探测清单：候选 op 表全部 + 三条 uid 作用域对照（uid 与包是两份存储，结论可能不同） */
    private val PKG_OPS = listOf(
        "CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION",
        "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "READ_SMS", "SEND_SMS",
        "READ_CALENDAR", "READ_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
        "POST_NOTIFICATION", "WAKE_LOCK", "VIBRATE",
    )
    private val UID_OPS = listOf("CAMERA", "RECORD_AUDIO", "VIBRATE")

    data class Report(
        val json: String?,
        val probeText: String,
        val rows: List<Row>,
        val savedPath: String?,
    )

    data class Row(
        val op: String,
        val scope: String,
        val before: Int,
        val after: Int,
        val writeCode: Int,
        val restoreCode: Int,
        val verdict: String,
        val channel: String,
        val advice: String,
    )

    /**
     * 跑一轮完整探测。**必须在 root/Shizuku 已授权的后台线程里调用**（每条 op 要跑 3 条命令）。
     *
     * @param uid 被探测应用的 uid（本应用自己）
     * @param ctx 用于拿 filesDir 落盘
     */
    fun run(ctx: Context, uid: Int, pkg: String): Report {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val state = readState(uid, pkg).toMutableMap()
        val rows = mutableListOf<Row>()
        val probeLines = StringBuilder()

        val targets = PKG_OPS.map { it to "package" } + UID_OPS.map { it to "uid" }
        for ((op, scope) in targets) {
            val scopeId = if (scope == "uid") 1 else 0
            val before = state[op to scope] ?: 0

            val setCmd = NativeEngine.nativeWriteOp(pkg, op, WANT_MODE, scopeId)
            if (setCmd == null) {
                rows += Row(op, scope, before, before, -1, -1, "skipped", "unknown", "bad_name")
                continue
            }
            val w = Shell.su(setCmd)
            // 回读必须重新读全量（uid 段行数也要重新数，写操作可能增删记录改变行数）
            val after = readState(uid, pkg)[op to scope] ?: 0
            state[op to scope] = after

            // 恢复：写回探测前的档（原来是「没有记录」就写 default，语义等价）
            val restoreCmd = NativeEngine.nativeWriteOp(pkg, op, before, scopeId)
            val r = if (restoreCmd == null) null else Shell.su(restoreCmd)

            probeLines.append(op).append('\t')
                .append(scope).append('\t')
                .append(before).append('\t')
                .append(WANT_MODE).append('\t')
                .append(after).append('\n')
            rows += Row(op, scope, before, after, w.code, r?.code ?: -1, "pending", "pending", "pending")
        }

        val json = NativeEngine.nativeCapability(pkg, stamp, probeLines.toString())
        val saved = json?.let { save(ctx, it) }

        // 把 Zig 侧的结论回填到行上：界面按行画灯，不再自己推断
        val judged = merge(rows, json)
        return Report(json, probeLines.toString(), judged, saved)
    }

    /** 读当前状态：op+scope → modeId。缺记录当 0（default），与「没有记录」的语义一致 */
    private fun readState(uid: Int, pkg: String): Map<Pair<String, String>, Int> {
        val uidLines = countNonEmpty(Shell.su("appops get --uid $uid").output)
        val all = Shell.su("appops get $pkg")
        val json = runCatching { NativeEngine.nativeReadOps(all.output, uidLines) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<Pair<String, String>, Int>()
        OP_ROW.findAll(json).forEach { m ->
            out[m.groupValues[1] to m.groupValues[3]] = m.groupValues[2].toIntOrNull() ?: 0
        }
        return out
    }

    /** 用引擎 JSON 里的结论覆盖占位值 */
    private fun merge(rows: List<Row>, json: String?): List<Row> {
        if (json == null) return rows
        val seen = mutableMapOf<Pair<String, String>, Row>()
        JUDGED_ROW.findAll(json).forEach { m ->
            seen[m.groupValues[1] to m.groupValues[2]] = Row(
                op = m.groupValues[1],
                scope = m.groupValues[2],
                before = 0, after = 0, writeCode = 0, restoreCode = 0,
                verdict = m.groupValues[4],
                channel = m.groupValues[3],
                advice = m.groupValues[5],
            )
        }
        return rows.map { r ->
            val j = seen[r.op to r.scope] ?: return@map r
            r.copy(verdict = j.verdict, channel = j.channel, advice = j.advice)
        }
    }

    private fun save(ctx: Context, json: String): String? {
        return runCatching {
            val f = File(ctx.filesDir, "capability_v1.json")
            f.writeText(json)
            f.absolutePath
        }.getOrNull()
    }

    private fun countNonEmpty(text: String): Int = text.lines().count { it.isNotBlank() }

    /** 从 `nativeReadOps` 的 JSON 里抠 op / modeId / scope —— 结构固定，用正则比手写解析器稳 */
    private val OP_ROW = Regex(
        "\"op\":\"([A-Z0-9_]+)\",\"mode\":\"[a-z]+\",\"modeId\":(\\d+),\"scope\":\"(package|uid)\""
    )

    /** 从 `nativeCapability` 的 JSON 里抠 op / scope / verdict / channel / advice */
    private val JUDGED_ROW = Regex(
        "\"op\":\"([A-Z0-9_]+)\",\"scope\":\"(package|uid)\".*?\"channel\":\"(appop|permission|unknown)\",\"verdict\":\"(writable|not_writable|partial)\",\"advice\":\"([a-z_]+)\""
    )
}
