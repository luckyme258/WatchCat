package com.luckyme.watchcat

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 空壳页面：验证 libwatchcat.so 在真机上能否被 System.loadLibrary 装载、
 * 六个 JNI 符号能否被解析、手写函数表偏移在 ART 上是否同样成立。
 *
 * 不申请权限、不联网、不落盘；结果同时写界面与 logcat（tag=WatchCat）。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sb = StringBuilder()
        sb.append("设备 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("系统 Android ").append(Build.VERSION.RELEASE)
            .append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("首选 ABI ").append(if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "?").append('\n')
        sb.append("全部 ABI ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("\n\n")

        try {
            val ver = NativeEngine.nativeVersion()
            val ping = NativeEngine.nativePing()
            val tag = NativeEngine.nativeTag()
            val jniVer = NativeEngine.nativeJniVersion()
            val m2 = NativeEngine.nativeModeName(2)
            val m9 = NativeEngine.nativeModeName(9)
            val ops = NativeEngine.nativeOpCount()

            val ok = ver == 0x00010000 &&
                ping == 0x5743 &&
                "watchcat/0.1.0" == tag &&
                jniVer >= 0x00010006 &&
                "忽略（给空数据）" == m2 &&
                "?" == m9 &&
                ops > 0

            sb.append(if (ok) "LOAD: PASS\n\n" else "LOAD: FAIL\n\n")
            sb.append(String.format(Locale.US, "version    = 0x%08x%n", ver))
            sb.append(String.format(Locale.US, "ping       = 0x%04x%n", ping))
            sb.append("tag        = ").append(tag).append('\n')
            sb.append(String.format(Locale.US, "jniVersion = 0x%x%n", jniVer))
            sb.append("modeName(2)= ").append(m2).append('\n')
            sb.append("modeName(9)= ").append(m9).append('\n')
            sb.append("opCount    = ").append(ops).append('\n')

            Log.i(TAG, sb.toString().replace('\n', ' '))
        } catch (e: Throwable) {
            // 装不上 / 符号找不到 / 类初始化失败，都把真实原因带出来，不要只报「加载失败」
            sb.append("LOAD: FAIL\n\n")
            sb.append("${e.javaClass.name}:\n${e.message}\n\n")
            var cause: Throwable? = e.cause
            if (cause != null) {
                sb.append("cause: ${cause.javaClass.name}: ${cause.message}\n\n")
            }
            sb.append("常见原因：\n")
                .append("1) ABI 不匹配（本机只有 arm64 库）；\n")
                .append("2) .so 未按 16KB 对齐、或被压缩进 APK；\n")
                .append("3) 类名/方法名与导出符号不一致（Java_com_luckyme_watchcat_NativeEngine_*）。\n")
            Log.e(TAG, "load failed: ${e.javaClass.name}: ${e.message}", e)
        }

        val tv = TextView(this).apply {
            text = sb.toString()
            textSize = 15f
            setPadding(48, 96, 48, 48)
        }

        setContentView(ScrollView(this).apply { addView(tv) })
    }

    companion object {
        private const val TAG = "WatchCat"
    }
}
