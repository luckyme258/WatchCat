#!/usr/bin/env python3
"""用 NDK 编 Zygisk 模块的 shim .so（P0 的加载探针），并按 16KB 门禁复核。

为什么必须走 NDK
  实测 Zig 自带 libc 覆盖不了 `aarch64-linux-android`（`unable to provide libc for target`），
  而 shim 要 <jni.h>、<android/log.h>、liblog —— 这些只能来自 NDK。Zig 继续只负责引擎静态库，
  两边各自用最擅长的工具链，边界清楚。

为什么链接引擎**静态库**
  规范 L2：native 只由 Zygisk 加载。静态链接后不存在「两个 .so 版本不一致」这一类最难查的问题，
  16KB 对齐也只需要在最终这个 shim 上验一次。

用法
  py tools/build_module_native.py                 # arm64-v8a + x86_64
  py tools/build_module_native.py --only arm64
  py tools/build_module_native.py --no-check      # 跳过 16KB/符号复核（仅排障）

NDK 查找顺序
  $ANDROID_NDK_HOME → $ANDROID_NDK_LATEST_HOME → $ANDROID_NDK_ROOT →
  $ANDROID_SDK_ROOT/ndk/* → 本机 Android Studio 默认路径
  找到后打印版本；找不到直接报错并给出装法，不猜。
"""

import glob
import os
import platform
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(REPO, "module", "src")
ENTRY = os.path.join(SRC_DIR, "entry.cpp")
ENGINE_DIR = os.path.join(REPO, "build", "engine")
OUT_DIR = os.path.join(REPO, "build", "native", "zygisk")

# 与 app/build.gradle.kts 的 minSdk 对齐：30（Android 11）。不要为了「兼容」硬压到 21——
# 这个模块只在 Zygisk 存在的新系统上跑，压低了反而要处理一堆老 API 分支。
API_LEVEL = "30"

# (名字, 目标三元组, 期望架构, 引擎静态库子目录)
TARGETS = {
    "arm64": ("aarch64-linux-android", "AArch64", "arm64-v8a"),
    "x86_64": ("x86_64-linux-android", "x86-64", "x86_64"),
}


def host_tag():
    sysname = platform.system()
    machine = platform.machine().lower()
    if sysname == "Windows":
        return "windows-x86_64"
    if sysname == "Darwin":
        return "darwin-x86_64" if machine in ("x86_64", "amd64") else "darwin-aarch64"
    return "linux-x86_64" if machine in ("x86_64", "amd64") else "linux-aarch64"


