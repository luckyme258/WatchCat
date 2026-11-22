package com.luckyme.watchcat

import android.os.Build
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.libxposed.api.XposedInterface

/**
 * 诊断页：把「这台机器到底能干什么」摊在明面上（W8.3 / W15-C12）。
 *
 * 两个按钮都很贵：能力探测会逐条试写本应用的 op，还会短暂开关 Wi-Fi，
 * 所以必须点一下才跑，并且跑之前讲清楚会付出什么代价。
 */
class DiagScreen(private val act: MainActivity) {

    private val root = Ui.col(act)
    private val identityText = Ui.tv(act, "检测中…", 12f, Ui.MUTED)
    private val frameworkText = Ui.tv(act, "读取中…", 12f, Ui.MUTED)
    private val engineText = Ui.tv(act, "读取中…", 12f, Ui.MUTED)
    private val capText = Ui.tv(act, "尚未跑过本轮探测。上次结果见 files/capability_v2.json。", 11.5f, Ui.MUTED)
    private val capRows = Ui.col(act)

    // 数据面（剪贴板）现状：闸门、钩子、审计三样分开显示，不合成一句"已保护"
    private val clipText = Ui.tv(act, "读取中…", 11.5f, Ui.MUTED)
    private val clipEvents = Ui.col(act)

    private var report: CapabilityProbe.Report? = null

