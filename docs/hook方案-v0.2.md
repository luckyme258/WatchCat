# WatchCat v0.2 · 数据面（Hook）实施底稿

> 状态：**架构已定；P0 ① 与 P1 已真机验收（2026-11-22）；判定层按 L16 定为 Kotlin/JVM，Zig 退出热路径**
> 日期：2026-11-21（首版）/ 2026-11-22（判定层归属修订）
> 决策已定：走 Vector（最新 LSPosed）｜**判定与 hook 全在 Kotlin/JVM，native 只留哨兵**｜接受"不自动生效 + 连续异常停用"兜底｜接受"system 侧读 root 文件、应用侧接受名单可见"的折中｜接受"自洽原则""不压系统提示"四条硬规矩
> 追加决策（2026-11-21）：**P0 先做 Zig 进 system_server 的加载探针**｜分发形态定为 **控制台 APK + Magisk/Zygisk 模块包**｜默认值走 **预置数据集**（见附录 A）｜替身内容 **自建词库先起个头**（见附录 A）｜审计 **只做简版历史 + 汇总**

---

## 0. 一句话结论

v0.2 只做两件事：**剪贴板（system_server 单点注入）** 与 **应用列表（应用内注入）**。前者风险最小、收益最直接，作为第一条主线；后者必须成套做（列表 + 单点 + Intent 三件套），单独做列表等于主动留破口。**判定与 hook 都在 Kotlin/JVM 一侧**（同一进程内的纯函数，无 IO、无 JNI）；Zig 只在数据面保留「我在这份进程里活着」的哨兵与构建期工具，不参与任何一次判定（L16.2.1）。Zig 能不能进 system_server 这件事已经由 P0 探针回答完了：能，`ping=0x5743` 已实测——所以它不需要承担判定，也仍然是我们确认「链对了」的唯一凭据。

---

## 1. 边界

**做**：剪贴板返回值（放行/空/替身）按调用 uid 决策；应用列表对指定应用隐藏；两份策略可导入导出。

**不做（本版明确排除）**：
- 通讯录、通话记录、短信——需要另一套 provider 级方案，不在本版。
- 相机、麦克风伪造——native 输出链，Xposed 钩不进（参考项目已确认这个限制），单列 v0.3。
- 权限面（`pm revoke`/`appops`）——已成型，不动，也不允许被 hook 逻辑污染。
- 反检测对抗（隐藏 Xposed 痕迹）——不做。真要做是另一场无休止的猫鼠游戏，且我们宁可"被发现"也不"装得像没装"。

---

## 2. 架构分层

```text
watchcat.apk   一个 APK，两种身份（LSPosed 模块要求配置属于模块包，故必须合并）
  ├─ UI 层：策略编辑 / 导入导出 / 审计 / 诊断 / 权限面（v0.1 资产全在这里）
  ├─ 控制层：写 remote preferences（框架托管）；不参与任何运行时判定
  └─ hook 层：极简入口类，onSystemServerStarting / onPackageReady
              · 定位注入点、枚举同名重载、取调用 uid
              · 判定也在这里，纯 Kotlin 函数：decide(uid, kind) -> allow|empty|fake|block
                filter(uid, pkgs[]) -> 保留子集 ／ pick(uid, scene, seed) -> 假数据内容
              · 零静态状态、零 UI 依赖、零 IO、失败即放行（fail-open）
                    │ 无 JNI：判定链上不存在任何 native 调用
watchcat-module.zip   Magisk 模块（只放 native 与脚本，不含判定逻辑）
  └─ libwatchcat.so（由 Zygisk 加载，不是 System.load）
                    · 只导出哨兵：wc_engine_ping / wc_engine_version
zig/  同一份源码，两个编译目标
  ├─ watchcat.zig  → 权限面导出（nativeReadOps / nativeWriteOp …，现有 8 个）
  └─ engine.zig    → 数据面只剩哨兵（ping / version）+ 构建期工具（策略与词库编译）
```

三层各管一件事，**任何一层都不许替另一层做决定**：UI 只写策略不判定；hook 层只回答「这一次调用该给什么」并且必须是纯函数（读不可变快照、不算业务外的东西）；native 不参与判定、不碰 Android API，只在构建期与哨兵上出现。

三个必须记住的工程事实：

1. **`.so` 会被加载两次**（App 进程一次、system_server 一次）。收缩成哨兵之后它没有状态，谁加载都只是返回一个常量；但这条也说明：**一旦有人往里加判定逻辑，它就立刻变成 bug 源**（两份状态、两份解释）。所以 L16 把它冻结成只读哨兵，而不是"再加一点也无所谓"。
2. **`.so` 里并存两组符号**，前缀分开（`native*` = 权限面拼命令，`wc_engine_*` = 数据面哨兵），且来自两个互不依赖的编译单元。
3. **hook 侧代码与 UI 代码同在一个 APK 但不得互相引用**：UI 类一旦在目标进程被初始化（拿 Context、读资源）就会拖崩别人，2.1 的 `XResources`/`findClass` 事故就是先例。

### 2.1 与 v0.1 的整合边界

**复用（不重写）**

| 资产 | 复用方式 |
|---|---|
| `Ui.kt` 视觉层 | 原样用，数据面新增“胶囊”而非“开关”控件 |
| `AppsScreen.kt` | 就是数据面的目标选择器，不改结构 |
| `DetailScreen.kt` | 增加“数据面”一节（权限面那节不动） |
| `DiagScreen.kt` | 增加“数据面状态”块（armed / 作用域 / 引擎） |
| `Audit` | 同一份日志、同一格式，只加字段（见 §6.1） |
| `Shell.su` + 身份三态 | 控制台侧继续用；hook 侧不依赖 root |
| `tools/build_so.py` | 继续做编译 + 16KB 门禁，ABI 只留 arm64 |
| `.github/workflows/build.yml` | 一条流水线出两个产物 |

**隔离（永不混合）**

| 边界 | 原因 |
|---|---|
| `CapabilityProbe` / `ChannelProbe` 不进 hook 侧 | 它们真的写系统状态（`appops set`、`svc wifi`），在别人进程里做实验不可接受 |
| `Engine.writeOp` 与 hook 判定分属两个类 | 失败语义相反：权限面失败要报错，数据面失败要放行 |
| 策略文件不承载“权限授予状态” | 权限面事实源是 `dumpsys`/`appops`；策略文件只是数据面事实源，混了就出现会漂移的假真相 |
| 能力表分两张 | 权限面 `capability_v2.json` 回答“系统让不让写”；数据面另出一张回答“我们钩没钩上、返回什么”。混在一张表里没人读得懂 |

**重构（小改）**

1. 详情页的“数据面”用胶囊（放行/空/替身/阻断），并在标题写明“决定它看到什么，不影响权限授予”——现在两者都用绿色开关，用户一定会误判。
2. 详情页 Hero 加一行汇总：“权限：已授予 3 项 · 数据面：剪贴板空、列表替身”，避免用户以为“权限关了却还能用”是 bug。
3. 产物变两个（`app-debug.apk` + `watchcat-module.zip`），Release 挂两个附件，这条要写进规范分发段。

---

## 3. 判定为什么放在 Kotlin（原「Zig 做判定引擎」已撤回）

**性能从来不是理由——这一点首版就写了，结论没变**：剪贴板判定量级是"每分钟几十次"，应用列表过滤是"每次几百条"，Kotlin 做这两件事毫无压力。首版给 Zig 的三条价值，按 L16.3 的账本逐条算下来都换不回一次真机回环：

- **确定性**：判定就是"读一张不可变策略表 + 比两个整数"。Kotlin 里只要不用 `HashMap` 的迭代顺序、不掺随机数，同样可复现。
- **可单测**：JVM 单测在 CI 里跑，比 `zig test` 更快也更容易断言（策略为空、uid 是输入法、包名缺失这类场景，真机上根本造不出来）。
- **一处实现两处用**：这条已经从假设变成事实——`.so` 确实在两个进程里各加载了一次（P0 已证）。但"能两处用"不等于"必须两处用"：剪贴板只有 system_server 一个消费方，应用列表也只有被注入进程一个消费方，**根本没有第二处**。
- **修改成本**：改一行判定逻辑，JVM 是 push → CI → install → `stop/start`，约 20 秒见结果；native 要重编 shim、重打 zip、`ksud module install`、整机重启，8–10 分钟一轮，失败形态还是静默的。

