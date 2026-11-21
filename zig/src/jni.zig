//! 手写的 JNI 表 —— 本项目不依赖 NDK 头文件（本机 NDK 半解压、无 jni.h）。
//!
//! 原理：C 侧 `JNIEnv*` 是「指向函数表指针的指针」，函数表前 4 个槽位是
//! reserved0..3，之后按 JNI 规范的字段顺序排。我们只取用到的槽位，按偏移
//! 强转成函数指针调用。
//!
//! 已验证的槽位（2026-11-21 主机侧 JDK17 真调通过）：
//!   4   GetVersion      -> 0xa0000
//!   167 NewStringUTF    -> 返回 "watchcat/zig"
//!
//! 新增槽位前先查 JNI 规范顺序，再在 `py tools/build_so.py --host` 产物上
//! 跑一遍冒烟测试，不要直接在真机上试。

pub const Slot = ?*const anyopaque;
pub const VTable = [256]Slot;
pub const JNIEnv = *const *const VTable;

pub const jint = i32;
pub const jobject = ?*anyopaque;
pub const jstring = jobject;
pub const jclass = jobject;
pub const jbyteArray = jobject;

pub const SLOT_GET_VERSION: usize = 4;
pub const SLOT_NEW_STRING_UTF: usize = 167;

inline fn raw(env: JNIEnv, comptime i: usize) *const anyopaque {
    return env.*[i].?;
}

inline fn call(env: JNIEnv, comptime i: usize, comptime F: type) F {
    return @ptrCast(@alignCast(raw(env, i)));
}

/// 把 UTF-8 字符串建成 Java 对象（内容不受 JVM 生命周期约束，调用方负责引用管理）
pub fn newStringUtf(env: JNIEnv, s: [*:0]const u8) jstring {
    const F = *const fn (JNIEnv, [*:0]const u8) callconv(.c) jstring;
    return call(env, SLOT_NEW_STRING_UTF, F)(env, s);
}

/// 取 JVM 的 JNI 版本号，用于自检「函数表偏移算对了没有」
pub fn getVersion(env: JNIEnv) jint {
    const F = *const fn (JNIEnv) callconv(.c) jint;
    return call(env, SLOT_GET_VERSION, F)(env);
}
