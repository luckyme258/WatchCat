package com.luckyme.watchcat

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据层：所有读写在系统里的落点都收在这一个对象里，界面不碰命令。
 *
 * 三条铁律（W8.3 / W9）：
 *   1. 每次写入前后都**各读一次真实状态**，只有回读一致才算成功，命令 rc 不参与判定；
 *   2. 写操作一律记审计（W6 的固定格式）；
 *   3. 禁写名单（Catalog.forbiddenReason）在写入口**再拦一次**，不靠界面灰按钮兜底。
 */
object Engine {

    data class OpState(val op: String, val scope: String, val modeId: Int, val time: String = "")

    data class PermState(val perm: String, val granted: Boolean, val fixed: Boolean)

    data class Outcome(
        val ok: Boolean,
        val cmd: String,
        val before: String,
        val after: String,
        val msg: String = "",
    )

    /** 当前身份；界面顶部要常显（W10.1）。只认 uid=0，不看有没有 su 文件 */
    fun identity(): String {
        if (!Shell.hasSuBinary()) return "none"
        return if (Shell.isRoot(Shell.probeRoot())) "root" else "denied"
    }

    fun identityText(): String = when (identity()) {
        "root" -> "root · uid=0"
        "denied" -> "su 存在但未授权"
        else -> "未检测到 su（需 Magisk/KernelSU）"
    }

    /**
     * 读某个应用的全部 op。
     *
     * 为什么要传 uid：`appops get` 的输出里 uid 段与包段同名 op 会并存，
     * 必须先用 `appops get --uid <uid>` 数出 uid 段行数，交给 Zig 划边界（否则作用域整体错位）。
     */
    fun readOps(pkg: String, uid: Int): List<OpState> {
        val uidLines = Shell.su("appops get --uid $uid").output.lines().count { it.isNotBlank() }
        val raw = Shell.su("appops get $pkg").output
        val json = runCatching { NativeEngine.nativeReadOps(raw, uidLines) }.getOrNull() ?: return emptyList()
        val times = parseTimes(raw)
        val out = ArrayList<OpState>()
        ROW.findAll(json).forEach { m ->
            val op = m.groupValues[1]
            val scope = m.groupValues[4]
            val id = m.groupValues[3].toIntOrNull() ?: 0
            out += OpState(op, scope, id, times[op to scope] ?: "")
        }
        return out
    }