结论（L16.2.1）：**业务判定不许放 native。** native 在这条链上只剩两个合法形态——`ping`/`version` 哨兵，以及构建期工具（真需要时把策略与词库编译成紧凑 blob）。

**首版担心的"极限"已经不成立**：`ping=0x5743`、`version=0x00020000` 已在小米 13 的 system_server（pid 1996）里实测拿到（提交 `68ec8d0`，证据见《P0-骨架.md》与《框架兼容性核对.md》6.1）。加载链成立已证完，"进不去就退回 Kotlin"这条退路不必再等证伪，直接按主路径走。

### 3.0 Vector 事实基线（已核对作者仓库，2026-11-21）

| 事实 | 结论 | 来源 |
|---|---|---|
| 定位 | Zygisk 模块，基于 **LSPlant** 提供 ART hook，保持与原版 Xposed 的 API 一致性；只在内存改行为，不改 APK | 作者 README |
| 版本范围 | Android **8.1 – 17 Beta** | 作者 README |
| 运行前提 | 较新的 Magisk 或 KernelSU **且启用 Zygisk** | 作者 README |
| API 代际 | 2.0 = API 100 的封版；**2.1（2026-08-02）= 首个 API 101 版本**；**2.2（2026-08-04，当前 stable）= API 102 热重载** | Releases |
| 16KB | 2.1 起**原生支持 16KB 页设备** | v2.1 release notes |
| 配置归属 | "一个模块 = 一个包 + 一个二进制"，配置属于该包；只在安装它的用户里运行，**system_server 例外**（它不属于任何用户） | v2.2 release notes |
| 已知致命坑 | 2.1 的 release 构建"模块加载了但什么也没钩上"：R8 把 `XResources` 合并进共享类，`system_server` 里一次 `findClass` 失败就是**整个开机周期永久失败**；2.2 已修 | v2.2 release notes |
| 管理器匹配 | 2.1 的管理器**连不上 2.2 的守护进程**，独立管理器要先卸载再升级 | v2.2 release notes |
| 开发者依赖 | 同时维护 legacy API 与 modern `libxposed` API（module API + service API 两个 submodule），**建议 checkout 与 Vector 相同的 commit** | README |
| 发版节奏 | stable 之外有 canary（约每周，例：3119 / 2026-11-15） | Releases |

**第三方指南里出现、但作者仓库未写的两条**（我们按"待实测"处理，不当结论用）：Magisk 具体版本门槛（v30.7 / v31.1）、"与旧 LSPosed 并存会 bootloop"。这两条只作为安装时的提醒，不作为代码里的硬判断。

### 3.1 加载形态：只走 Zygisk（原方案的 B 已废弃）

| 形态 | 谁把 .so 塞进目标进程 | 结论 |
|---|---|---|
| **A. Zygisk 模块（唯一方案）** | Magisk/KernelSU 的 Zygisk 在 zygote fork 阶段注入；Vector 本身也是 Zygisk 模块，二者共存于同一 Zygisk 环境 | **采用** |
| ~~B. Vector 内 `System.load`~~ | 由被注入的 Java 层 `dlopen` 我们 APK 里的 lib | **废弃**：Vector 修 dex2oat 时都要用 `memfd_create` 绕 bionic 命名空间限制（v2.0 release notes），说明"从可写目录 dlopen"在 Android 10+ 本来就不可靠 |

所以 Zig 的 `.so` 必须由 Zygisk 加载，**不是**由我们的 Java 代码 `System.load`。这条已在 P0 证实成立；而判定层已按 L16 定在 Kotlin，Zig 不再承担热路径，只剩哨兵与构建期工具。

**API 基线定为 101，不追 102。** 理由：101 在 2.1/2.2 上都支持；102 的收益是"代码热替换"，而我们的热更新对象是**规则**不是代码——规则变更走 remote preferences + 引擎 reload 就能生效，不值得为此把最低框架版本抬到 2.2。

---

## 4. 剪贴板：注入点与方法面

### 4.1 注入点

`ClipboardManager`（应用侧）→ binder → `com.android.server.clipboard.ClipboardService`（system_server）。**只钩 system_server 一侧**，一次注入覆盖全部应用，且每行都能取 `Binder.getCallingUid()`。

**但真正声明读方法的类不是 `ClipboardService`，是它的内层实现体 `ClipboardService$ClipboardImpl`**（`IClipboard.Stub` 的实现，`onTransact` 分发后按虚调用落到它身上）。2026-11-22 首轮真机跑出 `hooked=0` 就是踩了这个：dex 里五个读方法的每一段都印着 `(in Lcom/android/server/clipboard/ClipboardService$ClipboardImpl;)`，外层类一个同名方法都没有。所以钩点按"内层优先、外层兜底"两级取，命中即止，不重复钩同一方法。

### 4.2 方法面（按"族"钩，不按签名钩）

**关键工程决定：按方法名枚举"同名重载"，不在代码里写死签名。** 具体 API 形态按 libxposed 101：用 interceptor（不是 legacy 的 `XC_MethodHook`），入口用 `onSystemServerStarting`（不是 `handleLoadPackage`，也**不许靠进程名猜**——v2.1 就踩过"模块加载了但 `system_server` 里 `findClass` 全挂"的坑）。

原因：API 29→36 之间这几个方法的参数形态改过（多了 `pkg`、`attributionTag`、`userId`、`deviceId` 等），写死签名等于给自己埋一个"到某代系统就静默失效"的雷。回调里靠 `Binder.getCallingUid()` 判定，参数只用于取包名做审计。

**同理，钩点类名也不能按"看起来该是哪个类"去猜**：钩错类不会报错，只会静静拿到 `hooked=0`。所以探针必须把"没加载到类 / 名字没匹配 / 被框架拒绝"三种失败分开落盘（`diag` 里的 `noclass` / `miss` / `fail`），否则下次还是只有一个 `0`。

要覆盖的方法族（**具体重载在 P0 打点打印实测，不猜**）：

- `getPrimaryClip`（读）——主目标
- `hasPrimaryClip`（读，返回 boolean）
- `getPrimaryClipDescription`（读，返回描述）
- `addPrimaryClipChangedListener` / `removePrimaryClipChangedListener`（读事件）
- `setPrimaryClip` / `setPrimaryClipAsPackage` / `clearPrimaryClip`（写方向，**默认不拦**，只记审计；写入被拦会让"复制"这件事整体失效，属于自伤）
- 读方向之外还可能有 `getPrimaryClipSource` 一类的新 API，P0 一并打点

### 4.3 返回值三态

| 模式 | 返回 | 适用 | 风险 |
|---|---|---|---|
| 放行 | 原值 | 白名单、输入法 | — |
| **空**（默认） | `null` ClipData | 绝大多数应用 | 少数应用会当成"剪贴板为空"正常处理，极少数会 NPE |
| 替身 | 构造的 ClipData | 需要"看起来有内容"的场合 | 内容不符场景会被判定异常，必须分池 |
| 阻断（显式选项） | 抛 `SecurityException` | 不给任何机会 | **最容易让应用崩**，不设默认 |

**默认必须是"空"**。`null` 是系统自己也合法的返回值（真没内容时就是 null），最不容易被识别成异常。

### 4.4 三条必须遵守的细节

