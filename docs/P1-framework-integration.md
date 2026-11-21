# P1 框架接入（libxposed API 101）

目标：证明同一个 APK 能被 Vector 当作 LSPosed 模块加载，并且控制台能读到框架状态。**本阶段不注册任何 hook。**

## 范围

做了什么：

- `META-INF/xposed/module.prop`：`minApiVersion=101` / `targetApiVersion=101` / `staticScope=true`
- `META-INF/xposed/java_init.list`：唯一入口 `com.luckyme.watchcat.xposed.WatchCatModule`
- `META-INF/xposed/scope.list`：只有 `system`
- `WatchCatModule.java`：`onModuleLoaded` 打框架/API/能力日志；`onSystemServerStarting` 打日志并尽力写 `/data/adb/watchcat/system_server.marker`
- `WatchCatApp.kt`：注册 `XposedServiceHelper`，绑定 `XposedService`
- `FrameworkState.kt` / `PolicyStore.kt`：框架只读快照；策略优先走 remote preferences，未连接时回退本应用私有 prefs
- 诊断页新增「LSP 框架接入（P1）」卡片
- CI 增加 APK 内 `META-INF/xposed/*` 存在性校验

明确没做：

- 剪贴板 hook、应用列表 hook
- 策略语义、审计事件
- 注入到本应用自身或任何第三方应用（scope 只有 `system`）

## 依赖与版本

- `io.github.libxposed:api:101.0.0`（`compileOnly`）
- `io.github.libxposed:service:101.0.0`（`implementation`）
- Vector v2.2 自带 API 102，但模块按 L1 只声明/使用 101，不追 102。
- 两个 AAR 的 POM 都声明 `kotlin-stdlib:2.2.10`（源码是纯 Java），本项目 Kotlin 2.1.0；Gradle 依赖里已 `exclude(group = "org.jetbrains.kotlin")`，避免把 stdlib 顶到 2.2.10。
- `api` 只在编译期可见，运行时由框架注入；Kotlin 侧引用的 `PROP_CAP_*` 是编译期内联常量。

## 真机验收

前置：手机已 root（Magisk / KernelSU），已装 Vector v2.2。

1. 安装 CI 产出的 `watchcat-debug.apk`。
2. 在 Vector 里启用 WatchCat 模块，作用域应只显示/保留 `system`。
3. 重启手机。
4. `adb logcat -s WatchCat` 应看到：
   - `p1: module loaded process=... system=true`
   - `framework=... version=... api=101+`
   - `p1: onSystemServerStarting pid=...`
5. 打开 WatchCat → 诊断 → 「LSP 框架接入（P1）」：
   - 框架服务：已连接
   - 框架：真实 name/version/versionCode/API
   - 能力：system / remote / apiProtection
   - 作用域：`system`
   - 策略通道：`remote preferences（框架托管）`
   - 注入：marker 内容，或 `未找到`

### 读数说明

- **框架服务未连接**：Vector 没把 binder 送到应用，或模块没启用。诊断页显示“未知”，不猜版本。
- **注入未找到**：`/data/adb/watchcat` 可能不存在，或 system_server 的 SELinux 不允许写。以 logcat 为准，marker 只是加分项。
- **策略通道显示本地回退**：框架服务未连接；此时 UI 仍可用，但策略不会进入目标进程。

## 已知风险

- Vector 版本升级可能改变服务绑定时机；服务晚于诊断页打开时，点「重新检测」。
- 模块声明 `staticScope=true`，用户不应把它应用到 `system` 以外的包。
- release 目前 `isMinifyEnabled=false`；`proguard-rules.pro` 已保留入口类，防止以后打开混淆时 `java_init.list` 对不上。

## 下一步（P2）

剪贴板读方向三态（空/替身/阻断）+ 输入法放行 + 写方向只审计；仍然先不做应用列表。
