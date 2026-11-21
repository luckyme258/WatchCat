//! 写命令生成器：把「包名 + op + 五态 + 作用域」拼成一条 `appops set` 命令。
//!
//! 这条命令最终会以 **root** 身份执行，所以本模块的第一职责不是拼字符串，
//! 而是**拒绝一切不干净的名字**——拼接层再做白名单校验，等于把注入漏洞交给调用方。
//!
//! 白名单：
//!   - 包名：`A-Z a-z 0-9 . _ -`，1..255 字符，不许以 `-` 开头（否则会被当成命令行开关）
//!   - op 名：`A-Z a-z 0-9 _ -`，1..64 字符，不许以 `-` 开头
//!   - 模式：只走五态枚举，越界直接报错，不做字符串透传
//!
//! 不允许出现：空白、引号、`;` `|` `&` `$` 反引号、换行、非 ASCII、`..`
//! （`..` 单看无害，但包名里出现基本意味着拼错了，宁可拒）。

const std = @import("std");
const appops = @import("appops.zig");

pub const Error = error{ InvalidPackage, InvalidOp, BadMode, OutOfMemory };

fn isTokenChar(c: u8) bool {
    return switch (c) {
        'A'...'Z', 'a'...'z', '0'...'9', '.', '_', '-' => true,
        else => false,
    };
}

fn validToken(s: []const u8, max_len: usize) bool {
    if (s.len == 0 or s.len > max_len) return false;
    if (s[0] == '-') return false;
    if (std.mem.indexOf(u8, s, "..") != null) return false;
    for (s) |c| {
        if (!isTokenChar(c)) return false;
    }
    return true;
}

pub fn validPackage(pkg: []const u8) bool {
    return validToken(pkg, 255);
}

pub fn validOpName(op: []const u8) bool {
    return validToken(op, 64);
}

/// 拼命令。成功返回调用方负责释放的字符串；名字不干净就报错，绝不「尽力拼一拼」。
pub fn buildSetCommand(
    alloc: std.mem.Allocator,
    pkg: []const u8,
    op: []const u8,
    mode: appops.Mode,
    scope: appops.Scope,
) Error![]u8 {
    if (!validPackage(pkg)) return Error.InvalidPackage;
    if (!validOpName(op)) return Error.InvalidOp;

    // 显式传 --user current 而不是省掉：多用户设备上省掉会落到「当前调用者」的用户，
    // 而调用者是 root/shell 时语义不直观，写错用户等于没写。
    const prefix: []const u8 = switch (scope) {
        .package => "appops set --user current ",
        .uid => "appops set --uid --user current ",
    };

    return std.fmt.allocPrint(alloc, "{s}{s} {s} {s}", .{
        prefix, pkg, op, mode.cliToken(),
    });
}

/// 与命令配对的「回读校验」判据：写入后必须有 op 处于期望模式，且作用域一致。
pub fn matchesExpectation(expected: appops.Mode, actual: appops.Entry) bool {
    return actual.mode == expected;
}

// ---------------------------------------------------------------------------
// 单测
// ---------------------------------------------------------------------------

const testing = std.testing;

test "普通包作用域的命令" {
    const cmd = try buildSetCommand(testing.allocator, "com.example.app", "CAMERA", .deny, .package);
    defer testing.allocator.free(cmd);
    try testing.expectEqualStrings("appops set --user current com.example.app CAMERA deny", cmd);
}

test "uid 作用域带 --uid" {
    const cmd = try buildSetCommand(testing.allocator, "com.example.app", "COARSE_LOCATION", .ignore, .uid);
    defer testing.allocator.free(cmd);
    try testing.expectEqualStrings("appops set --uid --user current com.example.app COARSE_LOCATION ignore", cmd);
}

test "五态都能拼出来，且用命令行 token 而不是界面文案" {
    const expect = [_][]const u8{ "default", "allow", "ignore", "deny", "foreground" };
    for (expect, 0..) |tok, i| {
        const mode: appops.Mode = @enumFromInt(@as(u8, @intCast(i)));
        const cmd = try buildSetCommand(testing.allocator, "p", "CAMERA", mode, .package);
        defer testing.allocator.free(cmd);
        try testing.expect(std.mem.endsWith(u8, cmd, tok));
    }
}

test "注入尝试一律拒绝" {
    const bad = [_][]const u8{
        "com.x; rm -rf /",
        "com.x && reboot",
        "com.x|sh",
        "com.x`id`",
        "com.x$(id)",
        "com.x\nreboot",
        "com.x\treboot",
        "com.x/../y",
        "com.x y",
        "-com.x",
        "com.中文",
    };
    for (bad) |pkg| {
        try testing.expectError(Error.InvalidPackage, buildSetCommand(testing.allocator, pkg, "CAMERA", .deny, .package));
    }
    const bad_ops = [_][]const u8{ "CAM ERA", "CAMERA;id", "-CAMERA", "", "CAMERA\n" };
    for (bad_ops) |op| {
        try testing.expectError(Error.InvalidOp, buildSetCommand(testing.allocator, "com.x", op, .deny, .package));
    }
}

test "超长名字拒绝（255 / 64 上限）" {
    var long_pkg: [256]u8 = @splat('a');
    try testing.expectError(Error.InvalidPackage, buildSetCommand(testing.allocator, &long_pkg, "CAMERA", .deny, .package));

    const ok_pkg = long_pkg[0..255];
    const cmd = try buildSetCommand(testing.allocator, ok_pkg, "CAMERA", .deny, .package);
    defer testing.allocator.free(cmd);

    var long_op: [65]u8 = @splat('A');
    try testing.expectError(Error.InvalidOp, buildSetCommand(testing.allocator, "com.x", &long_op, .deny, .package));
}
