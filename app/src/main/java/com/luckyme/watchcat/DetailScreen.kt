package com.luckyme.watchcat

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 应用详情（W15.1-A2/A5 + B6）：
 *   上面是「它是谁、怎么处置它」；中间按权限组排开，每组一句人话；
 *   下面才是 appops 的五态 —— 因为五态是进阶操作，不该第一个撞到用户脸上。
 *
 * 所有开关都遵守「写后回读才算成功」：写不进去就弹提示并把开关弹回去，
 * 绝不出现「界面显示已禁止、系统里其实没变」的假象。
 */
class DetailScreen(
    private val act: MainActivity,
    private val pkg: String,
    private val uid: Int,
    private val label: String,
) {

    private val root = Ui.col(act)
    private val container = Ui.col(act)
    private val status = Ui.tv(act, "读取中…", 11.5f, Ui.FAINT)
    private val dyn = Ui.col(act)

    private var requested: List<String> = emptyList()
    private var perms: Map<String, Engine.PermState> = emptyMap()
    private var ops: List<Engine.OpState> = emptyList()
    private var bgAllowed = false
    private var identity = "none"
    private var busy = false

    init {
        build()
        refresh()
    }

    fun view(): View = root

    private fun build() {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), 0)

        val hero = Ui.card(act, 16)
        val top = Ui.row(act)
        val img = ImageView(act)
        img.setImageDrawable(runCatching { act.packageManager.getApplicationIcon(pkg) }.getOrNull())
        val ilp = LinearLayout.LayoutParams(Ui.dp(act, 52), Ui.dp(act, 52))
        ilp.rightMargin = Ui.dp(act, 14)
        img.layoutParams = ilp
        top.addView(img)

        val info = Ui.col(act)
        info.addView(Ui.tv(act, label, 18f, Ui.TEXT, bold = true))
        val pkgView = Ui.tv(act, pkg, 11.5f, Ui.MUTED)
        pkgView.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 6))
        info.addView(pkgView)
        val pills = Ui.row(act)
        pills.addView(Ui.pill(act, "uid $uid"))
        pills.addView(gap(6))
        val system = runCatching {
            (act.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
        if (system) pills.addView(Ui.pill(act, "系统应用", Ui.MUTED))
        info.addView(pills)
        top.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(top)

        val actions = Ui.row(act)
        actions.setPadding(0, Ui.dp(act, 14), 0, 0)
        actions.addView(Ui.button(act, "打开") {
            val i = act.packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) act.toast("这个应用没有可启动的界面") else runCatching { act.startActivity(i) }
        })
        actions.addView(gap(8))
        actions.addView(Ui.button(act, "强制停止", danger = true) {
            confirm("强制停止？", "$label 的进程会被立刻杀掉。它可能丢数据，也可能被系统重新拉起。") {
                act.bg {
                    val o = Engine.forceStop(pkg)
                    act.ui { act.toast(if (o.ok) "已发送强制停止" else "失败：${o.msg}") }
                }
            }
        })
        actions.addView(gap(8))
        actions.addView(Ui.button(act, "系统设置") {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            runCatching { act.startActivity(i) }
        })
        actions.addView(gap(8))
        actions.addView(Ui.button(act, "卸载") {
            confirm("卸载 $label？", "会走系统卸载流程，需要你确认。") {
                runCatching { act.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
            }
        })
        hero.addView(actions)
        root.addView(hero)

        root.addView(status)
        root.addView(Ui.spacer(act, 6))

        val scroll = ScrollView(act)
        container.setPadding(0, 0, 0, Ui.dp(act, 20))
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun refresh() {
        busy = true
        status.text = "读取中：权限与 appops 都要现读一次，不用缓存…"
        act.bg {
            identity = Engine.identity()
            val req = runCatching {
                act.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
            val p = if (identity == "root") Engine.readPerms(pkg) else emptyMap()
            val o = if (identity == "root") Engine.readOps(pkg, uid) else emptyList()
            val bg = if (identity == "root") Engine.bgTrafficAllowed(uid) else false
            act.ui {
                requested = req
                perms = p
                ops = o
                bgAllowed = bg
                busy = false
                status.text = if (identity == "root") {
                    "已读取 ${req.size} 条申请权限 / ${o.size} 条 op 记录"
                } else {
                    "没有 root：只能看，不能改"
                }
                status.setTextColor(if (identity == "root") Ui.FAINT else Ui.WARN)
                render()
            }
        }
    }

    private fun render() {
        container.removeAllViews()

        val reason = Catalog.forbiddenReason(act, pkg)
        if (reason != null) {
            val warn = Ui.card(act)
            warn.background = Ui.fill(Ui.alpha(Ui.WARN, 0x14), Ui.dp(act, 16), Ui.WARN, Ui.dp(act, 1))
            warn.addView(Ui.tv(act, "只读应用", 13.5f, Ui.WARN, bold = true))
            warn.addView(Ui.tv(act, reason, 11.5f, Ui.MUTED))
            container.addView(warn)
        }
        if (identity != "root") {
            val warn = Ui.card(act)
            warn.background = Ui.fill(Ui.alpha(Ui.WARN, 0x14), Ui.dp(act, 16), Ui.WARN, Ui.dp(act, 1))
            warn.addView(Ui.tv(act, "未授权", 13.5f, Ui.WARN, bold = true))
            warn.addView(Ui.tv(act, "写操作需要 root（su 已存在）或 Shizuku 授权。先去授权，再回来刷新。", 11.5f, Ui.MUTED))
            container.addView(warn)
        }

        if (requested.isNotEmpty()) {
            container.addView(Ui.sectionTitle(act, "它能碰到的敏感数据", "按权限组排；开关=是否真的授予。系统固定的权限改不动，会写明原因。"))
            for (g in Catalog.GROUPS) {
                val hit = g.perms.filter { it in requested }
                if (hit.isEmpty()) continue
                val card = Ui.card(act, 6)
                val head = Ui.col(act)
                head.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 6))
                head.addView(Ui.tv(act, g.title, 13.5f, Ui.TEXT, bold = true))
                head.addView(Ui.tv(act, g.note, 11f, Ui.MUTED))
                card.addView(head)
                for (perm in hit) addPermRow(card, perm, g.title)
                container.addView(card)
                container.addView(Ui.spacer(act, 8))
            }
        }

        if (ops.isNotEmpty()) {
            container.addView(Ui.sectionTitle(act, "行为管控（AppOps 五态）", "忽略=调用不报错但拿空数据；拒绝=直接抛异常，应用多半会崩。只有回读一致才算写成功。"))
            val card = Ui.card(act, 10)
            var first = true
            for (info in Catalog.OPS) {
                val scope = if (info.scope == 1) "uid" else "package"
                val st = ops.firstOrNull { it.op == info.name && it.scope == scope } ?: continue
                if (!first) card.addView(Ui.divider(act))
                first = false
                addOpBlock(card, info, st, scope)
            }
            if (first) card.addView(Ui.tv(act, "这个应用没有记录到可管控的行为项。", 12f, Ui.MUTED))
            container.addView(card)
        }

        container.addView(Ui.sectionTitle(act, "网络", "系统没有「按应用断网」这个键，能改的只有后台流量白名单。"))
        val netCard = Ui.card(act)
        val netRow = Ui.row(act)
        val netCol = Ui.col(act)
        netCol.addView(Ui.tv(act, "后台流量不受限制", 13.5f, Ui.TEXT))
        netCol.addView(Ui.tv(act, "加入 restrict-background-whitelist：系统限制后台流量时豁免它。不等于断网。", 10.5f, Ui.MUTED))
        netRow.addView(netCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Ui.switchView(act, bgAllowed) { want ->
            if (identity != "root" || Catalog.forbiddenReason(act, pkg) != null) {
                act.toast("没有写权限")
                return@switchView
            }
            act.bg {
                val o = Engine.setBgTraffic(uid, want)
                act.ui {
                    act.toast(if (o.ok) "已${if (want) "加入" else "移出"}白名单" else "失败：${o.msg}")
                    if (!o.ok) refresh()
                }
            }
        }
        sw.isEnabled = identity == "root" && Catalog.forbiddenReason(act, pkg) == null
        netRow.addView(sw)
        netCard.addView(netRow)
        container.addView(netCard)

        val footer = Ui.tv(
            act,
            "说明：改「拒绝」档会让应用抛异常，多数应用会崩；想让它安静跑但拿不到数据，用「忽略」。\n" +
                "系统提示「permissions revoked」时说明撤销权限把应用杀了 —— 这是系统行为，不是本应用的问题。",
            11f,
            Ui.FAINT,
        )
        footer.setPadding(0, Ui.dp(act, 16), 0, 0)
        container.addView(footer)
    }

    private fun addPermRow(card: LinearLayout, perm: String, groupTitle: String) {
        val st = perms[perm]
        val row = Ui.row(act)
        row.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10))

        val col = Ui.col(act)
        col.addView(Ui.tv(act, permLabel(perm), 13f, Ui.TEXT))
        val sub = when {
            st == null -> "未在 dumpsys 里读到（可能只在清单里声明）"
            perm in Catalog.SPECIAL -> "特殊权限，不走 pm，需要专属入口"
            st.fixed -> "系统固定授权，pm 也改不动"
            st.granted -> "已授予"
            else -> "未授予"
        }
        val subColor = when {
            st?.fixed == true || perm in Catalog.SPECIAL -> Ui.WARN
            st?.granted == true -> Ui.ACCENT
            else -> Ui.FAINT
        }
        val subView = Ui.tv(act, "$groupTitle · $sub", 10.5f, subColor)
        subView.setPadding(0, Ui.dp(act, 2), 0, 0)
        col.addView(subView)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val canToggle = identity == "root" && st != null && !st.fixed &&
            perm !in Catalog.SPECIAL && Catalog.forbiddenReason(act, pkg) == null
        val sw = Ui.switchView(act, st?.granted == true) { want ->
            if (busy) return@switchView
            if (!want) {
                confirm(
                    "撤销「${permLabel(perm)}」？",
                    "撤销一个正在运行的应用的运行时权限，系统会直接杀掉它（日志里的 permissions revoked）。应用可能丢数据。",
                ) { writePerm(perm, false) }
            } else {
                writePerm(perm, true)
            }
        }
        sw.isEnabled = canToggle
        row.addView(sw)
        card.addView(row)
    }

    private fun addOpBlock(card: LinearLayout, info: Catalog.Op, st: Engine.OpState, scope: String) {
        val block = Ui.col(act)
        block.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10))

        val head = Ui.row(act)
        val left = Ui.col(act)
        left.addView(Ui.tv(act, info.label, 13.5f, Ui.TEXT))
        val sub = info.note + if (st.time.isNotBlank()) " · 最近 ${st.time}" else ""
        left.addView(Ui.tv(act, sub, 10.5f, Ui.MUTED))
        head.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.pill(act, scope, Ui.FAINT))
        block.addView(head)

        val chips = Ui.row(act)
        chips.setPadding(0, Ui.dp(act, 8), 0, 0)
        val writable = identity == "root" && Catalog.forbiddenReason(act, pkg) == null
        for (m in 0..4) {
            val selected = st.modeId == m
            val tone = when (m) {
                Catalog.MODE_DENY -> Ui.DANGER
                Catalog.MODE_IGNORE -> Ui.WARN
                Catalog.MODE_ALLOW -> Ui.ACCENT
                Catalog.MODE_FOREGROUND -> Ui.ACCENT
                else -> Ui.MUTED
            }
            val chip = Ui.chip(act, Catalog.modeLabel(m), selected, tone) {
                if (!writable || busy || selected) return@chip
                if (Catalog.needsConfirm(m)) {
                    confirm(
                        "设为「拒绝」？",
                        "$label 调用${info.label}时会抛异常，多数应用会崩。更温和的选择是「忽略」。",
                    ) { writeOp(info, m, scope) }
                } else {
                    writeOp(info, m, scope)
                }
            }
            chips.addView(chip)
            if (m < 4) chips.addView(gap(6))
        }
        block.addView(chips)
        card.addView(block)
    }

    private fun writeOp(info: Catalog.Op, mode: Int, scope: String) {
        busy = true
        act.bg {
            val o = Engine.writeOp(act, pkg, uid, info.name, mode, if (scope == "uid") 1 else 0)
            act.ui {
                busy = false
                if (o.ok) {
                    act.toast("${info.label} → ${Catalog.modeLabel(mode)}（回读一致）")
                } else {
                    act.toast("没写进去：${o.msg}")
                }
                refresh()
            }
        }
    }

    private fun writePerm(perm: String, grant: Boolean) {
        busy = true
        act.bg {
            val o = Engine.writePerm(act, pkg, perm, grant)
            act.ui {
                busy = false
                if (o.ok) {
                    act.toast("${permLabel(perm)} → ${if (grant) "已授予" else "已撤销"}（回读一致）")
                } else {
                    act.toast("失败：${o.msg}")
                }
                refresh()
            }
        }
    }

    private fun permLabel(perm: String): String = when (perm.substringAfterLast('.')) {
        "CAMERA" -> "相机"
        "RECORD_AUDIO" -> "麦克风"
        "ACCESS_FINE_LOCATION" -> "精确位置"
        "ACCESS_COARSE_LOCATION" -> "大致位置"
        "ACCESS_BACKGROUND_LOCATION" -> "后台定位"
        "READ_CONTACTS" -> "读取联系人"
        "WRITE_CONTACTS" -> "修改联系人"
        "GET_ACCOUNTS" -> "读取账户"
        "READ_SMS" -> "读取短信"
        "SEND_SMS" -> "发送短信"
        "RECEIVE_SMS" -> "接收短信"
        "RECEIVE_MMS" -> "接收彩信"
        "READ_CALL_LOG" -> "读取通话记录"
        "WRITE_CALL_LOG" -> "修改通话记录"
        "PROCESS_OUTGOING_CALLS" -> "拦截外拨"
        "READ_CALENDAR" -> "读取日历"
        "WRITE_CALENDAR" -> "修改日历"
        "READ_MEDIA_IMAGES" -> "读取图片"
        "READ_MEDIA_VIDEO" -> "读取视频"
        "READ_MEDIA_AUDIO" -> "读取音频"
        "READ_EXTERNAL_STORAGE" -> "读取存储"
        "WRITE_EXTERNAL_STORAGE" -> "写入存储"
        "MANAGE_EXTERNAL_STORAGE" -> "所有文件访问"
        "POST_NOTIFICATIONS" -> "发通知"
        "READ_PHONE_STATE" -> "读取电话状态"
        "READ_PHONE_NUMBERS" -> "读取本机号码"
        "BODY_SENSORS" -> "身体传感器"
        "BLUETOOTH_CONNECT" -> "连接蓝牙"
        "BLUETOOTH_SCAN" -> "扫描蓝牙"
        "NEARBY_WIFI_DEVICES" -> "附近 Wi-Fi"
        "ACCESS_LOCAL_NETWORK" -> "本地网络"
        "ACTIVITY_RECOGNITION" -> "活动识别"
        else -> perm.substringAfterLast('.')
    }

    private fun gap(dp: Int): View {
        val v = View(act)
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(act, dp), 1)
        return v
    }

    private fun confirm(title: String, msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("确定") { d, _ -> d.dismiss(); onOk() }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
    }
}
