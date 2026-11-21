package com.luckyme.watchcat

import android.os.Build
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 诊断页：把「这台机器到底能干什么」摊在明面上（W8.3 / W15-C12）。
 *
 * 两个按钮都很贵：能力探测会逐条试写本应用的 op，还会短暂开关 Wi-Fi，
 * 所以必须点一下才跑，并且跑之前讲清楚会付出什么代价。
 */
class DiagScreen(private val act: MainActivity) {

    private val root = Ui.col(act)
    private val identityText = Ui.tv(act, "检测中…", 12f, Ui.MUTED)
    private val engineText = Ui.tv(act, "读取中…", 12f, Ui.MUTED)
    private val capText = Ui.tv(act, "尚未跑过本轮探测。上次结果见 files/capability_v2.json。", 11.5f, Ui.MUTED)
    private val capRows = Ui.col(act)

    private var report: CapabilityProbe.Report? = null

    init {
        build()
        refreshEngine()
    }

    fun view(): View = root

    private fun build() {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0)

        val scroll = ScrollView(act)
        val body = Ui.col(act)
        body.setPadding(0, 0, 0, Ui.dp(act, 20))
        scroll.addView(body)

        // 身份
        val idCard = Ui.card(act)
        idCard.addView(Ui.tv(act, "授权身份", 14.5f, Ui.TEXT, bold = true))
        idCard.addView(identityText)
        val idBtns = Ui.row(act)
        idBtns.setPadding(0, Ui.dp(act, 10), 0, 0)
        idBtns.addView(Ui.button(act, "重新检测") { act.refreshIdentity(); refreshEngine() })
        idCard.addView(idBtns)
        body.addView(idCard)
        body.addView(Ui.spacer(act, 10))

        // 引擎
        val engCard = Ui.card(act)
        engCard.addView(Ui.tv(act, "Zig 引擎", 14.5f, Ui.TEXT, bold = true))
        engCard.addView(engineText)
        body.addView(engCard)

        // 能力表
        body.addView(Ui.sectionTitle(act, "能力表 v2", "本机哪些 op 写得进、哪些只能走权限通道、网络通道能不能用。换 ROM 就必须重测。"))
        val capCard = Ui.card(act)
        capCard.addView(capText)
        val capBtns = Ui.row(act)
        capBtns.setPadding(0, Ui.dp(act, 10), 0, 0)
        capBtns.addView(Ui.button(act, "重跑探测（约 30 秒）", primary = true) { runCapability() })
        capBtns.addView(gap(8))
        capBtns.addView(Ui.button(act, "导出") { exportAll() })
        capCard.addView(capBtns)
        capCard.addView(Ui.spacer(act, 8))
        capCard.addView(capRows)
        body.addView(capCard)

