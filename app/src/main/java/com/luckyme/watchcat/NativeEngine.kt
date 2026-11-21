package com.luckyme.watchcat

/**
 * 与 libwatchcat.so 的唯一接口面。
 *
 * 硬约束（《安卓开发规范》W7）：导出函数 ≤ 8 个，一个函数一个职责。
 *
 * **类名即符号名**：`.so` 里导出的是 `Java_com_luckyme_watchcat_NativeEngine_*`，
 * 所以这个对象必须叫 `NativeEngine`，且这六个方法名不能再改 —— 改任一边都只会得到
 * `No implementation found for ...`（那不是加载失败，是符号对不上）。
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

    /** 五态编号 → 界面文案；越界返回 "?"，不抛异常 */
    external fun nativeModeName(mode: Int): String

    /** 候选 op 表条数 */
    external fun nativeOpCount(): Int
}
