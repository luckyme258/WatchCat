#!/usr/bin/env python3
"""空壳 APK 打包：不装 Gradle，直接用本机 build-tools 走 aapt2 + d8 + apksigner。

为什么要有这个脚本
  真机验 .so 能否装载，需要一个最小 APK；本机没有完整 Gradle 工程，
  手敲 aapt2/d8 一长串参数又容易错，所以固化成一条命令。

用法
  py tools/build_apk.py

产物
  apk/WatchCat-0.1.0-debug.apk（未签名中间件留在 build/apk/）

注意
  - .so 必须以「不压缩」方式进 APK，并且整包 16KB 对齐（-P 16），
    否则 Android 15+ 的 16KB 页设备会拒绝加载；
  - 签名用 keystore/debug.keystore（首次运行自动生成，口令 android）。
"""

import hashlib
import os
import shutil
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_SDK", r"E:\AndroidDev\sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", "35.0.0")
PLATFORM_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
JDK = os.environ.get("JAVA_HOME", r"E:\AndroidDev\jdk-17.0.2")

MANIFEST = os.path.join(REPO, "app", "src", "main", "AndroidManifest.xml")
JAVA_SRC = os.path.join(REPO, "app", "src", "main", "java")
JNI_LIBS = os.path.join(REPO, "app", "src", "main", "jniLibs")
KEYSTORE = os.path.join(REPO, "keystore", "debug.keystore")
WORK = os.path.join(REPO, "build", "apk")
OUT_APK = os.path.join(REPO, "apk", "WatchCat-0.1.0-debug.apk")

VERSION_CODE = "1"
VERSION_NAME = "0.1.0"
MIN_SDK = "30"
TARGET_SDK = "35"


def env():
    e = dict(os.environ)
    e["JAVA_HOME"] = JDK
    e["PATH"] = os.path.join(JDK, "bin") + os.pathsep + e.get("PATH", "")
    return e


