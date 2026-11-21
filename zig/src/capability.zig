//! 能力探测表的判定层（纯计算，不执行命令）。
//!
//! 输入是 Kotlin 侧跑完一轮探测后整理出来的**事实行**，每行五列、制表符分隔：
//!
//!     OP \t scope \t beforeId \t wantId \t afterId
//!
//! 输出是本机能力表 JSON：每条 op 在哪个作用域、写得进还是写不进、该走哪条通道。
//!
//! 为什么不在这里下结论式的「支持/不支持」：
//!   AppOps 的写入有三条互不相同的通道，判错的后果是界面上给了个假开关。
//!   - `appop`：普通 op（VIBRATE、WAKE_LOCK…），`appops set` 直接写存储。
//!   - `permission`：运行时权限型 op（CAMERA、RECORD_AUDIO…）。Android 15/16 起
//!     这些 op 的状态由权限系统持有，`appops set` 会被
//!     `com.android.server.permission.access.appop.AppOpService.setPackageMode`
//!     判为「should not be set directly」后**丢弃并返回 0**（2026-11-21 在
//!     Evolution X 11.8 / Android 16 上实测，日志见《安卓开发规范》W8.3）。
//!     这类只能走 `pm revoke/grant`。
//!   - `unknown`：本机既不认这条 op，也不认这次写入。

const std = @import("std");
const appops = @import("appops.zig");

pub const Verdict = enum {
    /// 写入后按作用域回读与期望一致
    writable,
    /// 写入后回读与写入前完全一样 —— 命令执行了，存储没变
    not_writable,
    /// 回读既不是期望值也不是原值（系统把它映射成了别的档，例如 foreground）
    partial,
};

pub const Channel = enum {
    appop,
    permission,
    unknown,
};

/// 一条探测结果
pub const Row = struct {
    op: []const u8,
    scope: appops.Scope,
    before: appops.Mode,
    want: appops.Mode,
    after: appops.Mode,

    pub fn verdict(self: Row) Verdict {
        if (self.after == self.want) return .writable;
        if (self.after == self.before) return .not_writable;
        return .partial;
    }

    pub fn channel(self: Row) Channel {
        if (!knownOp(self.op)) return .unknown;
        return if (isRuntimeOp(self.op)) .permission else .appop;
    }

    /// 给界面的一句话结论（英文键，文案在 Kotlin 层）
    pub fn advice(self: Row) []const u8 {
        return switch (self.verdict()) {
            .writable => "ok",
            .partial => "recheck",
            .not_writable => switch (self.channel()) {
                .permission => "use_permission_channel",
                .appop => "readonly",
                .unknown => "unsupported",
            },
        };
    }
};

/// 运行时权限型 op：`appops set` 在本机被丢弃，必须走 `pm revoke/grant`。
/// 这张表只用于**判定通道**，不代表本机支持这些 op（支持与否由探测结果说话）。
pub const runtime_ops = [_][]const u8{
    "CAMERA",
    "RECORD_AUDIO",
    "FINE_LOCATION",
    "COARSE_LOCATION",
    "READ_CONTACTS",
    "WRITE_CONTACTS",
    "READ_CALL_LOG",
    "WRITE_CALL_LOG",
    "READ_SMS",
    "SEND_SMS",
    "RECEIVE_SMS",
    "READ_CALENDAR",
    "WRITE_CALENDAR",
    "READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE",
    "READ_MEDIA_IMAGES",
    "READ_MEDIA_VIDEO",
    "READ_MEDIA_AUDIO",
    "READ_MEDIA_VISUAL_USER_SELECTED",
    "BODY_SENSORS",
    "ACTIVITY_RECOGNITION",
    "POST_NOTIFICATION",
    "ANSWER_PHONE_CALLS",
    "CALL_PHONE",
    "READ_PHONE_STATE",
    "READ_PHONE_NUMBERS",
    "ACCEPT_HANDOVER",
    "NEARBY_WIFI_DEVICES",
    "BLUETOOTH_CONNECT",
    "BLUETOOTH_SCAN",
    "UWB_RANGING",
};

pub fn isRuntimeOp(op: []const u8) bool {
    for (runtime_ops) |name| {
        if (std.mem.eql(u8, op, name)) return true;
    }
    return false;
}

/// 两种事实一起判：收录过（appops.known_ops）且通道已知，才算「认识这条 op」
fn knownOp(op: []const u8) bool {
    return appops.findOp(op) != null or isRuntimeOp(op);
}

pub const ParseResult = struct {
    rows: []Row,
    skipped: usize,

    pub fn deinit(self: *ParseResult, alloc: std.mem.Allocator) void {
        alloc.free(self.rows);
    }
};

