//! 快照：写任何 op 之前，先把当前全量状态固化成一段可解析、可校验的文本。
//!
//! 为什么要有它（《安卓开发规范》W9 安全边界）：
//!   `appops set` 改的是**别人手机上真实生效的权限**，写错不会有回滚按钮。
//!   所以规则是「没快照不许写」——快照既是回滚依据，也是事后审计的物证。
//!
//! 格式（v1，制表符分隔，行首 `#` 为头）：
//!
//!   # watchcat-snapshot v1
//!   # pkg=com.example.app
//!   # at=2026-11-21T20:05:11+08:00
//!   # ops=50 skipped=0
//!   # checksum=fnv1a64:8f2a1c0d3b4e5f60
//!   op<TAB>mode<TAB>scope<TAB>time<TAB>rejectTime<TAB>duration
//!   CAMERA<TAB>deny<TAB>package<TAB><TAB><TAB>
//!   WAKE_LOCK<TAB>allow<TAB>package<TAB>+3d0h8m56s467ms ago<TAB><TAB>+363ms
//!
//! 三条硬约定：
//!   1. 字段一律制表符分隔，**不用空格** —— 时间串里本来就有空格（`+3d… ago`）；
//!   2. 缺的字段留空列而不是省略列，列数恒定才好解析；
//!   3. 校验和只覆盖正文（不含头），FNV-1a 64 纯算术，不引加密库换体积。

const std = @import("std");
const appops = @import("appops.zig");

pub const FORMAT_VERSION = "v1";

/// 缺字段时的占位：空串，不是 "null"，避免把字面量 "null" 当成真值
const EMPTY: []const u8 = "";

/// FNV-1a 64。选它是因为：无需查表、无分支、几十字节代码，肉眼可复算。
pub fn fnv1a64(bytes: []const u8) u64 {
    var h: u64 = 0xcbf29ce484222325;
    for (bytes) |b| {
        h ^= @as(u64, b);
        h *%= 0x100000001b3;
    }
    return h;
}

fn appendFmt(alloc: std.mem.Allocator, out: *std.ArrayList(u8), comptime fmt: []const u8, args: anytype) !void {
    const s = try std.fmt.allocPrint(alloc, fmt, args);
    defer alloc.free(s);
    try out.appendSlice(alloc, s);
}

fn appendField(alloc: std.mem.Allocator, out: *std.ArrayList(u8), value: ?[]const u8) !void {
    try out.appendSlice(alloc, value orelse EMPTY);
    try out.append(alloc, '\t');
}

/// 生成快照全文。pkg 与 at（时间戳）由调用方给：本库不链 libc、取不到系统时间，
/// 时间必须由上层传入，顺带保证同一份输入能产出**逐字节相同**的快照。
pub fn toSnapshot(
    alloc: std.mem.Allocator,
    result: appops.ParseResult,
    pkg: []const u8,
    at: []const u8,
) ![]u8 {
    var body: std.ArrayList(u8) = .empty;
    // 正文拼完就整段拷进 out，此后 body 只是一段临时缓冲，正常返回时也要回收
    defer body.deinit(alloc);

    for (result.entries) |e| {
        try appendField(alloc, &body, e.op);
        try appendField(alloc, &body, e.mode.cliToken());
        try appendField(alloc, &body, @tagName(e.scope));
        try appendField(alloc, &body, e.time);
        try appendField(alloc, &body, e.reject_time);
        // 最后一列不带尾随制表符，行尾干净一点
        try body.appendSlice(alloc, e.duration orelse EMPTY);
        try body.append(alloc, '\n');
    }

    var out: std.ArrayList(u8) = .empty;
    errdefer out.deinit(alloc);

    try appendFmt(alloc, &out, "# watchcat-snapshot {s}\n", .{FORMAT_VERSION});
    try appendFmt(alloc, &out, "# pkg={s}\n", .{pkg});
    try appendFmt(alloc, &out, "# at={s}\n", .{at});
    try appendFmt(alloc, &out, "# ops={d} skipped={d}\n", .{ result.entries.len, result.skipped });
    try appendFmt(alloc, &out, "# checksum=fnv1a64:{x:0>16}\n", .{fnv1a64(body.items)});

    try out.appendSlice(alloc, body.items);
    return out.toOwnedSlice(alloc);
}