1. **输入法放行**：当前激活输入法（`InputMethodManager` 的当前 IME 包名 / `settings get secure default_input_method`）永远放行，否则用户自己的剪贴板粘贴功能会坏——这是 ClipBait 专门处理过的坑。
2. **保留系统提示**：Android 12+ 读剪贴板会弹"已粘贴来自…"，这个提示**不压**。它属于审计，不属于要伪造的东西；压掉等于帮应用隐瞒行为。
3. **替身内容要分池**：购物/社交/工具各一池，且内容长度、字符集要像"人复制的东西"。单一固定词在真实应用里等于自报"我被 hook 了"。起步词库见附录 A.2，先满足"每池≥8 条、长度 6–40 字、无敏感词"。

### 4.5 审计事件

每次决策产生一条：`ts | uid | pkg | kind(read) | mode | verdict`。同一 uid 在 1 秒内的连续读取合并计数（防止刷日志）。

审计按"简版"做，两档就够：

- **历史**：环形保留最近 200 条，只给"谁在什么时候读了几次"；
- **汇总**：按包名聚合"累计读取次数 + 最近一次时间"，页面上排个前 10。

不做"前台偷读比"这类精细统计（ClipBait 的"光荣榜"那套），后面有空再单独优化。

---

## 5. 应用列表：成套做，自洽优先

### 5.1 注入位置

**第一版走应用内注入**（注入到被管应用进程），钩 `android.app.ApplicationPackageManager`（`Context.getPackageManager()` 实际返回的就是它）。风险容器化：崩的只是那个应用，不至于让系统起不来。

（系统端在 `PackageManagerService` 里按 calling uid 过滤也可以，但一崩就是软重启甚至开不了机，不在第一版。）

### 5.2 必须同时覆盖的四个面

> **2026-11-22 已按 Android 17 真机 dex 重核（详见 `docs/应用列表-API核对.md`）**：下表的名字已改成实际存在的 API 名；每个名字都带多套重载（`int flags` 与 `*Flags` 对象），钩法改为"按方法名族钩 + 运行时枚举声明方法"，不再写死签名。

| 面 | 目标方法（族） | 处理 |
|---|---|---|
| 列表 | `getInstalledPackages`(+AsUser)、`getInstalledApplications`(+AsUser) | 从结果里剔除被隐藏项（空列表不返回 null） |
| 单点 | `getPackageInfo`(+AsUser, 含 `VersionedPackage`)、`getApplicationInfo`(+AsUser)、`getPackageUid`(+AsUser)、`getApplicationEnabledSetting`、`getComponentEnabledSetting`、`getInstallerPackageName`、`getLaunchIntentForPackage`/`getLeanbackLaunchIntentForPackage`/`getLaunchIntentSenderForPackage`、`getSharedLibraries`(+AsUser)、`getPreferredActivities` | 查到就**抛 `NameNotFoundException`**（不返回 null，null 不合法）；单值型返回 `null`；两个输出列表型要同时剔除 |
| Intent/组件 | `queryIntentActivities`、`queryIntentServices`、`queryIntentContentProviders`、`queryBroadcastReceivers`、`queryIntentActivityOptions`（均含 AsUser）、`queryContentProviders`、`resolveActivity`、`resolveService`、`resolveContentProvider`（均含 AsUser） | 剔除结果；`resolve*` 返回 null |
| 跨 uid | `getPackagesForUid`、`getNamesForUids` | 剔除（`getNamesForUids` 按位置置 null；同 uid 全隐才隐） |

**原表两处已作废**：`queryIntentProviders` 在 Android 17 上不存在（应为 `queryIntentContentProviders`/`queryContentProviders`/`resolveContentProvider`）；`queryIntentReceivers` 应用侧不存在（应为 `queryBroadcastReceivers`，`queryIntentReceivers` 只在 `IPackageManager` 系）。

二期再谈：`ActivityManager.getRunningAppProcesses`、`UsageStatsManager`、`LauncherApps`、无障碍窗口包名、`Runtime.exec("pm …")`（同 uid 另起进程，可能绕过 hook，优先级最高）。这些偏"行为"而非"清单"，漏了不容易被抓，但终归要补。`pm list packages` / `dumpsys package` 跑在 shell/system 进程，**应用内注入永远管不到**，属设计事实。

### 5.3 自洽原则（写进规范的那条，在这里具化）

**同一个"事实"在所有 API 上必须给同一个答案：**

- 列表里没有 → `getPackageInfo` 必须 `NameNotFoundException`
- 列表里没有 → `resolveActivity` 必须 `null`
- 列表里没有 → `getPackagesForUid` 必须空
- 反过来，**列表里有的，单点必须能查到**（否则"隐藏"变成了"全隐藏"，应用直接崩）

XPrivacyLua 明确写了"出于稳定性考虑不限制单个应用信息"，那等于自己留了最经典的反检测破口。我们不学它。

### 5.4 名单策略：宁少不多

Android 11 起 `<queries>` 让"应用只能看到一部分应用"成为常态，所以**"少看到"不引起怀疑，"多看到"才可疑**。因此：

- 默认模式 = **白名单可见**（只让它看到系统应用 + 一个低风险预置集），而不是"黑名单隐藏"；
- 预置集用市面上最常见的那批（微信/支付宝/抖音/淘宝/高德…），显得像一个正常用户；
- 允许按包名手工增删，导出只导出包名与标签，不带用量数据。

**不要返回完全空的列表**：很多应用把"一个应用都没装"当成环境异常，直接进异常分支。默认给一份**预置可见集**（见附录 A），看起来就是一个装了十几款常用应用、连银行都没装的普通用户。

### 5.5 ROM 特征包层（2026-11-22 定案，只写方案与边界）

风控判断"是不是 AOSP / LineageOS"最常见的写法就两条：扫 `org.lineageos.*` 这样的包前缀、或者单点 `getPackageInfo("org.lineageos.updater")`。**这两条都落在 P2 已有的注入面上**，不需要另开一层 hook，所以做成了判定层里的一层规则，而不是新模块。

- **规则**：前缀 `org.lineageos.` + 精确名 `lineageos.platform`、`android.overlay.lineage`。用前缀不用固定清单，是因为 LOS 的 overlay 包逐机不同（本机就有 `org.lineageos.settingsoverlay.fuxi`、`org.lineageos.frameworkresoverlay.xiaomi`），清单永远维护不过来；而"按前缀遍历"恰好也是风控的写法。
- **开关**：显式 `rom=yes`，缺省关（L10.1 的"默认不生效"不破例）。`rom=yes` 时即使用户名单为空也算"有东西可藏"，`enabled()` 允许启用——否则只想做 ROM 伪装时根本开不了。
- **顺序**：ROM 层排在"`uid<10000` 系统应用永不隐藏"**之前**。这是整套判定里唯一一次给保护规则开口子，理由很直接：LOS 特征包几乎全是系统应用，放在保护之后等于这层永远不生效。
- **代价与约束**：因此内建名单**只能收 ROM 专属包**。`android`、`com.android.systemui`、Settings、WebView、媒体/电话 provider 这些 AOSP 核心一旦写进去，就是"把应用的依赖藏了 → 应用崩"，禁止。
- **用户意图优先**：`mode=show` 里显式点名要看到的包，即使命中 ROM 前缀也照常可见。
- **边界（明确不做）**：① Java 层 `Build.TAGS`/`FINGERPRINT`/`DISPLAY` 不改——改 static final 风险高，改不干净本身就是更强的"改装机"特征；② native 的 `__system_property_get`、直接 open `/system/build.prop`、读 `/proc/*`，应用内注入永远覆盖不到；③ Key Attestation / Play Integrity 的 verified boot 状态是 TEE 签的，进程内不可伪造。**所以对外承诺只能是"经 PackageManager 的包可见性被我们接管"，不能是"骗过 ROM 检测"。**
- **验证方法**：主力机本身就是 LOS（fuxi），`pm list packages | grep org.lineageos` 有 30+；armed + `rom=yes` 时 `getInstalledApplications` 应出现 `act=filter removed=N`，marker 里有 `rom=yes`。

### 5.6 默认姿态「只看基础」与基础可见集（2026-11-22 定案，0.5.5）

