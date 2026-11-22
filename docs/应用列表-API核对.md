# 应用列表 · Android 17 API 核对（P2 第一阶段）

> 取证时间：2026-11-22 · 设备：小米 13 `2211133C`（fuxi / Android 17 / SDK 37 / arm64-v8a）
> 框架：KernelSU 3.3.1 + NeoZygisk 2.4 + Vector 2.2（frameworkVersionCode 3119 / api 102）
> 取证对象：`/system/framework/framework.jar`（44,746,157 B）解出的 `classes.dex`…`classes5.dex`，`dexdump -n` 全量导出
> 本文只核对事实与实现约束，不含代码。

---

## 1. 一句话结论

**应用进程里只有一个真实现：`android.app.ApplicationPackageManager`。** `android.content.pm.PackageManager` 只是抽象契约加少量过渡桥（桥最终仍落回 `ApplicationPackageManager` 的抽象实现）。所以"四个面"必须在 `ApplicationPackageManager` 上做，且**不能写死签名**——Android 17 上每个方法几乎都有 `int flags` 与 `*Flags` 对象两套重载。

同时，`docs/hook方案-v0.2.md` §5.2 原表里有**两个名字在 Android 17 上不存在**：

- `queryIntentProviders`：整个 framework.jar **0 命中**，真实名字是 `queryIntentContentProviders` / `queryContentProviders` / `resolveContentProvider`。
- `queryIntentReceivers`：只存在于 `IPackageManager` / `IPackageManager$Stub` / `IPackageManager$Default`（系统侧 AIDL），应用侧已由 `queryBroadcastReceivers` 取代。

---

## 2. 类结构事实（dex 证据）

| 事实 | 证据 |
|---|---|
| `ApplicationPackageManager` 只存在于 `classes.dex` | `dexdump -n` 全量导出后，`classes.dex` 命中 529 处；`classes2..5` 均为 0 |
| 全框架只有它一个 `PackageManager` 子类 | 全文件搜 `Superclass        : 'Landroid/content/pm/PackageManager;'` **仅 1 处**（169199 行） |
| `ApplicationPackageManager` 类段 | descriptor 在 `classes_names.txt` **169197 行**，到 **173811 行**（`source_file_idx … ApplicationPackageManager.java`），共 **363 个方法 / 29 个字段** |
| `PackageManager`（抽象契约）类段 | 162950–169194 行；核心方法 `access : 0x0401 (PUBLIC ABSTRACT)`、`code : (none)`；少量过渡桥（如 `getLaunchIntentForPackage(String,boolean)`、`queryBroadcastReceivers(Intent,int,int)`）是 `PUBLIC` 带实现，内部仍转调抽象方法 |
| 内部类不参与过滤 | 161655–162841 是 `$$ExternalSyntheticLambda0/1/2`、`$1`–`$4`、`$DexModule…`、`$GetPackagesForUidResult`、`$IntentActivitiesQuery`、`$MoveCallbackDelegate`、`$ResourceName` 等，均不是入口 |

> 注意：166797–168882、171290–173370 这两段在原始 txt 里看起来"像 APM"，实际分别属于 `PackageManager` 与 `ApplicationPackageManager`。670100–676996 段是 `IPackageManager` / `$Stub` / `$Default`，**不是应用侧入口**。

---

## 3. 四个面 → 方法族（Android 17 真实签名）

原则：**按方法名族钩，签名在运行时枚举**（见 §5.1）。下表是"应当被覆盖的方法名集合"，括号内是重载数。

### 3.1 列表面（2 个名字 / 8 个重载）

| 方法 | 签名 |
|---|---|
| `getInstalledPackages` | `(I)Ljava/util/List;` / `(Landroid/content/pm/PackageManager$PackageInfoFlags;)Ljava/util/List;` |
| `getInstalledPackagesAsUser` | `(II)Ljava/util/List;` / `(Landroid/content/pm/PackageManager$PackageInfoFlags;I)Ljava/util/List;` |
| `getInstalledApplications` | `(I)Ljava/util/List;` / `(Landroid/content/pm/PackageManager$ApplicationInfoFlags;)Ljava/util/List;` |
| `getInstalledApplicationsAsUser` | `(II)Ljava/util/List;` / `(Landroid/content/pm/PackageManager$ApplicationInfoFlags;I)Ljava/util/List;` |

### 3.2 单点面（10 个名字 / 20 个重载）