/// 解析探测事实行。看不懂的行**计数**，不 panic、不猜（界面要显示「有 N 行没看懂」）。
pub fn parseProbe(alloc: std.mem.Allocator, input: []const u8) !ParseResult {
    var list: std.ArrayList(Row) = .empty;
    errdefer list.deinit(alloc);
    var skipped: usize = 0;

    var lines = std.mem.splitScalar(u8, input, '\n');
    while (lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0) continue;
        if (line[0] == '#') continue;

        var it = std.mem.splitScalar(u8, line, '\t');
        const op_raw = it.next() orelse {
            skipped += 1;
            continue;
        };
        const scope_raw = it.next() orelse {
            skipped += 1;
            continue;
        };
        const before_raw = it.next() orelse {
            skipped += 1;
            continue;
        };
        const want_raw = it.next() orelse {
            skipped += 1;
            continue;
        };
        const after_raw = it.next() orelse {
            skipped += 1;
            continue;
        };

        const op = std.mem.trim(u8, op_raw, " \t");
        if (!validOpName(op)) {
            skipped += 1;
            continue;
        }

        const scope_text = std.mem.trim(u8, scope_raw, " \t");
        const scope: appops.Scope = if (std.mem.eql(u8, scope_text, "uid"))
            .uid
        else if (std.mem.eql(u8, scope_text, "package"))
            .package
        else {
            skipped += 1;
            continue;
        };

        const before = parseInt(std.mem.trim(u8, before_raw, " \t")) orelse {
            skipped += 1;
            continue;
        };
        const want = parseInt(std.mem.trim(u8, want_raw, " \t")) orelse {
            skipped += 1;
            continue;
        };
        const after = parseInt(std.mem.trim(u8, after_raw, " \t")) orelse {
            skipped += 1;
            continue;
        };

        try list.append(alloc, .{ .op = op, .scope = scope, .before = before, .want = want, .after = after });
    }

    return .{ .rows = try list.toOwnedSlice(alloc), .skipped = skipped };
}

fn parseInt(s: []const u8) ?appops.Mode {
    const v = std.fmt.parseInt(i32, s, 10) catch return null;
    return appops.Mode.fromInt(v);
}

/// op 名只允许大写字母/数字/下划线：这张表会拿到界面与命令层，别让奇怪字符混进来
fn validOpName(op: []const u8) bool {
    if (op.len == 0 or op.len > 64) return false;
    for (op) |c| {
        const ok = (c >= 'A' and c <= 'Z') or (c >= '0' and c <= '9') or c == '_';
        if (!ok) return false;
    }
    return true;
}

/// 拼接一小段格式化文本后立刻释放临时串（0.17 的 ArrayList 没有 writer 成员）
fn appendFmt(alloc: std.mem.Allocator, out: *std.ArrayList(u8), comptime fmt: []const u8, args: anytype) !void {
    const s = try std.fmt.allocPrint(alloc, fmt, args);
    defer alloc.free(s);
    try out.appendSlice(alloc, s);
}

pub const Meta = struct {
    pkg: []const u8,
    stamp: []const u8,
};

/// 序列化成能力表 JSON（v1）。只含 ASCII 安全字符（op 名与时间串都是系统/本进程产物）。
pub fn toJson(alloc: std.mem.Allocator, result: ParseResult, meta: Meta) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    errdefer out.deinit(alloc);

    var n_writable: usize = 0;
    var n_not: usize = 0;
    var n_partial: usize = 0;
    var n_appop: usize = 0;
    var n_perm: usize = 0;
    var n_unknown: usize = 0;

    for (result.rows) |r| {
        switch (r.verdict()) {
            .writable => n_writable += 1,
            .not_writable => n_not += 1,
            .partial => n_partial += 1,
        }
        switch (r.channel()) {
            .appop => n_appop += 1,
            .permission => n_perm += 1,
            .unknown => n_unknown += 1,
        }
    }

    try appendFmt(alloc, &out, "{{\"v\":1,\"pkg\":\"{s}\",\"stamp\":\"{s}\"", .{ meta.pkg, meta.stamp });
    try appendFmt(alloc, &out, ",\"total\":{d},\"skipped\":{d}", .{ result.rows.len, result.skipped });
    try appendFmt(alloc, &out, ",\"writable\":{d},\"notWritable\":{d},\"partial\":{d}", .{ n_writable, n_not, n_partial });
    try appendFmt(alloc, &out, ",\"channel\":{{\"appop\":{d},\"permission\":{d},\"unknown\":{d}}}", .{ n_appop, n_perm, n_unknown });
    try out.appendSlice(alloc, ",\"rows\":[");
    for (result.rows, 0..) |r, i| {
        if (i != 0) try out.append(alloc, ',');
        try appendFmt(alloc, &out, "{{\"op\":\"{s}\",\"scope\":\"{s}\"", .{ r.op, @tagName(r.scope) });
        try appendFmt(alloc, &out, ",\"before\":\"{s}\",\"want\":\"{s}\",\"after\":\"{s}\"", .{
            r.before.cliToken(), r.want.cliToken(), r.after.cliToken(),
        });
        try appendFmt(alloc, &out, ",\"beforeId\":{d},\"afterId\":{d}", .{ @intFromEnum(r.before), @intFromEnum(r.after) });
        try appendFmt(alloc, &out, ",\"channel\":\"{s}\",\"verdict\":\"{s}\",\"advice\":\"{s}\"", .{
            @tagName(r.channel()), @tagName(r.verdict()), r.advice(),
        });
        try out.append(alloc, '}');
    }
    try out.appendSlice(alloc, "]}");
    return out.toOwnedSlice(alloc);
}

