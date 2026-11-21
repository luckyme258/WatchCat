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

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
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

    private void writeMarker() {
        final File dir = new File(STATE_DIR);
        final File marker = new File(dir, "system_server.marker");
        // P1 的证据不该依赖 P0 模块是否装过：目录不存在就自己建，建不出来再记原因。
        // 之前没 mkdirs，导致没装 Zygisk 模块时 marker 永远是“未找到”，看起来像回调没跑。
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log(Log.WARN, TAG, "p1: marker dir unavailable (probe only): " + dir.getAbsolutePath());
            return;
        }
        final String line = "stage=system_server"
                + "\npid=" + Process.myPid()
                + "\nframework=" + getFrameworkName()
                + "\nframeworkVersion=" + getFrameworkVersion()
                + "\nframeworkVersionCode=" + getFrameworkVersionCode()
                + "\napi=" + getApiVersion()
                + "\ntimeMs=" + System.currentTimeMillis()
                + "\n";
        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            log(Log.INFO, TAG, "p1: marker written " + marker.getAbsolutePath());
        } catch (Throwable t) {
            log(Log.WARN, TAG, "p1: marker write failed (probe only, not fatal): "
                    + marker.getAbsolutePath() + " " + t);
        }
    }
}
