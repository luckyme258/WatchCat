//! WatchCat 引擎（Zig 侧）—— 骨架版
//!
//! 职责边界（《安卓开发规范》第 6 章）：
//!   - Kotlin 负责界面、Shizuku 授权、系统回调；
//!   - 本文件所在这一层负责算：拼命令、解析 `appops` 输出、算策略差异、生成审计行；
//!   - 导出给 JVM 的函数**不超过 8 个**，一个函数一个职责；禁止在这里做 UI 或弹窗。
//!
//! 当前已实现（2026-11-21，真机 API 36 上 LOAD: PASS）：
//!   nativeVersion / nativePing / nativeJniVersion / nativeOpCount
//!   nativeReadOps(raw)   —— 把 `appops get` 的原始输出解析成 JSON（真机样本 7/7 单测通过）
//!   nativeSnapshot(pkg, raw, stamp) —— 写前快照，带 FNV-1a 64 校验和（11/11 单测通过）
//!   nativeWriteOp(pkg, op, mode, scope) —— 拼 `appops set` 命令（含白名单校验，12/12 通过）
//!   nativeCapability(pkg, stamp, probe) —— 能力表判定：写得进 / 写不进 / 该走哪条通道
//!
//! 已按规范 W7「导出 ≤ 8 个」用满上限：nativeModeName 已下线（五态文案归 Kotlin 界面层），
//! nativeTag（纯 JNI 造串演示）让位给 nativeCapability。
//!
//! 待实现（对应规范 6 章的接口面，按需逐个补，不要一次全开）：
//!   nativePlanProbe()       -> 由候选 op 表生成探测计划（哪些 op、哪个作用域、试哪一档）
//!
//! 依赖：只用 libc 之下的 syscall/内存操作，不链 libc、不拖任何 .so（见 elfcheck 输出）。

const std = @import("std");
const jni = @import("jni.zig");
const appops = @import("appops.zig");
const snapshot = @import("snapshot.zig");
const writecmd = @import("writecmd.zig");
const capability = @import("capability.zig");

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

/// 能力表：Kotlin 跑完一轮探测后，把事实行（op/作用域/写前/期望/写后）丢进来，
/// 这里判定「写得进 / 写不进」，并给出该走哪条通道（appop 还是 pm 权限通道）。
///
/// 输入格式见 capability.zig 头部注释：每行 `OP \t scope \t beforeId \t wantId \t afterId`。
/// 为什么判定放在这里而不是 Kotlin：三条通道的语义差异是引擎知识（W8.3），
/// 界面层只该拿结论画灯，不该自己重新推断一遍。
export fn Java_com_luckyme_watchcat_NativeEngine_nativeCapability(
    env: jni.JNIEnv,
    clazz: jni.jclass,
    pkg: jni.jstring,
    stamp: jni.jstring,
    probe: jni.jstring,
) callconv(.c) jni.jstring {
    _ = clazz;

    const pkg_obj = pkg orelse return null;
    const pkg_chars = jni.getStringUtfChars(env, pkg_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, pkg_obj, pkg_chars);

    const stamp_obj = stamp orelse return null;
    const stamp_chars = jni.getStringUtfChars(env, stamp_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, stamp_obj, stamp_chars);

    const probe_obj = probe orelse return null;
    const probe_chars = jni.getStringUtfChars(env, probe_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, probe_obj, probe_chars);

    var fba = std.heap.FixedBufferAllocator.init(&arena_buf);
    const alloc = fba.allocator();

    var parsed = capability.parseProbe(alloc, std.mem.span(probe_chars)) catch return null;
    defer parsed.deinit(alloc);

    const json = capability.toJson(alloc, parsed, .{
        .pkg = std.mem.span(pkg_chars),
        .stamp = std.mem.span(stamp_chars),
    }) catch return null;
    const z = alloc.dupeSentinel(u8, json, 0) catch return null;

    return jni.newStringUtf(env, z.ptr);
}

/// 自检：取 JVM 的 JNI 版本，确认函数表偏移没错
export fn Java_com_luckyme_watchcat_NativeEngine_nativeJniVersion(env: jni.JNIEnv, clazz: jni.jclass) callconv(.c) jni.jint {
    _ = clazz;
    return jni.getVersion(env);
}

/// 已下线：nativeModeName（占用 8 个导出名额，五态文案属界面层，见《安卓开发规范》W7/W8）

/// 候选 op 表的条数，供界面先画个占位列表
export fn Java_com_luckyme_watchcat_NativeEngine_nativeOpCount(env: jni.JNIEnv, clazz: jni.jclass) callconv(.c) jni.jint {
    _ = env;
    _ = clazz;
    return @intCast(appops.known_ops.len);
}

// ---------------------------------------------------------------------------
// 引擎的静态堆：全部落 .bss，不 mmap、不 brk、不碰 libc 堆。
// 一次调用 50 条 op ≈ 3KB 条目 + 6KB JSON + 格式化临时串，64KB 留了 4 倍余量。
// ---------------------------------------------------------------------------
const ARENA_BYTES = 64 * 1024;
var arena_buf: [ARENA_BYTES]u8 align(16) = undefined;

