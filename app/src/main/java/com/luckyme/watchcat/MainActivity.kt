package com.luckyme.watchcat

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 壳：顶栏（身份常显，W10.1）+ 内容区 + 三个页签。
 *
 * 为什么不拆 Activity：详情页是「列表的一次下钻」，共享同一个授权与探针状态；
 * 拆成两个 Activity 就要把身份、审计、刷新逻辑复制两份，还会多出一次重建开销。
 */
class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var tabRow: LinearLayout
    private val tabs = ArrayList<TextView>()
    private var identityPill: TextView? = null

    private var apps: AppsScreen? = null
    private var detail: DetailScreen? = null
    private var log: LogScreen? = null
    private var diag: DiagScreen? = null
    private var target: TargetScreen? = null
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Ui.BG))

        val rootLayout = Ui.col(this)
        rootLayout.setBackgroundColor(Ui.BG)

        // 顶栏
        val top = Ui.row(this)
        top.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 8))
        val titleCol = Ui.col(this)
        val title = Ui.tv(this, "WatchCat", 21f, Ui.TEXT, bold = true)
        titleCol.addView(title)
        val sub = Ui.tv(this, "权限看门狗 · ${BuildConfig.VERSION_NAME}", 11f, Ui.MUTED)
        sub.setPadding(0, Ui.dp(this, 2), 0, 0)
        titleCol.addView(sub)
        top.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pill = Ui.pill(this, "检测中", Ui.MUTED)
        pill.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5))
        identityPill = pill
        top.addView(pill)
        rootLayout.addView(top)

        content = FrameLayout(this)
        rootLayout.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部页签
        rootLayout.addView(Ui.divider(this))
        tabRow = Ui.row(this, Gravity.CENTER)
        tabRow.setBackgroundColor(0xFF08080A.toInt())
        addTab("应用")
        addTab("目标")
        addTab("日志")
        addTab("诊断")
        rootLayout.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)))

        setContentView(rootLayout)

        // 系统栏配色必须放在 setContentView 之后：DecorView 此时才存在，
        // 之前拿 window.insetsController 会拿到 null 并抛 NPE（2026-11-21 真机崩过）。
        if (Build.VERSION.SDK_INT >= 30) {
            window.setStatusBarColor(Ui.BG)
            window.setNavigationBarColor(Ui.BG)
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }

        // 边到边：targetSdk 36 强制 edge-to-edge，内容必须自己让开系统栏
        rootLayout.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        refreshIdentity()
        showTab(0)
    }

    private fun addTab(name: String) {
        val index = tabs.size
        val t = Ui.tv(this, name, 13f, Ui.MUTED)
        t.gravity = Gravity.CENTER
        t.setOnClickListener { showTab(index) }
        tabs.add(t)
        tabRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun selectTab(index: Int) {
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.setTextColor(if (on) Ui.ACCENT else Ui.MUTED)
            t.alpha = if (on) 1f else 0.8f
        }
    }

    fun showTab(index: Int) {
        detail = null
        current = index
        selectTab(index)
        val v: View = when (index) {
            0 -> (apps ?: AppsScreen(this).also { apps = it }).view()
            1 -> (target ?: TargetScreen(this).also { target = it }).also { it.refresh() }.view()
            2 -> (log ?: LogScreen(this).also { log = it }).also { it.refresh() }.view()
            else -> (diag ?: DiagScreen(this).also { diag = it }).also { it.refreshEngine() }.view()
        }
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 页面切换时顺手复核一次身份：服务被杀了要当场看见（W10.2）
        refreshIdentity()
    }

    fun openDetail(pkg: String, uid: Int, label: String) {
        detail = DetailScreen(this, pkg, uid, label)
        tabs.forEach { it.setTextColor(Ui.MUTED); it.alpha = 0.8f }
        content.removeAllViews()
        content.addView(detail!!.view(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun refreshIdentity() {
        bg {
            val text = Engine.identityText()
            val ok = text.startsWith("root")
            ui {
                // 顶栏胶囊和列表页的身份条是同一份事实，必须同时更新，
                // 否则会出现「顶栏 root、卡片还在检测中」的自相矛盾（2026-11-21 真机见）
                apps?.setIdentity(text, ok)
                identityPill?.let {
                    it.text = if (ok) "root" else "未授权"
                    it.setTextColor(if (ok) Ui.ACCENT else Ui.WARN)
                    it.background = Ui.fill(
                        if (ok) Ui.alpha(Ui.ACCENT, 0x22) else Ui.alpha(Ui.WARN, 0x22),
                        Ui.dp(this, 8),
                        if (ok) Ui.ACCENT else Ui.WARN,
                        Ui.dp(this, 1),
                    )
                }
            }
        }
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    fun bg(block: () -> Unit) {
        Thread { block() }.apply {
            isDaemon = true
            name = "watchcat-bg"
        }.start()
    }

    fun ui(block: () -> Unit) {
        runOnUiThread { block() }
    }

    override fun onBackPressed() {
        if (detail != null) {
            showTab(current)
        } else if (current == 1 && target?.back() == true) {
            // 目标页的编辑器返回列表，不退出应用
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置/授权弹窗回来后，权限可能已经变了：一律重新校验，不吃启动时的缓存
        refreshIdentity()
        if (detail == null && current == 0) apps?.reload()
    }
}
