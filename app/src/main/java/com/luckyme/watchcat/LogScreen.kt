package com.luckyme.watchcat

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 审计页（W6 / W15-B15）：只看「我们改过什么」，不给伪造的历史。
 * 回滚只回退本应用改过的条目，不碰系统里别人设的策略。
 */
class LogScreen(private val act: MainActivity) {

    private val root = Ui.col(act)
    private val list = ListView(act)
    private val adapter = LineAdapter()
    private var lines: List<String> = emptyList()

    init {
        build()
        refresh()
    }

    fun view(): View = root

    private fun build() {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0)

        val head = Ui.card(act)
        head.addView(Ui.tv(act, "审计日志", 15f, Ui.TEXT, bold = true))
        head.addView(
            Ui.tv(
                act,
                "每次写操作都留一行：时间 pkg= op= old= new= src= caller=。\n文件：files/watchcat-audit.log（外部目录也有镜像，可 adb pull）",
                11f,
                Ui.MUTED,
            ),
        )
        val btns = Ui.row(act)
        btns.setPadding(0, Ui.dp(act, 12), 0, 0)
        btns.addView(Ui.button(act, "一键回滚") { rollback() })
        btns.addView(gap(8))
        btns.addView(Ui.button(act, "导出") { export() })
        btns.addView(gap(8))
        btns.addView(Ui.button(act, "清空", danger = true) { clear() })
        head.addView(btns)
        root.addView(head)
        root.addView(Ui.spacer(act, 10))

        list.adapter = adapter
        list.divider = null
        list.dividerHeight = Ui.dp(act, 1)
        list.setPadding(0, 0, 0, Ui.dp(act, 12))
        list.clipToPadding = false
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() {
        act.bg {
            val l = Audit.read(act, 400)
            act.ui {
                lines = l
                adapter.notifyDataSetChanged()
            }
        }
    }

    /** 只回退「我们改过的」：按最后一次写入的档回 default（W9.2） */
    private fun rollback() {
        val targets = Audit.rollbackTargets(act)
        if (targets.isEmpty()) {
            act.toast("没有可回滚的记录")
            return
        }
        var count = 0
        val sb = StringBuilder()
        targets.forEach { (pkg, op, mode) ->
            if (mode != Catalog.MODE_DEFAULT) {
                count++
                sb.append(pkg).append(" · ").append(op).append("  ")
            }
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("回滚 $count 条策略？")
            .setMessage("将把下列 pkg+op 写回「默认」：\n$sb\n\n只回退本应用改过的；写不进的会保持原状并在提示里说明。")
            .setPositiveButton("回滚") { d, _ ->
                d.dismiss()
                act.bg {
                    var ok = 0
                    var fail = 0
                    var pkgFail = ""
                    for ((pkg, op, _) in targets) {
                        val ai = runCatching { act.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                        val uid = ai?.uid ?: 0
                        val o = Engine.writeOp(act, pkg, uid, op, Catalog.MODE_DEFAULT, 0, src = "auto-revert")
                        if (o.ok) ok++ else { fail++; if (pkgFail.isEmpty()) pkgFail = "$pkg/$op：${o.msg}" }
                    }
                    act.ui {
                        act.toast("回滚完成：成功 $ok，失败 $fail" + if (fail > 0) "（$pkgFail）" else "")
                        refresh()
                    }
                }
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
    }

    private fun export() {
        act.bg {
            val p = Audit.export(act)
            act.ui { act.toast(if (p == null) "导出失败" else "已导出：$p") }
        }
    }

    private fun clear() {
        android.app.AlertDialog.Builder(act)
            .setTitle("清空审计日志？")
            .setMessage("只删本地日志文件，不改变系统里已生效的策略。清空后就无法用「一键回滚」找回改过哪些。")
            .setPositiveButton("清空") { d, _ ->
                d.dismiss()
                Audit.clear(act)
                refresh()
                act.toast("已清空")
            }
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .show()
    }

    private fun gap(dp: Int): View {
        val v = View(act)
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(act, dp), 1)
        return v
    }

    private inner class LineAdapter : BaseAdapter() {
        override fun getCount(): Int = lines.size
        override fun getItem(position: Int): Any = lines[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val line = lines[position]
            val v = Ui.tv(act, line, 11f, if (line.contains("new=default")) Ui.MUTED else Ui.TEXT)
            v.setPadding(Ui.dp(act, 10), Ui.dp(act, 9), Ui.dp(act, 10), Ui.dp(act, 9))
            v.setTextIsSelectable(true)
            return v
        }
    }
}