**决策**：P2 的默认姿态从"隐藏名单（默认全可见）"改成"只看基础"（`mode=show` + 一份**基础可见集**）。

- **为什么不能是空集**：`isHidden` 第 5 条在 `show` 为空时 fail-open（全可见）。真按"空集隐藏"跑，桌面点不开、输入法不出、登录页跳不回来，应用直接崩。所以 App 侧 `P2Store.save` 在白名单为空时**自动补基础集并回读**，返回文案写明补了多少；钩子侧保持"空 show = 不启用 + 全放行"不动。
- **基础集由本机探测**（`BaseSet.kt`），不写死机型：
  - 系统关键包（`BaseSet.SYSTEM_CRITICAL`）：`android`、SystemUI、Settings、权限控制器、WebView（AOSP + Google）、GMS/GSF、Play 商店、DocumentsUI、IntentResolver（分享面板）、两个安装器、媒体/下载/外置存储/defcontainer。
  - 桌面：`queryIntentActivities(ACTION_MAIN + CATEGORY_HOME)` 全量（第三方桌面也算）。
  - 输入法：`queryIntentServices("android.view.inputmethod.InputMethod")` 全量 + 当前默认输入法。**第三方输入法必须进基础集**，只看 `uid<10000` 会漏（Gboard 在这台机器是 10189）。
- **生效范围**：基础集只决定第三方可见性。`uid<10000` 的系统应用在判定里本来永不隐藏（自杀保护），列它只为语义完整、便于在 UI/文档里展示。**但"只拿包名"的单点查询读不到 uid**，所以钩子必须先 `proceed()` 拿真实对象、从对象里取 uid 再定案（0.5.6，决策 17）——否则 `show` 模式会把 `com.android.providers.settings` 这类系统 provider 一起藏掉。
- **UI 口径**：列表行不再问"藏谁"，而是"**能看到几个**"；编辑器 chip 从「隐藏名单 / 白名单可见」改成「隐藏名单 / 只看基础」，并给一个「恢复基础集」按钮。
- **补救回路（必须配，否则用户只会关功能）**：钩子每个被挡的调用都写了 `target=<被查包> act=nnf|iae`，App 侧 `P2Device.remedies()` 把最近 200 条事件聚合成"它想看谁 × 几次"，在编辑器运行状态卡里列成可点 chip：点一下就把该包放进暴露集合（`mode=show` 加进 `show`，`mode=hide` 从 `hide` 删掉），提示"记得保存并下发"。这样"最小暴露"坏了以后有可诊断、可修复的出口，而不是让用户自己猜哪个包该放行。
- **已知缺口**：补救回路目前只在**单个目标编辑器**里出现；列表页的跨目标汇总（"微信被 3 个应用问过"）还没做，需要先把各目标事件批量归集到一处（见 §13.2 未闭环第 2 条）。

---

## 6. 策略下发与保密（按 Vector 2.2 的真实机制改写）

**主通道：libxposed remote preferences。** 现代 API（101+）里配置由框架托管，取代了 legacy 的 `XSharedPreferences`；而且按作者的实现说明，**现代模块不再有"世界可读 prefs"重定向**（v2.2 release notes 也确认"一个模块 = 一个包 + 一个二进制，配置属于该包"）。这意味着原来"接受名单可见"的折中**可以升级成"默认不可见"**。

| 消费方 | 通道 | 保密性 | 说明 |
|---|---|---|---|
| system_server（剪贴板） | 框架 remote preferences（模块包提供） | 目标应用读不到（**待靶子实测确认**） | 判定在 system 侧，本来也不需要目标进程持有名单 |
| 目标应用进程（应用列表） | 同一份 remote preferences | 同上 | 注入进来的是模块代码，取配置走框架接口 |
| native（Zig） | **不再消费策略**（只留哨兵） | — | 判定在 Kotlin 侧读同一份 remote preferences，native 与策略彻底解耦（L16） |
| 兜底文件通道 | `/data/system/watchcat.policy.bin`（`0600 system:system`） | 普通应用读不到 | **已实测可写**（P0 探针落的就是这个目录）。`/data/misc/watchcat` 未实测、不再作为候选；`/data/adb` 写不进（`0700 root:root`，uid 1000 过不了 DAC） |

**明确禁止**：把策略放 `/sdcard`、`/data/local/tmp`，或做成"人人可读的明文 JSON"。那等于挂个牌子告诉应用"你正在被骗"。

**诚实边界**：注入是"同 uid 同进程"，理论上目标应用能 dump 出自己的内存，所以"绝对不可保密"对任何 in-process 方案都成立。我们能做的是"不落明文、不给世界可读入口、不主动暴露"，而不是承诺"读不到"。真正的绝对隔离只有 system 侧判定。

**由此确定的一条结构决策：控制台与模块合并成一个 APK。** 因为配置属于模块包，独立包名的控制台既写不了 remote preferences、也读不到模块的 scope 信息（v2.2 release notes 的"one module, one configuration"）。合并后 Magisk zip 只承载 Zig `.so` 与脚本，Release 仍是两个附件（APK + zip）。

---

## 7. native 侧接口（按 L16 收缩 · 原判定草案作废）

原则：**哨兵无状态、纯常量、可任意次数加载**。"按 handle 实例化"的设计随判定一起撤掉——它当初存在的唯一理由就是"一个进程一份判定表"，而判定已经不在 native 了。

| 函数 | 输入 | 输出 | 说明 |
|---|---|---|---|
| `wc_engine_ping()` | — | `0x5743` | 固定魔数，证明这份 shim 链的是本仓库的引擎 |
| `wc_engine_version()` | — | `0x00020000` | 版本口径，报障时用来对齐"你装的是哪一版" |

**从导出面撤回（代码保留为规格）**：`wc_engine_rule_set` / `rule_clear` / `rule_count` / `wc_engine_decide`、以及更早草案里的 `create` / `destroy` / `reload` / `wc_clip_decide` / `wc_clip_pick` / `wc_app_visible` / `wc_app_filter` / `wc_stats_push`。依据是 L16.2.1「业务不得放 native」：`export fn` 已改成 `pub fn`，不再进 shim 的符号表、不参与任何热路径，只留作**判定语义的规格**——`zig test` 里那 7 个用例（并集、拒系统 uid、未知 kind 走放行）就是 Kotlin 实现必须逐条对上的验收表。原「`wc_app_filter` 与逐条 `wc_app_visible` 必须等价」那条防漂移单测随接口一起作废：现在只有一条路径。

**仍然成立的单测口径**：Kotlin 判定层做表驱动单测进 CI（给定策略 + uid → 期望三态 / 期望保留集），断言内容照抄 `engine.zig` 里那 7 条。

**16KB**：`.so` 必须过现有门禁（`tools/build_so.py` 已校验），ABI 只保留 `arm64-v8a`。

---

## 8. 兜底：不自动生效 + 连续异常停用

这是本版最重要的安全设计，按"宁可漏报，不可变砖"来定。

### 8.0 启用前置自检（任一不满足就拒绝启用，并在诊断页写明原因）

这几条是 Vector 的实测坑，写成自动检查而不是文档里让人背：

1. **框架 API ≥ 101**（Vector 2.1+）。低于这个，现代模块完全不加载——按 v2.2 的记录，2.1 还出现过"模块加载了但什么都没钩上"的形态，必须拒绝启用而不是"看起来绿了"。
2. **scope 必须包含"系统框架"（包名 `system`）**。勾 `android` 会得到一个"显示已启用、静默不生效"的模块。诊断页直接读自己的 scope 并显示。
3. **管理器与守护版本配套**。2.1 管理器连不上 2.2 守护；版本不一致时只提示、不启用。
4. **模块确实在本用户安装**。v2.2 起模块只在其被安装的用户内运行（system_server 例外），多用户场景要检查。
5. **Zygisk 未被排除**。Magisk 的 DenyList / 排除进程若把相关进程列进去，注入会静默失效。