| 方法 | 签名 |
|---|---|
| `getPackageInfo` | `(String,int)` / `(String,PackageInfoFlags)` / `(VersionedPackage,int)` / `(VersionedPackage,PackageInfoFlags)` |
| `getPackageInfoAsUser` | `(String,int,int)` / `(String,PackageInfoFlags,int)` |
| `getApplicationInfo` | `(String,int)` / `(String,ApplicationInfoFlags)` |
| `getApplicationInfoAsUser` | `(String,int,int)` / `(String,ApplicationInfoFlags,int)` |
| `getPackageUid` | `(String,int)` / `(String,PackageInfoFlags)` |
| `getPackageUidAsUser` | `(String,int)` / `(String,int,int)` / `(String,PackageInfoFlags,int)` |
| `getApplicationEnabledSetting` | `(String)I` |
| `getComponentEnabledSetting` | `(ComponentName)I` |
| `getInstallerPackageName` | `(String)String` |
| `getApplicationLabel` | `(ApplicationInfo)CharSequence` |
| `getLaunchIntentForPackage` | `(String)Intent` / `(String,boolean)Intent` |
| `getLaunchIntentSenderForPackage` | `(String)IntentSender` |
| `getLeanbackLaunchIntentForPackage` | `(String)Intent` |
| `getSharedLibraries` | `(int)List` / `(PackageInfoFlags)List` |
| `getSharedLibrariesAsUser` | `(int,int)List` / `(PackageInfoFlags,int)List` |
| `getPreferredActivities` | `(List,List,String)int` |

### 3.3 Intent / 组件面（10 个名字 / 26 个重载）

| 方法 | 签名 |
|---|---|
| `queryIntentActivities` | `(Intent,int)` / `(Intent,ResolveInfoFlags)` |
| `queryIntentActivitiesAsUser` | `(Intent,int,int)` / `(Intent,ResolveInfoFlags,int)` |
| `queryIntentServices` | `(Intent,int)` / `(Intent,ResolveInfoFlags)` |
| `queryIntentServicesAsUser` | `(Intent,int,int)` / `(Intent,ResolveInfoFlags,int)` |
| `queryIntentContentProviders` | `(Intent,int)` / `(Intent,ResolveInfoFlags)` |
| `queryIntentContentProvidersAsUser` | `(Intent,int,int)` / `(Intent,ResolveInfoFlags,int)` |
| `queryBroadcastReceivers` | `(Intent,int)` / `(Intent,ResolveInfoFlags)` |
| `queryBroadcastReceiversAsUser` | `(Intent,int,int)` / `(Intent,ResolveInfoFlags,int)` |
| `queryIntentActivityOptions` | `(ComponentName,List,Intent,ResolveInfoFlags)` / `(ComponentName,Intent[],Intent,int)` |
| `queryContentProviders` | `(String,int,int)` / `(String,int,int,String)` / `(String,int,ComponentInfoFlags)` / `(String,int,ComponentInfoFlags,String)` |
| `resolveActivity` | `(Intent,int)` / `(Intent,ResolveInfoFlags)` |
| `resolveActivityAsUser` | `(Intent,int,int)` / `(Intent,ResolveInfoFlags,int)` / `(Intent,String,int,int)` / `(Intent,String,ResolveInfoFlags,int)` |
| `resolveService` | `(Intent,int)` / `(Intent,ResolveInfoFlags)` |
| `resolveServiceAsUser` | `(Intent,int,int)` / `(Intent,ResolveInfoFlags,int)` |
| `resolveContentProvider` | `(String,int)` / `(String,ComponentInfoFlags)` |
| `resolveContentProviderAsUser` | `(String,int,int)` / `(String,ComponentInfoFlags,int)` |
| `resolveContentProviderForUid` | `(String,ComponentInfoFlags,int)` |

> `AsUser` 变体普通应用一般调不到（要 `INTERACT_ACROSS_USERS` 系权限），但**一条命名族规则就全包住**，成本为零，别省。

### 3.4 跨 uid 面（2 个名字 / 2 个重载）

| 方法 | 签名 | 说明 |
|---|---|---|
| `getPackagesForUid` | `(int)[String` | 返回该 uid 下所有包名；隐藏其中一个会与"同 uid 兄弟"矛盾（见 §5.4） |
| `getNamesForUids` | `([int)[String` | 数组按 uid 一一对应，隐藏项置 `null`（不能整段丢，丢位会让调用方错位） |

