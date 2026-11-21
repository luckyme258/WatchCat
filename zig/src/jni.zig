//! 手写的 JNI 表 —— 本项目不依赖 NDK 头文件（本机 NDK 半解压、无 jni.h）。
//!
//! 原理：C 侧 `JNIEnv*` 是「指向函数表指针的指针」，函数表前 4 个槽位是
//! reserved0..3，之后按 JNI 规范的字段顺序排。我们只取用到的槽位，按偏移
//! 强转成函数指针调用。
//!
//! 槽位来源：**本机 NDK 27 的 jni.h**（`...\sysroot\usr\include\jni.h`，
//! struct JNINativeInterface 从 4 起按声明顺序编号，共 229 个），
//! 不是凭 JNI 规范背出来的——Android 的表比标准 jni.h 多插了 31 个槽位，
//! 背标准序号一定错。已验证：
//!   4   GetVersion       -> 0xa0000（真机 + 主机都过）
//!   167 NewStringUTF     -> 返回 "watchcat/zig"（真机 + 主机都过；标准表里是 136）
//! 由同一张表推出（2026-11-21）：
//!   169 GetStringUTFChars / 170 ReleaseStringUTFChars
//!
//! 新增槽位前先在本机 jni.h 里数一遍（脚本见《安卓开发规范》W13），
//! 再在 `py tools/build_so.py --host` 产物上冒烟，不要直接在真机上试。

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
pub const SLOT_GET_STRING_UTF_CHARS: usize = 169;
pub const SLOT_RELEASE_STRING_UTF_CHARS: usize = 170;

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

/// 取 Java 字符串的 UTF-8 只读视图（不保证是副本，可能指向 JVM 内部内存）。
/// 返回 null 表示取不到（此时 JVM 里已经挂了异常，直接把 null 透传回去即可）。
pub fn getStringUtfChars(env: JNIEnv, s: jstring) ?[*:0]const u8 {
    // 第三参 isCopy 传 null：我们只读，不关心它给的是副本还是内部指针
    const F = *const fn (JNIEnv, jstring, ?*u8) callconv(.c) ?[*:0]const u8;
    return call(env, SLOT_GET_STRING_UTF_CHARS, F)(env, s, null);
}

/// 配对的释放；不调会漏 JVM 侧内存（真机上会越跑越紧）
pub fn releaseStringUtfChars(env: JNIEnv, s: jstring, chars: [*:0]const u8) void {
    const F = *const fn (JNIEnv, jstring, [*:0]const u8) callconv(.c) void;
    call(env, SLOT_RELEASE_STRING_UTF_CHARS, F)(env, s, chars);
}