Magisk/KernelSU 的具体版本门槛来自第三方指南、作者 README 只写"较新版本 + 启用 Zygisk"，所以这一条**只提示不硬判**，避免把用户挡在门外。

### 8.1 激活闸门

1. 模块装好后**默认不生效**：策略目录里没有 `armed` 文件，hook 侧读到就直接返回、不挂任何钩子。
2. 用户在 App 里点"启用"→ 写 `armed`（内含当前 `boot_id` 和签发时间）。**装完即生效是禁止行为**。
3. `armed` 只在"用户明确启用后"存在；任何自动停用都会把它删掉，并留下 `tripped` 记录供 App 展示"已因异常自动停用"。

### 8.2 异常判定（三次机会）

- hook 侧启动后 **90 秒内**必须写出一次心跳（带 boot_id）。
- 每次开机若"没心跳"→ 计一次失败；失败计数 ≥2 → 自动停用（删除 `armed`），此后不再注入任何东西。
- 计数成功的标志是"心跳写成功且 hook 侧自检通过"（自检 = 引擎 `wc_init` 成功 + 至少一次决策被调用）。
- 判定口径写死在 root 侧脚本里，不依赖崩溃上报（可能根本来不及上报）。

### 8.3 fail-open

所有 hook 回调外层包"异常 → 走原逻辑"。**判定失败一律放行，绝不返回错误值。** 只有用户显式选"阻断"模式时才允许抛异常，且要在 UI 上标注"可能让应用崩溃"。

### 8.4 原「需要 P0 实测的两个点」（已结项）

- **system_server 能写哪些路径**：已测。`/data/system/watchcat.*` 可写（`0600 system:system`）；`/data/adb/watchcat` 是 `0700 root:root`，uid 1000 被 DAC 直接拒绝——**不能当 system_server 的落点**，这正是"回调没被调用"那轮误判的根因。`/data/misc/watchcat` 未测，不再候选。
- **"完全写不了"的退化方案**：不需要了。心跳与闸门按 L17 落 `/data/system`，root 侧脚本只负责"这次开机跑过没有"，两边各写各的、不互相猜。

---

## 9. 验收：自建靶子，交叉校验

写一个极简靶子应用（自己写，不上架），专门做四件事：

1. 读剪贴板（前台/后台各一次，记录返回值与耗时）；
2. `getInstalledPackages` 全量 dump；
3. 对名单里每一个包做 `getPackageInfo` + `resolveActivity`（**交叉比对**）；
4. 每 3 秒轮询一次剪贴板，模拟"偷读"，用于验证审计计数。

**通过标准**：

- 剪贴板：靶子读到 `null`（或指定替身），输入法粘贴仍正常，系统提示仍在；
- 列表：`getInstalledPackages` 里没有隐藏项，且对隐藏项 `getPackageInfo` 抛 `NameNotFoundException`、`resolveActivity` 为 null；
- **自洽**：三条路径的答案完全一致，没有"列表没有但单点有"；
- 审计：靶子的每次读取都能在 App 里对应上，且 1 秒内的连读被合并；
- 兜底：手工把 `armed` 删掉再重启，确认"完全不注入"；再模拟一次"无心跳"，确认第二次开机自动停用。

---

## 10. 分期

| 阶段 | 内容 | 产出 | 估时 |
|---|---|---|---|
| **P0 探针**（① ③ 已结项，② ④ 并入 A 阶段） | ① Zygisk 模块骨架能否把 Zig `.so` 带进 system_server 并 `nativePing` 通（**✔ 已验收**）；② 剪贴板方法族真实签名打点；③ system_server 在 SELinux 下的可读/可写路径（**✔ 已验收：`/data/system` 可写**）；④ Vector 在本机的作用域与热生效 | 一页探针报告 + 真机证据 | 0.5 天 |
| **A 剪贴板** | 三态决策（Kotlin 判定）+ 输入法放行 + 审计 + 热生效 | 可用的剪贴板保护 | 1–2 天 |
| **B 应用列表** | 四面覆盖 + 自洽 + 名单编辑/导入导出 | 可用的列表伪造 | 2–4 天 |
| **C 打磨** | 分池词库、简版审计页、自动停用 UI、诊断页接入 | 收尾 | 1–2 天 |

P0 的四条里 **① 与 ③ 已结项**：① 的结论是"Zig 只留哨兵"（L16），③ 的结论是"心跳与闸门落 `/data/system`"（L17）。**② 已结项（2026-11-22）**：不再打点，直接拉真机 `services.jar` 用 `dexdump` 读声明，`ClipboardService` 的读方向重载全部是 `(String pkg, String attributionTag, int userId, int deviceId)` 四参形态，`hasPrimaryClip` / `hasClipboardText` 返回 `boolean`；对应实现在 `xposed/ClipboardHook.kt`。**首轮真机修正（同日）**：这些声明实际落在内层 `ClipboardService$ClipboardImpl`，钩外层类只会得到 `hooked=0`——钩点已改为内层优先、外层兜底。**④ 作用域与热生效** 仍按"每次开机重新确认 + `stop/start` 重载"处理，不单独开一轮。

每阶段都走 CI 出包、真机装、靶子验，**不在本机出包**（G0.0）。

---

## 11. 风险登记

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| system_server 起不来 | 钩子写坏/加载失败 | 不自动生效 + 两次无心跳停用 + fail-open |
| Zig lib 进不去 system_server | SELinux/加载路径 | **已消除**：P0 实测 `ping=0x5743` 通过；且判定本就不在 native，即使哪天加载链复发也不影响功能 |
| 剪贴板被"读空"识别 | 应用把 null 当异常 | 默认空 + 可切替身 + 分池词库 |
| 列表不自洽被抓 | 只做列表不做单点 | 四面覆盖 + 靶子交叉校验门禁 |
| 反作弊/银行类误伤 | 注入痕迹 | 作用域最小化（只勾目标应用）＋ 明确不做隐藏痕迹 |
| Vector 版本漂移 | 框架升级改 API | 诊断页固定展示"框架 API / 作用域"，不满足就拒绝启用 |
| 16KB 未对齐 | 新系统加载失败 | 现有 `.so` 门禁复用，ABI 只留 arm64 |

---

## 12. 规范要新增的条目（草案）

**特定段（W 系列）**

1. 模块形态：hook 侧零 UI 依赖、无静态初始化副作用、作用域最小化；system_server 注入默认不生效 + 连续两次无心跳自动停用 + fail-open。
2. 规则下发：**主通道是框架托管的 remote preferences**（配置属于模块包）；文件通道只作 native 特殊场景的兜底，且必须 `root:system 0640`。**禁止人人可读的明文策略**。
3. 假数据自洽：同一事实在列表/单点/Intent/跨 uid 四个面必须同答案；禁止"列表空、单点有"。
4. 系统提示不当伪造对象：剪贴板读取提示、权限提示、定位提示一律保留。
5. 默认值方向：隐私类默认"少给"（空/白名单），不默认"多给"。
6. 产物形态：引入注入能力后，一次发布出两个附件（APK + 模块 zip），Release 与 CI artifact 都要成对出现，禁止只发 APK 让人"装了却什么都没生效"。
7. 审计字段扩展向后兼容：只加字段（`kind=perm|data`）、不改旧字段、不换分隔符；旧日志没有 `kind` 时按 `perm` 解释。
8. 能力表分账：权限面一张、数据面一张，各自回答不同问题，禁止合并成一张。
9. UI 语义不混用：权限面用"开关"（授予/不授予），数据面用"胶囊"（放行/空/替身/阻断），并在标题上写明"不影响授权"。
10. **框架依赖锁定**：`libxposed` module API / service API 两个 submodule 固定到与 Vector 当前 stable 相同的 commit，不跟随 master 漂移（作者 README 明确建议开发者 check out 同一 commit）。
11. **框架基线**：模块声明 API 101；运行时要求 Vector ≥ 2.1。低于基线一律拒绝启用，不允许"降级到 legacy 82 凑合用"。
12. **hook 侧构建禁混淆**：R8 不得触及 hook 侧类（2.1 的 `XResources` 被合并导致 `system_server` 里 `findClass` 永久失败就是先例），且 hook 侧禁止依赖资源系统。
13. **判定层归属**：业务判定一律在 Kotlin/JVM 的纯函数里，native 不得实现任何 `decide/filter/pick` 语义（L16.2.1）；native 只允许 `ping`/`version` 哨兵与构建期工具。评审时看到"这个判定放 native 更快"就按此条驳回。

