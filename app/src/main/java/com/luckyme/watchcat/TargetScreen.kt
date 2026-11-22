package com.luckyme.watchcat

import android.content.pm.ApplicationInfo
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luckyme.watchcat.xposed.PkgPolicy

/**
 * 「目标应用」页：P2 应用列表伪装的管理入口。
 *
 * 这一页存在的理由：钩子早就验证过能藏，但"怎么让用户用起来"一直是零——
 * 策略只能靠 su 往目标私有目录写文件，作用域要去 Vector 里手动勾，注入成没成也没人看得见。
 * 所以三件事必须在这一页闭环：
 *
 *   1. 策略走**远程 prefs 正式通道**（`p2` group，键 `policy.<包名>`），不再教用户写文件；
 *   2. 作用域**现读现加**（框架 `requestScope` 优先，`vector-cli` 兜底），别让用户以为开了就生效；
 *   3. 注入结果**回读目标进程的 marker**，页面上直接写"已注入 / 没有 marker"，不猜。
 */
class TargetScreen(private val act: MainActivity) {

    private val root = Ui.col(act)
    private val body = Ui.col(act)
    private val statusText = Ui.tv(act, "读取中…", 11.5f, Ui.MUTED)

    private var apps: List<Pair<ApplicationInfo, String>> = emptyList()
    private var showSystem = false
    private var editing: String? = null

    private var draft: P2Store.Target? = null

    init {
        build()
        refreshStatus()
        loadApps()
    }

    fun view(): View = root

    /** 重新读状态与列表（切页/回前台时调用，不吃缓存）。 */
    fun refresh() {
        editing = null
        draft = null
        refreshStatus()
        loadApps()
    }

    val inEditor: Boolean get() = editing != null

    /** 返回列表；返回 false 表示当前已在列表页（交给 Activity 处理）。 */
    fun back(): Boolean {
        if (editing == null) return false
        editing = null
        draft = null
        renderList()
        return true
    }

    private fun build() {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0)

        val head = Ui.col(act)
        head.addView(Ui.tv(act, "目标应用", 15f, Ui.TEXT, bold = true))
        head.addView(
            Ui.tv(
                act,
                "按目标应用下发「应用列表伪装」策略：藏哪些包、是否连 ROM 特征包一起藏。默认不生效，只有显式启用且 bootId 匹配才动手。",
                11f,
                Ui.MUTED,
            ).also { it.setPadding(0, Ui.dp(act, 3), 0, 0) },
        )
        head.addView(statusText.also { it.setPadding(0, Ui.dp(act, 6), 0, 0) })
        root.addView(head)

