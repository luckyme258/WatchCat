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

    /** 原值已经是 ignore 时的替代试写档：`deny`。两者必选其一，保证「写前后可区分」 */
    private const val PROBE_ALT_MODE = 3

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
        val channels: List<ChannelProbe.Row>,
        val savedPath: String?,
    )

    data class Row(
        val op: String,
        val scope: String,
        val before: Int,
        val want: Int,
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
    fun run(ctx: Context, uid: Int, pkg: String, permTarget: String = pkg): Report {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val state = readState(uid, pkg).toMutableMap()
        val rows = mutableListOf<Row>()
        val probeLines = StringBuilder()

        val targets = PKG_OPS.map { it to "package" } + UID_OPS.map { it to "uid" }
        for ((op, scope) in targets) {
            val scopeId = if (scope == "uid") 1 else 0
            val before = state[op to scope] ?: 0
            // 试写目标必须与原值不同：原值已经是 ignore 时再写 ignore，
            // 回读当然一致 —— 那会把「写不动」误判成「写得进」（第一轮就在 CAMERA(uid) 上踩到）
            val want = if (before == WANT_MODE) PROBE_ALT_MODE else WANT_MODE

            val setCmd = NativeEngine.nativeWriteOp(pkg, op, want, scopeId)
            if (setCmd == null) {
                rows += Row(op, scope, before, want, before, -1, -1, "skipped", "unknown", "bad_name")
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
                .append(want).append('\t')
                .append(after).append('\n')
            rows += Row(op, scope, before, want, after, w.code, r?.code ?: -1, "pending", "pending", "pending")
        }

        val json = NativeEngine.nativeCapability(pkg, stamp, probeLines.toString())
        // appops 之外的两条通道：权限（pm）与网络/设备（svc / cmd netpolicy）
        val channels = runCatching { ChannelProbe.probePermissions(permTarget, pkg) }.getOrDefault(emptyList()) +
            runCatching { ChannelProbe.probeNetwork(uid) }.getOrDefault(emptyList())

        val v2 = buildV2Json(pkg, uid, stamp, json, channels)
        val saved = save(ctx, json, v2)

        // 把 Zig 侧的结论回填到行上：界面按行画灯，不再自己推断
        val judged = merge(rows, json)
        return Report(json, probeLines.toString(), judged, channels, saved)
    }

    /**
     * 能力表 v2：appops 行（Zig 判定）与 channel 行（Kotlin 判定，状态只有 true/false 或 1/0，不存在五态）
     * 拼成一个文件，界面与诊断都只读这一份。
     */
    private fun buildV2Json(pkg: String, uid: Int, stamp: String, appopsJson: String?, channels: List<ChannelProbe.Row>): String {
        val sb = StringBuilder()
        sb.append("{\"v\":2,\"pkg\":\"").append(esc(pkg)).append("\",\"uid\":").append(uid)
            .append(",\"stamp\":\"").append(esc(stamp)).append("\",\"appops\":")
            .append(appopsJson ?: "null")
            .append(",\"channels\":[")
        channels.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"channel\":\"").append(esc(r.channel))
                .append("\",\"target\":\"").append(esc(r.target))
                .append("\",\"before\":\"").append(esc(r.before))
                .append("\",\"want\":\"").append(esc(r.want))
                .append("\",\"after\":\"").append(esc(r.after))
                .append("\",\"writeCmd\":\"").append(esc(r.writeCmd))
                .append("\",\"readCmd\":\"").append(esc(r.readCmd))
                .append("\",\"verdict\":\"").append(esc(r.verdict))
                .append("\",\"note\":\"").append(esc(r.note)).append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 极简 JSON 转义：命令里会出现引号/反斜杠（比如 grep '$perm:'），不转义就会写出坏 JSON */
    private fun esc(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(' ') else out.append(c)
            }
        }
        return out.toString()
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

    /** 用引擎 JSON 里的结论覆盖占位值（数值列保留探测时记下的事实，不被覆盖） */
    private fun merge(rows: List<Row>, json: String?): List<Row> {
        if (json == null) return rows
        val seen = mutableMapOf<Pair<String, String>, Triple<String, String, String>>()
        JUDGED_ROW.findAll(json).forEach { m ->
            // 组序：1=op 2=scope 3=channel 4=verdict 5=advice
            seen[m.groupValues[1] to m.groupValues[2]] =
                Triple(m.groupValues[4], m.groupValues[3], m.groupValues[5])
        }
        return rows.map { r ->
            val j = seen[r.op to r.scope] ?: return@map r
            r.copy(verdict = j.first, channel = j.second, advice = j.third)
        }
    }

    private fun save(ctx: Context, v1: String?, v2: String): String? {
        return runCatching {
            val f = File(ctx.filesDir, "capability_v2.json")
            f.writeText(v2)
            // v1 保留一份，兼容上一轮的读取口径；v2 才是新的全量表
            v1?.let { runCatching { File(ctx.filesDir, "capability_v1.json").writeText(it) } }
            // 外部目录再放一份：run-as 在本机被 SELinux 挡了（走不通），要取证只能从这里 adb pull
            ctx.getExternalFilesDir(null)?.let { dir ->
                runCatching { File(dir, "capability_v2.json").writeText(v2) }
                v1?.let { runCatching { File(dir, "capability_v1.json").writeText(it) } }
            }
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