**通用段（G 系列）**——暂不需要新增，G0.0（CI 优先）继续适用。

---

## 13. 决策记录

**已定（2026-11-22，判定层修订）**

1. **判定层回 JVM**：`wc_clip_decide` / `wc_clip_pick` / `wc_app_visible` / `wc_app_filter` / `wc_stats_push` 与 `create/destroy/reload` 全部撤回，判定改 Kotlin 纯函数（§3、§7、L16.2.1）。原"一处实现两处用"的理由随 P0 实测失效——两个消费方各只有一个。
2. **证据通道定案**：只看 `/data/system/watchcat.*` 落盘；`logcat`、`/proc/<pid>/maps`、`/data/adb` 状态目录**禁止**当判据（L15.1）。
3. **P0 ① 结项**：`ping=0x5743` / `version=0x00020000` / pid 1996，提交 `68ec8d0`。
4. **应用列表优先级下调**：先让剪贴板从"装了但零行为"变成"装了真的有行为"，再做列表；理由是与《P1-框架接入.md》"下一步 P2"一致，也避免两条主线同时改判定层。
5. **闸门与策略落 `/data/system`**（2026-11-22 实测）：root 能在该目录建文件（`0644 root:root`、label `system_data_file`），system_server（uid 1000）能读；App 经 `su` 写、system_server 读、诊断页再 `su` 读，三段各自可独立验证。`/data/adb/watchcat` 继续只放模块脚本自己的状态（0700 root:root）。
6. **输入法 uid 由 App 侧算**（`Settings.Secure.DEFAULT_INPUT_METHOD` → `getPackageUid`，包可见性兜底走 `dumpsys`），写进策略的 `imeuid=`；hook 侧只用 `Binder.getCallingUid()`，不解析包名、不碰私有 API。
7. **BLOCK 本期不上**：框架的 `PROTECTIVE` 异常模式会把钩子里抛出的异常吞掉并继续走原逻辑，要让阻断生效只能整钩改 `PASSTHROUGH`，那会把我们自己的 bug 也变成崩溃。策略里写 `block` 会被降级成 `empty`，并在审计行标 `downgrade=block`，不假装做到了。
8. **应用列表按方法名族钩、签名运行时枚举**（2026-11-22 dex 核对）：`ApplicationPackageManager` 在 Android 17 上 363 个方法，同一语义普遍有 `int flags` 与 `*Flags` 两套重载；写死签名必漏。族名清单见 `docs/应用列表-API核对.md` §3。
9. **`queryIntentProviders` / `queryIntentReceivers` 从方案里删掉**：前者在 framework.jar 0 命中，后者只在 `IPackageManager` 系；应用侧对应 `queryIntentContentProviders` / `queryContentProviders` / `resolveContentProvider` 与 `queryBroadcastReceivers`。
10. **P2 证据通道待定**（`docs/应用列表-API核对.md` §8）：钩子在应用 uid，写不了 `/data/system`；候选是"目标私有目录落盘 + App 用 su 归集"或"显式广播"，定案后要补一条 L15 例外，未定之前不写代码。
11. **ROM 特征包进 P2 判定层**（§5.5）：显式 `rom=yes` + 前缀 `org.lineageos.` + 两个精确名；排序在系统应用保护之前（唯一一次破例）。边界固定：不碰 `Build` 静态字段、不碰 native 属性读取、不碰 Key Attestation，对外只承诺"经 PackageManager 的包可见性被接管"。顺带记一条实测：**模块可以把自己注入自身进程**（用 `cli scope add <self>` 即可），列表面验证就是靠它当驱动器。
12. **`scope.list` 的 `staticScope` 教训**：`META-INF/xposed/module.prop` 写 `staticScope=true` 时 Vector 拒绝任何用户扩展作用域（`fixes its scope in module.prop`），且无任何报错，表现为"模块已启用但目标从不注入"。P2 这种"按目标应用生效"的形态必须 `staticScope=false`，规范 L6 已落档。
13. **P2 策略按目标分键**（0.5.3）：remote prefs group `p2`、键 `policy.<目标包名>`。不能共用一个 `policy` 键——一个模块一份 group，共用等于"给 A 配的名单也套到 B 上"。保存必须写当前 `bootId` 并**回读校验**（`apply` 异步，不信返回值）；钩子侧读取顺序 `policy.<pkg>` → 旧单键 `policy` → 目标私有目录文件。
14. **读别的应用私有目录必须 `su -M`**（0.5.4）：应用进程有自己的 mount namespace，里面看不到 `/data/user/0/<别的应用>`，于是 `su -c cat …` 变成"命令跑成功、`cat` 静默 `exit=1`"，页面显示成"读 marker 失败：exit=1"。读 marker/事件一律走全局挂载 ns，失败文案要区分"读不到目录"与"还没有 marker"。规范 L15 的例外条款随之下沉到 `Shell.suMount`。
15. **默认姿态改「只看基础」+ 基础集本机探测 + App 侧补集**（0.5.5）：`P2Store.Target` 默认 `mode=SHOW`；基础集由 `BaseSet.collect(pm, defaultImeId)` 现场探测（系统关键包 + 桌面 `ACTION_MAIN/CATEGORY_HOME` + 输入法 `queryIntentServices` + 当前默认输入法），不写死机型清单；空名单保存时自动补集并回读校验。钩子侧"空 `show` = 不启用 + 全放行"保持不变，两条规则各管一半。
16. **补救回路用事件聚合 + 一键放行**（0.5.5）：钩子把每个被挡的调用写成 `target=<被查包> act=nnf|iae`，App 侧 `P2Device.remedies(events)`（正则 `target=([^ ]+) act=([a-z]+)`，只认 `nnf/iae`，`count` 降序）聚合出"它想看谁 × 几次"，在目标编辑器里展示为可点 chip；点一下把该包放进暴露集合（`show` 加、`hide` 删），chip 变「✓ 已放行（未保存）」并提示"记得保存并下发"。事件读取从 `tail -n 5` 提到 `tail -n 200`，否则聚合样本太少。
17. **参数级预判不能只凭包名定案**（0.5.6，真机发现）：0.5.5 在 ClipBait 上验「只看基础」，`show=20 / hide=0 / module=0.5.5` 都符合预期，第三方（淘宝/微博/酷安/拼多多）全被 `nnf` 挡掉——但 `target=com.android.providers.settings`、`target=com.android.inputdevices` 也被挡了。原因是单点查询那一路 `argPackage()` 只拿得到包名字符串，`pkgUid=null`，于是 `uid<10000 → 放行` 这条保护规则轮不到。修法不是往基础集里塞更多系统包，而是**先 `proceed()` 拿真实返回对象、从对象里取 uid 再定案**（`pkgUidOf` 补 `ProviderInfo` 与 `ResolveInfo` 的 `applicationInfo.uid`）；`resolve*` 同样改成用返回对象里的 uid。判定层函数不变，单测不动。修后同机复验：`getInstalledApplicationsAsUser removed=220`、第三方仍 `nnf`、系统 provider 不再被挡。
18. **`requestScope` 必须有超时兜底**（0.5.7，真机发现）：编辑器里的「加入作用域（root）」在 Vector 2.2 上点了没反应——`svc.requestScope()` 被接受但两个回调一个都没来，`cliAdd` 这条兜底因此轮不到，界面停在「不在作用域」。改法：主线程 `Handler` 挂 3s 超时，超时就走 `vector-cli scope add`，回调到达则撤掉超时；`AtomicBoolean` 保证 `done` 只回一次。文案写明走的哪条通道（"框架批准"/"框架未回执（3s 超时），已改走 CLI"）。