        val scroll = ScrollView(act)
        body.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 20))
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun refreshStatus() {
        statusText.text = "读取中…"
        act.bg {
            val remote = P2Store.isRemote()
            val scope = P2Device.scope()
            act.ui {
                val s = buildString {
                    append("策略通道：").append(if (remote) "远程 prefs（p2）" else "未连接框架（无法下发）")
                    append("　作用域：").append(
                        when {
                            scope == null -> "未知（框架未连接）"
                            else -> scope.joinToString(", ").ifEmpty { "空" }
                        }
                    )
                }
                statusText.text = s
                statusText.setTextColor(if (remote) Ui.ACCENT else Ui.WARN)
            }
        }
    }

    private fun loadApps() {
        act.bg {
            val pm = act.packageManager
            val list = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
                .filter { it.packageName != act.packageName }
                .sortedBy { runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
                .map { it to runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
            act.ui {
                apps = list
                renderList()
            }
        }
    }

    private fun configured(): Set<String> = runCatching { P2Store.configured(act) }.getOrDefault(emptySet())

    private fun renderList() {
        val cfg = configured()
        body.removeAllViews()

        val filterRow = Ui.row(act)
        filterRow.addView(Ui.chip(act, "三方应用", !showSystem) { showSystem = false; renderList() })
        filterRow.addView(gap(8))
        filterRow.addView(Ui.chip(act, "含系统应用", showSystem) { showSystem = true; renderList() })
        filterRow.addView(gap(8))
        filterRow.addView(Ui.button(act, "刷新") { refreshStatus(); loadApps() })
        body.addView(filterRow)
        body.addView(Ui.spacer(act, 10))

        val visible = apps.filter { showSystem || (it.first.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        val card = Ui.card(act, 1)
        visible.forEach { (info, label) ->
            val pkg = info.packageName
            val row = Ui.row(act)
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
            row.background = Ui.clickableBackground(act, 12, Ui.CARD)
            val col = Ui.col(act)
            col.addView(Ui.tv(act, label, 13f, Ui.TEXT, bold = true))
            col.addView(Ui.tv(act, pkg, 10.5f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (pkg in cfg) row.addView(Ui.pill(act, "已配置", Ui.ACCENT))
            row.setOnClickListener { openEditor(pkg, label) }
            card.addView(row)
            if (info != visible.last().first) card.addView(Ui.divider(act))
        }
        if (visible.isEmpty()) card.addView(Ui.tv(act, "没有可管理的应用", 12f, Ui.MUTED))
        body.addView(card)

        body.addView(
            Ui.tv(
                act,
                "提示：目标应用第一次生效必须冷启动（它自己进程里才装钩子）；策略改动是按调用现读的，一般不用重启。",
                10.5f,
                Ui.FAINT,
            ).also { it.setPadding(0, Ui.dp(act, 10), 0, 0) },
        )
    }

    private fun openEditor(pkg: String, label: String) {
        editing = pkg
        val existing = runCatching { P2Store.read(act, pkg) }.getOrNull()
        draft = existing ?: P2Store.Target(pkg)
        renderEditor(pkg, label)
    }

    private fun renderEditor(pkg: String, label: String) {
        body.removeAllViews()

        val backRow = Ui.row(act)
        backRow.addView(Ui.button(act, "← 返回列表") { back() })
        body.addView(backRow)
        body.addView(Ui.spacer(act, 10))

        val info = Ui.card(act)
        info.addView(Ui.tv(act, label, 15f, Ui.TEXT, bold = true))
        info.addView(Ui.tv(act, pkg, 11f, Ui.MUTED))
        val uid = runCatching { act.packageManager.getPackageUid(pkg, 0) }.getOrNull()
        info.addView(Ui.tv(act, "uid=${uid ?: "?"}", 11f, Ui.MUTED))
        body.addView(info)
        body.addView(Ui.spacer(act, 10))

        // 作用域
        val scopeCard = Ui.card(act)
        scopeCard.addView(Ui.tv(act, "作用域", 13.5f, Ui.TEXT, bold = true))
        val scopeTv = Ui.tv(act, "读取中…", 11.5f, Ui.MUTED)
        scopeCard.addView(scopeTv)
        val scopeBtnRow = Ui.row(act)
        scopeBtnRow.setPadding(0, Ui.dp(act, 8), 0, 0)
        val addBtn = Ui.button(act, "加入作用域（root）", primary = true) { }
        addBtn.visibility = View.GONE
        scopeBtnRow.addView(addBtn)
        scopeCard.addView(scopeBtnRow)
        body.addView(scopeCard)
        body.addView(Ui.spacer(act, 10))

        act.bg {
            val scope = P2Device.scope()
            val inside = scope?.contains(pkg)
            act.ui {
                scopeTv.text = when (inside) {
                    null -> "框架未连接，读不到作用域（先确认 Vector 在运行）"
                    true -> "已在作用域：框架会在这个应用的所有进程里注入"
                    false -> "不在作用域：现在保存了策略也不会生效"
                }
                scopeTv.setTextColor(if (inside == true) Ui.ACCENT else Ui.WARN)
                addBtn.visibility = if (inside == false) View.VISIBLE else View.GONE
                addBtn.setOnClickListener {
                    addBtn.isEnabled = false
                    addBtn.text = "请求中…"
                    act.bg {
                        P2Device.requestScope(pkg) { msg ->
                            act.ui {
                                act.toast(msg)
                                addBtn.text = "加入作用域（root）"
                                addBtn.isEnabled = true
                            }
                        }
                    }
                }
            }
        }

        // 策略
        val policyCard = Ui.card(act)
        val d = draft ?: P2Store.Target(pkg).also { draft = it }
        policyCard.addView(Ui.tv(act, "策略", 13.5f, Ui.TEXT, bold = true))

        val armedRow = Ui.row(act)
        armedRow.setPadding(0, Ui.dp(act, 8), 0, 0)
        val armedCol = Ui.col(act)
        armedCol.addView(Ui.tv(act, "启用（armed）", 12.5f, Ui.TEXT))
        armedCol.addView(Ui.tv(act, "关掉 = 立刻恢复「全可见」，钩子还在但不判定", 10.5f, Ui.MUTED))
        armedRow.addView(armedCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        armedRow.addView(Ui.switchView(act, d.armed) { v -> draft = (draft ?: d).copy(armed = v) })
        policyCard.addView(armedRow)

        val modeRow = Ui.row(act)
        modeRow.setPadding(0, Ui.dp(act, 10), 0, 0)
        modeRow.addView(Ui.tv(act, "模式", 12.5f, Ui.TEXT))
        modeRow.addView(gap(10))
        val hideChip = Ui.chip(act, "隐藏名单", d.mode == PkgPolicy.Mode.HIDE) {
            draft = (draft ?: d).copy(mode = PkgPolicy.Mode.HIDE)
            renderEditor(pkg, label)
        }
        val showChip = Ui.chip(act, "白名单可见", d.mode == PkgPolicy.Mode.SHOW) {
            draft = (draft ?: d).copy(mode = PkgPolicy.Mode.SHOW)
            renderEditor(pkg, label)
        }
        modeRow.addView(hideChip)
        modeRow.addView(gap(8))
        modeRow.addView(showChip)
        policyCard.addView(modeRow)

        val romRow = Ui.row(act)
        romRow.setPadding(0, Ui.dp(act, 10), 0, 0)
        val romCol = Ui.col(act)
        romCol.addView(Ui.tv(act, "ROM 特征包", 12.5f, Ui.TEXT))
        romCol.addView(Ui.tv(act, "连 org.lineageos.* 一起藏（挡「按包名判断是不是 LineageOS」）", 10.5f, Ui.MUTED))
        romRow.addView(romCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        romRow.addView(Ui.switchView(act, d.rom) { v -> draft = (draft ?: d).copy(rom = v) })
        policyCard.addView(romRow)

        val listRow = Ui.row(act)
        listRow.setPadding(0, Ui.dp(act, 12), 0, 0)
        val countTv = Ui.tv(act, listSummary(), 11.5f, Ui.MUTED)
        listRow.addView(countTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listRow.addView(Ui.button(act, "选名单") { pickList(pkg, label, countTv) })
        policyCard.addView(listRow)

        val saveRow = Ui.row(act)
        saveRow.setPadding(0, Ui.dp(act, 12), 0, 0)
        saveRow.addView(Ui.button(act, "保存并下发", primary = true) { save(pkg, label) })
        saveRow.addView(gap(8))
        saveRow.addView(Ui.button(act, "删除策略") { remove(pkg, label) })
        policyCard.addView(saveRow)
        body.addView(policyCard)
        body.addView(Ui.spacer(act, 10))

        // 运行状态
        val runCard = Ui.card(act)
        runCard.addView(Ui.tv(act, "运行状态（回读目标进程）", 13.5f, Ui.TEXT, bold = true))
        val runTv = Ui.tv(act, "读取中…", 11f, Ui.MUTED)
        runCard.addView(runTv)
        val runRow = Ui.row(act)
        runRow.setPadding(0, Ui.dp(act, 8), 0, 0)
        runRow.addView(Ui.button(act, "刷新") { refreshRun(pkg, runTv) })
        runRow.addView(gap(8))
        runRow.addView(Ui.button(act, "重启目标应用") {
            act.bg {
                val msg = P2Device.restart(pkg)
                act.ui { act.toast(msg); refreshRun(pkg, runTv) }
            }
        })
        runCard.addView(runRow)
        body.addView(runCard)

        refreshRun(pkg, runTv)
    }

    private fun listSummary(): String {
        val d = draft ?: return "名单 空"
        return if ((d.mode == PkgPolicy.Mode.SHOW)) {
            "白名单 ${d.show.size} 个（空=不隐藏）"
        } else {
            "隐藏 ${d.hide.size} 个" + if (d.rom) " + ROM 特征包" else ""
        }
    }

    private fun pickList(pkg: String, label: String, countTv: TextView) {
        val d = draft ?: return
        val showMode = d.mode == PkgPolicy.Mode.SHOW
        val items = apps.map { it.second + "\n" + it.first.packageName }
        val pkgs = apps.map { it.first.packageName }
        val selected = BooleanArray(pkgs.size) { pkgs[it] in (if (showMode) d.show else d.hide) }
        android.app.AlertDialog.Builder(act)
            .setTitle(if (showMode) "选「要让目标看到」的应用" else "选「要从目标列表里藏掉」的应用")
            .setMultiChoiceItems(items.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("确定") { dlg, _ ->
                val picked = pkgs.filterIndexed { i, _ -> selected[i] }.toSet()
                draft = if (showMode) d.copy(show = picked) else d.copy(hide = picked)
                countTv.text = listSummary()
                dlg.dismiss()
                renderEditor(pkg, label)
            }
            .setNegativeButton("取消") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun save(pkg: String, label: String) {
        val d = draft ?: return
        act.bg {
            val msg = P2Store.save(act, d)
            act.ui {
                act.toast(msg)
                refreshStatus()
                renderEditor(pkg, label)
            }
        }
    }

    private fun remove(pkg: String, label: String) {
        act.bg {
            val msg = P2Store.remove(act, pkg)
            act.ui {
                act.toast(msg)
                draft = P2Store.Target(pkg)
                renderEditor(pkg, label)
            }
        }
    }

    private fun refreshRun(pkg: String, tv: TextView) {
        tv.text = "读取中…"
        act.bg {
            val inj = P2Device.injection(pkg)
            act.ui {
                tv.text = buildString {
                    val m = inj.marker
                    if (m == null) {
                        append(inj.error ?: "没有 marker")
                    } else {
                        append("已注入：pid=").append(m["pid"] ?: "?")
                        append(" · ").append(m["app"] ?: "?")
                        append(" · armed=").append(m["armed"] ?: "?")
                        append(" · policy=").append(m["policy"] ?: "?")
                        append(" · rom=").append(m["rom"] ?: "?")
                        append(" · hooked=").append(m["hooked"] ?: "?")
                    }
                    if (inj.events.isNotEmpty()) {
                        append('\n').append("最近事件：")
                        inj.events.takeLast(3).forEach { append('\n').append("· ").append(it) }
                    }
                }
                tv.setTextColor(if (inj.injected) Ui.ACCENT else Ui.WARN)
            }
        }
    }

    private fun gap(dp: Int): View {
        val v = View(act)
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(act, dp), 1)
        return v
    }
}
