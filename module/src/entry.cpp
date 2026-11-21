// WatchCat 数据面 —— Zygisk 模块入口（P0：加载探针）
//
// 这一版只证明两件事，别的什么都不做：
//   1) 模块能被 Zygisk 加载进 system_server；
//   2) 静态链进来的 Zig 引擎（libwatchcat_engine.a）在这个进程里能正常返回。
//
// 为什么 P0 必须先验这两条：如果 Zig 产出的代码在 system_server 里调用就崩，
// 那后面所有剪贴板/应用列表的 hook 都盖在流沙上；而 system_server 崩 = 用户循环重启变砖，
// 这是本项目里唯一「坏了就不是软件问题、是用户设备问题」的地方（规范 L10）。
//
// 行为约束（对应《LSP模块2026开发规范》L2 / L7 / L10）：
//   - 零 UI、零资源、零文件格式依赖；只用 liblog 打两行日志。
//   - 不注册任何 hook。P0 的 api 指针拿在手上但一次都不用。
//   - 每次进 app 进程的开销接近零（preAppSpecialize 直接 return，连文件都不开）。
//   - 未 arm（/data/adb/watchcat/armed 不存在）时绝不做任何决策，只记录现场。
//   - 全程不抛异常、不 abort：编译期关掉 exception/rtti，出错就 return。

// zygisk.hpp 里用到 dev_t / ino_t（pltHookRegister 的参数），但 jni.h 不带这两个类型，
// 所以必须先引 <sys/types.h>：官方样例是在更大的编译环境里编的，裸编会报 unknown type。
#include <sys/types.h>

#include "zygisk/zygisk.hpp"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// 引擎（zig/src/engine.zig）导出的朴素 C 符号；由构建脚本静态链接进来
extern "C" {
uint32_t wc_engine_version(void);
uint32_t wc_engine_ping(void);
}

#define TAG "WatchCat"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const char *kStateDir = "/data/adb/watchcat";
static const char *kArmFile = "/data/adb/watchcat/armed";
static const char *kProbeFile = "/data/adb/watchcat/p0.probe";

// 只读存在性，不读内容：P0 不消费规则，问「有没有」就够，少一次解析就少一个失败点
static bool armed_present() {
    int fd = open(kArmFile, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    close(fd);
    return true;
}

// system_server（u:r:system_server:s0）写 /data/adb 很可能被 SELinux 拒。
// 这不是失败：说明它是「探针」，不是「通道」——真正的通道是 remote preferences。
// 写不进去就把 errno 记下来，用户一看就知道该往哪查。
static void try_write_probe(const char *line) {
    int fd = open(kProbeFile, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        LOGW("p0: 写 probe 失败（不是崩溃，只是没权限）: errno=%d (%s)", errno, strerror(errno));
        return;
    }
    ssize_t n = write(fd, line, strlen(line));
    if (n < 0) LOGW("p0: probe 写入中断: errno=%d", errno);
    close(fd);
}

class WatchCatModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        // P0 不保存 api/env：拿了不用也得拿，但存下来会让人以为这里已经会钩东西了
        (void)api;
        (void)env;
    }

    // 每个 app 进程都走这里。P0 明确什么都不做——开销为零，绝不在别人的应用里留副作用。
    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override { (void)args; }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override { (void)args; }

    // 只有 system_server 会走到这两个回调，所以**不需要**去猜进程名（规范 L4：不靠猜）
    void preServerSpecialize(zygisk::ServerSpecializeArgs *args) override {
        (void)args;
        LOGI("p0: 进入 system_server 的 pre 阶段（zygote 权限），pid=%d", (int)getpid());
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *args) override {
        (void)args;
        const int pid = (int)getpid();
        const uint32_t ping = wc_engine_ping();
        const uint32_t ver = wc_engine_version();

        // 这一行就是 P0 的验收证据：ping 必须是 0x5743，否则说明链进来的不是我们那份引擎
        LOGI("p0: system_server 内调用 Zig 引擎 ping=0x%04x version=0x%08x pid=%d",
             ping, ver, pid);

        const bool armed = armed_present();
        if (!armed) {
            // 规范 L10「默认不生效」：没 armed 就到此为止，不做任何判定、不注册任何 hook
            LOGI("p0: armed 不存在 → 本次不启用任何功能（这是默认状态，不是错误）");
            return;
        }

        // 就算 armed 了，P0 也只多写一行证据；真正的 hook 要等规则通道做完
        char line[160];
        snprintf(line, sizeof(line),
                 "pid=%d ping=0x%04x version=0x%08x armed=1\n", pid, ping, ver);
        try_write_probe(line);
        LOGI("p0: armed 存在，只记证据，不注册 hook");
    }
};

REGISTER_ZYGISK_MODULE(WatchCatModule)
