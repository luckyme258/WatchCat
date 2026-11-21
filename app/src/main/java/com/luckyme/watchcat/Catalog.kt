package com.luckyme.watchcat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * 词典层：把系统里那些冰冷的名字翻成人话，并给出「该走哪条通道」。
 *
 * 这里的清单不是权威表 —— 权威的是能力探测表（W8.3）。它只负责：
 *   1. 界面上有中文可读；
 *   2. 权限型 → 权限通道、行为型 → appops 通道的分派依据；
 *   3. W9.1 的禁写名单。
 */
object Catalog {

    data class PermGroup(val title: String, val note: String, val perms: List<String>)

    data class Op(val name: String, val label: String, val note: String, val scope: Int)

    /** 权限组：详情页按组排，用户先看懂「这个应用能干什么」，再决定关不关 */
    val GROUPS = listOf(
        PermGroup("相机", "能随时打开摄像头拍照、录像，包括后台", listOf("android.permission.CAMERA")),
        PermGroup("麦克风", "能随时录音，后台也能听", listOf("android.permission.RECORD_AUDIO")),
        PermGroup(
            "定位", "知道你在哪：精确到街道，粗略到城市；后台定位单独一档",
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
            ),
        ),
        PermGroup(
            "通讯录", "能读走你所有联系人，也能改",
            listOf(
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS",
                "android.permission.GET_ACCOUNTS",
            ),
        ),
        PermGroup(
            "短信", "能读、能发、能接收验证码短信",
            listOf(
                "android.permission.READ_SMS",
                "android.permission.SEND_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.RECEIVE_MMS",
            ),
        ),
        PermGroup(
            "通话记录", "能看你的通话记录，也能改",
            listOf(
                "android.permission.READ_CALL_LOG",
                "android.permission.WRITE_CALL_LOG",
                "android.permission.PROCESS_OUTGOING_CALLS",
            ),
        ),
        PermGroup(
            "日历", "能读、能改你的日程",
            listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
        ),
        PermGroup(
            "照片与文件", "能读你的相册、下载目录；「所有文件」是另一档更高的权限",
            listOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            ),
        ),
        PermGroup("通知", "能往通知栏推消息（13 起才需要授权）", listOf("android.permission.POST_NOTIFICATIONS")),
        PermGroup(
            "电话状态", "能读设备号、SIM 状态、来电状态",
            listOf("android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS"),
        ),
        PermGroup("身体传感器", "能读心率等身体数据", listOf("android.permission.BODY_SENSORS")),
        PermGroup(
            "附近设备", "能扫蓝牙、附近的 Wi-Fi；17 起本地网络也要单独授权",
            listOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_LOCAL_NETWORK",
            ),
        ),
        PermGroup("活动识别", "能判断你在走路、跑步还是开车", listOf("android.permission.ACTIVITY_RECOGNITION")),
    )

    /** 所有归「权限通道」管的敏感权限（用于列表统计与过滤） */
    val SENSITIVE: List<String> = GROUPS.flatMap { it.perms }

    /**
     * 不能走 `pm grant/revoke` 的权限（实测会抛 `not a changeable permission type`，
     * 或压根不在 dumpsys 的 granted 里）。界面上必须灰掉并写原因，不许给假开关。
     */
    val SPECIAL = setOf(
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.ACCESS_NOTIFICATION_POLICY",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    )

    /**
     * 行为型 op：走 `appops set`。
     * 前 6 条是 2026-11-21 在小米 9（Android 16）上实测「写得进、读得回」的；
     * 后来者只有在能力探测里转绿，才允许在界面上画开关。
     */
    val OPS = listOf(
        Op("WAKE_LOCK", "唤醒锁", "让设备保持唤醒、阻止息屏", 0),
        Op("VIBRATE", "振动", "马达震动反馈", 0),
        Op("RUN_IN_BACKGROUND", "后台运行", "允许在后台持续跑；忽略=退到后台就停", 0),
        Op("RUN_ANY_IN_BACKGROUND", "后台活动", "按 uid 记录的后台活动档", 1),
        Op("REQUEST_INSTALL_PACKAGES", "安装应用", "允许它装别的 App（高风险）", 0),
        Op("ACTIVATE_VPN", "建立 VPN", "允许它接管你的全部流量", 0),
        Op("START_FOREGROUND", "前台服务", "允许挂常驻通知跑服务", 0),
        Op("POST_NOTIFICATION", "通知", "发通知（13 起同时受运行时权限管）", 0),
        Op("SYSTEM_ALERT_WINDOW", "悬浮窗", "能在别的应用上盖一层（特殊权限，需专属入口）", 0),
        Op("GET_USAGE_STATS", "使用统计", "能看到你在用哪些应用（特殊权限，需专属入口）", 0),
    )

    /** 五态的中文与配色口径，界面统一从这里取 */
    val MODE_LABELS = arrayOf("默认", "允许", "忽略", "拒绝", "仅前台")
    const val MODE_DEFAULT = 0
    const val MODE_ALLOW = 1
    const val MODE_IGNORE = 2
    const val MODE_DENY = 3
    const val MODE_FOREGROUND = 4

    fun modeLabel(id: Int): String = MODE_LABELS.getOrElse(id) { "?" }

    fun opInfo(name: String): Op? = OPS.firstOrNull { it.name == name }

    fun groupOfPerm(perm: String): PermGroup? = GROUPS.firstOrNull { perm in it.perms }

    fun isPermissionType(name: String): Boolean = name == "POST_NOTIFICATION" ||
        name == "CAMERA" || name == "RECORD_AUDIO" || name.endsWith("_LOCATION")

    /**
     * W9.1 禁写名单。命中就只许看，不许改 —— 这些包一改就是系统级事故：
     * 系统框架、设置、权限控制器、桌面、输入法、电话/短信/联系人 provider、我们自己、root 管理器。
     */
    private val FORBIDDEN = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.luckyme.watchcat",
        "com.topjohnwu.magisk",
        "me.weishu.kernelsu",
        "com.android.providers.contacts",
        "com.android.providers.telephony",
        "com.android.providers.media",
        "com.google.android.providers.media.module",
        "com.android.phone",
        "com.android.server.telecom",
    )

    /** 禁写原因；null = 可以改 */
    fun forbiddenReason(ctx: Context, pkg: String): String? {
        if (pkg in FORBIDDEN) {
            return when (pkg) {
                "com.luckyme.watchcat" -> "这是 WatchCat 自己：改自己的权限会把自己废掉"
                "com.topjohnwu.magisk", "me.weishu.kernelsu" -> "root 管理器：改了会丢掉授权链路"
                "android", "com.android.systemui", "com.android.settings",
                "com.android.permissioncontroller", "com.google.android.permissioncontroller",
                -> "系统核心组件：不参与权限管理"
                else -> "电话 / 短信 / 联系人基础服务：改了会影响收短信、打电话"
            }
        }
        // 桌面与输入法：改它们的权限会让人「点不开应用、打不出字」
        val home = runCatching {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        if (home != null && home == pkg) return "当前桌面：改了会回不了桌面"
        val ime = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }.getOrNull()
        if (ime != null && ime == pkg) return "当前输入法：改了会打不出字"
        return null
    }

    /** 某一档要不要二次确认：拒绝档会让应用抛异常、可能直接崩 */
    fun needsConfirm(modeId: Int): Boolean = modeId == MODE_DENY

    fun apiHint(): String = when {
        Build.VERSION.SDK_INT >= 36 -> "Android 16+：权限型 op 的 appops 写入会被系统丢弃（见 W8.3）"
        Build.VERSION.SDK_INT >= 33 -> "Android 13+：通知是运行时权限，存储拆成媒体权限"
        Build.VERSION.SDK_INT >= 31 -> "Android 12+：相机/麦克风有全局开关，系统关了我们也拿不到数据"
        else -> "Android 11：注意包可见性与闲置应用自动撤权"
    }
}
