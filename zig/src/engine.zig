//! WatchCat 数据面判定引擎（P0 空壳，但语义是真的）
//!
//! 与 `watchcat.zig` 的分工
//!   watchcat.zig —— 权限面：给 JVM 用的 JNI 导出（appops 解析、命令拼装、能力判定）。
//!   本文件      —— 数据面：**不导出 JNI 名字**，只导出朴素 C 符号，供 Zygisk 里的 C++ shim
//!                  静态链接后直接调用。理由见《LSP模块2026开发规范》L2：
//!                  native 只由 Zygisk 加载，hook 层零 UI/资源依赖；JNI 那一套到不了
//!                  system_server 之外的进程，也没必要到。
//!
//! 为什么判定放在这里而不是 Kotlin
//!   规则是「某个 uid 的某类数据要给空还是给真」——这是引擎知识，Kotlin 只负责把
//!   规则喂进来（remote preferences → 这里），以及把结果画成灯。P0 先把这条链路打通。
//!
//! 硬约束（规范 L7）
//!   - 无静态初始化副作用：下面只有一张 .bss 表，没有 comptime 里跑 IO、没有 constructor。
//!   - 不碰资源系统、不开文件、不弹窗、不 fork。
//!   - 不链 libc：只用 Zig 自带的内存操作，编译成静态库后由 shim 链接。
//!   - 单次调用不分配内存（表是定长数组），因此在 system_server 里调用不会触发 GC/JNI 抖动。
//!
//! 当前语义（故意做得极小，可单测）
//!   rule_set(slot, uid, kind_mask)  —— 往表里写一条规则；slot 越界/重复 uid 覆盖写
//!   rule_clear()                    —— 清空整张表（停用时必须能一键回到「全部放行」）
//!   decide(uid, kind)               —— 返回 1 给空数据 / 0 放行 / 2 未知 kind（调用方按放行处理）
//!   version / ping                  —— 版本与通路自检
//!
//! 注意：`decide` 的默认答案是 **0（放行）**。规范 L10「默认不生效」在引擎这一层的落点就是
//! 这张空表：进程刚起、规则还没喂进来时，任何应用都拿到真数据，不会把用户界面搞坏。

const std = @import("std");

pub const version_packed: u32 = 0x0002_0000; // 2.0.0：数据面引擎，与权限面 1.x 区分

/// kind：数据面的三类能力。数值是**契约**，Kotlin 与 C++ 两侧都要对得上，改值等于改协议。
pub const KIND_CLIPBOARD: u8 = 0;
pub const KIND_APP_LIST: u8 = 1;
pub const KIND_CONTACTS: u8 = 2;
pub const KIND_MAX: u8 = 3;

/// 判定结果，同样是契约
pub const DECIDE_PASS: u8 = 0;
pub const DECIDE_BLANK: u8 = 1;
pub const DECIDE_BAD_KIND: u8 = 2;

/// 规则表容量。P0 只验证通路，64 条足够覆盖「被管的常用应用」；
/// 真正上线时如果不够，改成两段式（定长热点表 + 冷数据查文件），不在这里无脑加数组。
pub const MAX_RULES: usize = 64;

const Rule = struct {
    uid: u32 = 0,
    /// bit0..2 对应 KIND_CLIPBOARD / APP_LIST / CONTACTS；bit7 标记这一格被用过
    mask: u8 = 0,
};

const OCCUPIED: u8 = 0x80;

var rules: [MAX_RULES]Rule = std.mem.zeroes([MAX_RULES]Rule);

fn kindBit(kind: u8) u8 {
    if (kind >= KIND_MAX) return 0;
    return @as(u8, 1) << @intCast(kind);
}

// ---------------------------------------------------------------------------
// 导出符号：朴素 C ABI，顺序、名字、类型都不能随便动（shim 侧按名字链接）
// ---------------------------------------------------------------------------

export fn wc_engine_version() callconv(.c) u32 {
    return version_packed;
}

/// 通路自检：只要这个返回 0x5743，就证明「Zig 编出来的代码确实在执行」
export fn wc_engine_ping() callconv(.c) u32 {
    return 0x5743; // "WC"
}

/// 写一条规则。返回 0 成功，1 slot 越界，2 kind_mask 没有有效位，3 uid 非法（<10000 是系统 uid 段）
export fn wc_engine_rule_set(slot: u32, app_uid: u32, kind_mask: u8) callconv(.c) u8 {
    if (slot >= MAX_RULES) return 1;
    const mask = kind_mask & 0x07;
    if (mask == 0) return 2;
    // uid 0 = root，< 10000 = 系统/内核 uid。P0 明确拒绝把规则挂到系统进程上：
    // 一旦 system_server 自己也被判成「给空」，剪贴板服务就散了（自洽原则的反面）。
    if (app_uid < 10000) return 3;
    rules[slot].uid = app_uid;
    rules[slot].mask = mask | OCCUPIED;
    return 0;
}

export fn wc_engine_rule_clear() callconv(.c) void {
    for (&rules) |*r| {
        r.* = .{};
    }
}

