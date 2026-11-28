package com.luckyme.watchcat.xposed

import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.VersionedPackage
import com.luckyme.watchcat.BuildConfig
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 应用列表的注入面（P2），装在**目标应用进程**里的那一半。
 *
 * 事实来源：2026-11-22 从小米 13（Android 17 / SDK 37）的 `framework.jar` 里 `dexdump -n`
 * 导出 `ApplicationPackageManager` 全类（363 个方法），核对结论见 `docs/应用列表-API核对.md`。
 * 三条不能忘的：
 *
 *   1. 应用侧只有 `android.app.ApplicationPackageManager` 一个真实现（全框架唯一
 *      `PackageManager` 子类）；`PackageManager` 只是抽象契约 + 过渡桥。
 *   2. 每个语义几乎都是双份重载（`int flags` + `PackageInfoFlags`/`ApplicationInfoFlags`/
 *      `ResolveInfoFlags`/`ComponentInfoFlags`），所以**按方法名族钩、签名运行时枚举**，
 *      一个名字下的所有重载一起钩，绝不写死签名。
 *   3. 原方案里的 `queryIntentProviders`、`queryIntentReceivers` 在 Android 17 不存在，
 *      真实名字是 `queryIntentContentProviders` / `queryContentProviders` / `resolveContentProvider`
 *      与 `queryBroadcastReceivers`。
 *
 * 契约（真机实测，见核对文档 §4.1）：隐藏时各方法必须"表现成什么样"——
 * `getPackageInfo`/`getApplicationInfo`/`getPackageUid` 抛 `NameNotFoundException`；
 * `getApplicationEnabledSetting`/`getComponentEnabledSetting`/`getInstallerPackageName`
 * **抛 `IllegalArgumentException`**（把 `getInstallerPackageName` 写成返回 null 是错的）；
 * 列表/查询给空集合；`resolve*` 与 `launchIntent*` 给 null；`getNamesForUids` 原位置 null。
 *
 * 纪律：
 *   · 默认不生效：策略里 `armed=yes` + `bootId` 等于本次开机、且名单非空才挂钩（L10.1）；
 *   · 探针不受闸门影响：不管启用与否都写 `files/watchcat.p2.marker`（L17.2.1）；
 *   · fail-open：判定/过滤里任何异常都退回原逻辑（L10.4）；只有**明确判定要隐藏**时才抛契约异常；
 *   · 证据走 L15.2：钩子进程是应用 uid，只能写目标应用私有目录，由 App 用 su 归集；
 *   · 绝不隐藏调用者自己、自己同 uid 的包、uid<10000 的系统应用（`PkgPolicy` 里硬编码）。
 *
 * 异常模式用 `PASSTHROUGH`：`PROTECTIVE` 会把我们要抛的 `NameNotFoundException` 吞掉，
 * 那样"隐藏"就变成"照常返回"，等于没做。代价是框架不再替我们兜 bug，所以判定/过滤
 * 全部内部 try 住，异常只允许来自 `chain.proceed()` 本身或我们自己的契约异常。
 */
object PkgHook {

    private const val GROUP = "p2"
    private const val CLS = "android.app.ApplicationPackageManager"

    private const val F_MARKER = "watchcat.p2.marker"
    private const val F_EVENTS = "watchcat.p2.events.jsonl"
    private const val F_POLICY = "watchcat.p2.policy"

    /**
     * 0.6.0 的单份隐藏名单：所有被注入目标共用一份。
     * 读不到时按 per-target 的 `policy.<目标包>` 兜底，保证"新钩子 + 旧配置"不会断档。
     */
    private const val KEY_LIST = "hideList"

    private const val MAX_EVENT_BYTES = 96L * 1024L
    private const val KEEP_EVENT_LINES = 200

    /** 进程内只装一次（不是跨进程状态，每个被注入进程各有一份）。 */
    @Volatile
    private var installed = false

    /** 列表面：返回 `List<PackageInfo>` / `List<ApplicationInfo>`，剔除即可。 */
    private val LIST_NAMES = setOf(
        "getInstalledPackages",
        "getInstalledPackagesAsUser",
        "getInstalledApplications",
        "getInstalledApplicationsAsUser",
    )

    /** 单点面：查不到时按契约抛 `NameNotFoundException`。 */
    private val NNF_NAMES = setOf(
        "getPackageInfo",
        "getPackageInfoAsUser",
        "getApplicationInfo",
        "getApplicationInfoAsUser",
        "getPackageUid",
        "getPackageUidAsUser",
    )