**已定（2026-11-21）**

1. P0 第一条 = **Zig 进 system_server 的加载探针**，但表述修正为：先用 `onSystemServerStarting` 证明"框架告知我在 system_server"，再跑 `wc_engine_ping`（v2.1 的教训是"加载了但钩不上"，所以两步必须都过）。
2. 分发形态 = **一个 APK 同时兼任控制台与 LSPosed 模块**（原"控制台 + 模块两个包"作废）：配置属于模块包，独立控制台写不了 remote preferences。Magisk zip 只承载 Zig `.so` 与脚本。
3. 框架基线 = **libxposed API 101 / Vector ≥ 2.1**，不追 API 102 热重载（我们热更新的是规则，不是代码）。
4. 默认值 = **预置数据集**：剪贴板默认"空"，白名单只含当前输入法与系统框架；应用列表默认"预置可见集"（见附录 A.1），默认不隐藏任何应用。
5. 替身内容 = **自建词库**，先按附录 A.2 起头（每池 8–10 条），后续空闲再扩。
6. 审计 = **简版**：最近 200 条历史 + 按包名汇总的读取次数，不做"前台偷读比"。
7. 配置通道 = **remote preferences 为主**（目标应用不可见，待靶子实测确认）；文件通道只作兜底，落点见 §6 与 L17。
8. 判定层 = **Kotlin/JVM 纯函数**，native 只留 `ping`/`version` 哨兵（2026-11-22 修订，见 §3、§7）。

**仍开放的实现细节（P0 后定，不需要现在拍板）**

- 是否需要单独的"停用工具"（root 侧脚本），还是靠 App 写标记即可。
- 词库热更新走 remote preferences 变更通知还是"重启模块生效"。

**已关闭的开放项**：心跳与闸门文件落点已定 `/data/system`（`0600 system:system`，实测可写，L17）；
`/data/misc` 未测，不再候选。

### 13.1 A 阶段真机验收记录（2026-11-22，小米 13 / Vector 2.2 / NeoZygisk 2.4 / KernelSU 3.3.1）

| 验的东西 | 手法 | 结果（原始证据） |
|---|---|---|
| 钩子装得上 | App 写闸门 + 策略，`stop; start` | `p3_clip_hook.marker`：`armed=yes`、`policy=ok`、`hooked=5`、`class=com.android.server.clipboard.ClipboardService$ClipboardImpl`、`declared=17`、`diag=-` |
| 放行路径 | 弹一次输入法 | `uid=10189 pkg=com.google.android.inputmethod.latin m=CLIP mode=ALLOW`（`DESC` 同） |
| 默认空路径 | 起一次淘宝 / 抖音 | `uid=10181 pkg=com.taobao.taobao m=HAS mode=EMPTY`、`uid=10179 pkg=com.ss.android.ugc.aweme m=HAS mode=EMPTY` |
| 策略现读（不重载） | 同一进程内把 `default=empty` 改成 `default=allow`，再起淘宝 | 紧接着的审计变成 `m=HAS/CLIP mode=ALLOW`，**没有重启框架** |
| 停用即时生效 | `rm` 策略文件后起淘宝 | 审计行数不再增长（钩子在、读不到策略就放行，§8.3） |
| 替身路径 | 策略加 `mode.10181=fake` / `mode.10179=fake`、`pool=社交`，再起淘宝 | `m=HAS mode=FAKE`、`m=CLIP mode=FAKE` 连出 5 行；对照 EMPTY 时淘宝只问两次 `HAS` 就不再去 `CLIP` —— **返回值确实变了，不是只改了审计文案** |
| 开机清闸门（模块 v0.4.0 已生效） | `ksud module install` 后整机重启 | `boot.marker`：`fail=0 uptime=3.20`；`service.marker`：`armed=no fail=0 heartbeat=yes uptime=14.30`；`/data/system/watchcat.armed` **不存在**、策略仍在；`p3` marker：`armed=no`、`gateBootId=none`、`policy=ok`、`hooked=0` |
| 重新启用能回到在线 | 写回 armed（当前 bootId）+ 一次 `stop; start` | `armed=yes`、`policy=ok`、`hooked=5`、`gateBootId` 与当前 bootId 一致；随后 IME `ALLOW`、淘宝 `EMPTY` 照常出审计 |
| App 按钮链路 | 真机点「停用」→「启用」 | 停用后 `armed`/`policy` 两个文件都不在；启用后按 `ClipGate` 的格式重建：`default=empty`、`imeuid=10189`、`allow=10199`（把自己的 uid 列白，保证 App 自己粘贴不受影响）、`bootId=` 为当前 boot_id、`0600 system:system` |
| 诊断页 | 打开「数据面 · 剪贴板」卡片 | 读数与 marker 一致：「闸门：已启用（bootId 匹配）」「钩子：armed=yes · policy=ok · hooked=5 · methods=…」「失败计数：0 · 自动停用：未触发」「审计：N 条」 |

**踩到的两个坑**（已写成规范）：

1. 钩点类名猜错 → `hooked=0` 且无任何报错。五个读方法在**内层** `ClipboardService$ClipboardImpl`，外层 `ClipboardService` 一个都没有（见 §4.1）。现在 marker 用 `noclass` / `miss` / `fail` 把三种失败分开落盘。
2. `stop` 与 `start` 拆成两条执行会黑屏失联（L18.6）。必须一条命令连写，一次开机窗口只做一次。

**替身内容的核对口径（尚未做屏幕级核对，别当成已验）**：替身文本本身只做到"单测级"——`ClipPolicyTest` 覆盖了同 `(uid, bootId)` 稳定、6–40 字；真机上只验到"应用拿到的是非空 ClipData（因此继续 `CLIP`）"。想在屏幕上直接看到那条文本很难：淘宝的粘贴提示在 WebView 里，`uiautomator dump` 导不出来。真要验内容，得加一个自建的读取靶子（App 内"剪贴板自检"），那要再走一轮 CI。

### 13.2 P2 目标应用管理页真机验收（2026-11-22，0.5.3 → 0.5.4）

**页面结构**：`MainActivity` 四个页签——应用 / 目标 / 日志 / 诊断。目标页两张视图：列表（`三方应用` / `含系统应用` 两个筛 + `刷新`，`已配置` 打标）与编辑器（目标信息 + 作用域卡 + 策略卡 + 运行状态卡）。返回键在编辑器里回列表，不停在列表就退出。

**下发链路（现读现写，没有缓存）**：

| 步骤 | 实现 | 真机证据 |
|---|---|---|
| 读作用域 | `FrameworkState.snapshot()?.scope` | 编辑器直接显示"已在作用域：框架会在这个应用的所有进程里注入" |
| 加作用域 | `XposedService.requestScope(listOf(pkg), OnScopeEventListener)` 优先，失败落 `vector-cli scope add` | 按钮只在 `inScope==false` 时出现；回调文案区分"框架批准 / 框架拒绝·已改走 CLI" |
| 写策略 | `P2Store.save` → remote prefs `p2` / `policy.<pkg>`，`commit()` 后立刻回读 | 框架 DB `configs` 行：`('com.luckyme.watchcat', 0, 'p2', 'policy.com.taobao.taobao', …)`，内容 `version=1 / armed=yes / bootId=bbfa59ab… / mode=hide / hide=com.lerist.andvm,com.mfcloudcalculate.networkdisk / show= / rom=yes` |
| 重启目标 | `am force-stop` + `monkey -l` | 淘宝 marker：`armed=yes`、`policy=ok`、`mode=hide`、`rom=yes`、`hide=2`、`hooked=70`、`gateBootId` 与当前 bootId 一致、`app=0.5.3 (8)`、`pid=8032` |
| 回读注入 | `su -M cat` marker + `tail` 事件 | 0.5.3 上这里踩坑（见下），0.5.4 页面直读：`已注入：pid=14961 · 0.5.4 (9) · armed=yes · policy=ok · rom=yes · hooked=70` + 最近 3 条事件 |
| 调用级证据 | 名单换成淘宝真的会查的包（微信 / 支付宝）后重启 | `events.jsonl` 新增 `ts=1795358679… uid=10181 pid=13782 api=getPackageInfo target=com.tencent.mm act=nnf module=0.5.4`（支付宝同），即这条策略是**经 App 写 remote prefs → 钩子读 → 判定生效**，不是手写文件的旧路径 |