### 3.5 明确不纳入本期

`getInstalledModules`（dex 模块，不是"应用"）、`queryInstrumentation`、`getPackageArchiveInfo`（读 APK 文件，不反映安装态）、`queryPermissionsByGroup`、`query*Property`（`queryActivityProperty` / `queryApplicationProperty` / `queryProviderProperty` / `queryReceiverProperty` / `queryServiceProperty`，返回的是属性值不是包清单）。

---

## 4. 契约：被隐藏的包在各方法上应当"表现成什么样"

**这一列不是风格问题，写错就是崩溃或自相矛盾。**

| 方法族 | 隐藏时（**2026-11-22 真机实测已定，见 §4.1**） |
|---|---|
| `getInstalledPackages` / `getInstalledApplications`（含 AsUser） | 从结果列表剔除；返回空列表**不返回 null** |
| `getPackageInfo` / `getPackageInfoAsUser` | 抛 `PackageManager.NameNotFoundException`（实测） |
| `getApplicationInfo` / `getApplicationInfoAsUser` | 抛 `PackageManager.NameNotFoundException`（实测） |
| `getPackageUid` / `getPackageUidAsUser` | 抛 `PackageManager.NameNotFoundException`（实测） |
| `getApplicationEnabledSetting` | 抛 `IllegalArgumentException: Unknown package: <pkg>`（实测，**不是返回 DEFAULT**） |
| `getComponentEnabledSetting` | 抛 `IllegalArgumentException: Unknown component: ComponentInfo{…}`（实测） |
| `getInstallerPackageName` | **抛 `IllegalArgumentException: Unknown package: <pkg>`**（实测；原以为返回 null，写错就是崩溃） |
| `getLaunchIntentForPackage` / `getLeanbackLaunchIntentForPackage` / `getLaunchIntentSenderForPackage` | `null`（实测前两者） |
| `queryIntentActivities` / `Services` / `ContentProviders` / `BroadcastReceivers` / `IntentActivityOptions` / `queryContentProviders` | 剔除；返回空列表，不返回 null（实测 `size=0`） |
| `resolveActivity` / `resolveService` / `resolveContentProvider`（含 AsUser） | `null`（实测 `resolveActivity`） |
| `getPackagesForUid` | 目标 uid 不存在时 `null`（实测）；uid 存在但包被隐藏时剔除该名字，同 uid 全隐才隐（§5.4） |
| `getNamesForUids` | 对应下标置 `null`，不缩数组（实测 `[99999,10173] → [null,"io.github.pgntgz.clipbait"]`） |
| `getSharedLibraries` / `AsUser` | 剔除（`SharedLibraryInfo#getDeclaringPackage` 的包名） |
| `getPreferredActivities` | 两个输出列表（`IntentFilter` / `ComponentName`）都要剔，并把返回计数相应减掉 |

过滤取"包名"的字段：`PackageInfo.packageName`、`ApplicationInfo.packageName`、`ResolveInfo.activityInfo/serviceInfo/providerInfo.packageName`（按面取，不要一律读 `resolveInfo.activityInfo`——receiver/provider 的 `activityInfo` 是 null）、`ProviderInfo.packageName`、`SharedLibraryInfo.getDeclaringPackage().getName()`。

### 4.1 实测原始输出（2026-11-22，小米 13 / SDK 37，`app_process` 以 uid 2000 运行）

探针零侵入：`javac` 编译 → `d8` 出 `classes.dex` → `adb push /data/local/tmp/probe.dex` → `CLASSPATH=/data/local/tmp/probe.dex app_process /system/bin Main`。**不装 APK、不改任何靶子、不重启框架。**

```
ctx ok, pm=android.app.ApplicationPackageManager
uid=2000 sdk=37
getInstalledPackages(0) -> size=258
getPackageInfo(absent,0) -> THREW android.content.pm.PackageManager$NameNotFoundException : com.watchcat.absent.probe.zzz
getApplicationInfo(absent,0) -> THREW android.content.pm.PackageManager$NameNotFoundException : com.watchcat.absent.probe.zzz
getPackageUid(absent,0) -> THREW android.content.pm.PackageManager$NameNotFoundException : com.watchcat.absent.probe.zzz
getApplicationEnabledSetting(absent) -> THREW java.lang.IllegalArgumentException : Unknown package: com.watchcat.absent.probe.zzz
getComponentEnabledSetting(absent) -> THREW java.lang.IllegalArgumentException : Unknown component: ComponentInfo{com.watchcat.absent.probe.zzz/com.watchcat.absent.probe.zzz.MainActivity}
getInstallerPackageName(absent) -> THREW java.lang.IllegalArgumentException : Unknown package: com.watchcat.absent.probe.zzz
getLaunchIntentForPackage(absent) -> NULL
getLeanbackLaunchIntentForPackage(absent) -> NULL
queryIntentActivities(pkg=absent) -> size=0
resolveActivity(pkg=absent) -> NULL
getPackagesForUid(99999) -> NULL
getNamesForUids -> null|io.github.pgntgz.clipbait|
getInstallerPackageName(taobao) -> mark.via
```

