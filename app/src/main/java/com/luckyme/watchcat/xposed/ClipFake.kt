package com.luckyme.watchcat.xposed

/**
 * 剪贴板替身词库（《hook方案-v0.2.md》附录 A.2 的落地版）。
 *
 * 三条硬要求来自方案原文，改词库时必须保留：
 *   1. 长度 6–40 字，像"人复制出去的东西"，不能是单个固定词；
 *   2. 不含账号、地址、金额、证件号——这些一旦被当成真实隐私，比"空"更糟；
 *   3. 同一应用在同一次开机内始终拿到同一条（`(uid, boot_id)` 做种子），跨开机可换。
 *      来回变会被应用当成异常，等于自报"我被 hook 了"。
 *
 * 纯函数，无 Android 依赖，可单测。
 */
object ClipFake {

    /** 池顺序即 `uid % 3` 的落点，和方案里"购物/社交/工具"三池对应。 */
    private val POOLS: List<Pair<String, List<String>>> = listOf(
        "购物" to listOf(
            "米白色 210 码 加厚款",
            "这个链接能便宜二十吗",
            "顺丰到付可以吗",
            "收货地址改到公司前台",
            "有没有 32G 的版本",
            "发票抬头开个人",
            "预售什么时候发货",
            "先加购物车，晚上再决定",
            "这个和上次买的有什么区别",
        ),
        "社交" to listOf(
            "晚上七点老地方见",
            "文件我发群里了，记得看",
            "这个周末我有空，你呢",
            "刚才那个链接打不开了",
            "明天上午十点开会",
            "照片原图发我一份",
            "你说的那家店叫什么",
            "路上堵，晚到十分钟",
            "收到，我处理一下",
        ),
        "工具" to listOf(
            "2026-11-21 14:30 项目周会",
            "待办：续费域名、备份相册",
            "端口 8080 被占用了",
            "把这段转成 Markdown",
            "订单号末四位 4471",
            "周二之前给回复",
            "这条先记下来，回头再看",
            "算一下三个人平摊多少",
            "明天记得带身份证",
        ),
    )

    val poolNames: List<String> get() = POOLS.map { it.first }

    /**
     * 取一条替身内容。
     *
     * @param poolName 策略里指定的池名；不认识就按 uid 取模回退
     * @param bootId   本次开机 id，用来保证"同一次开机内稳定"
     */
    fun pick(uid: Int, bootId: String, poolName: String? = null): String {
        val byName = poolName?.let { name -> POOLS.firstOrNull { it.first == name } }
        val pool = (byName ?: POOLS[Math.floorMod(uid, POOLS.size)]).second
        // 不用 Random：种子来自 (uid, bootId)，同一进程重算也拿同一条
        val seed = uid.toLong() * 1_000_003L + bootId.hashCode().toLong()
        return pool[Math.floorMod(seed, pool.size.toLong()).toInt()]
    }
}
