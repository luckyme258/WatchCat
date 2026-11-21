#!/usr/bin/env python3
"""本机出 .so：调用 Zig 交叉编译 WatchCat 引擎，并立刻跑 16KB 对齐门禁。

为什么要用脚本而不是直接敲命令
  PowerShell 会把 `-femit-bin=build/x.dll` 这类带等号的参数拆坏，实测报
  `error: .dll: file not found`；同样的参数用 subprocess 列表传就一次通过。
  所以构建一律经过本脚本，参数拼装与 shell 无关。

用法
  py tools/build_so.py              # 编 arm64-v8a + x86_64，产物直接落 jniLibs
  py tools/build_so.py --only arm64 # 只编一个
  py tools/build_so.py --host       # 额外编一个 Windows DLL，供本机 JNI 冒烟测试
  py tools/build_so.py --no-check   # 跳过 16KB 门禁（不建议，仅排障时用）

三个硬参数（漏一个就出废品，见《安卓开发规范》第 4 章）
  -dynamic                    产出动态库；漏了会得到静态库，loadLibrary 直接失败
  -z max-page-size=16384      16KB 页对齐；x86_64 默认只有 4KB，安卓 15+ 拒载
  列表方式传参                 避开 shell 拆参数
"""

import os
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "zig", "src", "watchcat.zig")
JNI_LIBS = os.path.join(REPO, "app", "src", "main", "jniLibs")

ZIG_VERSION_EXPECT = "0.17."
ZIG_CANDIDATES = [
    os.environ.get("ZIG", ""),
    "zig",
    os.path.expandvars(
        r"%LOCALAPPDATA%\Programs\Python\Python315\Lib\site-packages\ziglang\zig.exe"
    ),
]

# (名字, 目标三元组, 产物相对路径, 期望架构)
TARGETS = {
    "arm64": ("aarch64-linux-android", os.path.join("arm64-v8a", "libwatchcat.so"), "AArch64"),
    "x86_64": ("x86_64-linux-android", os.path.join("x86_64", "libwatchcat.so"), "x86-64"),
}


def find_zig():
    for cand in ZIG_CANDIDATES:
        if not cand:
            continue
        if os.path.isabs(cand):
            if os.path.exists(cand):
                return cand
            continue
        # PATH 里的名字，交给 shutil.which 判定
        import shutil
        p = shutil.which(cand)
        if p:
            return p
    raise SystemExit(
        "找不到 zig。装法：py -m pip install --no-cache-dir ziglang==0.17.1；"
        "或用环境变量 ZIG 指到 zig.exe。"
    )


def zig_version(zig):
    r = subprocess.run([zig, "version"], capture_output=True, text=True)
    v = (r.stdout or r.stderr).strip()
    if r.returncode != 0:
        raise SystemExit("zig version 执行失败: " + v)
    return v


def build(zig, target, out_path, extra=()):
    """编一个产物，返回 (ok, ms, size, stderr)"""
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    argv = [
        zig, "build-lib", SRC,
        "-target", target,
        "-O", "ReleaseSmall",
        "-femit-bin=" + out_path,
        "-dynamic",
        "-z", "max-page-size=16384",
        *extra,
    ]
    t0 = time.time()
    r = subprocess.run(argv, capture_output=True, text=True)
    ms = int((time.time() - t0) * 1000)
    size = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    return r.returncode == 0 and size > 0, ms, size, r.stderr


def main(argv):
    only = None
    do_host = False
    do_check = True
    if "--only" in argv:
        only = argv[argv.index("--only") + 1]
    do_host = "--host" in argv
    do_check = "--no-check" not in argv

    if not os.path.exists(SRC):
        raise SystemExit("缺少源文件: " + SRC)

    zig = find_zig()
    ver = zig_version(zig)
    print("zig         : %s (%s)" % (zig, ver))
    if not ver.startswith(ZIG_VERSION_EXPECT):
        print("警告        : 版本不是 %sx，基线见《安卓开发规范》第 17 章，换版本要重跑基线" % ZIG_VERSION_EXPECT)

    jobs = [k for k in TARGETS if only is None or k == only]
    if not jobs:
        raise SystemExit("--only 只认 arm64 / x86_64")

    failures = []
    for key in jobs:
        triple, rel, expect = TARGETS[key]
        out = os.path.join(JNI_LIBS, rel)
        ok, ms, size, err = build(zig, triple, out)
        print("%-9s %-9s %s  %5d ms  %d bytes" % (key, "OK" if ok else "FAIL", out.replace(REPO + os.sep, ""), ms, size))
        if not ok:
            failures.append((key, (err or "").strip()[:400]))
        elif do_check:
            sys.path.insert(0, os.path.join(REPO, "tools"))
            from elfcheck import analyze
            passed, lines = analyze(out, expect)
            for line in lines:
                print("    " + line)
            if not passed:
                failures.append((key, "16KB 门禁未通过"))
        print()

    if do_host:
        import platform
        host_triple = "x86_64-windows" if platform.system() == "Windows" else "x86_64-linux-gnu"
        host_out = os.path.join(REPO, "build", "host", "watchcat.dll" if platform.system() == "Windows" else "libwatchcat.so")
        ok, ms, size, err = build(zig, host_triple, host_out, extra=["-fno-emit-implib"])
        print("host      %-9s %s  %5d ms  %d bytes" % ("OK" if ok else "FAIL", host_out.replace(REPO + os.sep, ""), ms, size))
        if not ok:
            failures.append(("host", (err or "").strip()[:400]))
        print()

    if failures:
        print("失败明细：")
        for key, msg in failures:
            print("  [%s] %s" % (key, msg))
        return 1
    print("全部通过。产物已落 app/src/main/jniLibs，可直接进 APK。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