口径说明：本探针跑在 **uid 2000（shell）**，`getInstalledPackages` 因此看到 258 个包。上面的"存在性/抛错形态"与 uid 无关，可直接采用；**凡涉及"看得见哪些包"的结论，必须再用应用 uid 复验**（`<queries>` 交集那类问题不能拿 shell 的可见集当答案）。

---

## 5. 实现约束（写代码前必须定的五条）

### 5.1 按"族"钩，签名运行时枚举

Android 17 的重载是"旧 int + 新 Flags 对象"并存，且不同 ROM/版本还会有增减。所以：

1. `lpparam.classLoader.loadClass("android.app.ApplicationPackageManager")`；
2. `getDeclaredMethods()` 按名字集合过滤，跳过 `static`；
3. 逐个 `XposedBridge.hookMethod`，一个方法一个 `XC_MethodHook`。

好处：新增/缺失重载自动适配；不依赖 `dexdump` 的具体签名；与剪贴板"按族钩、不按签名钩"（§4.2）同一套口径。
待实测：`getDeclaredMethods()` 在 Vector 2.2 下是否会触发 hidden API 限制（预期不会，模块代码默认放行，但没有真机证据就不算成立）。

### 5.2 判定一个函数，注入面只做适配

`PkgPolicy.decide(callerUid, pkgName) → VISIBLE | HIDDEN`，纯 Kotlin、无 android 依赖、可 JVM 单测（沿用 `ClipPolicy` 的形态：`parse / decide` + 表驱动用例）。注入面里**不允许出现第二套判定逻辑**，四个面只是"把同一个答案翻译成该方法的合法返回形态"。

### 5.3 未知一律可见（fail-open）

策略缺失、解析失败、包名不在名单 → 可见。与 L10.4 一致：宁可漏，不可把应用搞崩。异常只能在 hook 内部吞掉并记一次失败，不能把异常抛给被钩方法。

### 5.4 自身与同 uid 的硬约束

- **绝不能隐藏调用方自己的包**（`callerUid == pkgUid` 直接放行），否则应用自检第一步就自杀。
- **同 uid 的包必须全显或全隐**：`getPackagesForUid(uid)` 一次返回该 uid 全部包名，半隐半显会出现"列表里有 A、`getPackageInfo(B)` 抛错、但 `getPackagesForUid` 又说 B 在"的三方矛盾——这正是 §5.3 自洽原则禁止的形态。名单里出现 shared uid 时，以"整组"为单位判定。

### 5.5 策略读取与刷新

剪贴板侧的"策略现读"已实测成立（改文件后不重启即生效）。应用侧要付的代价是**每个被注入进程一份策略快照**：进程启动读一次，命中审计时比对 mtime/版本号再决定是否重读，重读失败保留旧快照。不允许每次调用都读文件（列表 API 在启动路径上被高频调用）。

---

## 6. 旁路清单（本期不做，但必须写清为什么）

| 通道 | 本期是否覆盖 | 事实与理由 |
|---|---|---|
| `pm list packages` / `dumpsys package` | **不覆盖** | 跑在 `com.android.shell`（uid 2000）或 system_server，与应用内注入完全无关。隐藏对"外部检查"无效，这是设计事实不是 bug |
| `Runtime.exec("pm …")`（应用内起 `app_process`） | **不覆盖·待实测** | 同 uid 但**另一个进程**，走 `IPackageManager`  binder，绕过 `ApplicationPackageManager`。需要真机确认普通应用 uid 下 `pm list packages` 是否成功、返回是否受 `<queries>` 限制 |
| `LauncherApps.getActivityList` | 不覆盖 | 系统服务侧查询，按包名直查，不受应用内 hook 影响；普通应用可用 |
| `UsageStatsManager.queryUsageStats` | 不覆盖 | 需 `PACKAGE_USAGE_STATS`（用户授权）后才拿得到，属"行为面" |
| `ActivityManager.getRunningAppProcesses` | 不覆盖 | 普通应用只能看到自己 |
| `AccessibilityService` 窗口包名 | 不覆盖 | 属"行为面"，且需用户开无障碍 |
| 反射拿 `IPackageManager` 直连 | 不覆盖 | hidden API 拦截 + 权限；模块代码能过，目标应用不能 |
| `PackageInstaller` / `getInstalledModules` | 不覆盖 | 不是"已安装应用清单"语义 |

