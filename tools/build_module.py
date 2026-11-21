#!/usr/bin/env python3
"""把 module/ 打成 Magisk / Vector 都能直接装的模块 zip，并算 APK 的配对信息。

为什么要脚本而不是手动压缩
  1) zip 里 module.prop 必须在根，不能多套一层目录；
  2) 脚本文件必须 LF 且 0755 —— Windows 检出成 CRLF 后 /system/bin/sh 会说
     `bad interpreter` 或直接拒绝执行，这种错在真机上排查很费时间；
  3) 时间戳固定（1980-01-01），同一份源码重复打出来的包 hash 一致，方便核对「CI 那个包
     是不是我这次 commit 的」。

包内只放运行时需要的东西（白名单，见 PACK_FILES）：
  module.prop + 四个脚本 + README（含 zygisk.hpp 的 0BSD 声明）+ zygisk/arm64-v8a.so
module/src/ 下的源码与第三方头**不进包** —— 装了模块的设备不需要编译器源文件。

用法
  py tools/build_module.py                    # 出 build/module/watchcat-module-<ver>-<sha8>.zip
  py tools/build_module.py --require-zygisk   # 要求包里必须有 zygisk/arm64-v8a.so（发布用）
  py tools/build_module.py --with-x86_64      # 额外带 x86_64（只给模拟器调试，不进发布包）
"""

import hashlib
import os
import re
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE_DIR = os.path.join(REPO, "module")
OUT_DIR = os.path.join(REPO, "build", "module")
NATIVE_DIR = os.path.join(REPO, "build", "native", "zygisk")
# 规范 L8：发布包只留 arm64-v8a；x86_64 仅供模拟器，必须显式 --with-x86_64 才带
ABIS_RELEASE = ("arm64-v8a",)
ABIS_ALL = ("arm64-v8a", "x86_64")

# 白名单：只有这些文件进包。不要去 walk 整个 module/，那会把 src/ 的源码和 zygisk.hpp 一起装进 /data/adb
PACK_FILES = (
    "module.prop",
    "post-fs-data.sh",
    "service.sh",
    "uninstall.sh",
    "README.md",      # 里面有 zygisk.hpp 的 0BSD 许可声明，必须随包
)


def read_prop(key):
    path = os.path.join(MODULE_DIR, "module.prop")
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith(key + "="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("module.prop 里没有 %s" % key)


def git_sha():
    try:
        return subprocess.run(
            ["git", "-C", REPO, "rev-parse", "--short=8", "HEAD"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return "nogit"


def git_dirty():
    # 只认“已跟踪文件的改动”。构建产物（jniLibs/.so、build/）现在都不入库，
    # 未跟踪的临时文件也不该让包名变成 -dirty —— 否则每个 CI 包都叫 -dirty，溯源就废了。
    try:
        r = subprocess.run(["git", "-C", REPO, "status", "--porcelain", "--untracked-files=no"],
                           capture_output=True, text=True, check=True)
        return bool(r.stdout.strip())
    except Exception:
        return False


def collect(native_dir, abis):
    files = []
    # 白名单打包：源码（module/src/）与第三方头不进包，设备上只需要运行时文件
    for rel in PACK_FILES:
        full = os.path.join(MODULE_DIR, rel)
        if os.path.exists(full):
            files.append((rel, full))
    # shim .so 不进 git（是产物），打包时才从 build/native 并进来：
    # 包里的路径必须是 zygisk/<abi>.so，文件名就是 Zygisk 认的 ABI 名，不能带 lib 前缀。
    if native_dir and os.path.isdir(native_dir):
        for abi in abis:
            src = os.path.join(native_dir, abi + ".so")
            if os.path.exists(src):
                files.append(("zygisk/%s.so" % abi, src))
    return files


def add(zf, rel, full):
    info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    is_sh = rel.endswith(".sh")
    info.external_attr = ((0o100755 if is_sh else 0o100644) & 0xFFFF) << 16
    with open(full, "rb") as f:
        data = f.read()
    # 所有 .sh 强制 LF：CRLF 的 shell 脚本在真机上是会直接失败的
    if is_sh:
        data = data.replace(b"\r\n", b"\n")
    zf.writestr(info, data)


def main(argv):
    require_zygisk = "--require-zygisk" in argv
    native_dir = NATIVE_DIR
    if "--no-native" in argv:
        native_dir = None
    # 发布包只留 arm64（规范 L8）；模拟器要 x86_64 就显式加 --with-x86_64
    abis = ABIS_ALL if "--with-x86_64" in argv else ABIS_RELEASE

    version = read_prop("version")
    version_code = read_prop("versionCode")
    sha = git_sha()
    dirty = git_dirty()

    files = collect(native_dir, abis)
    if not any(rel == "module.prop" for rel, _ in files):
        raise SystemExit("module/ 下没有 module.prop，打出来是废包")

    present = [a for a in abis if any(rel == "zygisk/%s.so" % a for rel, _ in files)]
    if require_zygisk and "arm64-v8a" not in present:
        raise SystemExit("要求 zygisk/arm64-v8a.so，但 build/native/zygisk 下没有；"
                         "先跑 py tools/build_module_native.py --only arm64")

    os.makedirs(OUT_DIR, exist_ok=True)
    name = "watchcat-module-%s-%s%s.zip" % (version, sha, "-dirty" if dirty else "")
    out = os.path.join(OUT_DIR, name)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for rel, full in files:
            add(zf, rel, full)

    size = os.path.getsize(out)
    digest = hashlib.sha256(open(out, "rb").read()).hexdigest()

    print("module zip : %s" % out.replace(REPO + os.sep, ""))
    print("version    : %s (versionCode %s)" % (version, version_code))
    print("commit     : %s%s" % (sha, " (工作区有未提交改动)" if dirty else ""))
    print("大小/摘要  : %d bytes  sha256=%s" % (size, digest[:16]))
    if present:
        print("zygisk     : %s 已在包内（只 arm64 进发布包）" % ", ".join(a + ".so" for a in present))
        if "--with-x86_64" in argv and "x86_64" not in present:
            print("警告       : 指定了 --with-x86_64，但 build/native/zygisk 下没有 x86_64.so")
    else:
        print("zygisk     : **包内没有 zygisk/*.so** —— 这就是 P0 当前状态：装了等于没装，不会注入任何进程")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