    /** 单点面：查不到时按契约抛 `IllegalArgumentException`（真机实测，不是返回默认值/null）。 */
    private val IAE_NAMES = setOf(
        "getApplicationEnabledSetting",
        "getComponentEnabledSetting",
        "getInstallerPackageName",
    )

    /** 单点面：查不到时按契约返回 null。 */
    private val NULL_NAMES = setOf(
        "getLaunchIntentForPackage",
        "getLeanbackLaunchIntentForPackage",
        "getLaunchIntentSenderForPackage",
    )

    /** Intent/组件面：返回 `List<ResolveInfo>` / `List<ProviderInfo>`，剔除。 */
    private val QUERY_NAMES = setOf(
        "queryIntentActivities",
        "queryIntentActivitiesAsUser",
        "queryIntentServices",
        "queryIntentServicesAsUser",
        "queryIntentContentProviders",
        "queryIntentContentProvidersAsUser",
        "queryBroadcastReceivers",
        "queryBroadcastReceiversAsUser",
        "queryIntentActivityOptions",
        "queryContentProviders",
    )

    /** Intent/组件面：返回单个对象，命中隐藏则给 null。 */
    private val RESOLVE_NAMES = setOf(
        "resolveActivity",
        "resolveActivityAsUser",
        "resolveService",
        "resolveServiceAsUser",
        "resolveContentProvider",
        "resolveContentProviderAsUser",
        "resolveContentProviderForUid",
    )

    private val UID_NAMES = setOf("getPackagesForUid", "getNamesForUids")
    private val PREFERRED_NAMES = setOf("getPreferredActivities")

    private val ALL_NAMES: Set<String> =
        LIST_NAMES + NNF_NAMES + IAE_NAMES + NULL_NAMES + QUERY_NAMES + RESOLVE_NAMES + UID_NAMES + PREFERRED_NAMES

    /**
     * 在目标应用进程的 `onPackageLoaded` 里调用。
     *
     * `appInfo.dataDir` 是唯一可靠的"自己私有目录"来源（工作资料/多用户都不用手算），
     * 写不进去就整块降级为"证据缺失"，不影响钩子本身。
     */
    @JvmStatic
    fun install(api: XposedInterface, pkgName: String, appInfo: ApplicationInfo?, loader: ClassLoader?) {
        // 一个进程只装一次：`onPackageLoaded` 会按包回调多次（共享 uid / 多包进程），
        // 重复装会把同一方法钩两遍，过滤跑两次、审计双份。
        if (installed) return
        installed = true

        val dataDir = (appInfo?.dataDir ?: "/data/user/0/$pkgName").trimEnd('/')
        val ctx = Ctx(
            selfPkg = pkgName,
            selfUid = android.os.Process.myUid(),
            dataDir = dataDir,
            bootId = readBootId(),
            source = Source(openPrefs(api), File("$dataDir/files/$F_POLICY"), "policy.$pkgName"),
        )
        val snap = ctx.source.current()
        val armed = PkgPolicy.enabled(snap, ctx.bootId)

        val hooked = ArrayList<String>()
        val hookedNames = HashSet<String>()
        val diag = ArrayList<String>()
        var declared = 0

        if (armed) {
            val cls = try {
                (loader ?: ClassLoader.getSystemClassLoader()).loadClass(CLS)
            } catch (t: Throwable) {
                diag.add("noclass=$CLS:${brief(t)}")
                null
            }
            if (cls != null) {
                val methods = try {
                    cls.declaredMethods.also { declared = it.size }
                } catch (t: Throwable) {
                    diag.add("declared:${brief(t)}")
                    emptyArray<Method>()
                }
                for (m in methods) {
                    val name = m.name
                    if (name !in ALL_NAMES) continue
                    if (Modifier.isStatic(m.modifiers)) continue
                    val err = hookOne(api, m, name, ctx)
                    if (err == null) {
                        hookedNames.add(name)
                        hooked.add(name)
                    } else {
                        diag.add("fail=$name(${arity(m)}p):$err")
                    }
                }
                val missing = ALL_NAMES.filter { it !in hookedNames }
                if (missing.isNotEmpty()) diag.add("miss=" + missing.joinToString(","))
            }
        }

        writeMarker(
            ctx,
            buildString {
                append("stage=p2_pkg_hook")
                append("\narmed=").append(if (armed) "yes" else "no")
                append("\npolicy=").append(when {
                    !ctx.source.present() -> "missing"
                    snap.armed && snap.bootId == null -> "badboot"
                    snap.armed -> "ok"
                    else -> "disarmed"
                })
                append("\nmode=").append(snap.mode.name.lowercase())
                append("\nrom=").append(if (snap.rom) "yes" else "no")
                append("\nhide=").append(snap.hide.size)
                append("\nshow=").append(snap.show.size)
                append("\nkeep=").append(
                    listOfNotNull(snap.keepHome, snap.keepIme, snap.keepWeb).joinToString(",").ifEmpty { "-" },
                )
                append("\nselfPkg=").append(ctx.selfPkg)
                append("\nselfUid=").append(ctx.selfUid)
                append("\nhooked=").append(hooked.size)
                append("\nmethods=").append(if (hooked.isEmpty()) "-" else hooked.distinct().joinToString(","))
                append("\ndeclared=").append(declared)
                append("\ndiag=").append(if (diag.isEmpty()) "-" else diag.joinToString(";").take(600))
                append("\nbootId=").append(ctx.bootId)
                append("\ngateBootId=").append(snap.bootId ?: "none")
            },
        )
    }

