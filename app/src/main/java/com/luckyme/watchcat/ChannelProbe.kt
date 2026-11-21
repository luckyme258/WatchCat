package com.luckyme.watchcat

/**
 * 通道探测：appops 之外那几条路的“写 → 回读 → 还原”闭环。
 *
 * 为什么要单独一层：
 *   appops 管的是「行为档」，权限管的是「有没有这个能力」，网络开关管的是「设备状态」，
 *   三者的**回读源完全不同**。用 appops 的回读方式去验权限通道，只会验出假结论。
 *
 * 本轮覆盖（都在本应用自己身上，不碰其他应用）：
 *   permission：CAMERA / POST_NOTIFICATIONS / SYSTEM_ALERT_WINDOW
 *              写 = `pm revoke`，读 = `dumpsys package` 里的 granted，还原 = `pm grant`
 *   net        ：移动数据、Wi-Fi、按 uid 的后台流量
 *              写 = `svc data` / `svc wifi` / `cmd netpolicy`，读 = `settings get` / `cmd netpolicy list`
 *
 * 纪律：
 *   - 每条都先读原值，写完再读，最后**必须**还原；
 *   - Wi-Fi 关闭会掐断本机 adb（调试走无线），所以断/开放在**同一条 su 命令**里连续执行，
 *     中间 sleep 2 秒，失败也只留 2 秒空窗，不会把人锁在外面；
 *   - 结论只认回读，命令 rc 与 stdout 都不参与判定。
 */
object ChannelProbe {

    data class Row(
        val channel: String,
        val target: String,
        val before: String,
        val want: String,
        val after: String,
        val writeCmd: String,
        val readCmd: String,
        val verdict: String,
        val note: String = "",
    )

    /**
     * 权限通道：grant/revoke → 回读 granted → 还原。
     *
     * **同一件事不能拿本应用自己做靶子**：撤销一个正在运行的应用的运行时权限，系统会直接
     * 以「permissions revoked」把该进程杀掉（实测日志 `ActivityManager: Killing … (adj 0): permissions revoked`）。
     * 所以 targetPkg 等于自身时一律跳过，并把这句原因写进表里，免得以后有人以为没验。
     *
     * 已在本机用「停进程 + 外部执行」验证过闭环：CAMERA granted=false → `pm grant` → granted=true 可回读。
     */
    fun probePermissions(targetPkg: String, selfPkg: String): List<Row> {
        if (targetPkg == selfPkg) {
            return listOf(
                Row(
                    channel = "permission",
                    target = "self",
                    before = "-", want = "-", after = "-",
                    writeCmd = "pm revoke/grant <target> <perm>",
                    readCmd = "dumpsys package <target> | grep '<perm>:'",
                    verdict = "skipped",
                    note = "不能拿自身做靶子：撤销运行中应用的运行时权限会被系统杀进程（permissions revoked）；需选其他应用，或停掉应用后由外部执行",
                )
            )
        }
        val perms = listOf(
            "android.permission.CAMERA",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.SYSTEM_ALERT_WINDOW",
        )
        return perms.map { perm ->
            val readCmd = "dumpsys package $targetPkg | grep '$perm:'"
            val before = readGrant(targetPkg, perm)
            // 试写档必须与原值相反：原值已是 false 还去 revoke，回读当然一样（假阳性）
            val want = if (before == "true") "false" else "true"
            val writeCmd = if (want == "true") "pm grant $targetPkg $perm" else "pm revoke $targetPkg $perm"
            val w = Shell.su(writeCmd)
            val afterWrite = readGrant(targetPkg, perm)
            val restoreCmd = if (before == "true") "pm grant $targetPkg $perm" else "pm revoke $targetPkg $perm"
            val r = Shell.su(restoreCmd)
            val restored = readGrant(targetPkg, perm)

            val err = firstError(w.output) ?: firstError(r.output)
            val verdict = when {
                before == "-" -> "skipped"
                afterWrite == want && restored == before -> "writable"
                else -> "not_writable"
            }
            Row(
                channel = "permission",
                target = perm.substringAfterLast('.'),
                before = before,
                want = want,
                after = afterWrite,
                writeCmd = writeCmd,
                readCmd = readCmd,
                verdict = verdict,
                note = buildString {
                    if (err != null) append("写入被拒：").append(err)
                    if (verdict != "writable") {
                        if (isNotEmpty()) append("；")
                        append("回读未变（还原后=").append(restored).append("）")
                    }
                },
            )
        }
    }

