//! AppOps 的数据模型：五态 + 常用 op 名表。
//!
//! 纪律（《安卓开发规范》7.1 / 7.3）：
//!   - 五态的语义是硬约定，`ignore`（空头支票）与 `deny`（抛异常）不能混为一谈；
//!   - 这里的 op 名表只是**候选清单**，真正支持哪些、能不能写、支持哪几档，
//!     一律以开机后的能力探测结果为准，不许把这张表当成结论。

const std = @import("std");

/// 五态，数值与内部枚举顺序对齐，便于从 jint 直接转换
pub const Mode = enum(u8) {
    default = 0,
    allow = 1,
    ignore = 2,
    deny = 3,
    foreground = 4,

    /// 传给 `appops set` 的命令行 token
    pub fn cliToken(self: Mode) [:0]const u8 {
        return switch (self) {
            .default => "default",
            .allow => "allow",
            .ignore => "ignore",
            .deny => "deny",
            .foreground => "foreground",
        };
    }

    /// 界面上给人看的名字（与 cliToken 分开，避免把命令词直接甩给用户）
    pub fn label(self: Mode) [:0]const u8 {
        return switch (self) {
            .default => "默认",
            .allow => "允许",
            .ignore => "忽略（给空数据）",
            .deny => "拒绝（抛异常）",
            .foreground => "仅前台允许",
        };
    }

    /// 从 JNI 传来的整数转换，越界返回 null（不要 panic 到 JVM 里）
    pub fn fromInt(v: i32) ?Mode {
        if (v < 0 or v > 4) return null;
        return @enumFromInt(@as(u8, @intCast(v)));
    }

    /// 从 CLI 输出里解析（`appops get` 每行形如 `CAMERA: ignore; time=...`）
    pub fn parse(s: []const u8) ?Mode {
        const t = std.mem.trim(u8, s, " \t\r\n");
        if (std.mem.eql(u8, t, "allow") or std.mem.eql(u8, t, "allowed")) return .allow;
        if (std.mem.eql(u8, t, "ignore") or std.mem.eql(u8, t, "ignored")) return .ignore;
        if (std.mem.eql(u8, t, "deny") or std.mem.eql(u8, t, "errored")) return .deny;
        if (std.mem.eql(u8, t, "default")) return .default;
        if (std.mem.eql(u8, t, "foreground")) return .foreground;
        return null;
    }
};

/// 一条 op 的静态描述；运行期能力（supported / writable / modes）由探测填充
pub const OpSpec = struct {
    cli: []const u8,
    label: []const u8,
    sensitive: bool,
};

/// 候选 op 表。按「最容易出事、最值得管」排序，不是全集。
/// 注意：`POST_NOTIFICATION` 自 13 起才有、存储类在 13 起被拆成 READ_MEDIA_*，
/// 这些版本差异靠探测兜底，不要在这里写死结论。
pub const known_ops = [_]OpSpec{
    .{ .cli = "CAMERA", .label = "相机", .sensitive = true },
    .{ .cli = "RECORD_AUDIO", .label = "麦克风", .sensitive = true },
    .{ .cli = "FINE_LOCATION", .label = "精确定位", .sensitive = true },
    .{ .cli = "COARSE_LOCATION", .label = "粗略定位", .sensitive = true },
    .{ .cli = "READ_CONTACTS", .label = "读通讯录", .sensitive = true },
    .{ .cli = "WRITE_CONTACTS", .label = "写通讯录", .sensitive = true },
    .{ .cli = "READ_CALL_LOG", .label = "读通话记录", .sensitive = true },
    .{ .cli = "READ_SMS", .label = "读短信", .sensitive = true },
    .{ .cli = "SEND_SMS", .label = "发短信", .sensitive = true },
    .{ .cli = "READ_CALENDAR", .label = "读日历", .sensitive = true },
    .{ .cli = "READ_EXTERNAL_STORAGE", .label = "读存储（11–12）", .sensitive = true },
    .{ .cli = "READ_MEDIA_IMAGES", .label = "读图片（13+）", .sensitive = true },
    .{ .cli = "POST_NOTIFICATION", .label = "发通知（13+）", .sensitive = false },
    .{ .cli = "WAKE_LOCK", .label = "保持唤醒", .sensitive = false },
    .{ .cli = "VIBRATE", .label = "振动", .sensitive = false },
};

