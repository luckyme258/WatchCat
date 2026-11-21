package com.luckyme.watchcat

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 应用列表（W15.1-A1/A3）：
 *   全部 / 敏感项 两个筛，加一个「显示系统应用」；
 *   敏感数量来自 PackageManager 的 requestedPermissions —— 这一步不需要 root，所以列表永远能打开；
 *   真正的 granted 状态留给详情页去实读（列表不猜，避免缓存骗人，W9.4）。
 */
class AppsScreen(private val act: MainActivity) {

    data class Entry(
        val pkg: String,
        val label: String,
        val uid: Int,
        val system: Boolean,
        val sens: List<String>,
    )

    private val root = Ui.col(act)
    private val list = ListView(act)
    private val adapter = AppAdapter()
    private val statusText = Ui.tv(act, "身份检测中…", 12f, Ui.MUTED)
    private val countText = Ui.tv(act, "", 12f, Ui.MUTED)
    private val search = EditText(act)

    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var onlySensitive = false
    private var showSystem = false
    private var query = ""
    private val iconCache = HashMap<String, Drawable?>()

    init {
        build()
        reload()
    }

    fun view(): View = root

    private fun build() {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0)

        // 身份条：未授权时这里是唯一能看懂「为什么全是灰的」的地方
        val idCard = Ui.card(act)
        val idRow = Ui.row(act)
        val dot = View(act)
        dot.setBackgroundColor(Ui.ACCENT)
        val dlp = LinearLayout.LayoutParams(Ui.dp(act, 8), Ui.dp(act, 8))
        dlp.rightMargin = Ui.dp(act, 8)
        dot.layoutParams = dlp
        idRow.addView(dot)
        idRow.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        idRow.addView(Ui.button(act, "重新检测") { act.refreshIdentity(); reload() })
        idCard.addView(idRow)
        root.addView(idCard)
        root.addView(Ui.spacer(act, 10))

        // 搜索
        search.hint = "搜索应用名或包名"
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        search.setTextColor(Ui.TEXT)
        search.setHintTextColor(Ui.FAINT)
        search.setSingleLine(true)
        search.background = Ui.fill(Ui.CARD_ALT, Ui.dp(act, 12), Ui.STROKE, Ui.dp(act, 1))
        search.setPadding(Ui.dp(act, 12), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
        })
        root.addView(search)
        root.addView(Ui.spacer(act, 10))

        // 筛选行
        val filterRow = Ui.row(act)
        val chipAll = Ui.chip(act, "全部", true) { onlySensitive = false; refreshChips(); applyFilter() }
        val chipSens = Ui.chip(act, "敏感项", false, Ui.WARN) { onlySensitive = true; refreshChips(); applyFilter() }
        chipAll.tag = "all"
        chipSens.tag = "sens"
        filterRow.addView(chipAll)
        val gap = View(act)
        gap.layoutParams = LinearLayout.LayoutParams(Ui.dp(act, 8), 1)
        filterRow.addView(gap)
        filterRow.addView(chipSens)
        filterRow.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        val sysSwitch = Ui.switchView(act, showSystem) { showSystem = it; applyFilter() }
        val sysLabel = Ui.tv(act, "系统应用", 11.5f, Ui.MUTED)
        filterRow.addView(sysLabel)
        filterRow.addView(sysSwitch)
        root.addView(filterRow)

        countText.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 6))
        root.addView(countText)

        list.divider = null
        list.dividerHeight = Ui.dp(act, 1)
        list.setPadding(0, 0, 0, Ui.dp(act, 8))
        list.clipToPadding = false
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        registerChips(chipAll, chipSens)
    }

    private var chipAllRef: TextView? = null
    private var chipSensRef: TextView? = null

    private fun registerChips(a: TextView, b: TextView) {
        chipAllRef = a
        chipSensRef = b
    }

    /** 胶囊选中态只能重建背景，不做动画 —— 列表页要的是响应快 */
    private fun refreshChips() {
        val a = chipAllRef ?: return
        val b = chipSensRef ?: return
        a.setTextColor(if (!onlySensitive) Ui.ACCENT else Ui.MUTED)
        a.background = Ui.fill(if (!onlySensitive) Ui.alpha(Ui.ACCENT, 0x26) else Ui.CARD_ALT,
            Ui.dp(act, 9), if (!onlySensitive) Ui.ACCENT else Ui.STROKE, Ui.dp(act, 1))
        b.setTextColor(if (onlySensitive) Ui.WARN else Ui.MUTED)
        b.background = Ui.fill(if (onlySensitive) Ui.alpha(Ui.WARN, 0x26) else Ui.CARD_ALT,
            Ui.dp(act, 9), if (onlySensitive) Ui.WARN else Ui.STROKE, Ui.dp(act, 1))
    }

    fun setIdentity(text: String, ok: Boolean) {
        statusText.text = text
        statusText.setTextColor(if (ok) Ui.MUTED else Ui.WARN)
    }

    fun reload() {
        countText.text = "正在读取应用列表…"
        act.bg {
            val pm = act.packageManager
            val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            val entries = ArrayList<Entry>(apps.size)
            for (ai in apps) {
                val sens = runCatching {
                    pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.filter { it in Catalog.SENSITIVE } ?: emptyList()
                }.getOrDefault(emptyList())
                entries += Entry(
                    pkg = ai.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                    uid = ai.uid,
                    system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    sens = sens,
                )
            }
            val sorted = entries.sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
            act.ui {
                all = sorted
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query
        shown = all.filter { e ->
            (showSystem || !e.system) &&
                (!onlySensitive || e.sens.isNotEmpty()) &&
                (q.isEmpty() || e.label.lowercase().contains(q) || e.pkg.lowercase().contains(q))
        }
        countText.text = "共 ${shown.size} 个应用" + if (onlySensitive) "（申请了敏感项）" else ""
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }

    private fun icon(pkg: String): Drawable? {
        if (iconCache.containsKey(pkg)) return iconCache[pkg]
        val d = runCatching { act.packageManager.getApplicationIcon(pkg) }.getOrNull()
        iconCache[pkg] = d
        return d
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = shown[position]
            val row = Ui.row(act)
            row.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10))
            row.background = Ui.clickableBackground(act, 14)
            row.isClickable = true
            row.setOnClickListener { act.openDetail(e.pkg, e.uid, e.label) }

            val img = ImageView(act)
            img.setImageDrawable(icon(e.pkg))
            val ilp = LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40))
            ilp.rightMargin = Ui.dp(act, 12)
            img.layoutParams = ilp
            row.addView(img)

            val col = Ui.col(act)
            col.addView(Ui.tv(act, e.label, 14.5f, Ui.TEXT, bold = false))
            val sub = if (e.sens.isEmpty()) {
                e.pkg
            } else {
                e.pkg + " · " + e.sens.map { Catalog.groupOfPerm(it)?.title ?: "?" }.distinct().joinToString("/")
            }
            val subView = Ui.tv(act, sub, 11f, if (e.sens.isEmpty()) Ui.FAINT else Ui.alpha(Ui.WARN, 0xCC))
            subView.setPadding(0, Ui.dp(act, 2), 0, 0)
            col.addView(subView)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (e.sens.isNotEmpty()) {
                val badge = Ui.pill(act, e.sens.size.toString() + " 项", Ui.WARN)
                row.addView(badge)
            }
            val arrow = Ui.tv(act, "›", 16f, Ui.FAINT)
            arrow.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 2), 0)
            row.addView(arrow)

            row.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return row
        }
    }
}