    init {
        build()
        refreshEngine()
        refreshClip()
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

        // LSP 框架接入（P1）：能读到的现读，读不到一律“未知”，不猜版本
        val fwCard = Ui.card(act)
        fwCard.addView(Ui.tv(act, "LSP 框架接入（P1）", 14.5f, Ui.TEXT, bold = true))
        fwCard.addView(frameworkText)
        body.addView(fwCard)
        body.addView(Ui.spacer(act, 10))

        // 引擎
        val engCard = Ui.card(act)
        engCard.addView(Ui.tv(act, "Zig 引擎", 14.5f, Ui.TEXT, bold = true))
        engCard.addView(engineText)
        body.addView(engCard)

        // 数据面：剪贴板读方向（A 阶段自适应项）
        body.addView(Ui.spacer(act, 10))
        val clipCard = Ui.card(act)
        clipCard.addView(Ui.tv(act, "数据面 · 剪贴板", 14.5f, Ui.TEXT, bold = true))
        clipCard.addView(
            Ui.tv(
                act,
                "只动「读」方向：其余应用读到空，输入法与系统身份放行；写方向与系统提示都不碰。",
                11f,
                Ui.MUTED,
            ),
        )
        clipCard.addView(Ui.spacer(act, 6))
        clipCard.addView(clipText)
        val clipBtns = Ui.row(act)
        clipBtns.setPadding(0, Ui.dp(act, 10), 0, 0)
        clipBtns.addView(Ui.button(act, "启用", primary = true) { confirmClipEnable() })
        clipBtns.addView(gap(8))
        clipBtns.addView(Ui.button(act, "停用") { confirmClipDisable() })
        clipBtns.addView(gap(8))
        clipBtns.addView(Ui.button(act, "刷新") { refreshClip() })
        clipCard.addView(clipBtns)
        clipCard.addView(Ui.spacer(act, 8))
        clipCard.addView(clipEvents)
        body.addView(clipCard)

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
        frameworkText.text = "读取中…"
        engineText.text = "读取中…"
        act.bg {
            val id = Engine.identityText()
            val fw = frameworkReport()
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
                frameworkText.text = fw.text
                frameworkText.setTextColor(fw.color)
                engineText.text = eng
            }
        }
    }

    private data class FrameworkReport(val text: String, val color: Int)

    /**
     * 注入证据只认两处：框架服务能读到的状态，或 system_server 写下的 marker。
     * marker 写不进去（SELinux）就如实说“未找到”，不要拿“应该已注入”糊弄。
     */
    private fun frameworkReport(): FrameworkReport {
        val snap = FrameworkState.snapshot()
        val channel = if (PolicyStore.isRemote()) {
            "remote preferences（框架托管）"
        } else {
            "本地回退（框架未连接）"
        }
        val marker = Shell.su("cat /data/adb/watchcat/system_server.marker 2>/dev/null", 3_000)
        val markerText = if (marker.ok && marker.output.isNotBlank()) {
            marker.output.lineSequence().filter { it.isNotBlank() }.take(4).joinToString(" · ")
        } else {
            "未找到（需 root 读或看 logcat -s WatchCat）"
        }

        if (snap == null) {
            return FrameworkReport(
                "框架服务：未连接 / 未知\n" +
                    "框架：未知（不猜版本）\n" +
                    "作用域：未知\n" +
                    "策略通道：$channel\n" +
                    "注入：$markerText",
                Ui.WARN,
            )
        }

        val props = snap.properties
        fun cap(bit: Long) = if ((props and bit) != 0L) "✓" else "—"
        val caps = "system=${cap(XposedInterface.PROP_CAP_SYSTEM)} " +
            "remote=${cap(XposedInterface.PROP_CAP_REMOTE)} " +
            "apiProtection=${cap(XposedInterface.PROP_RT_API_PROTECTION)}"
        val scope = snap.scope.ifEmpty { listOf("（空）") }.joinToString(", ")
        return FrameworkReport(
            "框架服务：已连接\n" +
                "框架：${snap.name} ${snap.version}（versionCode ${snap.versionCode}）· API ${snap.api}\n" +
                "能力：$caps\n" +
                "作用域：$scope\n" +
                "策略通道：$channel\n" +
                "注入：$markerText",
            if (snap.api >= 101) Ui.ACCENT else Ui.WARN,
        )
    }

    /**
     * 剪贴板现状（L12 的"兜底 + 事件"两问，现读不缓存）：
     *   · 闸门：本轮 bootId 是否匹配，匹配才算真的启用；
     *   · 钩子：p3 marker 里的 hooked 数与方法名——证明"真挂上了"，不是"点了启用"；
     *   · 审计：最近几条决策，谁读的、给的是什么。
     */
    fun refreshClip() {
        clipText.text = "读取中…"
        act.bg {
            val st = runCatching { ClipGate.status() }.getOrNull()
            act.ui {
                if (st == null) {
                    clipText.setTextColor(Ui.WARN)
                    clipText.text = "读取失败：su 拿不到状态"
                    clipEvents.removeAllViews()
                    return@ui
                }
                val marker = st.markerText?.lineSequence()
                    ?.map { it.trim() }
                    ?.filter {
                        it.startsWith("armed=") || it.startsWith("hooked=") ||
                            it.startsWith("methods=") || it.startsWith("policy=")
                    }
                    ?.joinToString(" · ")
                val gate = when {
                    st.gateBootId == null -> "未启用（无闸门）"
                    st.armedNow -> "已启用（bootId 匹配）"
                    else -> "闸门过期（bootId 不符，需重新启用）"
                }
                clipText.setTextColor(if (st.armedNow) Ui.ACCENT else Ui.WARN)
                clipText.text = buildString {
                    append("闸门：").append(gate).append('\n')
                    append("钩子：")
                        .append(marker ?: "无 p3 marker（system_server 还没执行到钩子安装）")
                        .append('\n')
                    append("失败计数：").append(st.failCount ?: "-")
                    append(" · 自动停用：").append(if (st.tripped != null) "已触发" else "未触发").append('\n')
                    append("审计：")
                        .append(if (st.events.isEmpty()) "暂无事件" else "${st.events.size} 条（最近 20）")
                }
                clipEvents.removeAllViews()
                if (st.events.isNotEmpty()) {
                    clipEvents.addView(Ui.tv(act, "最近事件", 12f, Ui.TEXT, bold = true))
                    st.events.takeLast(8).forEach {
                        clipEvents.addView(Ui.tv(act, "· $it", 10.5f, Ui.MUTED))
                    }
                }
            }
        }
    }

    private fun confirmClipEnable() {
        android.app.AlertDialog.Builder(act)
            .setTitle("启用剪贴板保护？")
            .setMessage(
                "会做：\n" +
                    "· 其余应用读剪贴板时拿到「空」，默认模式；\n" +
                    "· 输入法与 root/system 身份照旧放行，你自己的粘贴不受影响；\n" +
                    "· 每次读取记一条审计，写 /data/system/watchcat.events。\n\n" +
                    "不会做：\n" +
                    "· 不改「复制」这个动作（写方向不钩）；\n" +
                    "· 不压 Android 的「已粘贴来自…」提示；\n" +
                    "· 不伪造内容（替身模式需要在策略里显式开）。\n\n" +
                    "写入靠 su；装好后要重启一次框架才会装载钩子——只重启框架，不是重启手机。",
            )
            .setPositiveButton("启用") { d, _ ->
                d.dismiss()
                clipText.text = "写入中…"
                act.bg {
                    val msg = ClipGate.enable(act)
                    act.ui {
                        act.toast(msg.lineSequence().first())
                        clipText.text = msg
                        refreshClip()
                    }
                }
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
    }

    private fun confirmClipDisable() {
        android.app.AlertDialog.Builder(act)
            .setTitle("停用剪贴板保护？")
            .setMessage(
                "删掉闸门与策略文件，行为立刻恢复原样；钩子仍留在进程里，但读不到策略就一律放行。\n审计事件保留。",
            )
            .setPositiveButton("停用") { d, _ ->
                d.dismiss()
                act.bg {
                    val msg = ClipGate.disable()
                    act.ui {
                        act.toast(msg)
                        refreshClip()
                    }
                }
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
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
