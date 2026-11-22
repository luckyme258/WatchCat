package com.luckyme.watchcat.xposed;

import android.os.Process;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

import com.luckyme.watchcat.BuildConfig;

/**
 * P1 框架接入：只证明「模块被 Vector 加载、生命周期入口可观测」，不注册任何 hook。
 *
 * 证据通道是 2026-11-22 在小米 13（Android 17 / KernelSU 3.3.1 / NeoZygisk 2.4 / Vector 2.2）
 * 上一条条试出来的，不要再退回别的判据：
 *
 *   1. 日志不是证据。框架的 log() 写进 /data/adb/lspd/log，android.util.Log 在本机
 *      system_server 里 logcat 也抓不到；两条通道同时沉默，会把「代码没跑」和
 *      「日志没落盘」混在一起——上一轮就是在这里误判成「回调没被调用」。
 *   2. /data/adb/watchcat 不是证据。它是 0700 root:root，system_server（uid 1000）
 *      连 DAC 都过不去，写失败和回调没跑长得一模一样。
 *   3. 唯一可靠的通道是落盘到 /data/system：同机的 ClipBox（legacy 模块，同样跑在
 *      system_server 里）能写 /data/system/clipbox_config.json，这是现成的权限证明。
 *
 * 约束（L2/L3/L7/L10）：不引用 UI/资源类，不拿 Context，不做有副作用的静态初始化，
 * 不在 app 进程做任何动作；写不进去只吞掉异常，不重试，不影响开机。
 */
public final class WatchCatModule extends XposedModule {
    private static final String TAG = "WatchCat";
    private static final String STATE_DIR = "/data/adb/watchcat";
    private static final String MARKER_DIR = "/data/system";

    public WatchCatModule() {
        // 构造函数保持无副作用：L2/L7 不让模块对象在 attach 之前做任何事。
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            log(Log.INFO, TAG, "p1: module loaded process=" + param.getProcessName()
                    + " system=" + param.isSystemServer()
                    + " framework=" + getFrameworkName()
                    + " version=" + getFrameworkVersion()
                    + " versionCode=" + getFrameworkVersionCode()
                    + " api=" + getApiVersion());
            log(Log.INFO, TAG, "p1: caps system=" + hasCap(XposedInterface.PROP_CAP_SYSTEM)
                    + " remote=" + hasCap(XposedInterface.PROP_CAP_REMOTE)
                    + " apiProtection=" + hasCap(XposedInterface.PROP_RT_API_PROTECTION));
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "p1: onModuleLoaded failed", t);
        }
        writeMarker("p1_module_loaded",
                "process=" + param.getProcessName()
                        + "\nsystem=" + param.isSystemServer()
                        + "\nframework=" + getFrameworkName()
                        + "\nframeworkVersion=" + getFrameworkVersion()
                        + "\nframeworkVersionCode=" + getFrameworkVersionCode()
                        + "\napi=" + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            log(Log.INFO, TAG, "p1: onSystemServerStarting pid=" + Process.myPid()
                    + " classLoader=" + param.getClassLoader()
                    + " api=" + getApiVersion());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "p1: onSystemServerStarting failed", t);
        }
        writeMarker("p1_system_server",
                "classLoader=" + param.getClassLoader()
                        + "\napi=" + getApiVersion()
                        + "\nframework=" + getFrameworkName());

        // L10.2：启动后 90 秒内必须有一次心跳。这里是最早的可靠时机，够用且不必起线程
        // （hook 侧零常驻状态，起线程就要管生命周期，属于自找的复杂度）。
        writeHeartbeat();

        // A 阶段：剪贴板读方向的钩子。默认不挂（armed 不存在或 bootId 对不上），
        // 但不论挂没挂都会写 /data/system/watchcat.p3_clip_hook.marker（L17.2.1）。
        try {
            ClipboardHook.install(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "p3: clipboard hook install failed", t);
        }
    }

    private boolean hasCap(long prop) {
        return (getFrameworkProperties() & prop) != 0;
    }

    /**
     * 把一段 ASCII 证据写到 /data/system/watchcat.&lt;stage&gt;.marker。
     *
     * 首选 /data/system（system_server 可写）；/data/adb/watchcat 只作回退，那里通常
     * 打不开，留着一是为了将来把目录权限改成 0771 root:system 时能继续用。
     * 两个都失败就静默放弃——这是诊断探针，不是功能。
     */
    private void writeMarker(String stage, String body) {
        writeTo(stage, "watchcat." + stage + ".marker", stage + ".marker", body);
    }

    /**
     * 心跳：L10.3 的「连续两次无心跳自动停用」靠它，root 侧 post-fs-data.sh 会在下一次开机时
     * 拿这里写的 bootId 结算上一轮。所以 bootId 必须写对，不能省。
     *
     * 已知缺口：L10.2 要求「心跳 + 自检（引擎 ping）两项都成功才算这一轮活下来」，而自检那一半
     * 在 Java 侧做不到——native 由 Zygisk 加载，Java 拿不到它的句柄。引擎是否活着单独由
     * entry.cpp 的探针写 /data/system/watchcat.p0.probe 证明。所以这里的心跳只能证明
     * 「框架回调执行了」，两项要合起来读。
     */
    private void writeHeartbeat() {
        String body = "bootId=" + readBootId()
                + "\nframework=" + getFrameworkName()
                + "\nframeworkVersion=" + getFrameworkVersion()
                + "\napi=" + getApiVersion();
        writeTo("heartbeat", "watchcat.heartbeat", "heartbeat", body);
    }

    private void writeTo(String stage, String systemName, String stateName, String body) {
        final String content = "stage=" + stage
                + "\n" + body
                + "\napp=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + "\npid=" + Process.myPid()
                + "\ntimeMs=" + System.currentTimeMillis()
                + "\n";
        for (String path : new String[]{
                MARKER_DIR + "/" + systemName,
                STATE_DIR + "/" + stateName,
        }) {
            try (FileOutputStream out = new FileOutputStream(path, false)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                return;
            } catch (Throwable ignored) {
                // 只尝试下一个落点，不记录、不重试。
            }
        }
    }

    /** 读本次开机的 boot_id。读不到就写 unknown——脚本那边匹配不上，会算一次失败，这是可接受的保守方向。 */
    private static String readBootId() {
        try (FileInputStream in = new FileInputStream("/proc/sys/kernel/random/boot_id")) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n > 0) {
                return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            }
        } catch (Throwable ignored) {
            // 落到下面的 unknown
        }
        return "unknown";
    }
}