/// 按命令行名查表，找不到返回 null（调用方应回落到「未收录」而不是报错）
pub fn findOp(cli: []const u8) ?OpSpec {
    for (known_ops) |op| {
        if (std.mem.eql(u8, op.cli, cli)) return op;
    }
    return null;
}

// ---------------------------------------------------------------------------
// `appops get <pkg>` 输出解析
//
// 格式以小米 9 / API 36 **实测**为准（2026-11-21 抓的原样样本），三种字段都可能缺：
//
//   Uid mode: COARSE_LOCATION: ignore
//   WAKE_LOCK: allow; time=+3d0h8m56s467ms ago; duration=+363ms
//   SYSTEM_ALERT_WINDOW: default; rejectTime=+3d16h21m39s105ms ago
//   ACTIVATE_VPN: allow; time=+10d21h10m4s888ms ago; rejectTime=+16d0h47m57s11ms ago
//
// 纪律：解析不出来就**计数**，不 panic、不猜；界面要能把「有 N 行没看懂」显示出来，
// 否则解析器悄悄丢数据，比解析失败更危险。
// ---------------------------------------------------------------------------

/// 条目作用域：`Uid mode:` 段里的条目按 uid 存储（多包同 uid 会互相影响）
pub const Scope = enum { package, uid };

/// 一条 op 的运行期状态（字段值原样保留，不解析时间语义 —— 时间只用于展示）
pub const Entry = struct {
    op: []const u8,
    mode: Mode,
    scope: Scope = .package,
    time: ?[]const u8 = null,
    reject_time: ?[]const u8 = null,
    duration: ?[]const u8 = null,
};

pub const ParseResult = struct {
    entries: []Entry,
    /// 无法识别的行数（既不是空行、也解析不出 op+模式）
    skipped: usize,

    pub fn deinit(self: *ParseResult, alloc: std.mem.Allocator) void {
        alloc.free(self.entries);
    }
};

/// 解析 `appops get` 的多行输出。内存由调用方 allocator 负责，用完 deinit。
///
/// **`uid_line_count` 是必须由调用方提供的事实，不是可选项**：
/// AOSP 的 `appops get` 会把 uid 段（PackageOps 的 packageName==null）排在包段之前，
/// 且只在 uid 段**开头打印一次** `Uid mode: `（见 AppOpsService.java 的 get 分支，
/// 2026-11-21 核对源码与真机）。也就是说这段文本里**没有任何行级标记**能区分两段，
/// 解析器若自己猜边界，只会把 uid 的条目错标成包作用域——写操作就会写到错误的那份存储。
/// 所以：调用方先跑 `appops get --uid <uid>` 数出 uid 段行数，再把数字传进来。
pub fn parseGet(alloc: std.mem.Allocator, input: []const u8, uid_line_count: usize) !ParseResult {
    var list: std.ArrayList(Entry) = .empty;
    errdefer list.deinit(alloc);
    var skipped: usize = 0;
    // 已经遇到的非空行序号（与 uid_line_count 比较决定作用域）
    var line_index: usize = 0;

    var lines = std.mem.splitScalar(u8, input, '\n');
    while (lines.next()) |raw_line| {
        var line = std.mem.trim(u8, raw_line, " \t\r");
        if (line.len == 0) continue;

        var scope: Scope = if (line_index < uid_line_count) .uid else .package;
        if (std.mem.startsWith(u8, line, "Uid mode:")) {
            // 前缀只出现在 uid 段第一行；剥掉后这一行本身仍是一个条目
            scope = .uid;
            line = std.mem.trim(u8, line["Uid mode:".len..], " \t");
            if (line.len == 0) continue; // 只有段头、没有条目
        }
        line_index += 1;

        // op 名里不会出现冒号，所以「第一个 ": " 」就是 op 与模式的边界
        const colon = std.mem.indexOf(u8, line, ": ") orelse {
            skipped += 1;
            continue;
        };
        const op = std.mem.trim(u8, line[0..colon], " \t");
        const rest = std.mem.trim(u8, line[colon + 2 ..], " \t");
        if (op.len == 0 or rest.len == 0) {
            skipped += 1;
            continue;
        }

        var entry = Entry{ .op = op, .mode = .default, .scope = scope };
        var mode_ok = false;
        var fields = std.mem.splitScalar(u8, rest, ';');
        var is_first = true;
        while (fields.next()) |field_raw| {
            const field = std.mem.trim(u8, field_raw, " \t\r");
            if (field.len == 0) continue;

            if (is_first) {
                is_first = false;
                if (Mode.parse(field)) |m| {
                    entry.mode = m;
                    mode_ok = true;
                } else {
                    break; // 模式看不懂，整行作废，交给 skipped 记账
                }
                continue;
            }

            const eq = std.mem.indexOfScalar(u8, field, '=') orelse continue;
            const key = std.mem.trim(u8, field[0..eq], " \t");
            const val = std.mem.trim(u8, field[eq + 1 ..], " \t");
            if (std.mem.eql(u8, key, "time")) {
                entry.time = val;
            } else if (std.mem.eql(u8, key, "rejectTime")) {
                entry.reject_time = val;
            } else if (std.mem.eql(u8, key, "duration")) {
                entry.duration = val;
            }
            // 未知键直接忽略：系统后续版本可能加字段，不能因此判定整行失败
        }

        if (!mode_ok) {
            skipped += 1;
            continue;
        }
        try list.append(alloc, entry);
    }

    return .{ .entries = try list.toOwnedSlice(alloc), .skipped = skipped };
}

