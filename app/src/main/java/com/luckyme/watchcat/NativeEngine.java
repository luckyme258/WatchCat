package com.luckyme.watchcat;

/**
 * 与 libwatchcat.so 的唯一天然接口面。
 *
 * 硬约束（《安卓开发规范》W7）：导出函数 ≤ 8 个，一个函数一个职责；
 * 这里的类名与 .so 里的符号前缀 `Java_com_luckyme_watchcat_NativeEngine_` 必须逐字一致 ——
 * **改类名等于改符号名，两边必须同时改**，否则运行时只会得到
 * `No implementation found for ...`（这不是加载失败，是符号对不上）。
 */
public final class NativeEngine {

    static {
        System.loadLibrary("watchcat");
    }

    private NativeEngine() {
    }

    /** 引擎版本，打包成 0x0001_0000 = 1.0.0 */
    public static native int nativeVersion();

    /** 冒烟：不看参数，只证明调用链通了，固定返回 0x5743 */
    public static native int nativePing();

    /** 冒烟：证明 Zig 侧能造 Java 字符串，返回 watchcat/0.1.0 */
    public static native String nativeTag();

    /** 自检：取 JVM 的 JNI 版本（0x00010006 表示 JNI 1.6 能力） */
    public static native int nativeJniVersion();

    /** 五态编号 → 界面文案；越界返回 "?"，不抛异常 */
    public static native String nativeModeName(int mode);

    /** 候选 op 表条数 */
    public static native int nativeOpCount();
}