        // 设备
        body.addView(Ui.sectionTitle(act, "环境", null))
        val devCard = Ui.card(act)
        val devLines = listOf(
            "设备：${Build.MANUFACTURER} ${Build.MODEL}",
            "系统：Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            "ABI：${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
            "本应用 uid：${Process.myUid()}",
            "适配提示：${Catalog.apiHint()}",
        )
        devLines.forEach { devCard.addView(Ui.tv(act, it, 11.5f, Ui.MUTED)) }
        body.addView(devCard)

        body.addView(
            Ui.tv(
                act,
                "WatchCat ${BuildConfig.VERSION_NAME} · 权限管理/审计工具，不做「调用瞬间拦截」；已设策略会被系统重新计算，界面只保证写后回读一致。",
                11f,
                Ui.FAINT,
            ).also { it.setPadding(0, Ui.dp(act, 16), 0, 0) },
        )

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refreshEngine() {
        identityText.text = "检测中…"
        engineText.text = "读取中…"
        act.bg {
            val id = Engine.identityText()
            val eng = buildString {
                try {
                    val ver = NativeEngine.nativeVersion()
                    val ping = NativeEngine.nativePing()
                    val jni = NativeEngine.nativeJniVersion()
                    val ops = NativeEngine.nativeOpCount()
                    append("装载：PASS（libwatchcat.so 已加载）\n")
                    append("version=0x%08x  ping=0x%04x  jni=0x%x  opCount=%d".format(java.util.Locale.US, ver, ping, jni, ops))
                } catch (e: Throwable) {
                    append("装载：FAIL\n${e.javaClass.name}: ${e.message}")
                }
            }
            act.ui {
                identityText.text = id
                identityText.setTextColor(if (id.startsWith("root")) Ui.ACCENT else Ui.WARN)
                engineText.text = eng
            }
        }
    }

    private fun runCapability() {
        android.app.AlertDialog.Builder(act)
            .setTitle("跑一轮能力探测？")
            .setMessage(
                "它会：\n" +
                    "· 逐条试写本应用自己的 op，写完立刻还原（约 18 条）；\n" +
                    "· 权限通道目标是自己 → 自动跳过（撤自己的权限会被系统杀）；\n" +
                    "· 短暂关闭再打开 Wi-Fi 验证断/开通道，**可能让无线调试掉线**（USB 调试不受影响）。\n\n" +
                    "结论落盘 files/capability_v2.json，写不进的项目不会在详情页给开关。",
            )
            .setPositiveButton("开始") { d, _ ->
                d.dismiss()
                capText.text = "探测中…（先写本应用 op，再动网络，别拔 USB）"
                capText.setTextColor(Ui.WARN)
                capRows.removeAllViews()
                act.bg {
                    val uid = Process.myUid()
                    val r = runCatching { CapabilityProbe.run(act, uid, act.packageName) }.getOrNull()
                    act.ui {
                        report = r
                        if (r == null) {
                            capText.text = "探测失败：异常或引擎返回 null"
                            capText.setTextColor(Ui.DANGER)
                        } else {
                            renderReport(r)
                        }
                    }
                }
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
    }

    private fun renderReport(r: CapabilityProbe.Report) {
        val rows = r.rows
        val w = rows.count { it.verdict == "writable" }
        val n = rows.count { it.verdict == "not_writable" }
        val p = rows.count { it.verdict == "partial" }
        capText.text = "本轮：${rows.size} 条 op，可写 $w / 写不进 $n / 待复核 $p；channels ${r.channels.size} 条。\n落盘：${r.savedPath ?: "未落盘"}"
        capText.setTextColor(Ui.MUTED)

        capRows.removeAllViews()
        capRows.addView(Ui.tv(act, "可写 op（详情页才会给开关）", 12.5f, Ui.TEXT, bold = true))
        rows.filter { it.verdict == "writable" }.forEach {
            capRows.addView(Ui.tv(act, "· ${it.op}（${it.scope}）写 ${Catalog.modeLabel(it.want)} → 回读 ${Catalog.modeLabel(it.after)}", 11f, Ui.ACCENT))
        }
        if (w == 0) capRows.addView(Ui.tv(act, "· 没有可写的 op（说明当前身份不够）", 11f, Ui.WARN))

        capRows.addView(Ui.tv(act, "写不进的 op（不许画假开关）", 12.5f, Ui.TEXT, bold = true).also { it.setPadding(0, Ui.dp(act, 10), 0, 0) })
        rows.filter { it.verdict != "writable" }.take(12).forEach {
            capRows.addView(Ui.tv(act, "· ${it.op}（${it.scope}）通道 ${it.channel} · ${advice(it.advice)}", 11f, Ui.MUTED))
        }

        capRows.addView(Ui.tv(act, "通道", 12.5f, Ui.TEXT, bold = true).also { it.setPadding(0, Ui.dp(act, 10), 0, 0) })
        r.channels.forEach { c ->
            val tone = when (c.verdict) {
                "writable" -> Ui.ACCENT
                "skipped" -> Ui.FAINT
                else -> Ui.WARN
            }
            capRows.addView(Ui.tv(act, "· [${c.channel}] ${c.target}：${c.before} → ${c.want}（实得 ${c.after}）${c.verdict}", 11f, tone))
            if (c.note.isNotBlank()) capRows.addView(Ui.tv(act, "   " + c.note, 10.5f, Ui.FAINT))
        }
    }

    private fun advice(a: String): String = when (a) {
        "use_permission_channel" -> "走 pm grant/revoke"
        "readonly", "unsupported" -> "本机改不动"
        "recheck" -> "回读不符，人工复核"
        "ok" -> "可写"
        else -> a
    }

    private fun exportAll() {
        act.bg {
            val r = report
            val dir = act.getExternalFilesDir(null)
            val p = runCatching {
                if (dir == null) return@runCatching null
                val sb = StringBuilder()
                sb.append("# WatchCat 诊断\n")
                sb.append(Audit.deviceHeader()).append('\n')
                sb.append("identity=").append(Engine.identityText()).append("\n\n")
                if (r != null) {
                    sb.append("## 能力表\n").append(r.json ?: "(null)").append("\n\n")
                    sb.append("## 通道\n")
                    for (c in r.channels) {
                        sb.append(c.channel).append('\t').append(c.target).append('\t')
                            .append(c.before).append("->").append(c.after).append('\t')
                            .append(c.verdict).append('\t').append(c.note).append('\n')
                    }
                }
                val f = java.io.File(dir, "watchcat-dump.txt")
                f.writeText(sb.toString())
                f.absolutePath
            }.getOrNull()
            act.ui { act.toast(if (p == null) "导出失败" else "已导出：$p") }
        }
    }

    private fun gap(dp: Int): View {
        val v = View(act)
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(act, dp), 1)
        return v
    }
}