/// 拼接一小段格式化文本后立刻释放临时串（ArrayList 在 0.17 没有 writer 成员）
fn appendFmt(alloc: std.mem.Allocator, out: *std.ArrayList(u8), comptime fmt: []const u8, args: anytype) !void {
    const s = try std.fmt.allocPrint(alloc, fmt, args);
    defer alloc.free(s);
    try out.appendSlice(alloc, s);
}

/// 把解析结果序列化成紧凑 JSON，供 JNI 侧直接交给 Kotlin 解析。
/// 只含 ASCII 安全字符（op 名与时间串都是系统产物），因此不做转义。
pub fn toJson(alloc: std.mem.Allocator, result: ParseResult) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    errdefer out.deinit(alloc);

    try appendFmt(alloc, &out, "{{\"count\":{d},\"skipped\":{d},\"ops\":[", .{ result.entries.len, result.skipped });
    for (result.entries, 0..) |e, i| {
        if (i != 0) try out.append(alloc, ',');
        try appendFmt(alloc, &out, "{{\"op\":\"{s}\",\"mode\":\"{s}\",\"modeId\":{d},\"scope\":\"{s}\"", .{
            e.op, e.mode.cliToken(), @intFromEnum(e.mode), @tagName(e.scope),
        });
        if (e.time) |t| try appendFmt(alloc, &out, ",\"time\":\"{s}\"", .{t});
        if (e.reject_time) |t| try appendFmt(alloc, &out, ",\"rejectTime\":\"{s}\"", .{t});
        if (e.duration) |t| try appendFmt(alloc, &out, ",\"duration\":\"{s}\"", .{t});
        try out.append(alloc, '}');
    }
    try out.appendSlice(alloc, "]}");
    return out.toOwnedSlice(alloc);
}

const testing = std.testing;

test "解析普通条目与 Uid mode 单行段头" {
    // 样本取自小米 9 / API 36 的 `appops get`，包名已替换为中性值
    const raw =
        \\Uid mode: COARSE_LOCATION: ignore
        \\FINE_LOCATION: ignore
        \\POST_NOTIFICATION: ignore
        \\READ_SMS: ignore
    ;
    var r = try parseGet(testing.allocator, raw, 1);
    defer r.deinit(testing.allocator);

    try testing.expectEqual(@as(usize, 4), r.entries.len);
    try testing.expectEqual(@as(usize, 0), r.skipped);

    // 第一行既带段头又是首个条目：必须被识别为 uid 作用域
    try testing.expectEqualStrings("COARSE_LOCATION", r.entries[0].op);
    try testing.expectEqual(Mode.ignore, r.entries[0].mode);
    try testing.expectEqual(Scope.uid, r.entries[0].scope);
    // 后续行回到 package 作用域
    try testing.expectEqual(Scope.package, r.entries[1].scope);
    try testing.expectEqualStrings("FINE_LOCATION", r.entries[1].op);
}