    /** 网络/设备通道：移动数据、Wi-Fi 断/开、按 uid 的后台流量 */
    fun probeNetwork(uid: Int): List<Row> {
        val rows = mutableListOf<Row>()

        // ---- 移动数据 ----
        // 2026-11-21 实测：本机无 SIM（gsm.sim.state=ABSENT），`svc data enable` 当然不动
        // mobile_data（0→0）。这不是「写不进」，是**样本无效**，必须记 skipped 而不是 not_writable。
        val dataRead = "settings get global mobile_data"
        val sim = Shell.su("getprop gsm.sim.state").output.trim()
        if (sim.isEmpty() || sim.contains("ABSENT") || sim.contains("NOT_READY")) {
            rows += Row(
                channel = "net", target = "mobile_data",
                before = Shell.su(dataRead).output.trim(), want = "-", after = "-",
                writeCmd = "svc data enable/disable", readCmd = dataRead,
                verdict = "skipped",
                note = "无可用 SIM（gsm.sim.state=$sim）：没有卡就没有数据链路可验证，不能记成写不进",
            )
        } else {
            val beforeData = Shell.su(dataRead).output.trim()
            val wantData = if (beforeData == "1") "0" else "1"
            val dataCmd = if (wantData == "1") "svc data enable" else "svc data disable"
            val w1 = Shell.su(dataCmd)
            val afterData = Shell.su(dataRead).output.trim()
            Shell.su(if (beforeData == "1") "svc data enable" else "svc data disable")
            val restoredData = Shell.su(dataRead).output.trim()
            rows += Row(
                channel = "net", target = "mobile_data",
                before = beforeData, want = wantData, after = afterData,
                writeCmd = dataCmd, readCmd = dataRead,
                verdict = if (afterData == wantData && restoredData == beforeData) "writable" else "not_writable",
                note = firstError(w1.output) ?: "",
            )
        }

        // ---- Wi-Fi 断/开 ----
        // 踩过的坑（2026-11-21）：旧写法 `disable; sleep 2; enable` 只回读了「开关=1」，
        // 但手机重连后 DHCP 换了 IP（.3 → .250），无线 adb 连旧 IP 自然连不上 ——
        // 看起来像「没恢复」，其实是**地址漂移**。所以现在分两步取证：
        //   ① 断开后必须回读到 0（证明真的断了）
        //   ② 开启后必须回读到 1，并记录新 IP（证明它真的回来了）
        // 并且断开与开启各自独立执行，任何一步异常都不会把 Wi-Fi 留在关闭态。
        val wifiRead = "settings get global wifi_on"
        val beforeWifi = Shell.su(wifiRead).output.trim()
        val ipBefore = wifiIp()
        var offVal = "-"
        var onVal = beforeWifi
        val wifiCmd = "svc wifi disable; sleep 3; settings get global wifi_on; " +
            "svc wifi enable; sleep 6; settings get global wifi_on; cmd wifi set-wifi-enabled enabled"
        val w2 = try {
            val off = Shell.su("svc wifi disable; sleep 3; settings get global wifi_on", timeoutMs = 20_000)
            offVal = lastValue(off.output)
            val on = Shell.su("svc wifi enable; sleep 6; settings get global wifi_on", timeoutMs = 25_000)
            onVal = lastValue(on.output)
            on
        } finally {
            // 兜底：只要最后不是 1，就再拉一次；宁可在探测里多花 5 秒，也不能把机器留在离线态
            if (Shell.su(wifiRead).output.trim() != "1") {
                Shell.su("svc wifi enable; cmd wifi set-wifi-enabled enabled; sleep 5; settings get global wifi_on", timeoutMs = 20_000)
            }
        }
        val afterWifi = Shell.su(wifiRead).output.trim()
        val ipAfter = wifiIp()
        rows += Row(
            channel = "net", target = "wifi",
            before = beforeWifi, want = "1", after = afterWifi,
            writeCmd = wifiCmd, readCmd = wifiRead,
            verdict = if (offVal == "0" && afterWifi == "1") "writable" else "not_writable",
            note = buildString {
                append("断开回读=").append(offVal).append("，开启回读=").append(onVal)
                append("；IP ").append(ipBefore.ifBlank { "?" }).append(" → ").append(ipAfter.ifBlank { "?" })
                if (ipBefore.isNotBlank() && ipAfter.isNotBlank() && ipBefore != ipAfter) {
                    append("（重连后地址变了：无线 adb 必须按新 IP 连，不是没恢复）")
                }
                if (w2.timedOut) append("；命令超时")
            },
        )

        // ---- 按 uid 的后台流量白名单 ----
        val netRead = "cmd netpolicy list restrict-background-whitelist"
        val bgCmd = "cmd netpolicy add restrict-background-whitelist $uid"
        val rmCmd = "cmd netpolicy remove restrict-background-whitelist $uid"
        val beforeBg = Shell.su(netRead).output
        val w3 = Shell.su(bgCmd)
        val afterBg = Shell.su(netRead).output
        Shell.su(rmCmd)
        val restoredBg = Shell.su(netRead).output
        val hasUid = { s: String -> Regex("(^|[^0-9])$uid([^0-9]|$)").containsMatchIn(s) }
        rows += Row(
            channel = "net",
            target = "restrict_background:$uid",
            before = if (hasUid(beforeBg)) "in" else "out",
            want = "in",
            after = if (hasUid(afterBg)) "in" else "out",
            writeCmd = bgCmd,
            readCmd = netRead,
            verdict = if (hasUid(afterBg) && !hasUid(restoredBg)) "writable" else "not_writable",
            note = firstError(w3.output) ?: "",
        )

        return rows
    }

