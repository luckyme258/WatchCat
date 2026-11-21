//! WatchCat 引擎（Zig 侧）—— 骨架版
//!
//! 职责边界（《安卓开发规范》第 6 章）：
//!   - Kotlin 负责界面、Shizuku 授权、系统回调；
//!   - 本文件所在这一层负责算：拼命令、解析 `appops` 输出、算策略差异、生成审计行；
//!   - 导出给 JVM 的函数**不超过 8 个**，一个函数一个职责；禁止在这里做 UI 或弹窗。
//!
//! 当前已实现（2026-11-21，主机侧 JDK17 真调通过）：
//!   nativeVersion / nativePing / nativeTag / nativeJniVersion / nativeModeName / nativeOpCount
//!
//! 待实现（对应规范 6 章的接口面，按需逐个补，不要一次全开）：
//!   nativeReadOps(pkg)      -> 读全量 op（appops get 的解析结果）
//!   nativeWriteOp(pkg, op, mode) -> 写单个 op，返回是否回读一致
//!   nativeSnapshot()        -> 生成快照文本（写操作前必调）
//!   nativeProbe()           -> 能力探测，产出本机 op 能力表
//!
//! 依赖：只用 libc 之下的 syscall/内存操作，不链 libc、不拖任何 .so（见 elfcheck 输出）。

const std = @import("std");
const jni = @import("jni.zig");
const appops = @import("appops.zig");

/// 版本号，打包成 0x0001_0000 = 1.0.0。改版本要同步改 build 脚本里的说明。
pub const version_packed: u32 = 0x0001_0000;

// ---------------------------------------------------------------------------
// 导出函数：名字必须是 Java_<包名下划线化>_<类名>_<方法名>
// 对应 Kotlin 侧 com.luckyme.watchcat.NativeEngine
// ---------------------------------------------------------------------------

export fn Java_com_luckyme_watchcat_NativeEngine_nativeVersion(env: jni.JNIEnv, clazz: jni.jclass) callconv(.c) jni.jint {
    _ = env;
    _ = clazz;
    return @intCast(version_packed);
}

/// 冒烟用：不看任何参数，只证明调用链通了
export fn Java_com_luckyme_watchcat_NativeEngine_nativePing(env: jni.JNIEnv, clazz: jni.jclass) callconv(.c) jni.jint {
    _ = env;
    _ = clazz;
    return 0x5743; // "WC"
}

/// 冒烟用：证明 Zig 能造 Java 字符串
export fn Java_com_luckyme_watchcat_NativeEngine_nativeTag(env: jni.JNIEnv, clazz: jni.jclass) callconv(.c) jni.jstring {
    _ = clazz;
    return jni.newStringUtf(env, "watchcat/0.1.0");
}

/// 自检：取 JVM 的 JNI 版本，确认函数表偏移没错
export fn Java_com_luckyme_watchcat_NativeEngine_nativeJniVersion(env: jni.JNIEnv, clazz: jni.jclass) callconv(.c) jni.jint {
    _ = clazz;
    return jni.getVersion(env);
}

/// 把五态编号翻成界面文案；越界返回 "?"，不抛异常给 JVM
export fn Java_com_luckyme_watchcat_NativeEngine_nativeModeName(env: jni.JNIEnv, clazz: jni.jclass, mode: jni.jint) callconv(.c) jni.jstring {
    _ = clazz;
    const m = appops.Mode.fromInt(mode) orelse return jni.newStringUtf(env, "?");
    return jni.newStringUtf(env, m.label());
}

/// 候选 op 表的条数，供界面先画个占位列表
export fn Java_com_luckyme_watchcat_NativeEngine_nativeOpCount(env: jni.JNIEnv, clazz: jni.jclass) callconv(.c) jni.jint {
    _ = env;
    _ = clazz;
    return @intCast(appops.known_ops.len);
}