    /** 读运行时权限：granted 与 flags（SYSTEM_FIXED 的权限 pm 也改不动） */
    fun readPerms(pkg: String): Map<String, PermState> {
        val out = Shell.su("dumpsys package $pkg").output
        val map = HashMap<String, PermState>()
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("android.permission.") || !t.contains("granted=")) continue
            val colon = t.indexOf(':')
            if (colon <= 0) continue
            val perm = t.substring(0, colon).trim()
            if (!perm.startsWith("android.permission.")) continue
            val rest = t.substring(colon + 1)
            map[perm] = PermState(
                perm = perm,
                granted = rest.contains("granted=true"),
                fixed = rest.contains("SYSTEM_FIXED") || rest.contains("SYSTEM_POLICY"),
            )
        }
        return map
    }

    fun currentMode(pkg: String, uid: Int, op: String, scope: String): Int =
        readOps(pkg, uid).firstOrNull { it.op == op && it.scope == scope }?.modeId ?: Catalog.MODE_DEFAULT

    /**
     * 写一个 op 的五态。
     *
     * @param scopeOne 0=包作用域 1=uid 作用域（命令由 Zig 侧校验并拼装，这里不自己拼串）
     */
    fun writeOp(ctx: Context, pkg: String, uid: Int, op: String, modeId: Int, scopeOne: Int, src: String = "manual"): Outcome {
        Catalog.forbiddenReason(ctx, pkg)?.let {
            return Outcome(false, "-", "", "", it)
        }
        val scopeName = if (scopeOne == 1) "uid" else "package"
        val before = currentMode(pkg, uid, op, scopeName)
        val cmd = NativeEngine.nativeWriteOp(pkg, op, modeId, scopeOne)
            ?: return Outcome(false, "-", Catalog.modeLabel(before), "", "命令生成被引擎拒绝（名字里含非法字符）")

        val r = Shell.su(cmd)
        val after = currentMode(pkg, uid, op, scopeName)
        val ok = after == modeId
        Audit.append(ctx, pkg, op, Catalog.modeLabel(before), Catalog.modeLabel(after), src)

        val msg = when {
            ok -> ""
            r.timedOut -> "命令超时（可能卡在授权弹窗）"
            else -> "回读与期望不一致：写 ${Catalog.modeLabel(modeId)}，实得 ${Catalog.modeLabel(after)}"
        }
        return Outcome(ok, cmd, Catalog.modeLabel(before), Catalog.modeLabel(after), msg)
    }

    /** 写运行时权限（权限通道）。返回前后 granted，便于界面显示「系统固定」这类失败原因 */
    fun writePerm(ctx: Context, pkg: String, perm: String, grant: Boolean, src: String = "manual"): Outcome {
        Catalog.forbiddenReason(ctx, pkg)?.let {
            return Outcome(false, "-", "", "", it)
        }
        if (perm in Catalog.SPECIAL) {
            return Outcome(false, "-", "", "", "特殊权限不走 pm，需要用各自专属入口")
        }
        val before = readPerms(pkg)[perm]
        if (before == null) {
            return Outcome(false, "-", "-", "-", "该应用没有申请这条权限")
        }
        if (before.fixed) {
            return Outcome(false, "-", before.granted.toString(), before.granted.toString(), "系统固定授权（SYSTEM_FIXED），改不动")
        }
        val cmd = (if (grant) "pm grant $pkg $perm" else "pm revoke $pkg $perm")
        val r = Shell.su(cmd)
        val after = readPerms(pkg)[perm]
        val ok = after?.granted == grant
        Audit.append(ctx, pkg, perm.substringAfterLast('.'), before.granted.toString(), after?.granted.toString() ?: "-", src)
        val msg = when {
            ok -> ""
            r.timedOut -> "命令超时"
            else -> "回读未变（可能被杀进程/系统固定）：" + firstError(r.output)
        }
        return Outcome(ok, cmd, before.granted.toString(), after?.granted.toString() ?: "-", msg)
    }

    fun forceStop(pkg: String): Outcome {
        val r = Shell.su("am force-stop $pkg")
        return Outcome(r.ok, "am force-stop $pkg", "", "", if (r.ok) "" else firstError(r.output))
    }

    /**
     * 后台流量白名单：系统只提供「限制后台流量时豁免谁」这一条通道（W8.3）。
     * 它**不是**按应用断网 —— 界面文案必须写清，否则用户会以为开关一关就断网。
     */
    fun bgTrafficAllowed(uid: Int): Boolean {
        val out = Shell.su("cmd netpolicy list restrict-background-whitelist").output
        return Regex("(^|[^0-9])$uid([^0-9]|$)").containsMatchIn(out)
    }

    fun setBgTraffic(uid: Int, allow: Boolean): Outcome {
        val before = bgTrafficAllowed(uid)
        val cmd = if (allow) {
            "cmd netpolicy add restrict-background-whitelist $uid"
        } else {
            "cmd netpolicy remove restrict-background-whitelist $uid"
        }
        val r = Shell.su(cmd)
        val after = bgTrafficAllowed(uid)
        return Outcome(after == allow, cmd, before.toString(), after.toString(), if (after == allow) "" else "回读未变：${firstError(r.output)}")
    }

    /** 最近使用时间：从 `appops get` 原文里抠，`CAMERA: allow; time=+1d2h3m ago` */
    private fun parseTimes(raw: String): Map<Pair<String, String>, String> {
        val out = HashMap<Pair<String, String>, String>()
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || !t.contains("time=")) continue
            val uidMode = t.startsWith("Uid mode: ")
            val body = if (uidMode) t.removePrefix("Uid mode: ") else t
            val colon = body.indexOf(':')
            if (colon <= 0) continue
            val op = body.substring(0, colon).trim()
            if (!op.matches(OP_NAME)) continue
            val time = body.substringAfter("time=", "").substringBefore(';').trim()
            out[op to (if (uidMode) "uid" else "package")] = time
        }
        return out
    }

    private fun firstError(out: String): String {
        val m = Regex("(SecurityException|IllegalArgumentException|Exception|Error): *([^\\n]*)").find(out)
        return m?.value?.take(100) ?: out.trim().lines().firstOrNull { it.isNotBlank() }?.take(100) ?: ""
    }

    private val OP_NAME = Regex("[A-Z0-9_]+")

    /** 与 Zig `nativeReadOps` 输出对齐：op / mode / modeId / scope */
    private val ROW = Regex(
        "\"op\":\"([A-Z0-9_]+)\",\"mode\":\"[a-z]+\",\"modeId\":(\\d+),\"scope\":\"(package|uid)\""
    )
}