test "解析 time / rejectTime / duration 三种字段" {
    const raw =
        \\WAKE_LOCK: allow; time=+3d0h8m56s467ms ago; duration=+363ms
        \\SYSTEM_ALERT_WINDOW: default; rejectTime=+3d16h21m39s105ms ago
        \\ACTIVATE_VPN: allow; time=+10d21h10m4s888ms ago; rejectTime=+16d0h47m57s11ms ago
        \\CONTROL_AUDIO: foreground; time=+3d0h8m20s371ms ago; duration=+18s730ms
    ;
    var r = try parseGet(testing.allocator, raw, 0);
    defer r.deinit(testing.allocator);

    try testing.expectEqual(@as(usize, 4), r.entries.len);
    try testing.expectEqualStrings("+3d0h8m56s467ms ago", r.entries[0].time.?);
    try testing.expectEqualStrings("+363ms", r.entries[0].duration.?);
    try testing.expectEqual(@as(?[]const u8, null), r.entries[0].reject_time);

    try testing.expectEqual(Mode.default, r.entries[1].mode);
    try testing.expectEqualStrings("+3d16h21m39s105ms ago", r.entries[1].reject_time.?);
    try testing.expectEqual(@as(?[]const u8, null), r.entries[1].time);

    try testing.expectEqual(Mode.allow, r.entries[2].mode);
    try testing.expect(r.entries[2].time != null and r.entries[2].reject_time != null);

    try testing.expectEqual(Mode.foreground, r.entries[3].mode);
    try testing.expectEqualStrings("+18s730ms", r.entries[3].duration.?);
}

test "看不懂的行计数而不是丢弃或 panic" {
    const raw =
        \\CAMERA: ignore
        \\这一段不是 op 行
        \\MYSTERY_OP: weirdmode; time=+1s ago
        \\WAKE_LOCK: allow
    ;
    var r = try parseGet(testing.allocator, raw, 0);
    defer r.deinit(testing.allocator);

    try testing.expectEqual(@as(usize, 2), r.entries.len);
    try testing.expectEqual(@as(usize, 2), r.skipped); // 非 op 行 + 未知模式各记一条
    try testing.expectEqualStrings("CAMERA", r.entries[0].op);
    try testing.expectEqualStrings("WAKE_LOCK", r.entries[1].op);
}

test "CRLF、空行、行尾空格都不影响解析" {
    const raw = "CAMERA: ignore\r\n\r\n  WAKE_LOCK: allow  \r\n";
    var r = try parseGet(testing.allocator, raw, 0);
    defer r.deinit(testing.allocator);

    try testing.expectEqual(@as(usize, 2), r.entries.len);
    try testing.expectEqual(@as(usize, 0), r.skipped);
    try testing.expectEqualStrings("WAKE_LOCK", r.entries[1].op);
    try testing.expectEqual(Mode.allow, r.entries[1].mode);
}

test "序列化成 JSON 且字段完整" {
    const raw = "CAMERA: ignore; time=+2s ago\nWAKE_LOCK: allow; duration=+363ms";
    var r = try parseGet(testing.allocator, raw, 0);
    defer r.deinit(testing.allocator);

    const json = try toJson(testing.allocator, r);
    defer testing.allocator.free(json);

    try testing.expect(std.mem.indexOf(u8, json, "\"count\":2") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"op\":\"CAMERA\",\"mode\":\"ignore\",\"modeId\":2") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"time\":\"+2s ago\"") != null);
    try testing.expect(std.mem.indexOf(u8, json, "\"duration\":\"+363ms\"") != null);
}

// ---------------------------------------------------------------------------
// 真机样本回归：`samples/` 里是小米 9 / API 36 上 `appops get` 的**原样输出**，
// 只保留了 op 行（输出本身不含包名），可以直接入库。
// 判据：解析出的条目数 + 看不懂的行数 == 文件里的非空行数，且看不懂的行数为 0。
// 任何一次格式变化（新系统、新 op、新字段）都会在这里炸出来。
// ---------------------------------------------------------------------------

const SampleCase = struct {
    file: []const u8,
    raw: []const u8,
    non_empty_lines: usize,
};

