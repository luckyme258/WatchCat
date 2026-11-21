#!/usr/bin/env python3
"""本机出 .so：调用 Zig 交叉编译 WatchCat 引擎，并立刻跑 16KB 对齐门禁。

为什么要用脚本而不是直接敲命令
  PowerShell 会把 `-femit-bin=build/x.dll` 这类带等号的参数拆坏，实测报
  `error: .dll: file not found`；同样的参数用 subprocess 列表传就一次通过。
  所以构建一律经过本脚本，参数拼装与 shell 无关。

用法
  py tools/build_so.py               # 编权限面 .so（arm64-v8a + x86_64），产物直接落 jniLibs
  py tools/build_so.py --only arm64  # 只编一个
  py tools/build_so.py --host        # 额外编一个 Windows DLL，供本机 JNI 冒烟测试
  py tools/build_so.py --no-check    # 跳过 16KB 门禁（不建议，仅排障时用）
  py tools/build_so.py --engine      # 编数据面引擎的**静态库**（给 Zygisk shim 链接），落 build/engine
  py tools/build_so.py --probe       # 本机用 C 链接引擎静态库并跑一遍，验证 ABI 名字/类型没歪
  py tools/build_so.py --engine --probe   # 两条一起跑（CI 就是这么调的）

三个硬参数（漏一个就出废品，见《安卓开发规范》第 4 章）
  -dynamic                    产出动态库；漏了会得到静态库，loadLibrary 直接失败
  -z max-page-size=16384      16KB 页对齐；x86_64 默认只有 4KB，安卓 15+ 拒载
  列表方式传参                 避开 shell 拆参数

为什么数据面引擎出**静态库**而不是 .so
  它由 Zygisk 里的 C++ shim 静态链接（《LSP模块2026开发规范》L2：native 只由 Zygisk 加载）。
  静态链接的好处是不用管 .so 搜索路径、不会被别的进程半路 loadLibrary 走 UI 依赖，
  16KB 对齐只需要在**最终那个 shim .so** 上验一次。
"""


import os
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "zig", "src", "watchcat.zig")
ENGINE_SRC = os.path.join(REPO, "zig", "src", "engine.zig")
JNI_LIBS = os.path.join(REPO, "app", "src", "main", "jniLibs")
ENGINE_OUT = os.path.join(REPO, "build", "engine")
PROBE_C = os.path.join(REPO, "tools", "probe", "engine_probe.c")

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


def build_engine(zig, target, out_path):
    """编数据面引擎的静态库，给 Zygisk shim 静态链接用。返回 (ok, ms, size, stderr)"""
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    argv = [
        zig, "build-lib", ENGINE_SRC,
        "-target", target,
        "-O", "ReleaseSmall",
        "-femit-bin=" + out_path,
        "-static",
        # 静态库要进 shim 那个共享库，必须 PIC：否则 lld 报
        # `relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used ...`
        "-fPIC",
        "-fno-emit-implib",
    ]
    t0 = time.time()
    r = subprocess.run(argv, capture_output=True, text=True)
    ms = int((time.time() - t0) * 1000)
    size = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    return r.returncode == 0 and size > 0, ms, size, r.stderr


def host_triple():
    import platform
    return "x86_64-windows-gnu" if platform.system() == "Windows" else "x86_64-linux-gnu"


def run_probe(zig):
    """本机用 C 链接引擎静态库：证明 shim 照名字链接时不会 `undefined symbol`。

    这一步不碰设备、不要 root，跑的是和 CI 同一个源码 —— ABI 名字/类型一旦被改名，
    这里立刻红，不用等真机上 system_server 起不来才发现。
    """
    if not os.path.exists(PROBE_C):
        return False, "缺少探针源: " + PROBE_C
    lib = os.path.join(REPO, "build", "engine", "host", "libwatchcat_engine.a")
    ok, ms, size, err = build_engine(zig, host_triple(), lib)
    if not ok:
        return False, "host 静态库编译失败: " + (err or "").strip()[:400]
    exe = os.path.join(REPO, "build", "engine", "host", "engine_probe.exe" if os.name == "nt" else "engine_probe")
    argv = [zig, "cc", PROBE_C, lib, "-o", exe, "-O2"]
    r = subprocess.run(argv, capture_output=True, text=True)
    if r.returncode != 0:
        return False, "探针链接失败（ABI 或符号名不符）: " + (r.stderr or "").strip()[:600]
    r = subprocess.run([exe], capture_output=True, text=True)
    out = (r.stdout or "").strip()
    return r.returncode == 0, out or (r.stderr or "").strip()[:400]


def main(argv):
    only = None
    do_host = False
    do_check = True
    do_engine = "--engine" in argv
    do_probe = "--probe" in argv
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

    # 只编引擎/只跑探针时不去碰权限面的 .so（省时间，也让 CI 的日志各管一段）
    jobs = [] if (do_engine or do_probe) else [k for k in TARGETS if only is None or k == only]
    if not jobs and not (do_engine or do_probe):
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

    if do_engine:
        if not os.path.exists(ENGINE_SRC):
            raise SystemExit("缺少引擎源: " + ENGINE_SRC)
        for key in [k for k in TARGETS if only is None or k == only]:
            triple, rel, _ = TARGETS[key]
            # 和 .so 同目录名（arm64-v8a / x86_64），shim 按 abi 取哪个一目了然
            out = os.path.join(ENGINE_OUT, os.path.dirname(rel), "libwatchcat_engine.a")
            ok, ms, size, err = build_engine(zig, triple, out)
            print("%-9s %-9s %s  %5d ms  %d bytes" % (key, "OK" if ok else "FAIL", out.replace(REPO + os.sep, ""), ms, size))
            if not ok:
                failures.append((key, (err or "").strip()[:400]))
            print()

    if do_probe:
        ok, out = run_probe(zig)
        print("probe     %-9s %s" % ("OK" if ok else "FAIL", out.replace("\n", " | ")))
        if not ok:
            failures.append(("probe", out))
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
    print("全部通过。")
    if jobs:
        print("  权限面：app/src/main/jniLibs/*/libwatchcat.so")
    if do_engine:
        print("  数据面：build/engine/*/libwatchcat_engine.a（给 Zygisk shim 静态链接）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