/// 数一下当前有效规则条数，供审计/自检用（不做成「猜」，就是遍历，64 条开销可忽略）
export fn wc_engine_rule_count() callconv(.c) u32 {
    var n: u32 = 0;
    for (rules) |r| {
        if ((r.mask & OCCUPIED) != 0) n += 1;
    }
    return n;
}

/// 核心判定：这个 uid 的这类数据，要不要给空。
///
/// 语义细节（写进注释是因为 shim 侧和 Kotlin 侧都会照着理解）：
///   - 同一 uid 多条规则时取**并集**（后写的不会把先写的抹掉，避免配置下发顺序影响结果）；
///   - 未知 kind 返回 2，调用方按放行处理（fail-open，规范 L10）；
///   - 表空 → 全放行。
export fn wc_engine_decide(app_uid: u32, kind: u32) callconv(.c) u8 {
    if (kind >= KIND_MAX) return DECIDE_BAD_KIND;
    const bit = kindBit(@intCast(kind));
    if (bit == 0) return DECIDE_BAD_KIND;

    for (rules) |r| {
        if ((r.mask & OCCUPIED) == 0) continue;
        if (r.uid != app_uid) continue;
        if ((r.mask & bit) != 0) return DECIDE_BLANK;
    }
    return DECIDE_PASS;
}

// ---------------------------------------------------------------------------
// 单测：主机侧就能跑，不需要设备、不需要 root（CI 每一步都跑）
// ---------------------------------------------------------------------------

test "空表默认全放行：进程刚起、规则未下发时不许把界面搞坏" {
    wc_engine_rule_clear();
    try std.testing.expectEqual(DECIDE_PASS, wc_engine_decide(10123, KIND_CLIPBOARD));
    try std.testing.expectEqual(DECIDE_PASS, wc_engine_decide(10123, KIND_APP_LIST));
    try std.testing.expectEqual(@as(u32, 0), wc_engine_rule_count());
}

test "只拦配了的 kind，其余 kind 照旧放行（自洽：不搞连坐）" {
    wc_engine_rule_clear();
    try std.testing.expectEqual(@as(u8, 0), wc_engine_rule_set(0, 10200, 0b001));
    try std.testing.expectEqual(DECIDE_BLANK, wc_engine_decide(10200, KIND_CLIPBOARD));
    try std.testing.expectEqual(DECIDE_PASS, wc_engine_decide(10200, KIND_APP_LIST));
    try std.testing.expectEqual(DECIDE_PASS, wc_engine_decide(10201, KIND_CLIPBOARD));
    wc_engine_rule_clear();
}

test "同一 uid 多条规则取并集，与写入顺序无关" {
    wc_engine_rule_clear();
    _ = wc_engine_rule_set(0, 10300, 0b001);
    _ = wc_engine_rule_set(1, 10300, 0b010);
    try std.testing.expectEqual(DECIDE_BLANK, wc_engine_decide(10300, KIND_CLIPBOARD));
    try std.testing.expectEqual(DECIDE_BLANK, wc_engine_decide(10300, KIND_APP_LIST));
    wc_engine_rule_clear();

    _ = wc_engine_rule_set(1, 10300, 0b010);
    _ = wc_engine_rule_set(0, 10300, 0b001);
    try std.testing.expectEqual(DECIDE_BLANK, wc_engine_decide(10300, KIND_CLIPBOARD));
    try std.testing.expectEqual(DECIDE_BLANK, wc_engine_decide(10300, KIND_APP_LIST));
    wc_engine_rule_clear();
}

test "拒绝把规则挂到系统 uid：system_server 自己被判空就没人干活了" {
    wc_engine_rule_clear();
    try std.testing.expectEqual(@as(u8, 3), wc_engine_rule_set(0, 1000, 0b001));
    try std.testing.expectEqual(@as(u8, 3), wc_engine_rule_set(0, 0, 0b111));
    try std.testing.expectEqual(DECIDE_PASS, wc_engine_decide(1000, KIND_CLIPBOARD));
}

test "入参越界都返回错误码，不写入脏数据" {
    wc_engine_rule_clear();
    try std.testing.expectEqual(@as(u8, 1), wc_engine_rule_set(MAX_RULES, 10400, 0b001));
    try std.testing.expectEqual(@as(u8, 2), wc_engine_rule_set(0, 10400, 0));
    try std.testing.expectEqual(@as(u8, 2), wc_engine_rule_set(0, 10400, 0xF8)); // 只有高位，没有有效 kind
    try std.testing.expectEqual(@as(u32, 0), wc_engine_rule_count());
}

test "未知 kind 返回 2 而不是 BLANK（fail-open 的具体证据）" {
    wc_engine_rule_clear();
    _ = wc_engine_rule_set(0, 10500, 0b111);
    try std.testing.expectEqual(DECIDE_BAD_KIND, wc_engine_decide(10500, 9));
    try std.testing.expectEqual(DECIDE_BAD_KIND, wc_engine_decide(10500, KIND_MAX));
}

test "版本与 ping 是稳定常量，改了就说明协议变了" {
    try std.testing.expectEqual(@as(u32, 0x0002_0000), wc_engine_version());
    try std.testing.expectEqual(@as(u32, 0x5743), wc_engine_ping());
}
