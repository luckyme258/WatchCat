# WatchCat 数据面模块（P0）

这个包**装上去什么都不会做**，这是设计而不是缺陷。

`module.prop` 里写了「只放加载探针：默认不生效、不注入任何进程」。当前 `module/` 下
没有 `zygisk/*.so`，所以 Zygisk 根本不会往任何进程（包括 system_server）里加载东西；
模块能做的只有 `post-fs-data.sh` / `service.sh` 写两个标记文件，证明「模块被 Magisk
正常装载、脚本在真机上能跑」。

## 现在能验证什么

装完后看 `/data/adb/watchcat/`：

| 文件 | 什么时候写 | 用来判断什么 |
|---|---|---|
| `boot.marker` | 开机最前面 | 模块被装载了、脚本没报 `bad interpreter`（CRLF 问题） |
| `service.marker` | 开机完成后 | service 阶段活着；里面 `armed=no` 表示确实没启用 |

目录权限是 `0700`、文件 `0600`。将来规则落在这里，权限就是第一道防线（规范 L5）。

两个文件里都有 `fingerprint` / `sdk` / `uptime`，报问题时贴上来就能定位是哪次开机。

## 卸载

Magisk 里删掉模块即可；`uninstall.sh` 会把 `/data/adb/watchcat` 一起清掉。

## 下一步（P0-B：真正的加载探针）

要出 `zygisk/arm64-v8a.so` 还需要两样东西，两个都得先定：

1. **NDK**：shim 是 C++，要 `liblog` 和系统头文件；CI 里加 `sdkmanager "ndk;27.x"` 即可。
   Zig 自带 libc 覆盖不了 `aarch64-linux-android`（实测报
   `unable to provide libc for target`），所以这条路绕不开 NDK。
2. **Zygisk 头文件**：`zygisk.hpp` 在 Magisk 仓库里，GPL-3.0。是 vendored 进仓库
   （要接受许可证传染性），还是构建时按固定 commit 拉取（仓库干净但依赖网络），
   这是个许可证决定，不该由脚本替你做。

shim 的行为已经定死，写在这里免得后面走样：

- 只在 `process name == system_server` 时动作，其他进程立刻返回；
- 先读 `/data/adb/watchcat/armed`，**文件不存在就什么都不做**（规范 L10 的落点）；
- 链接 `libwatchcat_engine.a`，调 `wc_engine_ping()` 拿 `0x5743` 当「Zig 代码真跑起来了」的证据；
- 抓到证据就写 `p0.probe`（含进程名、pid、ping、version），**不注册任何 hook**；
- 全程 fail-open：任何一步出错都 return，不抛、不 abort、不阻塞 system_server。
