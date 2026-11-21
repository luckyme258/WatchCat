package com.luckyme.watchcat;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 空壳页面：验证 libwatchcat.so 在真机上能否被 System.loadLibrary 装载、
 * 六个 JNI 符号能否被解析、手写函数表偏移在 ART 上是否同样成立。
 *
 * 不申请权限、不联网、不落盘；结果同时写界面与 logcat（tag=WatchCat）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "WatchCat";

    static {
        System.loadLibrary("watchcat");
    }

    private static native int nativeVersion();
    private static native int nativePing();
    private static native String nativeTag();
    private static native int nativeJniVersion();
    private static native String nativeModeName(int mode);
    private static native int nativeOpCount();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        StringBuilder sb = new StringBuilder();
        sb.append("设备 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统 Android ").append(Build.VERSION.RELEASE)
          .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("首选 ABI ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append("全部 ABI ").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n\n");

        try {
            int ver = nativeVersion();
            int ping = nativePing();
            String tag = nativeTag();
            int jniVer = nativeJniVersion();
            String m2 = nativeModeName(2);
            String m9 = nativeModeName(9);
            int ops = nativeOpCount();

            boolean ok = ver == 0x00010000
                    && ping == 0x5743
                    && "watchcat/0.1.0".equals(tag)
                    && jniVer >= 0x00010006
                    && "忽略（给空数据）".equals(m2)
                    && "?".equals(m9)
                    && ops > 0;

            sb.append(ok ? "LOAD: PASS\n\n" : "LOAD: FAIL\n\n");
            sb.append(String.format("version    = 0x%08x%n", ver));
            sb.append(String.format("ping       = 0x%04x%n", ping));
            sb.append("tag        = ").append(tag).append('\n');
            sb.append(String.format("jniVersion = 0x%x%n", jniVer));
            sb.append("modeName(2)= ").append(m2).append('\n');
            sb.append("modeName(9)= ").append(m9).append('\n');
            sb.append("opCount    = ").append(ops).append('\n');

            Log.i(TAG, sb.toString().replace('\n', ' '));
        } catch (UnsatisfiedLinkError e) {
            // 装不上 / 符号找不到时，把原因原样带出来，不要只报「加载失败」
            sb.append("LOAD: FAIL\n\n");
            sb.append("UnsatisfiedLinkError:\n").append(e.getMessage()).append('\n');
            sb.append("\n常见原因：\n")
              .append("1) ABI 不匹配（本机只有 arm64 库）；\n")
              .append("2) .so 未按 16KB 对齐、或被压缩进 APK；\n")
              .append("3) 导出符号名与 Java 包名/类名不一致。\n");
            Log.e(TAG, "load failed: " + e.getMessage());
        } catch (Throwable t) {
            sb.append("LOAD: FAIL\n\n").append(t).append('\n');
            Log.e(TAG, "unexpected: " + t);
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(15f);
        tv.setPadding(48, 96, 48, 48);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }
}
