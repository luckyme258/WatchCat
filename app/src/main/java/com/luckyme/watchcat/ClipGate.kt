package com.luckyme.watchcat

import android.content.Context
import android.provider.Settings
import java.io.File

/**
 * 剪贴板数据面的闸门与策略（A 阶段）。
 *
 * 为什么门和策略都落在 `/data/system`：写得进是唯一标准（L17.1）。
 * 2026-11-22 实测：root 能在 `/data/system` 建文件（label `system_data_file`、0644 root:root），
 * system_server（uid 1000）能读；反过来 `/data/adb/watchcat` 是 0700 root:root，system_server
 * 连 DAC 都过不去，放那儿等于闸门永远读不到。
 *
 * 所以链路是：App 用 `su` 写 → system_server 读 → 钩子按策略给结果 → 事件写回同一个目录 → 诊断页再 `su` 读。
 * 三段都不需要对方持有特权，也都能各自独立验证（L17.2）。
 *
 * 每次开机 `post-fs-data.sh` 会删掉 armed（L10.1），用户要重新点一次"启用"；这不是缺陷，
 * 是"装完即生效"的反面要求。
 */
object ClipGate {

    const val ARMED = "/data/system/watchcat.armed"
    const val POLICY = "/data/system/watchcat.clip.policy"
    const val EVENTS = "/data/system/watchcat.events"
    const val MARKER = "/data/system/watchcat.p3_clip_hook.marker"
    const val TRIPPED = "/data/system/watchcat.tripped.marker"
    const val FAIL_COUNT = "/data/adb/watchcat/fail.count"

    data class Status(
        val bootId: String,
        val gateBootId: String?,
        val policyText: String?,
        val markerText: String?,
        val events: List<String>,
        val tripped: String?,
        val failCount: String?,
    ) {
        /** 本轮是否真的启用：闸门里的 bootId 必须等于当前 boot_id */
        val armedNow: Boolean get() = gateBootId != null && gateBootId == bootId
    }

    fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unknown")

    /**
     * 现读一遍状态。全部经过 `su cat`：这些文件是 `0600 system:system` / `0644 root:root`，
     * 应用进程直接 `File` 读不到，读不到就说读不到，不猜。
     */
    fun status(): Status {
        val boot = bootId()
        val armedRaw = suCat(ARMED)
        val gate = armedRaw?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("bootId=") }
            ?.substringAfter('=')
            ?.trim()
        val events = suCat(EVENTS)?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            ?.takeLast(20)
            ?: emptyList()
        return Status(
            bootId = boot,
            gateBootId = gate,
            policyText = suCat(POLICY),
            markerText = suCat(MARKER),
            events = events,
            tripped = suCat(TRIPPED),
            failCount = suCat(FAIL_COUNT)?.trim(),
        )
    }

    /**
     * 启用：先把策略写进去，再写闸门。
     *
     * 顺序有讲究——钩子启动时"有闸门但没策略"会被当成没启用（ClipboardHook.install 里的判定），
     * 所以策略必须先落。两个文件写完后回读校验，回读不符就当失败，不给"看起来启用了"的假象。
     */
    fun enable(ctx: Context): String {
        val boot = bootId()
        val policy = buildPolicy(ctx, boot)
        val tmpPolicy = File(ctx.cacheDir, "clip.policy.tmp")
        val tmpGate = File(ctx.cacheDir, "clip.armed.tmp")
        return try {
            tmpPolicy.writeText(policy)
            tmpGate.writeText(
                "bootId=$boot\n" +
                    "timeMs=${System.currentTimeMillis()}\n" +
                    "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n"
            )
            val r = Shell.su(
                "cp '${tmpPolicy.absolutePath}' $POLICY && " +
                    "chown system:system $POLICY && chmod 0600 $POLICY && " +
                    "cp '${tmpGate.absolutePath}' $ARMED && " +
                    "chown system:system $ARMED && chmod 0600 $ARMED && " +
                    "echo OK",
                8_000
            )
            if (!r.ok || !r.output.contains("OK")) {
                return "写入失败：${r.output.trim().ifEmpty { "exit=" + r.code }}"
            }
            // 回读：只信读回来的内容，不信 cp 的返回码
            val back = suCat(ARMED)
            if (back == null || !back.contains("bootId=$boot")) {
                return "写入后回读不符，未启用"
            }
            "已启用（策略 ${policy.lines().size} 行，闸门 bootId=$boot）\n" +
                "钩子要等 system_server 下次启动才装载：现在只重启框架（stop; start），不是重启手机。"
        } catch (t: Throwable) {
            "启用失败：${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { tmpPolicy.delete() }
            runCatching { tmpGate.delete() }
        }
    }

    /** 停用：删闸门与策略；事件保留（审计不该因为关功能就被抹掉）。 */
    fun disable(): String {
        val r = Shell.su("rm -f $ARMED $POLICY && echo OK", 6_000)
        return if (r.ok && r.output.contains("OK")) "已停用" else "停用失败：${r.output.trim()}"
    }

    /**
     * 策略文本。默认「空」，输入法与自身放行，其余一律空（方案附录 A.3）。
     *
     * 逐应用设置从 PolicyStore 的 `clip.mode.<uid>` 读——本期 UI 还没给这个入口，
     * 但格式先固定下来，后面接详情页胶囊时不用改协议。
     */
    fun buildPolicy(ctx: Context, bootId: String): String {
        val sb = StringBuilder()
        sb.append("# WatchCat 剪贴板策略（由 App 生成，勿手改）\n")
        sb.append("version=1\n")
        sb.append("default=empty\n")
        sb.append("imeuid=").append(imeUid(ctx)?.toString() ?: "").append('\n')
        sb.append("allow=").append(android.os.Process.myUid()).append('\n')
        sb.append("pool=").append(poolFor(ctx)).append('\n')
        sb.append("# bootId=").append(bootId).append('\n')
        runCatching {
            PolicyStore.prefs(ctx).all.forEach { (k, v) ->
                if (!k.startsWith("clip.mode.")) return@forEach
                val uid = k.removePrefix("clip.mode.").toIntOrNull() ?: return@forEach
                val mode = v?.toString()?.lowercase() ?: return@forEach
                if (mode in setOf("allow", "empty", "fake")) {
                    sb.append("mode.").append(uid).append('=').append(mode).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** 当前输入法的 uid。取不到就留空——钩子那边 imeuid 为空时只放行系统身份，粘贴会坏，所以这里必须尽力。 */
    private fun imeUid(ctx: Context): Int? {
        val ime = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull() ?: return null
        val pkg = ime.substringBefore('/')
        runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull()?.let { return it }
        // 包可见性兜底：走 root 问一遍（本应用已声明 QUERY_ALL_PACKAGES，正常走不到这条）
        val r = Shell.su("dumpsys package $pkg | grep -m1 userId=", 8_000)
        val uid = Regex("userId=(\\d+)").find(r.output)?.groupValues?.get(1)?.toIntOrNull()
        return uid
    }

    private fun poolFor(ctx: Context): String = runCatching {
        PolicyStore.prefs(ctx).getString("clip.pool", null)
    }.getOrNull() ?: "社交"

    private fun suCat(path: String): String? {
        val r = Shell.su("cat $path 2>/dev/null", 6_000)
        return if (r.ok && r.output.isNotBlank()) r.output else null
    }
}