**本轮踩到并修掉的坑**：应用进程的 mount namespace 里没有 `/data/user/0/<别的应用>`，`su -c "cat …"` 会"命令跑成功、`cat` 静默 `exit=1`"，页面于是显示"读 marker 失败：exit=1"（同一秒 `run-as` 走 shell 的 ns 却能正常读，所以极容易误判成权限/SELinux）。0.5.4 把读取改成 `su -M -c`，并把失败文案拆成"读不到目标私有目录（su -M exit=N）"与"还没有 marker：目标没启动过或没进作用域"。

**仍未闭环的三点（如实记）**：

1. 远程 prefs 的更新对**已运行的注入进程**是否即时可见仍未实测（`RemotePreferences` 在目标侧是本地快照 + binder 更新），页面文案只承诺"首次生效必须冷启动"。
2. `requestScope`（框架回调那条正式加作用域路径）**一次都没在真机上走过**——本轮的 scope 全部由 `vector-cli` 兜底路径进入。另外该回调会把 Vector 的作用域选择页拉到前台（`topResumedActivity` 仍是本体 Activity），UI 自动化时会被误判成"页面跳飞"。
3. 页面顶部"作用域："那行只在进页/列表刷新时重读；从 CLI 或 Vector 侧改过 scope 后再进编辑器会看到旧值（本轮就出现了这个现象）。修法：`openEditor` 里顺手 `refreshStatus()`，留到下个包一起做。

**本轮顺手清掉的环境污染**：调试过程中 scope 被 Vector 的选择页扩到 22 个包（含淘宝/微信/支付宝等），已用 `cli scope set com.luckyme.watchcat system/0` 还原为只有 `system`；测试用的 `policy.com.taobao.taobao` 保留在 remote prefs 里（下次要用直接删策略）。


---

# 附录 A · 预置数据（首版）

> 这些是**默认值**，用户可整体替换、可单条增删。原则：像普通用户、不带敏感偏好、不含金融与政务类应用。

## A.1 应用列表预置可见集

**始终可见（系统面）**：`com.android.systemui`、`com.android.settings`、`com.android.shell`、`com.google.android.gms`（若存在）、桌面包（`com.google.android.apps.nexuslauncher` / `com.miui.home` 等按机型探测，不硬编码）。

**预置常用（默认"装了但只是普通应用"的观感）**：

| 类别 | 包名 |
|---|---|
| 社交 | `com.tencent.mm`、`com.tencent.mobileqq`、`com.sina.weibo` |
| 支付/电商 | `com.eg.android.AlipayGphone`、`com.taobao.taobao`、`com.jingdong.app.mall`、`com.xunmeng.pinduoduo` |
| 内容 | `com.ss.android.ugc.aweme`、`com.xingin.xhs`、`com.netease.cloudmusic`、`com.tencent.qqlive` |
| 出行/工具 | `com.autonavi.minimap`、`com.baidu.BaiduMap`、`com.alibaba.android.rimet`、`com.tencent.wework` |

**默认隐藏名单**：空。默认不做隐藏，用户显式勾选才生效（"宁少不多"的另一面：先不主动改变现状）。

**自定义**：按包名添加/删除，导入支持一行一个包名，导出只导出 `pkg` + 可选 `label`。

## A.2 剪贴板替身词库（起步）

每池 8–10 条，长度 6–40 字，全部为可公开复述的日常内容，不含账号、地址、金额等任何可能被误当真实隐私的字符串。

**购物池**（电商、比价类应用适用）
1. `米白色 210 码 加厚款`
2. `这个链接能便宜二十吗`
3. `顺丰到付可以吗`
4. `收货地址改到公司前台`
5. `有没有 32G 的版本`
6. `发票抬头开个人`
7. `预售什么时候发货`
8. `先加购物车，晚上再决定`
9. `这个和上次买的有什么区别`

**社交池**（IM、内容社区适用）
1. `晚上七点老地方见`
2. `文件我发群里了，记得看`
3. `这个周末我有空，你呢`
4. `刚才那个链接打不开了`
5. `明天上午十点开会`
6. `照片原图发我一份`
7. `你说的那家店叫什么`
8. `路上堵，晚到十分钟`
9. `收到，我处理一下`

**工具池**（效率、笔记、翻译类适用）
1. `2026-11-21 14:30 项目周会`
2. `待办：续费域名、备份相册`
3. `端口 8080 被占用了`
4. `把这段转成 Markdown`
5. `订单号末四位 4471`
6. `周二之前给回复`
7. `这条先记下来，回头再看`
8. `算一下三个人平摊多少`
9. `明天记得带身份证`

**选取规则**：`(uid, boot_id)` 做种子，**同一应用在同一次开机内始终给同一条**（来回变容易被判定异常），跨开机可换。每个池首位留给"看起来最普通"的那条。

## A.3 默认策略表（首版）

| 目标 | 默认 | 说明 |
|---|---|---|
| 输入法、系统框架、WatchCat 自身 | 放行 | 自伤最小化，剪贴板必须放行输入法 |
| 其余全部应用 · 剪贴板 | 空 | 用户可在详情页逐应用切"放行/替身/阻断" |
| 其余全部应用 · 应用列表 | 预置可见集 | 默认不隐藏；需要隐藏时按应用勾选 |
| 新装应用 | 剪贴板空、列表预置集 | 与"隐私默认少给"一致 |

---

# 附录 B · v0.2 目录形态（意向，P0 建立）

```text
E:\codes\WatchCat\
├─ app\                         唯一 APK：控制台 + LSPosed 模块（同一个包名）
│  └─ src\main\java\...\watchcat\
│     ├─ Ui.kt  AppsScreen.kt  MainActivity.kt        ← 复用
│     ├─ DetailScreen.kt  LogScreen.kt  DiagScreen.kt ← 加"数据面"一节/一块
│     ├─ Engine.kt  Shell.kt                          ← 权限面，原样保留
│     ├─ policy\                                      ← 新增：策略编辑 / 导入导出 / remote preferences 读写
│     └─ xposed\                                      ← 新增：极简 hook 入口（零 UI 依赖、零静态副作用、不引用上面任何 UI 类）
│  └─ src\main\resources\META-INF\xposed\             ← 新增：modern API 的模块描述（取代 manifest 元数据）
├─ module\                        Magisk 模块包（新增；只放 native 与脚本）
│  ├─ module.prop  post-fs-data.sh  service.sh
│  └─ libs\arm64-v8a\libwatchcat.so                   ← 由 Zygisk 加载
├─ zig\                           同一份源码，两个编译目标
│  ├─ src\watchcat.zig            ← 权限面导出（现有 8 个符号）
│  └─ src\engine.zig              ← 数据面临界符号：wc_engine_ping / wc_engine_version（判定不在这里）
├─ tools\build_so.py              编译 + 16KB 门禁（复用，加 engine 目标）
├─ docs\hook方案-v0.2.md          本文件
└─ .github\workflows\build.yml    一条流水线：APK（含模块 dex）+ 模块 zip（含 .so）
```

CI 的顺序：`assembleDebug` → `.so` 16KB 校验（**引擎与模块 lib 都算**）→ `zig test`（含 engine 单测）→ 打 `watchcat-module.zip` → 两个 artifact 成对上传。任一环失败即整条红，不允许"APK 绿、模块红"这种半成品出厂。

依赖锁定：`libxposed` 的 module API / service API 以 submodule 引入，**固定到与 Vector stable 相同的 commit**，不用 `master`。
