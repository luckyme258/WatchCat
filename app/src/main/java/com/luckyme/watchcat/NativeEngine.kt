package com.luckyme.watchcat

/**
 * 与 libwatchcat.so 的唯一接口面。
 *
 * 硬约束（《安卓开发规范》W7）：导出函数 ≤ 8 个，一个函数一个职责。
 *
 * **类名即符号名**：`.so` 里导出的是 `Java_com_luckyme_watchcat_NativeEngine_*`，
 * 所以这个对象必须叫 `NativeEngine`，且这八个方法名不能再改 —— 改任一边都只会得到
 * `No implementation found for ...`（那不是加载失败，是符号对不上）。
 *
 * 注意：`nativeModeName` 已下线（导出名额 8/8 用满），五态文案改由界面层维护。
 *
 * 用 `object` 而不是 companion，是为了让方法名不被 Kotlin 做任何名字修饰；
 * 原生侧第 2 个参数（jobject this / jclass）我们不使用，所以静态与非静态都成立。
 */
object NativeEngine {

    init {
        System.loadLibrary("watchcat")
    }

    /** 引擎版本，打包成 0x0001_0000 = 1.0.0 */
    external fun nativeVersion(): Int

    /** 冒烟：不看参数，只证明调用链通了，固定返回 0x5743 */
    external fun nativePing(): Int

    /** 冒烟：证明 Zig 侧能造 Java 字符串，返回 watchcat/0.1.0 */
    external fun nativeTag(): String

    /** 自检：取 JVM 的 JNI 版本（0x00010006 表示 JNI 1.6 能力） */
    external fun nativeJniVersion(): Int

    /** 候选 op 表条数 */
    external fun nativeOpCount(): Int

    /**
     * 把 `appops get <pkg>` 的**原始输出**解析成 JSON（`{"count","skipped","ops":[…]}`）。
     *
     * 分工：命令由拿到授权的 Kotlin 侧执行，Zig 只做纯计算 —— 所以本函数在主机上可单测，
     * 也不需要 .so 链 libc 或 fork。null 入参或内存不足时返回 null，调用方按「读失败」处理。
     */
    /**
     * 把 `appops get <pkg>` 的**原始输出**解析成 JSON（`{"count","skipped","ops":[…]}`）。
     *
     * 分工：命令由拿到授权的 Kotlin 侧执行，Zig 只做纯计算 —— 所以本函数在主机上可单测，
     * 也不需要 .so 链 libc 或 fork。null 入参或内存不足时返回 null，调用方按「读失败」处理。
     *
     * @param uidLines **必填**：AOSP 的 `appops get` 里 uid 段与包段在文本上无法区分，
     *   只能先跑 `appops get --uid <uid>` 数出 uid 段行数再传进来；传错会让作用域整体错位。
     */
    external fun nativeReadOps(raw: String?, uidLines: Int): String?

    /**
     * 写前快照：pkg + `appops get` 原始输出 + 时间戳（形如 `2026-11-21T20:05:11+08:00`）→ 文本。
     *
     * 返回 null 一律视为「快照没拿到」——按规范 W9，此时**不许执行任何写操作**。
     * 时间戳由这里传入，是因为 .so 不链 libc 取不到系统时间，顺带换来快照可复现。
     */
    external fun nativeSnapshot(pkg: String?, raw: String?, stamp: String?, uidLines: Int): String?

    /**
     * 写单个 op：返回一条 `appops set` 命令，**由调用方拿到 root/Shizuku 权限后执行**。
     *
     * @param mode  0=默认 1=允许 2=忽略 3=拒绝 4=仅前台
     * @param scope 0=包作用域 1=uid 作用域（uid 类 op 必须用 1，否则写的不是同一份状态）
     *
     * 名字里带空白/引号/分号/换行/非 ASCII 时一律返回 null —— 这条命令以 root 执行，
     * 校验在 Zig 侧就做完了，调用方不要自己拼串。
     */
    external fun nativeWriteOp(pkg: String?, op: String?, mode: Int, scope: Int): String?
}