def find_ndk():
    candidates = [
        os.environ.get("ANDROID_NDK_HOME"),
        os.environ.get("ANDROID_NDK_LATEST_HOME"),
        os.environ.get("ANDROID_NDK_ROOT"),
    ]
    for sdk in (os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"),
                os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")):
        if sdk and os.path.isdir(os.path.join(sdk, "ndk")):
            candidates += sorted(glob.glob(os.path.join(sdk, "ndk", "*")))
    for cand in candidates:
        if not cand or not os.path.isdir(cand):
            continue
        if os.path.exists(os.path.join(cand, "toolchains", "llvm", "prebuilt", host_tag())):
            return cand
    raise SystemExit(
        "::error::找不到 NDK（ANDROID_NDK_HOME / ANDROID_NDK_LATEST_HOME / SDK 的 ndk 目录都没命中）"
    )


def ndk_version(ndk):
    prop = os.path.join(ndk, "source.properties")
    if os.path.exists(prop):
        with open(prop, encoding="utf-8") as f:
            for line in f:
                if line.startswith("Pkg.Revision"):
                    return line.split("=", 1)[1].strip()
    return "?"


def gh_error(msg):
    """把失败原因打成 Actions 注解：日志要登录才看得到，注解是公开可读的（排障用）。

    GitHub 的注解单行上限 1000 字符左右，超了会被截断，所以只留最关键的几百字符。
    """
    one = " ".join(str(msg).split())
    print("::error::" + one[:900])


def compile_one(ndk, triple, abi_dir, out_path):
    prebuilt = os.path.join(ndk, "toolchains", "llvm", "prebuilt", host_tag())
    clangxx = os.path.join(prebuilt, "bin", "clang++")
    sysroot = os.path.join(prebuilt, "sysroot")
    engine_lib = os.path.join(ENGINE_DIR, abi_dir, "libwatchcat_engine.a")
    if not os.path.exists(engine_lib):
        gh_error("缺少引擎静态库 %s（先跑 py tools/build_so.py --engine）" % engine_lib)
        raise SystemExit(1)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    argv = [
        clangxx,
        "-target", "%s%s" % (triple, API_LEVEL),
        "--sysroot=" + sysroot,
        "-std=c++17",
        "-O2",
        # 关掉异常和 RTTI：这个 .so 在 system_server 里跑，任何 new/throw 都是多余的失败面
        "-fno-exceptions", "-fno-rtti",
        "-ffunction-sections", "-fdata-sections",
        "-Wall", "-Wextra", "-Wno-unused-parameter",
        "-I", SRC_DIR,
        "-shared",
        "-o", out_path,
        ENTRY,
        engine_lib,
        "-Wl,--gc-sections",
        "-Wl,-z,max-page-size=16384",   # 16KB 页对齐，安卓 15+ 的硬门槛
        "-llog", "-lc", "-lm", "-ldl",
    ]
    t0 = time.time()
    r = subprocess.run(argv, capture_output=True, text=True)
    ms = int((time.time() - t0) * 1000)
    size = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    return r.returncode == 0 and size > 0, ms, size, (r.stderr or "")


def check(ndk, out_path, expect_arch):
    """两步复核：16KB 对齐 + 入口符号真的导出了（hidden 全开时最容易漏这个）。"""
    sys.path.insert(0, os.path.join(REPO, "tools"))
    from elfcheck import analyze
    passed, lines = analyze(out_path, expect_arch)
    for line in lines:
        print("    " + line)
    if not passed:
        return False, "16KB 门禁未通过"

    prebuilt = os.path.join(ndk, "toolchains", "llvm", "prebuilt", host_tag())
    readelf = os.path.join(prebuilt, "bin", "llvm-readelf")
    if not os.path.exists(readelf):
        print("    （跳过入口符号复核：NDK 里没有 llvm-readelf）")
        return True, ""
    r = subprocess.run([readelf, "--dyn-syms", out_path], capture_output=True, text=True)
    syms = r.stdout or ""
    if "zygisk_module_entry" not in syms:
        return False, "动态符号表里没有 zygisk_module_entry（-fvisibility=hidden 把它藏了？）"
    print("    zygisk_module_entry: 已导出")
    # 引擎符号不该进动态表：它们是内部实现，导出只会让人误以为可以从外面调
    leaked = [k for k in ("wc_engine_ping", "wc_engine_version") if k in syms]
    if leaked:
        print("    （提示）引擎符号也被导出了：%s —— 不影响功能，后续可用 version script 收口" % ", ".join(leaked))
    return True, ""


def main(argv):
    only = None
    do_check = "--no-check" not in argv
    if "--only" in argv:
        only = argv[argv.index("--only") + 1]

    if not os.path.exists(ENTRY):
        raise SystemExit("缺少入口源: " + ENTRY)

    ndk = find_ndk()
    print("ndk        : %s (%s)" % (ndk, ndk_version(ndk)))

    jobs = [k for k in TARGETS if only is None or k == only]
    if not jobs:
        raise SystemExit("--only 只认 arm64 / x86_64")

    failures = []
    for key in jobs:
        triple, expect, abi_dir = TARGETS[key]
        out = os.path.join(OUT_DIR, "%s.so" % abi_dir)   # 文件名就是 Zygisk 认的 ABI 名
        ok, ms, size, err = compile_one(ndk, triple, abi_dir, out)
        print("%-9s %-9s %s  %5d ms  %d bytes" % (key, "OK" if ok else "FAIL",
                                                  out.replace(REPO + os.sep, ""), ms, size))
        if not ok:
            failures.append((key, (err or "").strip()[:600]))
            gh_error("%s 编译失败: %s | cmd=%s" % (key, (err or "")[:500], " ".join(argv[:6])))
        elif do_check:
            good, msg = check(ndk, out, expect)
            if not good:
                failures.append((key, msg))
                gh_error("%s 复核失败: %s" % (key, msg))
        print()

    if failures:
        print("失败明细：")
        for key, msg in failures:
            print("  [%s] %s" % (key, msg))
        return 1
    print("全部通过。shim 已落 build/native/zygisk，打模块包时自动并进 zip。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
