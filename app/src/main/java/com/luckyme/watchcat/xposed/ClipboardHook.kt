package com.luckyme.watchcat.xposed

import android.content.ClipData
import android.os.Binder
import com.luckyme.watchcat.BuildConfig
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method

/**
 * 剪贴板读方向的钩子（A 阶段闭合实现）。
 *
 * 注入点与方法面都不是猜的：2026-11-22 从本机（小米 13 / Android 17）拉下
 * `/system/framework/services.jar`，用 `dexdump` 读出 ClipboardService 的真实重载，
 * 结论在《hook方案-v0.2.md》§4.2 与 §13 的决策记录里。要点：
 *
 *   · 读方向五个方法一律是 `(String pkg, String attributionTag, int userId, int deviceId)`
 *     这四参形态，`hasPrimaryClip` / `hasClipboardText` 返回 boolean；
 *   · 写方向（`setPrimaryClip` / `setPrimaryClipAsPackage` / `clearPrimaryClip`）不钩——
 *     拦写入会让"复制"整体失效，属于自伤（方案 §4.2 已定）。
 *
 * 纪律（违反任何一条都会被审出来）：
 *   1. **默认不生效**：`/data/system/watchcat.armed` 里的 bootId 必须等于当前 boot_id，
 *      否则一个钩都不挂（L10.1）。armed 由 post-fs-data 每次开机删除，用户要重新确认。
 *   2. **探针不受闸门影响**：不管 armed 与否都写 `/data/system/watchcat.p3_clip_hook.marker`
 *      （L17.2.1），否则"没钩上"和"没启用"永远分不清。
 *   3. **零静态可变状态**：策略每次现读文件，不缓存、不留计数变量——同一进程里两份状态
 *      是 v0.2 明确要避免的东西。
 *   4. **fail-open**：判定里任何异常都走原逻辑（L10.4），绝不返回错误值。
 *   5. **不碰 Android 私有 API**：只用 `Binder.getCallingUid()` + `ClipData`，
 *      IME 的 uid 由 App 侧算好写进策略，hook 侧不做包名解析。
 *
 * 本期明确不上"阻断（抛 SecurityException）"：框架的 PROTECTIVE 异常模式会把钩子里抛出的
 * 异常吞掉并继续走原逻辑，要让阻断生效只能整钩改 PASSTHROUGH，那会把我们自己的 bug 也
 * 变成崩溃。等 UI 有明确"可能让应用崩溃"的警示后再单独开。
 */
object ClipboardHook {

    private const val DIR = "/data/system"
    private const val F_ARMED = "$DIR/watchcat.armed"
    private const val F_POLICY = "$DIR/watchcat.clip.policy"
    private const val F_EVENTS = "$DIR/watchcat.events"
    private const val F_MARKER = "$DIR/watchcat.p3_clip_hook.marker"

    private const val SERVICE_CLASS = "com.android.server.clipboard.ClipboardService"
    private const val MAX_EVENT_BYTES = 96L * 1024L
    private const val KEEP_EVENT_LINES = 200

    private enum class Kind { CLIP, DESC, SOURCE, HAS, HAS_TEXT }

    /** 方法名 → 返回形态。只认读方向，不在这张表里的名字一律不钩（不去猜系统实现）。 */
    private val READ_METHODS: Map<String, Kind> = linkedMapOf(
        "getPrimaryClip" to Kind.CLIP,
        "getPrimaryClipDescription" to Kind.DESC,
        "getPrimaryClipSource" to Kind.SOURCE,
        "hasPrimaryClip" to Kind.HAS,
        "hasClipboardText" to Kind.HAS_TEXT,
    )

    /** 在 system_server 里调用一次，返回一行摘要（只用于日志，不参与判定）。 */
    @JvmStatic
    fun install(api: XposedInterface, loader: ClassLoader): String {
        val bootId = readText("/proc/sys/kernel/random/boot_id")?.trim() ?: "unknown"
        val gateBootId = gateBootId()
        val policyText = readText(F_POLICY)

        val policyState = when {
            policyText == null -> "missing"
            policyText.isBlank() -> "blank"
            else -> "ok"
        }
        // armed 必须"本轮签发"：bootId 对不上就当没启用（重启后旧 gate 一律无效）
        val armed = gateBootId != null && gateBootId == bootId && policyState == "ok"

        val hooked = ArrayList<String>()
        var classState = "skip"
        if (armed) {
            val cls = try {
                loader.loadClass(SERVICE_CLASS)
            } catch (t: Throwable) {
                classState = "not-found:" + t.javaClass.simpleName
                null
            }
            if (cls != null) {
                classState = cls.name
                val declared = try {
                    cls.declaredMethods
                } catch (t: Throwable) {
                    emptyArray<Method>()
                }
                for ((name, kind) in READ_METHODS) {
                    for (m in declared) {
                        if (m.name != name) continue
                        if (hookOne(api, m, kind)) hooked.add(name)
                    }
                }
            }
        }

        writeMarker(
            "stage=p3_clip_hook" +
                "\narmed=" + (if (armed) "yes" else "no") +
                "\npolicy=" + policyState +
                "\nhooked=" + hooked.size +
                "\nmethods=" + (if (hooked.isEmpty()) "-" else hooked.joinToString(",")) +
                "\nclass=" + classState +
                "\nblock=unsupported" +
                "\nbootId=" + bootId +
                "\ngateBootId=" + (gateBootId ?: "none")
        )
        return "armed=" + armed + " policy=" + policyState + " hooked=" + hooked.size
    }

