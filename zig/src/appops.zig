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