    private fun hookOne(api: XposedInterface, method: Method, name: String, ctx: Ctx): String? = try {
        api.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain -> handle(chain, name, ctx) }
        null
    } catch (t: Throwable) {
        brief(t)
    }

    /**
     * 一次调用的全过程：读策略 → 参数级预判 → 原调用 → 结果过滤 → 审计。
     *
     * 只有"参数本身就是被隐藏的包名/组件/启动 Intent 目标"时才走预判；其余一律先 proceed
     * 再过滤，这样处理的是真实返回对象的字段，不猜系统实现。
     */
    private fun handle(chain: XposedInterface.Chain, name: String, ctx: Ctx): Any? {
        val snap = ctx.source.current()
        if (!PkgPolicy.enabled(snap, ctx.bootId)) return chain.proceed()

        val argPkg = argPackage(chain)
        if (argPkg != null && safelyHidden(argPkg, null, ctx, snap)) {
            // 参数级预判只拿到包名、拿不到 uid，而 uid<10000 的保护规则需要 uid。
            // 所以预判只当"候选"，先拿真实返回对象里的 uid 再定案（L10.4：宁可漏，不可错杀系统 provider）。
            val raw = chain.proceed()
            val uid = pkgUidOf(raw)
            if (!safelyHidden(argPkg, uid, ctx, snap)) return filter(chain, name, raw, ctx, snap)
            when {
                name in NULL_NAMES -> {
                    audit(ctx, name, argPkg, "null")
                    return null
                }
                name in NNF_NAMES -> {
                    audit(ctx, name, argPkg, "nnf")
                    throw PackageManager.NameNotFoundException(argPkg)
                }
                name in IAE_NAMES -> {
                    audit(ctx, name, argPkg, "iae")
                    throw IllegalArgumentException(
                        (if (name == "getComponentEnabledSetting") "Unknown component: " else "Unknown package: ") + argPkg
                    )
                }
            }
            return filter(chain, name, raw, ctx, snap)
        }

        val raw = chain.proceed()
        return filter(chain, name, raw, ctx, snap)
    }

    /** 从参数里取"包名"：String 型方法取第一个 String，`VersionedPackage` 取包名，组件取 `packageName`。 */
    private fun argPackage(chain: XposedInterface.Chain): String? = try {
        when (val a = chain.getArg(0)) {
            is String -> a
            is VersionedPackage -> a.packageName
            is ComponentName -> a.packageName
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    private fun filter(
        chain: XposedInterface.Chain,
        name: String,
        raw: Any?,
        ctx: Ctx,
        snap: PkgPolicy.Snapshot,
    ): Any? = try {
        when {
            name in LIST_NAMES || name in QUERY_NAMES -> filterList(raw, name, ctx, snap)
            name in RESOLVE_NAMES -> {
                val p = pkgOf(raw)
                if (p != null && safelyHidden(p, pkgUidOf(raw), ctx, snap)) {
                    audit(ctx, name, p, "null")
                    null
                } else {
                    raw
                }
            }
            name == "getPackagesForUid" -> filterPackagesForUid(raw, chainUid(chain), ctx, snap)
            name == "getNamesForUids" -> filterNamesForUids(raw, ctx, snap)
            name in PREFERRED_NAMES -> filterPreferred(raw, chain, ctx, snap)
            else -> raw
        }
    } catch (t: Throwable) {
        // 过滤失败一律退回原值（L10.4）：宁可漏隐藏，也不能把应用搞崩
        raw
    }

    private fun filterList(raw: Any?, name: String, ctx: Ctx, snap: PkgPolicy.Snapshot): Any? {
        val list = raw as? List<*> ?: return raw
        val kept = ArrayList<Any?>(list.size)
        var removed = 0
        for (item in list) {
            val p = pkgOf(item)
            if (p != null && safelyHidden(p, pkgUidOf(item), ctx, snap)) {
                removed++
                continue
            }
            kept.add(item)
        }
        if (removed == 0) return raw
        audit(ctx, name, "removed=" + removed, "filter")
        return kept
    }

    private fun filterPackagesForUid(raw: Any?, uid: Int?, ctx: Ctx, snap: PkgPolicy.Snapshot): Any? {
        val arr = raw as? Array<*> ?: return raw
        if (uid != null && uid == ctx.selfUid) return raw
        val kept = ArrayList<String>(arr.size)
        var removed = 0
        for (e in arr) {
            val p = e as? String
            if (p != null && safelyHidden(p, uid, ctx, snap)) {
                removed++
            } else if (p != null) {
                kept.add(p)
            }
        }
        if (removed == 0) return raw
        audit(ctx, "getPackagesForUid", "removed=" + removed, "filter")
        return kept.toTypedArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterNamesForUids(raw: Any?, ctx: Ctx, snap: PkgPolicy.Snapshot): Any? {
        // 实参/返回值都是 String[]，这里只把命中项置 null（数组长度与下标必须保持，调用方按 uid 对位）
        val arr = raw as? Array<Any?> ?: return raw
        var changed = 0
        for (i in arr.indices) {
            val p = arr[i] as? String ?: continue
            if (safelyHidden(p, null, ctx, snap)) {
                try {
                    arr[i] = null
                    changed++
                } catch (t: Throwable) {
                    // 个别运行时对 String[] 写 null 也可能抛，忽略单项
                }
            }
        }
        if (changed > 0) audit(ctx, "getNamesForUids", "nulled=" + changed, "filter")
        return raw
    }

    /**
     * `getPreferredActivities(outFilters, outActivities, packageName)`：往调用方传进来的两个可变
     * 列表里写结果、返回写入条数。按 ComponentName 的包名成对剔除，并把计数一起改小。
     */
    @Suppress("UNCHECKED_CAST")
    private fun filterPreferred(
        raw: Any?,
        chain: XposedInterface.Chain,
        ctx: Ctx,
        snap: PkgPolicy.Snapshot,
    ): Any? {
        val filters = try {
            chain.getArg(0) as? MutableList<Any?>
        } catch (t: Throwable) {
            null
        } ?: return raw
        val activities = try {
            chain.getArg(1) as? MutableList<Any?>
        } catch (t: Throwable) {
            null
        } ?: return raw

        var i = 0
        var removed = 0
        while (i < activities.size) {
            val cn = activities[i] as? ComponentName
            if (cn != null && safelyHidden(cn.packageName, null, ctx, snap)) {
                activities.removeAt(i)
                if (i < filters.size) filters.removeAt(i)
                removed++
            } else {
                i++
            }
        }
        if (removed > 0) audit(ctx, "getPreferredActivities", "removed=" + removed, "filter")
        return activities.size
    }

    private fun chainUid(chain: XposedInterface.Chain): Int? = try {
        chain.getArg(0) as? Int
    } catch (t: Throwable) {
        null
    }

    private fun pkgOf(o: Any?): String? = when (o) {
        is PackageInfo -> o.packageName
        is ApplicationInfo -> o.packageName
        is ProviderInfo -> o.packageName
        is ResolveInfo -> o.activityInfo?.packageName
            ?: o.serviceInfo?.packageName
            ?: o.providerInfo?.packageName
        is ComponentName -> o.packageName
        else -> null
    }

    private fun pkgUidOf(o: Any?): Int? = when (o) {
        is ApplicationInfo -> o.uid
        is PackageInfo -> o.applicationInfo?.uid
        is ProviderInfo -> o.applicationInfo?.uid
        is ResolveInfo -> o.activityInfo?.applicationInfo?.uid
            ?: o.serviceInfo?.applicationInfo?.uid
            ?: o.providerInfo?.applicationInfo?.uid
        else -> null
    }

    private fun safelyHidden(pkg: String, pkgUid: Int?, ctx: Ctx, snap: PkgPolicy.Snapshot): Boolean = try {
        PkgPolicy.isHidden(pkg, pkgUid, ctx.selfPkg, ctx.selfUid, snap)
    } catch (t: Throwable) {
        false
    }

    private fun audit(ctx: Ctx, api: String, target: String, act: String) {
        try {
            val f = File("${ctx.dataDir}/files/$F_EVENTS")
            f.appendText(
                "ts=" + System.currentTimeMillis() +
                    " uid=" + ctx.selfUid +
                    " pid=" + android.os.Process.myPid() +
                    " pkg=" + ctx.selfPkg +
                    " api=" + api +
                    " target=" + target.replace('\n', ' ') +
                    " act=" + act +
                    " module=" + BuildConfig.VERSION_NAME + "\n"
            )
            if (f.length() > MAX_EVENT_BYTES) {
                val tail = f.readLines().takeLast(KEEP_EVENT_LINES)
                f.writeText(tail.joinToString("\n", postfix = "\n"))
            }
        } catch (t: Throwable) {
            // 写不进就吞掉（L10.4 / L15.2），证据缺失不影响业务
        }
    }

    private fun writeMarker(ctx: Ctx, body: String) {
        val content = body +
            "\napp=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" +
            "\npid=" + android.os.Process.myPid() +
            "\ntimeMs=" + System.currentTimeMillis() + "\n"
        try {
            File("${ctx.dataDir}/files").mkdirs()
            File("${ctx.dataDir}/files/$F_MARKER").writeText(content)
        } catch (t: Throwable) {
            // 同上：证据失败不重试
        }
    }

    private fun openPrefs(api: XposedInterface): SharedPreferences? = try {
        api.getRemotePreferences(GROUP)
    } catch (t: Throwable) {
        null
    }

    private fun readBootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (t: Throwable) {
        "unknown"
    }

    private fun brief(t: Throwable): String =
        (t.javaClass.simpleName + ":" + (t.message ?: ""))
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(120)

    private fun arity(m: Method): String = try {
        m.parameterCount.toString()
    } catch (t: Throwable) {
        "?"
    }

    private class Ctx(
        val selfPkg: String,
        val selfUid: Int,
        val dataDir: String,
        val bootId: String,
        val source: Source,
    )

    /**
     * 策略来源：**remote preferences 优先**（正式通道，目标应用读不到框架托管的那份），
     * 回退到目标应用私有目录里的 `watchcat.p2.policy`（只给联调/真机验证用，root 用 su 写）。
     *
     * 读取顺序（0.6.0 起）：
     *   1. 全局单键 `hideList` —— 一份名单作用于所有被注入目标；
     *   2. 按目标键 `policy.<目标包名>` —— 0.5.x 的正式格式，迁移期内继续兜底；
     *   3. 旧单键 `policy` —— 更早的"所有目标共用一份"，只在上面都读不到时兜底。
     *
     * 每次判定都会查一次：prefs 是内存 map（无 binder），file 走 mtime 比较，没变就不读盘。
     */
    private class Source(
        private val prefs: SharedPreferences?,
        private val file: File,
        private val key: String,
    ) {
        @Volatile
        private var lastText: String? = null

        @Volatile
        private var lastMtime: Long = -1L

        @Volatile
        private var snap: PkgPolicy.Snapshot = PkgPolicy.Snapshot()

        fun present(): Boolean = lastText != null

        fun current(): PkgPolicy.Snapshot {
            val text = read() ?: run {
                lastText = null
                snap = PkgPolicy.Snapshot()
                return snap
            }
            if (text == lastText) return snap
            val parsed = PkgPolicy.parse(text)
            lastText = text
            snap = parsed
            return parsed
        }

        private fun read(): String? {
            try {
                val list = prefs?.getString(KEY_LIST, null)
                if (!list.isNullOrBlank()) return list
                val t = prefs?.getString(key, null)
                if (!t.isNullOrBlank()) return t
                val legacy = prefs?.getString("policy", null)
                if (!legacy.isNullOrBlank()) return legacy
            } catch (t: Throwable) {
                // 框架没连上/被拒，继续走文件回退
            }
            try {
                if (!file.exists()) return null
                val m = file.lastModified()
                if (m == lastMtime && lastText != null) return lastText
                lastMtime = m
                return file.readText()
            } catch (t: Throwable) {
                return null
            }
        }
    }
}