二期要补的顺序：`LauncherApps` → `Runtime.exec("pm")`（若实测成立则优先级最高）→ `UsageStats`。

---

## 7. `<queries>`：隐藏必须落在"本来就可见"的集合里

Android 11 起，应用能看到的包 = 系统强制可见集（forceQueryable / 同签名 / 交互过的）∪ manifest `<queries>` 声明的集合。实测：

- 淘宝（`com.taobao.taobao`）：`dumpsys package` 的 `Queries:` 段列出 **60+ 个** by-package-name / by-component 目标（含微信、支付宝、抖音、快手、小红书系、Termux、WatchCat 自己），另有 `queryable via interaction` 的强制集。
- ClipBait（`io.github.pgntgz.clipbait`）：`system apps queryable: false`，`queries via package name:` / `via component:` 均为空，只有别人反向声明它。

两条直接结论：

1. **隐藏一个本来不在可见集里的包 = 空操作**（系统压根不会返回它，hook 也拿不到它）。
2. **不能为被隐藏的包伪造可见**——列表/单点/Intent 三面必须取同一个答案；一旦 hook 让某个包"在 Intent 里出现但 `getPackageInfo` 抛错"，这就是最典型的自相矛盾特征。

因此名单生效前应做一次"可见集交集"：`list∩queries` 才是真正能隐藏的对象，其余记为"no-op"并在诊断页写明（否则用户会以为没生效）。

---

## 8. 证据通道冲突（必须先定案）

P1 剪贴板的钩点在 **system_server（uid 1000）**，所以能写 `/data/system/watchcat.*`（0600 system:system），L15.1 的"只认 `/data/system` 落盘"天然成立。

P2 应用列表的钩点在 **目标应用进程（应用 uid）**：

- 写不了 `/data/system`（DAC 挡住，uid 1000 才能写）；
- 写不了 `/data/adb`（0700 root:root）；
- `/data/local/tmp` 是 `shell:shell 0771`，应用 uid 也写不进；
- 目标应用自己的 `/data/data/<target>/files/` **可写**，但那是"被管对象的私有目录"。

**已定：方案 A（2026-11-22 用户批准），条款落 `LSP模块2026开发规范.md` L15.2。**

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（已采用）** | 注入侧只写 `<target>/files/watchcat.p2.jsonl`（0600 target uid，一行一条聚合），WatchCat App 用 su **批量**归集到 `/data/system/watchcat.p2.events`（0600 system:system），并校验目录 uid ↔ 包名一致 | 已在 L15.2 立条款：禁止逐条 su；判据仍是归集后的 `/data/system` 文件 |
| B（弃） | 注入侧发显式广播给 WatchCat，由 App 落盘 | 广播可被同 uid 伪造，证据强度低于文件；还会让被管应用看到一条异常广播 |
| C（弃） | 不落盘，只在目标进程内存计数，靠诊断页主动探针取 | 需要被管应用里有自检入口（等于要求用户装靶子），不能作为常规验收通道 |

---

## 9. 与 §5.2 原表的差异（要改的文档）

| 原文 | 实际（Android 17） |
|---|---|
| `queryIntentProviders` | 不存在；应为 `queryIntentContentProviders` / `queryContentProviders` / `resolveContentProvider`（含 AsUser） |
| `queryIntentReceivers` | 应用侧不存在；应为 `queryBroadcastReceivers`（含 AsUser）。`queryIntentReceivers` 只在 `IPackageManager` 系 |
| 列表/单点各只列 1 个方法 | 单点实际 10 个名字 20 个重载；列表面 4 个名字 8 个重载 |
| 只写"含 13+ 的 `ResolveInfoFlags` 重载" | 还有 `PackageInfoFlags` / `ApplicationInfoFlags` / `ComponentInfoFlags`；且 `getPackageInfo` 有 `VersionedPackage` 重载 |
| 未提 provider 面 | `resolveContentProvider` / `queryContentProviders` 会直接暴露包名，必须纳入 Intent/组件面 |
| 未提 `getLaunchIntent*` / `getInstallerPackageName` / `getApplicationEnabledSetting` / `getSharedLibraries` / `getPreferredActivities` | 都是"单点探测"的常见用法，必须纳入单点面 |