/**
 * 审计（W6）：每次写操作追加一行，格式固定；
 * 同时镜像一份到外部目录，取证时 `adb pull` 就能拿到。
 */
object Audit {

    private const val NAME = "watchcat-audit.log"

    fun file(ctx: Context) = java.io.File(ctx.filesDir, NAME)

    fun append(ctx: Context, pkg: String, op: String, old: String, new: String, src: String) {
        val line = buildString {
            append(stamp()).append(' ')
            append("pkg=").append(pkg).append(' ')
            append("op=").append(op).append(' ')
            append("old=").append(old).append(' ')
            append("new=").append(new).append(' ')
            append("src=").append(src).append(' ')
            append("caller=").append(if (Engine.identity() == "root") "root" else "shell")
            append('\n')
        }
        runCatching {
            file(ctx).appendText(line)
            ctx.getExternalFilesDir(null)?.let { java.io.File(it, NAME).appendText(line) }
        }
        android.util.Log.i("WatchCat", "audit: ${line.trim()}")
    }

    fun read(ctx: Context, limit: Int = 300): List<String> =
        runCatching {
            if (!file(ctx).exists()) emptyList()
            else file(ctx).readLines().takeLast(limit).reversed()
        }.getOrDefault(emptyList())

    fun clear(ctx: Context) {
        runCatching { file(ctx).writeText("") }
    }

    fun export(ctx: Context): String? = runCatching {
        val dir = ctx.getExternalFilesDir(null) ?: return@runCatching null
        val f = java.io.File(dir, NAME)
        f.writeText(file(ctx).takeIf { it.exists() }?.readText() ?: "")
        f.absolutePath
    }.getOrNull()

    /**
     * 一键回滚：只回退「本应用改过的」，逐条写回 default（W9.2 最后一条）。
     * 同一个 pkg+op 取最后一次写入，避免把中间态也翻一遍。
     */
    fun rollbackTargets(ctx: Context): List<Triple<String, String, Int>> {
        val last = LinkedHashMap<Pair<String, String>, Int>()
        read(ctx, 5000).forEach { line ->
            val pkg = between(line, "pkg=", ' ')
            val op = between(line, "op=", ' ')
            val newLabel = between(line, "new=", ' ')
            if (pkg.isBlank() || op.isBlank()) return@forEach
            val mode = Catalog.MODE_LABELS.indexOfFirst { it == newLabel }
            if (mode >= 0) last[pkg to op] = mode
        }
        return last.map { (k, mode) -> Triple(k.first, k.second, mode) }
    }

    private fun between(s: String, key: String, end: Char): String {
        val i = s.indexOf(key)
        if (i < 0) return ""
        val from = i + key.length
        val to = s.indexOf(end, from).let { if (it < 0) s.length else it }
        return s.substring(from, to).trim()
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    fun deviceHeader(): String = buildString {
        append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" api=").append(Build.VERSION.SDK_INT)
        append(" release=").append(Build.VERSION.RELEASE)
    }
}