const samples = [_]SampleCase{
    .{
        .file = "api36_adguard.txt",
        .raw = @embedFile("samples/api36_adguard.txt"),
        .non_empty_lines = 50,
    },
    .{
        .file = "api36_twitch.txt",
        .raw = @embedFile("samples/api36_twitch.txt"),
        .non_empty_lines = 47,
    },
    .{
        .file = "api36_permissioncontroller.txt",
        .raw = @embedFile("samples/api36_permissioncontroller.txt"),
        .non_empty_lines = 43,
    },
};

test "真机样本全量解析，一行都不许丢" {
    for (samples) |case| {
        var r = try parseGet(testing.allocator, case.raw, 0);
        defer r.deinit(testing.allocator);

        try testing.expectEqual(@as(usize, 0), r.skipped);
        try testing.expectEqual(case.non_empty_lines, r.entries.len);
        // 每条都必须带得出 op 名与模式
        for (r.entries) |e| {
            try testing.expect(e.op.len > 0);
        }
    }
}

test "真机样本解析出的模式都在五态之内" {
    for (samples) |case| {
        var r = try parseGet(testing.allocator, case.raw, 0);
        defer r.deinit(testing.allocator);

        for (r.entries) |e| {
            // Mode 是枚举，能取到值就说明落在五态内；这里额外确认没有靠 default 兜底
            const in_five = switch (e.mode) {
                .default, .allow, .ignore, .deny, .foreground => true,
            };
            try testing.expect(in_five);
        }
    }
}

test "作用域按 uid 段行数划分，边界不多不少" {
    // 真机原样：3 条 uid 段条目 + 2 条包段条目（前缀只出现在第一行）
    const raw =
        \\Uid mode: CAMERA: ignore
        \\RECORD_AUDIO: ignore
        \\VIBRATE: deny
        \\CAMERA: allow
        \\ACCESS_RESTRICTED_SETTINGS: default
    ;
    var r = try parseGet(testing.allocator, raw, 3);
    defer r.deinit(testing.allocator);
    try testing.expectEqual(@as(usize, 5), r.entries.len);
    try testing.expectEqual(@as(usize, 0), r.skipped);
    // 前 3 条 uid
    try testing.expectEqual(Scope.uid, r.entries[0].scope);
    try testing.expectEqual(Scope.uid, r.entries[1].scope);
    try testing.expectEqual(Scope.uid, r.entries[2].scope);
    // 其余是包作用域——这才是「写哪一份存储」的依据
    try testing.expectEqual(Scope.package, r.entries[3].scope);
    try testing.expectEqual(Scope.package, r.entries[4].scope);
    // 同名 op 在两段里可以同时出现，模式不同，绝不能合并
    try testing.expectEqualStrings("CAMERA", r.entries[0].op);
    try testing.expectEqual(Mode.ignore, r.entries[0].mode);
    try testing.expectEqualStrings("CAMERA", r.entries[3].op);
    try testing.expectEqual(Mode.allow, r.entries[3].mode);
}

test "真机两段样本：uid 45 条 + 包 3 条" {
    const uid_raw = @embedFile("samples/api36_self_uid.txt");
    const all_raw = @embedFile("samples/api36_self_all.txt");

    var u = try parseGet(testing.allocator, uid_raw, 0);
    defer u.deinit(testing.allocator);
    try testing.expectEqual(@as(usize, 45), u.entries.len);
    try testing.expectEqual(@as(usize, 0), u.skipped);

    var a = try parseGet(testing.allocator, all_raw, 45);
    defer a.deinit(testing.allocator);
    try testing.expectEqual(@as(usize, 48), a.entries.len);
    try testing.expectEqual(@as(usize, 0), a.skipped);

    // 边界：前 45 必须 uid，后 3 必须包
    for (a.entries, 0..) |e, i| {
        const expect_uid = i < 45;
        try testing.expectEqual(if (expect_uid) Scope.uid else Scope.package, e.scope);
    }
    // 包段最后三条就是 VIBRATE / CAMERA / ACCESS_RESTRICTED_SETTINGS
    try testing.expectEqualStrings("ACCESS_RESTRICTED_SETTINGS", a.entries[47].op);
}

