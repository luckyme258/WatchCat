package com.luckyme.watchcat.xposed;

import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * P1 框架接入：只证明「模块被 Vector 加载、生命周期入口可观测」，不注册任何 hook。
 *
 * 约束（L2/L3/L7/L10）：
 *   - 不引用任何 UI/资源类，不拿 Context，不做静态初始化；
 *   - 不在 app 进程做任何动作；
 *   - 唯一副作用是尽力写 /data/adb/watchcat/system_server.marker 供诊断页取证；
 *     写不进去（SELinux）只记 errno，不重试，不影响开机。
 */
public final class WatchCatModule extends XposedModule {
    private static final String TAG = "WatchCat";
    private static final String STATE_DIR = "/data/adb/watchcat";

    // 排障用：Vector 的 log() 写它自己的日志文件，抓不到就等于没有。
    // 这里直连 android.util.Log，只打一行"我到过这里"，用来区分
    // 「回调没被调用」和「log() 没落盘」这两种完全不同的故障。
    private static void raw(String s) {
        try {
            Log.i("WatchCatP1", s + " pid=" + Process.myPid());
        } catch (Throwable ignored) {
        }
    }

    public WatchCatModule() {
        raw("ctor");
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        raw("onModuleLoaded");
        try {
            final boolean system = param.isSystemServer();
            log(Log.INFO, TAG, "p1: module loaded process=" + param.getProcessName()
                    + " system=" + system
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
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        raw("onSystemServerStarting");
        try {
            log(Log.INFO, TAG, "p1: onSystemServerStarting pid=" + Process.myPid()
                    + " classLoader=" + param.getClassLoader()
                    + " api=" + getApiVersion());
            writeMarker();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "p1: onSystemServerStarting failed", t);
        }
    }

    private boolean hasCap(long prop) {
        return (getFrameworkProperties() & prop) != 0;
    }

    /**
     * marker 落盘位置按「DAC + SELinux 实际能不能写」排序，逐个试：
     *
     *   1. /data/system/watchcat.marker —— system_server 自己的数据目录。
     *      同机 ClipBox（legacy 模块，同样跑在 system_server 里）能写
     *      /data/system/clipbox_config.json，说明这条路径在 uid 1000 下确实可写。
     *   2. /data/adb/watchcat/system_server.marker —— KernelSU 模块目录。
     *      它是 0700 root:root，system_server 写不进去（DAC 就拦了），留着只为
     *      将来若把目录改成 0771 root:system 时能继续用。
     *
     * 两个都失败只记 errno，不重试、不影响开机。
     */
    private void writeMarker() {
        final String line = "stage=system_server"
                + "\npid=" + Process.myPid()
                + "\nframework=" + getFrameworkName()
                + "\nframeworkVersion=" + getFrameworkVersion()
                + "\nframeworkVersionCode=" + getFrameworkVersionCode()
                + "\napi=" + getApiVersion()
                + "\ntimeMs=" + System.currentTimeMillis()
                + "\n";
        final String[] candidates = {
                "/data/system/watchcat.marker",
                STATE_DIR + "/system_server.marker",
        };
        for (String path : candidates) {
            final File marker = new File(path);
            try (FileOutputStream out = new FileOutputStream(marker, false)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                log(Log.INFO, TAG, "p1: marker written " + path);
                return;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "p1: marker write failed (probe only, not fatal): "
                        + path + " " + t);
            }
        }
    }
}