/// 读 op：Kotlin 侧把 `appops get <pkg>` 的**原始输出**原样丢进来，这里返回 JSON。
///
/// 为什么命令不在 .so 里执行：本库不链 libc、不 fork（见 elfcheck 的 DT_NEEDED 为空），
/// 执行权限属于已经拿到 Shizuku/root 授权的那一层；这里只做纯计算，方便主机侧单测。
///
/// `uidLines` 是 **必填事实**：AOSP 的 `appops get` 里 uid 段与包段在文本上无法区分，
/// 只有「先跑 `appops get --uid <uid>` 数出的行数」能划出边界（见 appops.parseGet 注释）。
/// 传 0 等于声明「这份输出没有 uid 段」；传错会让包/uid 作用域整体错位。
///
/// 返回 null 表示失败（入参为 null / 取串失败 / 内存不够），JVM 侧按「读失败」处理。
export fn Java_com_luckyme_watchcat_NativeEngine_nativeReadOps(
    env: jni.JNIEnv,
    clazz: jni.jclass,
    raw: jni.jstring,
    uid_lines: jni.jint,
) callconv(.c) jni.jstring {
    _ = clazz;
    const text = raw orelse return null;

    const chars = jni.getStringUtfChars(env, text) orelse return null;
    defer jni.releaseStringUtfChars(env, text, chars);

    var fba = std.heap.FixedBufferAllocator.init(&arena_buf);
    const alloc = fba.allocator();

    const parsed = appops.parseGet(alloc, std.mem.span(chars), nonNegative(uid_lines)) catch return null;
    const json = appops.toJson(alloc, parsed) catch return null;
    // NewStringUTF 要 NUL 结尾；arena 每次调用都从头分配，不需要手动回收
    const z = alloc.dupeSentinel(u8, json, 0) catch return null;

    return jni.newStringUtf(env, z.ptr);
}

/// 写单个 op：返回**一条可直接执行的 `appops set` 命令**，不在这里执行。
///
/// 参数：pkg、op、mode（0..4 五态）、scope（0=包作用域，1=uid 作用域）。
/// 名字不干净（空白/引号/分号/换行/非 ASCII/超长）一律返回 null —— 这条命令要以 root 跑，
/// 校验必须在拼串的**同一层**做完，不能指望调用方自己过滤。
export fn Java_com_luckyme_watchcat_NativeEngine_nativeWriteOp(
    env: jni.JNIEnv,
    clazz: jni.jclass,
    pkg: jni.jstring,
    op: jni.jstring,
    mode: jni.jint,
    scope: jni.jint,
) callconv(.c) jni.jstring {
    _ = clazz;

    const mode_val = appops.Mode.fromInt(mode) orelse return null;
    const scope_val: appops.Scope = switch (scope) {
        0 => .package,
        1 => .uid,
        else => return null,
    };

    const pkg_obj = pkg orelse return null;
    const pkg_chars = jni.getStringUtfChars(env, pkg_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, pkg_obj, pkg_chars);

    const op_obj = op orelse return null;
    const op_chars = jni.getStringUtfChars(env, op_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, op_obj, op_chars);

    var fba = std.heap.FixedBufferAllocator.init(&arena_buf);
    const alloc = fba.allocator();

    const cmd = writecmd.buildSetCommand(
        alloc,
        std.mem.span(pkg_chars),
        std.mem.span(op_chars),
        mode_val,
        scope_val,
    ) catch return null;

    const z = alloc.dupeSentinel(u8, cmd, 0) catch return null;
    return jni.newStringUtf(env, z.ptr);
}

/// 写前快照：pkg + `appops get` 原始输出 + 时间戳 → 可解析、带校验和的文本。
///
/// 时间戳由 Kotlin 传入（本库不链 libc，取不到系统时间；也让快照可复现）。
/// 返回 null 表示失败，调用方**必须**当作「快照没拿到 → 不许写」处理。
/// 把 JNI 传来的行数收成 usize；负数当 0（宁可按「没有 uid 段」解析，也不让它回绕成天文数字）
fn nonNegative(v: jni.jint) usize {
    if (v <= 0) return 0;
    return @intCast(v);
}

export fn Java_com_luckyme_watchcat_NativeEngine_nativeSnapshot(
    env: jni.JNIEnv,
    clazz: jni.jclass,
    pkg: jni.jstring,
    raw: jni.jstring,
    stamp: jni.jstring,
    uid_lines: jni.jint,
) callconv(.c) jni.jstring {
    _ = clazz;

    const pkg_obj = pkg orelse return null;
    const pkg_chars = jni.getStringUtfChars(env, pkg_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, pkg_obj, pkg_chars);

    const raw_obj = raw orelse return null;
    const raw_chars = jni.getStringUtfChars(env, raw_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, raw_obj, raw_chars);

    const stamp_obj = stamp orelse return null;
    const stamp_chars = jni.getStringUtfChars(env, stamp_obj) orelse return null;
    defer jni.releaseStringUtfChars(env, stamp_obj, stamp_chars);

    var fba = std.heap.FixedBufferAllocator.init(&arena_buf);
    const alloc = fba.allocator();

    const parsed = appops.parseGet(alloc, std.mem.span(raw_chars), nonNegative(uid_lines)) catch return null;
    const snap = snapshot.toSnapshot(alloc, parsed, std.mem.span(pkg_chars), std.mem.span(stamp_chars)) catch return null;
    const z = alloc.dupeSentinel(u8, snap, 0) catch return null;

    return jni.newStringUtf(env, z.ptr);
}
