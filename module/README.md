# WatchCat 数据面模块（P0）

**装上去不会改变任何行为**——它只往 logcat 打两行日志证明「Zig 代码在 system_server 里跑起来了」，
一个 hook 都不注册。

## 怎么验证

```bash
adb logcat -s WatchCat
# 重启一次效果更稳（模块在 fork 之后才加载）
```

应该看到：

```
p0: 进入 system_server 的 pre 阶段（zygote 权限），pid=1234
p0: system_server 内调用 Zig 引擎 ping=0x5743 version=0x00020000 pid=1234
p0: armed 不存在 → 本次不启用任何功能（这是默认状态，不是错误）
```

`ping=0x5743` 是验收点：不是这个值，就说明链进来的不是我们那份引擎（或引擎符号被改名/裁掉了）。

**普通应用进程一行日志都没有**，这是刻意的：`preAppSpecialize` 直接 return，连文件都不开。

## 文件与目录

| 路径 | 说明 |
|---|---|
| `/data/adb/watchcat/` | `0700`，root 所有；将来规则落这里 |
| `boot.marker` / `service.marker` | 证明模块脚本真跑了（含 fingerprint / sdk / uptime） |
| `armed` | **不存在就是不启用**。P0 不写它，所以永远是「不启用」状态 |
| `p0.probe` | armed 存在时才尝试写；system_server 下大概率被 SELinux 拒，拒了只记 errno 不重试 |

`p0.probe` 写不进去不是缺陷：说明这条路径是探针而不是通道，真正的通道是 remote preferences。

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

包内**只有** `module.prop`、四个脚本、本 README 和 `zygisk/arm64-v8a.so`（白名单，`tools/build_module.py`
的 `PACK_FILES`）。`module/src/` 的源码与 `zygisk.hpp` 不进包；`x86_64.so` 只在 `--with-x86_64`
时带上，供模拟器调试，不进发布包（规范 L8）。

本机没 NDK 就只跑 CI——runner 自带 NDK，`tools/build_module_native.py` 会先复用再兜底。

## 下一步（P0-C）

1. 真机装一次，把上面三行日志贴回来；`ping` 不对就说明静态链接那段有问题。
2. 打通规则通道（remote preferences → 引擎 `wc_engine_rule_set`），此时 `armed` 才可能被写。
3. 再上第一个 hook：剪贴板（`ClipboardManager` / `IClipboard` 侧），每加一个 API 都要满足
   自洽原则——同一个事实在多个 API 上给同一个答案。