    /** 取命令输出的最后一行有效值（同一条 su 里跑多步时，只有最后一步的 stdout 是结论） */
    private fun lastValue(out: String): String =
        out.lines().lastOrNull { it.trim().isNotEmpty() }?.trim() ?: "-"

    /** 当前 Wi-Fi 的 IPv4 地址；拿不到返回空串（未连上/无权限都算拿不到） */
    private fun wifiIp(): String {
        val out = Shell.su("cmd wifi status").output
        val m = Regex("IP: /([0-9.]+)").find(out)
        return m?.groupValues?.getOrNull(1) ?: ""
    }

    /** 从 dumpsys package 里读某个权限的 granted 状态；读不到用 "-"（不等于 false） */
    private fun readGrant(pkg: String, perm: String): String {        val out = Shell.su("dumpsys package $pkg").output
        val line = out.lineSequence().firstOrNull { it.trim().startsWith("$perm:") }?.trim() ?: return "-"
        return when {
            line.contains("granted=true") -> "true"
            line.contains("granted=false") -> "false"
            else -> "-"
        }
    }

    /** 命令失败时把异常类型抠出来（pm 的拒绝是 SecurityException，会打到 stdout） */
    private fun firstError(out: String): String? {
        val e = Regex("(SecurityException|IllegalArgumentException|Exception): *([^\\n]*)").find(out)
        return e?.groupValues?.let { "${it[1]}: ${it[2].take(60)}" }
    }
}
