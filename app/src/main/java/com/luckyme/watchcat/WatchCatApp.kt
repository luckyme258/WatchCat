package com.luckyme.watchcat

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 只做一件事：注册框架服务监听，让诊断页能读到 Vector 的 API/版本/作用域。
 * 不在这里读策略、不初始化 UI、不做任何 hook。
 */
class WatchCatApp : Application(), XposedServiceHelper.OnServiceListener {
    private companion object {
        const val TAG = "WatchCat"
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        FrameworkState.bind(service)
        Log.i(TAG, "p1: framework service bound: ${service.frameworkName} API ${service.apiVersion}")
    }

    override fun onServiceDied(service: XposedService) {
        FrameworkState.unbind(service)
        Log.w(TAG, "p1: framework service died")
    }
}