---

## 10. 待实测（不实测不进验收表）

**已实测（2026-11-22，`app_process` 探针，原始输出见 §4.1）**：

- ✔ 契约全表：`NameNotFoundException`（info/uid 类）、`IllegalArgumentException`（enabled/installer 类）、`null`（launch/resolve）、`size=0`（query）、`null` 数组元素（`getNamesForUids`）、未知 uid 返回 `null`（`getPackagesForUid`）。

**已实测（2026-11-22 21:25，P2 钩子在小米 13 / Android 17 / Vector 2.2 上首次闭环）**：

- ✔ 作用域：模块 `META-INF/xposed/module.prop` 原写 `staticScope=true`，Vector CLI 拒绝扩作用域（`fixes its scope in module.prop ... It claims: system.`）；改 `staticScope=false` 后 `cli scope add com.luckyme.watchcat com.taobao.taobao/0` 成功，`scope ls` 出现目标。**这是"模块启用但目标从不注入"的静默失败源**，已写入规范 L6。
- ✔ 注入：0.5.1 冷启动淘宝后 `files/watchcat.p2.marker` = `armed=yes policy=ok hooked=70 declared=361 diag=- mode=hide hide=2 selfPkg=com.taobao.taobao selfUid=10181`，36 个名字族一个不少（`diag=-` 表示无缺失、无失败）。
- ✔ 契约在真实业务路径上成立：策略 `hide=com.tencent.mm,com.eg.android.AlipayGphone` 下，淘宝启动即产生 3 条 `act=nnf` 事件（`api=getPackageInfo target=com.tencent.mm / com.eg.android.AlipayGphone`），说明应用侧真实现走 `ApplicationPackageManager`、且我们的抛错实现被它正常接收（无崩溃）。
- ✔ 多进程：同一次冷启动出现 pid 20144 与 21242 两个注入进程，marker 被后写覆盖（规范 L15.2 第 6 条）。
- ✘ 列表面过滤（`act=filter`）在淘宝启动窗口内 0 次触发：它启动期只做单点 `getPackageInfo`，没调 `getInstalledPackages`/`queryIntentActivities`。列表面要用 `getInstalledApplications` 驱动的靶子（WatchCat 自己的 `AppsScreen`）单独验。

**仍未实测**：

1. `Runtime.exec("pm list packages")` 的旁路半边已确认：`run-as com.luckyme.watchcat` 以 uid 10199 成功执行 `pm list packages`，得 **258** 条，与 shell 的 258 一致（意外结论：**带 `QUERY_ALL_PACKAGES` 的应用可见集与 shell 相同**，L15.3 那句"shell 不能当应用答案"只对无该权限的应用成立）。仍缺"该结果是否绕过应用内钩子"的实测——结构上必然绕过（另一个进程、不经 `ApplicationPackageManager`），待 P2 验收时补一条记录。
2. `getDeclaredMethods()` + `hookMethod` 在 Vector 2.2 / API 102 下不需 hidden API 放行（实测 70 个钩子全部装上，可视为已答）。
3. `*Flags` 重载是否内部转调 `int` 重载（只影响性能，不影响正确性——按族枚举已兜住）。
4. 同 uid 半隐的实际后果（用两个同 uid 靶子包验证"整组判定"是否必要）。
5. 无 `QUERY_ALL_PACKAGES` 的应用 uid 可见集，与 `<queries>` 取交集后的真实隐藏对象。

---

## 11. 结论（对下一步）

事实层与契约层都已落地：

1. **证据通道**已定 A 并写成 L15.2（目标私有目录落盘 + App 批量归集）；
2. **抛错契约**已真机测完（含原以为返回 `null`、实际抛 `IllegalArgumentException` 的 `getInstallerPackageName`）；
3. **P2 钩子已在真机闭环到"单点面"**：注入成功、契约成立、证据落盘、审计可读，验收只差列表面（`getInstalledPackages`/`queryIntentActivities` 的剔除）与旁路记录。