// ---------------------------------------------------------------------------
// 单测：主机侧跑，不依赖授权、不依赖设备
// ---------------------------------------------------------------------------

const testing = std.testing;

test "快照头齐全，且列数恒定" {
    const raw =
        \\Uid mode: COARSE_LOCATION: ignore
        \\WAKE_LOCK: allow; time=+3d0h8m56s467ms ago; duration=+363ms
    ;
    var r = try appops.parseGet(testing.allocator, raw);
    defer r.deinit(testing.allocator);

    const snap = try toSnapshot(testing.allocator, r, "com.example.app", "2026-11-21T20:05:11+08:00");
    defer testing.allocator.free(snap);

    try testing.expect(std.mem.startsWith(u8, snap, "# watchcat-snapshot v1\n"));
    try testing.expect(std.mem.indexOf(u8, snap, "# pkg=com.example.app\n") != null);
    try testing.expect(std.mem.indexOf(u8, snap, "# at=2026-11-21T20:05:11+08:00\n") != null);
    try testing.expect(std.mem.indexOf(u8, snap, "# ops=2 skipped=0\n") != null);

    // 正文两行，每行都必须是 6 列
    var lines = std.mem.splitScalar(u8, snap, '\n');
    var checked: usize = 0;
    while (lines.next()) |line| {
        if (line.len == 0 or line[0] == '#') continue;
        var cols = std.mem.splitScalar(u8, line, '\t');
        var n: usize = 0;
        while (cols.next()) |_| n += 1;
        try testing.expectEqual(@as(usize, 6), n);
        checked += 1;
    }
    try testing.expectEqual(@as(usize, 2), checked);
}

test "缺字段留空列，不省略列" {
    const raw = "CAMERA: deny\n";
    var r = try appops.parseGet(testing.allocator, raw);
    defer r.deinit(testing.allocator);

    const snap = try toSnapshot(testing.allocator, r, "p", "t");
    defer testing.allocator.free(snap);

    // 期望正文就是「CAMERA deny package + 三个空列」
    try testing.expect(std.mem.indexOf(u8, snap, "\nCAMERA\tdeny\tpackage\t\t\t\n") != null);
}

test "校验和随正文变化而变化" {
    const a =
        \\CAMERA: allow
    ;
    const b =
        \\CAMERA: deny
    ;
    var ra = try appops.parseGet(testing.allocator, a);
    defer ra.deinit(testing.allocator);
    var rb = try appops.parseGet(testing.allocator, b);
    defer rb.deinit(testing.allocator);

    const sa = try toSnapshot(testing.allocator, ra, "p", "t");
    defer testing.allocator.free(sa);
    const sb = try toSnapshot(testing.allocator, rb, "p", "t");
    defer testing.allocator.free(sb);

    const hunk = "# checksum=fnv1a64:";
    const ia = (std.mem.indexOf(u8, sa, hunk).? + hunk.len);
    const ib = (std.mem.indexOf(u8, sb, hunk).? + hunk.len);
    try testing.expect(!std.mem.eql(u8, sa[ia .. ia + 16], sb[ib .. ib + 16]));
}

test "真机样本的快照能完整落盘" {
    const raw = @embedFile("samples/api36_adguard.txt");
    var r = try appops.parseGet(testing.allocator, raw);
    defer r.deinit(testing.allocator);

    const snap = try toSnapshot(testing.allocator, r, "com.adguard.android", "2026-11-21T20:10:00+08:00");
    defer testing.allocator.free(snap);

    try testing.expectEqual(@as(usize, 0), r.skipped);
    // 快照行数 = 头 5 行 + 条目 n 行（尾部换行不额外算）
    var n: usize = 0;
    var lines = std.mem.splitScalar(u8, snap, '\n');
    while (lines.next()) |line| {
        if (line.len > 0) n += 1;
    }
    try testing.expectEqual(r.entries.len + 5, n);
}
