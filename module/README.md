# WatchCat 数据面模块（P0）

**装上去不会改变任何行为**——一个 hook 都不注册。唯一目的是证明「Zig 引擎在 system_server 里能被调用」，
证据是**落盘文件**，不是日志。

## 怎么验证

```bash
# 先确认已经整机重启过一次（模块在 zygote fork 之后才加载）
adb shell su -c 'cat /data/system/watchcat.p0.probe'
adb shell su -c 'cat /data/adb/watchcat/boot.marker'
```

应该看到：

```
stage=postServerSpecialize pid=1996 ping=0x5743 version=0x00020000
```

`ping=0x5743` 是验收点：不是这个值，就说明链进来的不是我们那份引擎（或引擎符号被改名/裁掉了）。

**不要用 logcat 找这三行。** system_server 里的 `android.util.Log` 进不了 `logcat -b all`，
框架自己的 `log()` 只写 `/data/adb/lspd/log`——三条通道全黑，本机已实测过，规范 L15.1 已把
logcat 与 `/proc/<pid>/maps` 都列为**禁止判据**。

**普通应用进程一行日志都没有**，这是刻意的：`preAppSpecialize` 直接 return，连文件都不开。

## 文件与目录

| 路径 | 说明 |
|---|---|
| `/data/system/watchcat.p0.probe` | **主证据**。`0600 system:system`，system_server 无条件写，不受 `armed` 影响 |
| `/data/adb/watchcat/` | `0700 root:root`，**只有 root 上下文的脚本能写**；system_server（uid 1000）过不了 DAC，不能拿它当 system_server 的落点 |
| `boot.marker` / `service.marker` | 证明模块脚本真跑了（含 fingerprint / sdk / uptime / fail） |
| `fail.count` / `last.boot` | 跨重启的心跳计数与上一轮 boot_id（L10.3 的判据，**不许每次开机清零**） |
| `armed` | **不存在就是不启用**。P0 不写它，所以永远是「不启用」状态 |
| `p0.probe` | 回退副本：`/data/system` 写失败时才写这里；同样**无条件**写，与 `armed` 无关 |

`/data/system` 下还有两个由模块运行时写出来的东西：

| 路径 | 说明 |
|---|---|
| `watchcat.heartbeat` | system_server 里的心跳（带 `bootId`）。root 脚本在**下一次开机**拿它结算「上一轮有没有活」 |
| `watchcat.tripped.marker` | 连续两轮没心跳时由脚本写的停用记录（`fail=2`），诊断页要读它 |

`/data/adb/watchcat/p0.probe` 写不出来不算缺陷：它只是 `/data/system` 的回退位，不是通道。
真正的通道是 remote preferences（框架托管，配置属于模块包），**策略与规则一律不放 `/data/adb`**（规范 L17）。

## 许可证（这一段必须留着）

`module/src/zygisk/zygisk.hpp` 来自 `topjohnwu/zygisk-module-sample`（即 Magisk 官方发布的模块 API 头）。
它**不是 GPL**，文件头部自带 0BSD 风格的许可：

> Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted.

Magisk 整个仓库是 GPL-3.0，但这个公开 API 头是单独授权的。所以把它原样放进仓库是允许的，
前提是不改动文件内容、保留版权与许可声明（就在这里）。**不要改这个头文件**，要兼容新版本就整份替换。

## 构建

```bash
py tools/build_so.py --engine            # Zig 引擎静态库（build/engine/<abi>/）
py tools/build_module_native.py          # NDK 编 shim（build/native/zygisk/<abi>.so，含 16KB 门禁）
py tools/build_module.py --require-zygisk # 打 zip，自动把 shim 并成 zygisk/<abi>.so
```

包内**只有** `module.prop`、三个脚本、本 README 和 `zygisk/arm64-v8a.so`（白名单，`tools/build_module.py`
的 `PACK_FILES`）。`module/src/` 的源码与 `zygisk.hpp` 不进包；`x86_64.so` 只在 `--with-x86_64`
时带上，供模拟器调试，不进发布包（规范 L8）。

本机没 NDK 就只跑 CI——runner 自带 NDK，`tools/build_module_native.py` 会先复用再兜底。

## 下一步（P0-C）

1. **P0 ① 已验收**（小米 13 / Android 17 / Vector 2.2，`ping=0x5743`）。此后任何改动都回读上面那个文件，
   不要退回去看 logcat——那是上一轮误判的起点。
2. 打通规则通道（remote preferences → 策略缓存），此时 `armed` 才可能被写。
3. 再上第一个 hook：剪贴板（`ClipboardManager` → `ClipboardService`，**只钩 system_server 一侧**）。
   **判定与 hook 全部在 Kotlin/JVM 侧**（L16.2.1：业务不得放 native），本模块的 `.so` 在这条链上
   只留 `wc_engine_ping` / `wc_engine_version` 两个哨兵。每加一个 API 都要满足自洽原则——同一个事实
   在多个 API 上给同一个答案。
