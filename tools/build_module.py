#!/usr/bin/env python3
"""把 module/ 打成 Magisk / Vector 都能直接装的模块 zip，并算 APK 的配对信息。

为什么要脚本而不是手动压缩
  1) zip 里 module.prop 必须在根，不能多套一层目录；
  2) 脚本文件必须 LF 且 0755 —— Windows 检出成 CRLF 后 /system/bin/sh 会说
     `bad interpreter` 或直接拒绝执行，这种错在真机上排查很费时间；
  3) 时间戳固定（1980-01-01），同一份源码重复打出来的包 hash 一致，方便核对「CI 那个包
     是不是我这次 commit 的」。

用法
  py tools/build_module.py                    # 出 build/module/watchcat-module-<ver>-<sha8>.zip
  py tools/build_module.py --require-zygisk   # 要求包里必须有 zygisk/<abi>.so（P0-B 之后才用得上）
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
ABIS = ("arm64-v8a", "x86_64")


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
    try:
        r = subprocess.run(["git", "-C", REPO, "status", "--porcelain"],
                           capture_output=True, text=True, check=True)
        return bool(r.stdout.strip())
    except Exception:
        return False


def collect():
    files = []
    for root, dirs, names in os.walk(MODULE_DIR):
        dirs.sort()
        for name in sorted(names):
            full = os.path.join(root, name)
            rel = os.path.relpath(full, MODULE_DIR).replace(os.sep, "/")
            files.append((rel, full))
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

    version = read_prop("version")
    version_code = read_prop("versionCode")
    sha = git_sha()
    dirty = git_dirty()

    files = collect()
    if not any(rel == "module.prop" for rel, _ in files):
        raise SystemExit("module/ 下没有 module.prop，打出来是废包")

    has_zygisk = any(rel.startswith("zygisk/") and rel.endswith(".so") for rel, _ in files)
    if require_zygisk and not has_zygisk:
        raise SystemExit("要求 zygisk/<abi>.so 但 module/zygisk 下没有 .so；先跑构建 shim 的那一步")
    missing_abi = [a for a in ABIS if not any(rel == "zygisk/%s.so" % a for rel, _ in files)]

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
    if has_zygisk:
        print("zygisk     : arm64-v8a.so 已在包内")
        if missing_abi:
            print("警告       : 缺 %s 的 .so（模拟器/部分设备会不生效）" % "/".join(missing_abi))
    else:
        print("zygisk     : **包内没有 zygisk/*.so** —— 这就是 P0 当前状态：装了等于没装，不会注入任何进程")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