def run(argv, cwd=None, what=""):
    print("  $ " + " ".join(os.path.basename(a) if i == 0 else a for i, a in enumerate(argv)))
    r = subprocess.run(argv, cwd=cwd or REPO, capture_output=True, text=True, env=env(),
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print((r.stdout or "")[-1500:])
        print((r.stderr or "")[-1500:])
        raise SystemExit("步骤失败：%s" % what)
    if (r.stderr or "").strip():
        print((r.stderr or "").strip()[:600])
    return r


def collect_java(root):
    out = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def ensure_keystore():
    if os.path.exists(KEYSTORE):
        return
    os.makedirs(os.path.dirname(KEYSTORE), exist_ok=True)
    print("生成固定调试签名 keystore（仅调试用，口令 android）")
    run([os.path.join(JDK, "bin", "keytool.exe"), "-genkeypair",
         "-keystore", KEYSTORE, "-storetype", "PKCS12",
         "-storepass", "android", "-keypass", "android",
         "-alias", "androiddebugkey",
         "-dname", "CN=WatchCat Debug,O=WatchCat,C=CN",
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000"],
        what="生成 keystore")


def add_entries(apk_path, dex, libs):
    """把 dex 与 .so 以「不压缩」方式塞进 aapt2 产出的 APK"""
    with zipfile.ZipFile(apk_path, "a", compression=zipfile.ZIP_STORED) as z:
        names = set(z.namelist())
        if "classes.dex" not in names:
            z.write(dex, "classes.dex")
            names.add("classes.dex")
        for abi, path in libs.items():
            arc = "lib/%s/%s" % (abi, os.path.basename(path))
            if arc not in names:
                z.write(path, arc)


def verify_entries(apk_path, libs):
    """只核对「有没有被压缩」；页对齐交给 zipalign -c（本地头额外字段长度
    与中央目录不一定一致，手算容易误判，实测就误判过）。"""
    ok = True
    with zipfile.ZipFile(apk_path) as z:
        for abi, path in libs.items():
            arc = "lib/%s/%s" % (abi, os.path.basename(path))
            info = z.getinfo(arc)
            stored = info.compress_type == zipfile.ZIP_STORED
            print("    %-28s stored=%s  size=%d" % (arc, stored, info.file_size))
            ok = ok and stored
        dex = z.getinfo("classes.dex")
        print("    %-28s stored=%s  size=%d" % ("classes.dex", dex.compress_type == zipfile.ZIP_STORED, dex.file_size))
    return ok


def main():
    for p in (BUILD_TOOLS, PLATFORM_JAR, MANIFEST):
        if not os.path.exists(p):
            raise SystemExit("缺少依赖：%s" % p)

    libs = {}
    for abi in ("arm64-v8a", "x86_64"):
        p = os.path.join(JNI_LIBS, abi, "libwatchcat.so")
        if os.path.exists(p):
            libs[abi] = p
    if not libs:
        raise SystemExit("jniLibs 里没有 libwatchcat.so，先跑 py tools/build_so.py")

    shutil.rmtree(WORK, ignore_errors=True)
    for sub in ("classes", "dex"):
        os.makedirs(os.path.join(WORK, sub), exist_ok=True)
    os.makedirs(os.path.dirname(OUT_APK), exist_ok=True)

    print("[1/6] 编译 Java")
    sources = collect_java(JAVA_SRC)
    if not sources:
        raise SystemExit(
            "没有 .java 源文件 —— 空壳已迁到 Kotlin，正式构建走 `gradle :app:assembleDebug`。\n"
            "本脚本只保留为「Java 时代」的本机对照工具；Kotlin 代码它编不了（不依赖 kotlinc）。"
        )
    run([os.path.join(JDK, "bin", "javac.exe"), "-source", "8", "-target", "8",
         "-Xlint:-options", "-bootclasspath", PLATFORM_JAR,
         "-encoding", "UTF-8", "-d", os.path.join(WORK, "classes"), *sources], what="javac")

    print("[2/6] 打包 class 并 dex")
    jar_path = os.path.join(WORK, "classes.jar")
    run([os.path.join(JDK, "bin", "jar.exe"), "cf", jar_path, "-C", os.path.join(WORK, "classes"), "."], what="jar")
    run([os.path.join(BUILD_TOOLS, "d8.bat"), "--lib", PLATFORM_JAR,
         "--min-api", MIN_SDK, "--output", os.path.join(WORK, "dex"), jar_path], what="d8")

    print("[3/6] aapt2 链接清单")
    base = os.path.join(WORK, "base.apk")
    run([os.path.join(BUILD_TOOLS, "aapt2.exe"), "link",
         "-o", base, "-I", PLATFORM_JAR, "--manifest", MANIFEST,
         "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--version-code", VERSION_CODE, "--version-name", VERSION_NAME], what="aapt2 link")

    print("[4/6] 塞入 dex 与 未压缩 .so")
    add_entries(base, os.path.join(WORK, "dex", "classes.dex"), libs)

    print("[5/6] 16KB 对齐（zipalign -P 16）")
    aligned = os.path.join(WORK, "aligned.apk")
    run([os.path.join(BUILD_TOOLS, "zipalign.exe"), "-P", "16", "-f", "4", base, aligned], what="zipalign")

    ensure_keystore()
    print("[6/6] 签名并复核")
    run([os.path.join(BUILD_TOOLS, "apksigner.bat"), "sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--min-sdk-version", MIN_SDK, "--out", OUT_APK, aligned], what="apksigner sign")
    run([os.path.join(BUILD_TOOLS, "apksigner.bat"), "verify", "--print-certs", OUT_APK], what="apksigner verify")
    run([os.path.join(BUILD_TOOLS, "zipalign.exe"), "-c", "-P", "16", "4", OUT_APK], what="zipalign 复核")

    print("\nAPK 内部条目：")
    entries_ok = verify_entries(OUT_APK, libs)

    size = os.path.getsize(OUT_APK)
    with open(OUT_APK, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    print("\n产物   : %s" % OUT_APK.replace(REPO + os.sep, ""))
    print("大小   : %d bytes" % size)
    print("SHA256 : %s" % sha)
    print("条目   : %s" % ("OK" if entries_ok else "有未压缩/未对齐项，请检查"))
    return 0 if entries_ok else 1


if __name__ == "__main__":
    sys.exit(main())
