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
    private var query = ""

    private var draft: P2Store.Target? = null
    private var countTv: TextView? = null

    /** 本次会话里"策略已下发、目标进程还没重启"的包；重开页面就丢弃（进程状态不能靠猜缓存）。 */
    private val pendingRestart = HashSet<String>()

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

        // 搜索：130+ 应用靠滚动找目标不现实（用户实测的第一道摩擦）
        val search = android.widget.EditText(act)
        search.hint = "搜索应用名 / 包名"
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        search.setTextColor(Ui.TEXT)
        search.setHintTextColor(Ui.FAINT)
        search.setSingleLine(true)
        search.setPadding(Ui.dp(act, 10), Ui.dp(act, 7), Ui.dp(act, 10), Ui.dp(act, 7))
        search.background = Ui.fill(Ui.CARD_ALT, Ui.dp(act, 9), Ui.STROKE, Ui.dp(act, 1))
        search.setText(query)
        search.setSelection(search.text.length)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim() ?: ""
                renderRows(cfg)
            }
        })
        body.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = Ui.dp(act, 8)
        })

        renderStale(cfg)
        renderRemedyBoard(cfg)

        // 应用列表单独装进框里，搜索时只重绘它（别整页重建）
        val listBox = Ui.col(act)
        listBox.tag = "rows"
        body.addView(listBox)
        renderRows(cfg, listBox)

        body.addView(
            Ui.tv(
                act,
                "「全部可见」= 这个应用又能看到你装的所有东西；「已下发 · 待冷启动」= 策略写进去了，进程还是旧的。",
                10.5f,
                Ui.FAINT,
            ).also { it.setPadding(0, Ui.dp(act, 10), 0, 0) },
        )
    }

    /** 只重绘行区域（搜索框输入、切换三态后都走这里，避免滚动位置被整页重建打断）。 */
    private fun renderRows(cfg: Set<String>, box: LinearLayout? = null) {
        val target = box ?: (body.findViewWithTag("rows") as? LinearLayout) ?: return
        target.removeAllViews()
        val visible = apps.asSequence()
            .filter { showSystem || (it.first.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { query.isEmpty() || it.second.contains(query, true) || it.first.packageName.contains(query, true) }
            .toList()
        if (visible.isEmpty()) {
            target.addView(Ui.tv(act, if (query.isEmpty()) "没有可管理的应用" else "没有匹配「$query」的应用", 12f, Ui.MUTED))
            return
        }
        val card = Ui.card(act, 1)
        visible.forEachIndexed { i, (info, label) ->
            val pkg = info.packageName
            val row = Ui.row(act)
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
            row.background = Ui.clickableBackground(act, 12, Ui.CARD)
            val col = Ui.col(act)
            col.addView(Ui.tv(act, label, 13f, Ui.TEXT, bold = true))
            col.addView(Ui.tv(act, pkg, 10.5f, Ui.MUTED))
            if (pkg in pendingRestart) col.addView(Ui.tv(act, "已下发，等它冷启动", 10f, Ui.FAINT))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statusFor(pkg, cfg)?.let { (text, tone) ->
                row.addView(Ui.chip(act, text, true, tone) { quickMode(pkg, label) })
            }
            row.setOnClickListener { openEditor(pkg, label) }
            card.addView(row)
            if (i != visible.lastIndex) card.addView(Ui.divider(act))
        }
        target.addView(card)
    }

    /** 开机后失效的一键续期：列表顶部一条，而不是让用户进 6 个页面各点一次保存。 */
    private fun renderStale(cfg: Set<String>) {
        val current = P2Store.bootId()
        val stale = cfg.filter { p ->
            val t = runCatching { P2Store.read(act, p) }.getOrNull() ?: return@filter false
            t.bootId != null && t.bootId != current
        }
        if (stale.isEmpty()) return
        val card = Ui.card(act)
        card.addView(Ui.tv(act, "${stale.size} 个目标因重启失效（旧授权已过期）", 13f, Ui.WARN, bold = true))
        card.addView(
            Ui.tv(act, "续期只改 bootId 这一行，名单和姿态原样保留；续期后目标仍需冷启动。", 10.5f, Ui.MUTED)
                .also { it.setPadding(0, Ui.dp(act, 2), 0, 0) },
        )
        val row = Ui.row(act)
        row.setPadding(0, Ui.dp(act, 8), 0, 0)
        row.addView(Ui.button(act, "一键续期（${stale.size} 个）", primary = true) {
            act.bg {
                val (done, failed) = P2Store.renewBootId(act)
                act.ui {
                    act.toast(if (failed == 0) "已续期 $done 个目标（记得让它们冷启动）" else "续期 $done 个，失败 $failed 个")
                    renderList()
                }
            }
        })
        card.addView(row)
        body.addView(card)
        body.addView(Ui.spacer(act, 10))
    }

    /** 行尾状态：null = 没配过；颜色 tone 用 ACCENT/WARN/FAINT 区分"生效 / 需注意 / 无害"。 */
    private fun statusFor(pkg: String, cfg: Set<String>): Pair<String, Int>? {
        if (pkg !in cfg) return null
        val t = runCatching { P2Store.read(act, pkg) }.getOrNull() ?: return "已配置" to Ui.FAINT
        if (t.bootId != null && t.bootId != P2Store.bootId()) return "开机后失效" to Ui.WARN
        if (!t.armed) return "全部可见" to Ui.FAINT
        val base = when (t.mode) {
            PkgPolicy.Mode.SHOW -> "只看基础 · 传${t.show.size}"
            PkgPolicy.Mode.HIDE -> "藏${t.hide.size}" + (if (t.rom) "+ROM" else "")
        }
        return (if (pkg in pendingRestart) "$base · 待冷启动" else base) to Ui.ACCENT
    }

    /** 三态就地切换：只看基础 / 全部可见 / 自定义。自定义不再写空策略，直接进编辑器勾选。 */
    private fun quickMode(pkg: String, label: String) {
        val cur = runCatching { P2Store.read(act, pkg) }.getOrNull()
        val idx = when {
            cur == null -> 0
            !cur.armed -> 1
            cur.mode == PkgPolicy.Mode.SHOW -> 0
            else -> 2
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("$label：这个应用能看到什么")
            .setSingleChoiceItems(
                arrayOf(
                    "只看基础（最小暴露）",
                    "全部可见（关掉伪装）",
                    "自定义（自己选要藏哪些）",
                ),
                idx,
            ) { dlg, which ->
                dlg.dismiss()
                if (which == 2) openEditorWithMode(pkg, label, PkgPolicy.Mode.HIDE) else applyMode(pkg, label, which)
            }
            .setNegativeButton("取消") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun applyMode(pkg: String, label: String, which: Int) {
        act.bg {
            val base = BaseSet.collect(act.packageManager, P2Store.currentImeId(act))
            val cur = runCatching { P2Store.read(act, pkg) }.getOrNull()
            val t = when (which) {
                0 -> P2Store.Target(
                    pkg = pkg,
                    armed = true,
                    mode = PkgPolicy.Mode.SHOW,
                    show = cur?.show?.takeIf { it.isNotEmpty() } ?: base,
                    rom = cur?.rom ?: false,
                )
                else -> P2Store.Target( // 全部可见 = armed=false，钩子照样注入但不判定
                    pkg = pkg,
                    armed = false,
                    mode = PkgPolicy.Mode.SHOW,
                    show = cur?.show?.takeIf { it.isNotEmpty() } ?: base,
                    rom = cur?.rom ?: false,
                )
            }
            val msg = P2Store.save(act, t)
            act.ui {
                act.toast(msg)
                if (msg.startsWith("已保存")) pendingRestart.add(pkg) else pendingRestart.remove(pkg)
                renderRows(configured())
            }
        }
    }

    /**
     * 跨目标补救汇总：把各目标事件里"被藏了还问"的包聚合到列表页。
     *
     * 单个`su`批量读（见 [P2Device.eventsBatch]），只统计 `nnf/iae`；一个包被 ≥2 个目标问过就值得置顶，
     * 因为这些包往往是登录/分享的公共依赖，藏了以后用户会在好几个应用里遇到"打不开"。
     */
    private fun renderRemedyBoard(cfg: Set<String>) {
        if (cfg.isEmpty()) return
        act.bg {
            val batch = P2Device.eventsBatch(cfg.toList())
            val counts = LinkedHashMap<String, Int>()
            val owners = LinkedHashMap<String, MutableSet<String>>()
            for ((target, events) in batch) {
                for (r in P2Device.remedies(events)) {
                    counts[r.pkg] = (counts[r.pkg] ?: 0) + r.count
                    owners.getOrPut(r.pkg) { mutableSetOf() }.add(target)
                }
            }
            val entries = counts.entries
                .filter { owners[it.key]!!.size >= 2 }
                .sortedByDescending { it.value }
                .take(6)
                .map { it.key to (it.value to owners[it.key]!!) }
            if (entries.isEmpty()) return@bg
            val labels = entries.associate { (pkg, _) ->
                pkg to runCatching {
                    act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            act.ui {
                val card = Ui.card(act)
                card.addView(Ui.tv(act, "补救 · 多个应用都在找这些包", 13.5f, Ui.TEXT, bold = true))
                card.addView(
                    Ui.tv(act, "它们被藏了，而且不止一个应用问过；点一下给这些目标全部放行。", 10.5f, Ui.MUTED)
                        .also { it.setPadding(0, Ui.dp(act, 2), 0, 0) },
                )
                entries.forEach { (pkg, pair) ->
                    val (count, owners) = pair
                    val row = Ui.row(act)
                    row.setPadding(0, Ui.dp(act, 8), 0, 0)
                    val name = labels[pkg] ?: pkg
                    row.addView(
                        Ui.tv(act, "$name  ×$count · ${owners.size} 个应用问过", 11.5f, Ui.TEXT),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    row.addView(Ui.chip(act, "全部放行", true, Ui.WARN) {})
                    row.getChildAt(1).setOnClickListener { allowAcross(owners.toList(), pkg, name) }
                    card.addView(row)
                }
                body.addView(card)
                body.addView(Ui.spacer(act, 10))
            }
        }
    }

    /** 把某个包对若干目标全部放行：只看基础→加进 show；隐藏名单→从 hide 删。逐目标 save（每个目标一条 prefs）。 */
    private fun allowAcross(targets: List<String>, pkg: String, name: String) {
        act.bg {
            var ok = 0
            val fails = mutableListOf<String>()
            for (p in targets) {
                val cur = runCatching { P2Store.read(act, p) }.getOrNull()
                if (cur == null) { fails.add(p); continue }
                val next = if (cur.mode == PkgPolicy.Mode.SHOW) {
                    cur.copy(show = cur.show + pkg, armed = true)
                } else {
                    cur.copy(hide = cur.hide - pkg, armed = true)
                }
                val msg = P2Store.save(act, next)
                if (msg.startsWith("已保存")) ok++ else fails.add(p)
            }
            act.ui {
                act.toast(
                    if (fails.isEmpty()) "已给 $ok 个目标放行 $name（记得让它们冷启动一次）"
                    else "已放行 $ok 个，失败 ${fails.size} 个：${fails.joinToString(", ")}"
                )
                renderList()
            }
        }
    }

    private fun openEditor(pkg: String, label: String) {
        editing = pkg
        refreshStatus() // 作用域可能被 CLI / Vector 侧改过，进编辑器时重读，别显示旧值
        val existing = runCatching { P2Store.read(act, pkg) }.getOrNull()
        // 新目标默认就是「只看基础」：直接带上基础可见集，用户看到的是"能看到 N 个"，而不是空的 0
        draft = existing ?: P2Store.Target(
            pkg = pkg,
            armed = true,
            mode = PkgPolicy.Mode.SHOW,
            show = BaseSet.collect(act.packageManager, P2Store.currentImeId(act)),
        )
        renderEditor(pkg, label)
    }

    /** 从列表的三态弹层直接进编辑器，并且已经落在指定姿态（自定义=隐藏名单，不再先写一份空策略）。 */
    private fun openEditorWithMode(pkg: String, label: String, mode: PkgPolicy.Mode) {
        openEditor(pkg, label)
        val base = draft ?: P2Store.Target(pkg)
        draft = base.copy(armed = true, mode = mode)
        if (mode == PkgPolicy.Mode.HIDE) act.toast("勾选要藏的包，再点保存并下发")
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

        // 策略：**只有姿态三选一**，不再有独立的 armed 开关——"配置了但没启用"在结构上就不存在
        val policyCard = Ui.card(act)
        val d = draft ?: P2Store.Target(pkg).also { draft = it }
        policyCard.addView(Ui.tv(act, "这个应用能看到什么", 13.5f, Ui.TEXT, bold = true))

        val modeRow = Ui.row(act)
        modeRow.setPadding(0, Ui.dp(act, 10), 0, 0)
        val tri = when {
            !d.armed -> 1
            d.mode == PkgPolicy.Mode.SHOW -> 0
            else -> 2
        }
        modeRow.addView(Ui.chip(act, "只看基础", tri == 0) {
            draft = d.copy(armed = true, mode = PkgPolicy.Mode.SHOW).let {
                if (it.show.isEmpty()) it.copy(show = BaseSet.collect(act.packageManager, P2Store.currentImeId(act))) else it
            }
            renderEditor(pkg, label)
        })
        modeRow.addView(gap(8))
        modeRow.addView(Ui.chip(act, "全部可见", tri == 1) {
            draft = d.copy(armed = false, mode = PkgPolicy.Mode.SHOW)
            renderEditor(pkg, label)
        })
        modeRow.addView(gap(8))
        modeRow.addView(Ui.chip(act, "自定义", tri == 2) {
            draft = d.copy(armed = true, mode = PkgPolicy.Mode.HIDE, rom = d.rom)
            renderEditor(pkg, label)
        })
        policyCard.addView(modeRow)

        policyCard.addView(
            Ui.tv(
                act,
                when (tri) {
                    0 -> "只看基础：它能看到的就下面这份集合，其余第三方一律看不到。默认集 = 系统关键包 + 桌面 + 输入法。"
                    1 -> "全部可见：钩子照样注入，但不做任何判定 —— 等于关掉伪装，不是「更安全」。"
                    else -> "自定义：它能看全部，只把下面这些藏掉。名单空着点保存会直接打开勾选页。"
                },
                10.5f,
                Ui.FAINT,
            ).also { it.setPadding(0, Ui.dp(act, 4), 0, 0) },
        )

        if (tri != 1) {
            val romRow = Ui.row(act)
            romRow.setPadding(0, Ui.dp(act, 10), 0, 0)
            val romCol = Ui.col(act)
            romCol.addView(Ui.tv(act, "ROM 特征包", 12.5f, Ui.TEXT))
            romCol.addView(Ui.tv(act, "连 org.lineageos.* 一起藏（挡「按包名判断是不是 LineageOS」）", 10.5f, Ui.MUTED))
            romRow.addView(romCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            romRow.addView(Ui.switchView(act, d.rom) { v -> draft = (draft ?: d).copy(rom = v) })
            policyCard.addView(romRow)
        }

        val listRow = Ui.row(act)
        listRow.setPadding(0, Ui.dp(act, 12), 0, 0)
        val countTv = Ui.tv(act, listSummary(), 11.5f, Ui.MUTED).also { this.countTv = it }
        listRow.addView(countTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listRow.addView(Ui.button(act, "选名单") { pickList(pkg, label, countTv) })
        policyCard.addView(listRow)

        if (tri == 0) {
            policyCard.addView(
                Ui.tv(act, "基础可见集：" + BaseSet.describe(act.packageManager, P2Store.currentImeId(act)), 10.5f, Ui.FAINT)
                    .also { it.setPadding(0, Ui.dp(act, 6), 0, 0) },
            )
        }

        val saveRow = Ui.row(act)
        saveRow.setPadding(0, Ui.dp(act, 12), 0, 0)
        saveRow.addView(Ui.button(act, "保存并下发", primary = true) { save(pkg, label) })
        policyCard.addView(saveRow)

        // 清空配置是次要动作：一行淡色文字，不抢主按钮
        policyCard.addView(
            Ui.tv(act, "删除这条策略", 10.5f, Ui.FAINT).apply {
                setPadding(0, Ui.dp(act, 12), 0, 0)
                isClickable = true
                setOnClickListener { remove(pkg, label) }
            },
        )
        body.addView(policyCard)
        body.addView(Ui.spacer(act, 10))

        // 运行状态
        val runCard = Ui.card(act)
        runCard.addView(Ui.tv(act, "运行状态（回读目标进程）", 13.5f, Ui.TEXT, bold = true))
        val runTv = Ui.tv(act, "读取中…", 11f, Ui.MUTED)
        runCard.addView(runTv)
        val remedyBox = Ui.col(act)
        runCard.addView(remedyBox)
        val runRow = Ui.row(act)
        runRow.setPadding(0, Ui.dp(act, 8), 0, 0)
        runRow.addView(Ui.button(act, "刷新") { refreshRun(pkg, runTv, remedyBox) })
        runRow.addView(gap(8))
        runRow.addView(Ui.button(act, "重启目标应用") {
            act.bg {
                val msg = P2Device.restart(pkg)
                act.ui { act.toast(msg); refreshRun(pkg, runTv, remedyBox) }
            }
        })
        runCard.addView(runRow)
        body.addView(runCard)

        refreshRun(pkg, runTv, remedyBox)
    }

    private fun listSummary(): String {
        val d = draft ?: return "名单 空"
        return when {
            !d.armed -> "全部可见：它能看到你装的所有应用"
            d.mode == PkgPolicy.Mode.SHOW -> "只看基础：能看到 ${d.show.size} 个（其余第三方看不到）"
            else -> "自定义：藏 ${d.hide.size} 个" + if (d.rom) " + ROM 特征包" else ""
        }
    }

    private fun pickList(pkg: String, label: String, countTv: TextView) {
        val d = draft ?: return
        val showMode = d.mode == PkgPolicy.Mode.SHOW && d.armed
        val items = apps.map { it.second + "\n" + it.first.packageName }
        val pkgs = apps.map { it.first.packageName }
        val selected = BooleanArray(pkgs.size) { pkgs[it] in (if (showMode) d.show else d.hide) }
        android.app.AlertDialog.Builder(act)
            .setTitle(if (showMode) "选「要让目标看到」的应用" else "选「要从目标列表里藏掉」的应用")
            .setMultiChoiceItems(items.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("确定") { dlg, _ ->
                val picked = pkgs.filterIndexed { i, _ -> selected[i] }.toSet()
                draft = if (showMode) d.copy(show = picked) else d.copy(armed = true, hide = picked)
                countTv.text = listSummary()
                dlg.dismiss()
                renderEditor(pkg, label)
            }
            .setNegativeButton("取消") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun save(pkg: String, label: String) {
        val d = draft ?: return
        // 自定义姿态却一份名单都没勾：直接进勾选页，别让用户先吃一次"没有可藏的对象"再回来
        if (d.armed && d.mode == PkgPolicy.Mode.HIDE && d.hide.isEmpty() && !d.rom) {
            act.toast("先勾选要藏的包")
            countTv?.let { pickList(pkg, label, it) }
            return
        }
        act.bg {
            val msg = P2Store.save(act, d)
            act.ui {
                act.toast(msg)
                if (msg.startsWith("已保存")) pendingRestart.add(pkg) else pendingRestart.remove(pkg)
                // 回读一次：save 可能替我们补了基础可见集，页面必须显示真正下发的那份
                draft = runCatching { P2Store.read(act, pkg) }.getOrNull() ?: draft
                refreshStatus()
                renderEditor(pkg, label)
            }
        }
    }

    private fun remove(pkg: String, label: String) {
        android.app.AlertDialog.Builder(act)
            .setTitle("删除 $label 的策略？")
            .setMessage("这个应用会恢复成「全可见」，名单一起丢掉，不能撤销。")
            .setPositiveButton("删除") { _, _ ->
                act.bg {
                    val msg = P2Store.remove(act, pkg)
                    act.ui {
                        act.toast(msg)
                        pendingRestart.remove(pkg)
                        back()
                    }
                }
            }
            .setNegativeButton("取消") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun refreshRun(pkg: String, tv: TextView, remedyBox: LinearLayout) {
        tv.text = "读取中…"
        remedyBox.removeAllViews()
        act.bg {
            val inj = P2Device.injection(pkg)
            val remedies = P2Device.remedies(inj.events)
            val labels = remedies.associate { r ->
                r.pkg to runCatching {
                    act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(r.pkg, 0)).toString()
                }.getOrDefault(r.pkg)
            }
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

                // 补救回路：它问过、但我们藏了的包，点一下直接放进暴露集合
                if (remedies.isNotEmpty()) {
                    remedyBox.addView(
                        Ui.tv(act, "补救：它问过这些应用但被藏了（点一下放进暴露集合）", 10.5f, Ui.WARN)
                            .also { it.setPadding(0, Ui.dp(act, 10), 0, 0) },
                    )
                    remedies.take(8).forEach { r ->
                        val row = Ui.row(act)
                        row.setPadding(0, Ui.dp(act, 6), 0, 0)
                        val label = labels[r.pkg] ?: r.pkg
                        val chip = Ui.chip(act, "$label ×${r.count}", true) { }
                        chip.setOnClickListener {
                            applyRemedy(r, chip, label)
                        }
                        row.addView(chip)
                        remedyBox.addView(row)
                    }
                }
            }
        }
    }

    /** 把"它想看却被藏"的包放行：只看基础模式加进 show；隐藏名单模式从 hide 里删掉。 */
    private fun applyRemedy(r: P2Device.Remedy, chip: TextView, label: String) {
        val d = draft ?: return
        draft = if (d.mode == PkgPolicy.Mode.SHOW) d.copy(show = d.show + r.pkg) else d.copy(hide = d.hide - r.pkg)
        chip.text = "✓ $label 已放行（未保存）"
        chip.isClickable = false
        countTv?.text = listSummary()
        act.toast("已放行 $label（记得点「保存并下发」）")
    }

    private fun gap(dp: Int): View {
        val v = View(act)
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(act, dp), 1)
        return v
    }
}