    private fun hookOne(api: XposedInterface, method: Method, kind: Kind): Boolean = try {
        api.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptOne(chain, kind) }
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * 一次读取的全过程：取调用者 → 现读策略 → 判定 → 审计 → 决定返回值。
     *
     * 判定段单独包在 try 里、失败就 proceed（L10.4）；**返回段不在 try 里**，
     * 这样将来真开阻断时 SecurityException 不会被自己的 catch 吃掉。
     */
    private fun interceptOne(chain: XposedInterface.Chain, kind: Kind): Any? {
        val verdict = try {
            // 策略文件被删（用户点"停用"）时 hook 还挂在进程里，这时必须放行——
            // 把 "文件不存在" 当成默认空，等于停用按钮失效。
            val policyText = readText(F_POLICY) ?: return chain.proceed()
            val uid = Binder.getCallingUid()
            val pkg = try {
                chain.getArg(0) as? String
            } catch (t: Throwable) {
                null
            }
            Verdict(uid, pkg, ClipPolicy.decide(uid, ClipPolicy.parse(policyText)))
        } catch (t: Throwable) {
            null
        }
        if (verdict == null) return chain.proceed()

        // 阻断本期不生效：policy 里写了 block 也按 empty 走，并在审计里标出来，不假装做到了
        val blockDowngraded = verdict.mode == ClipPolicy.Mode.BLOCK
        val mode = if (blockDowngraded) ClipPolicy.Mode.EMPTY else verdict.mode
        audit(verdict.uid, verdict.pkg, kind, mode, blockDowngraded)

        return when (mode) {
            ClipPolicy.Mode.ALLOW -> chain.proceed()
            ClipPolicy.Mode.EMPTY -> emptyValue(kind)
            ClipPolicy.Mode.FAKE -> fakeValue(kind, verdict.uid)
            // 已被上面降级，走不到这里；留着是为了 when 穷尽，改语义时编译器会提醒
            ClipPolicy.Mode.BLOCK -> emptyValue(kind)
        }
    }

    private class Verdict(val uid: Int, val pkg: String?, val mode: ClipPolicy.Mode)

    /** "空"必须在每个方法自己的返回类型上合法：null ClipData / null 描述 / false，都不是异常值。 */
    private fun emptyValue(kind: Kind): Any? = when (kind) {
        Kind.CLIP, Kind.DESC, Kind.SOURCE -> null
        Kind.HAS, Kind.HAS_TEXT -> false
    }

    private fun fakeValue(kind: Kind, uid: Int): Any? {
        val bootId = readText("/proc/sys/kernel/random/boot_id")?.trim() ?: ""
        val snap = ClipPolicy.parse(readText(F_POLICY))
        val text = ClipFake.pick(uid, bootId, snap.fakePool)
        return when (kind) {
            Kind.CLIP -> ClipData.newPlainText("text", text)
            Kind.DESC -> ClipData.newPlainText("text", text).description
            // 来源包名只用于展示，给假包名反而更像异常；给 null
            Kind.SOURCE -> null
            Kind.HAS, Kind.HAS_TEXT -> true
        }
    }

    /**
     * 审计一行：`ts=.. uid=.. pkg=.. m=.. mode=.. [downgrade=block]`
     *
     * 本期不做"1 秒内合并计数"（方案 §4.5 的加分项）——它需要跨调用状态，
     * 与"零静态状态"冲突；等诊断页真要看量级时再按 uid 做聚合。
     */
    private fun audit(uid: Int, pkg: String?, kind: Kind, mode: ClipPolicy.Mode, downgraded: Boolean) {
        try {
            val f = File(F_EVENTS)
            f.appendText(
                "ts=" + System.currentTimeMillis() +
                    " uid=" + uid +
                    " pkg=" + (pkg ?: "-") +
                    " m=" + kind.name +
                    " mode=" + mode.name +
                    (if (downgraded) " downgrade=block" else "") + "\n"
            )
            if (f.length() > MAX_EVENT_BYTES) {
                val tail = f.readLines().takeLast(KEEP_EVENT_LINES)
                f.writeText(tail.joinToString("\n", postfix = "\n"))
            }
        } catch (t: Throwable) {
            // 审计写不进去只吞掉（与 L10.4 同口径），不回灌到业务
        }
    }

    /** armed 文件里第一行 `bootId=...`；没有文件或读不到就返回 null。 */
    private fun gateBootId(): String? {
        val text = readText(F_ARMED) ?: return null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("bootId=")) return line.substring(7).trim()
        }
        return null
    }

    private fun writeMarker(body: String) {
        val content = body +
            "\napp=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" +
            "\npid=" + android.os.Process.myPid() +
            "\ntimeMs=" + System.currentTimeMillis() + "\n"
        try {
            File(F_MARKER).writeText(content)
        } catch (t: Throwable) {
            // 主落点写不进就算了：/data/system 已实测可写，写不进说明环境变了，不在功能路径上重试
        }
    }

    private fun readText(path: String): String? = try {
        val f = File(path)
        if (f.exists()) f.readText() else null
    } catch (t: Throwable) {
        null
    }
}