const testing = std.testing;

test "写进去读回来就是 writable" {
    var r = try parseProbe(testing.allocator, "VIBRATE\tpackage\t0\t2\t2");
    defer r.deinit(testing.allocator);
    try testing.expectEqual(@as(usize, 1), r.rows.len);
    try testing.expectEqual(Verdict.writable, r.rows[0].verdict());
    try testing.expectEqual(@as([]const u8, "ok"), r.rows[0].advice());
}

test "写进去读回来还是原值 = not_writable，且权限型 op 指向权限通道" {
    // 真机事实：CAMERA 包级 ignore 写入后仍读回 allow
    var r = try parseProbe(testing.allocator, "CAMERA\tpackage\t1\t2\t1\nVIBRATE\tpackage\t0\t2\t0");
    defer r.deinit(testing.allocator);
    try testing.expectEqual(Verdict.not_writable, r.rows[0].verdict());
    try testing.expectEqual(Channel.permission, r.rows[0].channel());
    try testing.expectEqualStrings("use_permission_channel", r.rows[0].advice());
    try testing.expectEqual(Verdict.not_writable, r.rows[1].verdict());
    try testing.expectEqual(Channel.appop, r.rows[1].channel());
    try testing.expectEqualStrings("readonly", r.rows[1].advice());
}

test "回读被系统映射成别的档 = partial" {
    var r = try parseProbe(testing.allocator, "LOCATION\tpackage\t0\t2\t4");
    defer r.deinit(testing.allocator);
    try testing.expectEqual(Verdict.partial, r.rows[0].verdict());
    try testing.expectEqual(@as([]const u8, "recheck"), r.rows[0].advice());
}

test "开探测时没有记录（before/after 都可能是 default）不影响判定" {
    var r = try parseProbe(testing.allocator, "WAKE_LOCK\tpackage\t0\t2\t2\r\n\r\n# 注释行\n");
    defer r.deinit(testing.allocator);
    try testing.expectEqual(@as(usize, 1), r.rows.len);
    try testing.expectEqual(@as(usize, 0), r.skipped);
    try testing.expectEqual(Verdict.writable, r.rows[0].verdict());
}

test "坏行计数而不是 panic：列数不够、模式越界、op 名非法、作用域非法" {
    const raw = "CAMERA\tpackage\t0\t2\n" ++
        "CAMERA\tpackage\t0\t2\t9\n" ++
        "CAMERA;rm -rf\tpackage\t0\t2\t2\n" ++
        "CAMERA\tuid-something\t0\t2\t2\n" ++
        "VIBRATE\tuid\t0\t2\t2\n";
    var r = try parseProbe(testing.allocator, raw);
    defer r.deinit(testing.allocator);
    try testing.expectEqual(@as(usize, 1), r.rows.len);
    try testing.expectEqual(@as(usize, 4), r.skipped);
    try testing.expectEqual(appops.Scope.uid, r.rows[0].scope);
}

test "JSON 汇总数字与通道统计对得上" {
    const raw = "CAMERA\tpackage\t1\t2\t1\n" ++
        "RECORD_AUDIO\tpackage\t1\t2\t1\n" ++
        "VIBRATE\tpackage\t0\t2\t2\n" ++
        "LOCATION\tpackage\t0\t2\t4\n" ++
        "MADE_UP\tpackage\t0\t2\t0\n";
    var r = try parseProbe(testing.allocator, raw);
    defer r.deinit(testing.allocator);

    const json = try toJson(testing.allocator, r, .{ .pkg = "com.luckyme.watchcat", .stamp = "2026-11-21T20:00:00+08:00" });
    defer testing.allocator.free(json);

    try testing.expect(std.mem.indexOf(u8, json, "\"total\":5") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"writable\":1") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"notWritable\":3") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"partial\":1") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"permission\":2") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"appop\":1") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"unknown\":2") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"scope\":\"package\"") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"advice\":\"use_permission_channel\"") != null);
}
