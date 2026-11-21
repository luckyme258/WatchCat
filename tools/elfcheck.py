#!/usr/bin/env python3
"""ELF64 体检：不开 NDK、不装 readelf，直接读二进制头。

用途
  1. 本机验 .so 是否 16KB 页对齐（Android 15+ 的 16KB 设备会拒载 4KB 对齐的库）；
  2. 验产物类型、架构、导出符号、依赖；
  3. 给 CI 当门禁：任何一项不过，退出码非 0。

用法
  py tools/elfcheck.py app/src/main/jniLibs/arm64-v8a/libwatchcat.so
  py tools/elfcheck.py <多个文件>            # 全部通过才返回 0
  from elfcheck import analyze               # 也可被 build_so.py 直接 import

判定口径
  - 类型必须是 ET_DYN（共享库）；
  - 架构必须匹配预期（arm64 为 AArch64，x86_64 为 x86-64）；
  - 每个 PT_LOAD 的 p_align 必须 >= 0x4000 且是 0x4000 的整数倍；
  - 不允许出现 TEXTREL（部分场景下安卓会拒绝加载）；
  - 报告 DT_NEEDED 与未定义符号，便于确认「有没有偷偷拖别的库」。
"""

import struct
import sys

PT_LOAD = 1
SHT_DYNSYM = 11
SHT_DYNAMIC = 6
ELF_TYPE_DYN = 3
MACHINE_NAMES = {0xB7: "AArch64", 0x3E: "x86-64", 0x28: "ARM"}


class Elf64:
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            self.data = f.read()
        d = self.data
        if d[:4] != b"\x7fELF":
            raise ValueError("不是 ELF 文件: " + path)
        if d[4] != 2:
            raise ValueError("只支持 ELF64: " + path)
        self.end = "<" if d[5] == 1 else ">"
        e = self.end
        self.e_type, self.e_machine = struct.unpack_from(e + "HH", d, 0x10)
        self.e_phoff, = struct.unpack_from(e + "Q", d, 0x20)
        self.e_shoff, = struct.unpack_from(e + "Q", d, 0x28)
        self.e_phentsize, self.e_phnum = struct.unpack_from(e + "HH", d, 0x36)
        self.e_shentsize, self.e_shnum = struct.unpack_from(e + "HH", d, 0x3A)
        self.e_shstrndx, = struct.unpack_from(e + "H", d, 0x3E)

    # ---- 程序头 ----
    def load_aligns(self):
        out = []
        for i in range(self.e_phnum):
            off = self.e_phoff + i * self.e_phentsize
            p_type, = struct.unpack_from(self.end + "I", self.data, off)
            if p_type != PT_LOAD:
                continue
            p_align, = struct.unpack_from(self.end + "Q", self.data, off + 0x30)
            out.append(p_align)
        return out

    # ---- 节表 ----
    def sections(self):
        secs = []
        for i in range(self.e_shnum):
            off = self.e_shoff + i * self.e_shentsize
            sh_name, sh_type = struct.unpack_from(self.end + "II", self.data, off)
            sh_offset, sh_size = struct.unpack_from(self.end + "QQ", self.data, off + 0x18)
            sh_link, = struct.unpack_from(self.end + "I", self.data, off + 0x28)
            secs.append({"name_off": sh_name, "type": sh_type, "off": sh_offset,
                         "size": sh_size, "link": sh_link})
        return secs

    def _strtab(self, sec):
        return self.data[sec["off"]:sec["off"] + sec["size"]]

    def _sec_name(self, sec, strtab):
        p = sec["name_off"]
        q = strtab.find(b"\0", p)
        return strtab[p:q].decode(errors="replace")

    def dynamic_info(self):
        """返回 (soname, needed[], has_textrel)"""
        soname, needed, textrel = None, [], False
        secs = self.sections()
        shstr = self._strtab(secs[self.e_shstrndx])
        for sec in secs:
            if sec["type"] != SHT_DYNAMIC:
                continue
            dstr = self._strtab(secs[sec["link"]])
            for i in range(sec["size"] // 16):
                tag, val = struct.unpack_from(self.end + "qQ", self.data, sec["off"] + i * 16)
                if tag == 0:
                    break
                if tag == 1:      # DT_NEEDED
                    needed.append(dstr[val:dstr.find(b"\0", val)].decode(errors="replace"))
                elif tag == 14:   # DT_SONAME
                    soname = dstr[val:dstr.find(b"\0", val)].decode(errors="replace")
                elif tag == 22:   # DT_TEXTREL
                    textrel = True
                elif tag == 30 and (val & 0x4):  # DT_FLAGS & DF_TEXTREL
                    textrel = True
        return soname, needed, textrel

    def symbols(self):
        """返回 (导出[], 未定义[])，只列 GLOBAL/WEAK 且有名字的符号"""
        exported, imported = [], []
        secs = self.sections()
        for sec in secs:
            if sec["type"] != SHT_DYNSYM:
                continue
            dstr = self._strtab(secs[sec["link"]])
            for i in range(1, sec["size"] // 24):
                off = sec["off"] + i * 24
                st_name, = struct.unpack_from(self.end + "I", self.data, off)
                st_shndx, = struct.unpack_from(self.end + "H", self.data, off + 6)
                if not st_name:
                    continue
                nm = dstr[st_name:dstr.find(b"\0", st_name)].decode(errors="replace")
                (imported if st_shndx == 0 else exported).append(nm)
        return exported, imported


def analyze(path, expect_machine=None):
    """体检单个文件，返回 (ok, report 行列表)"""
    lines, ok = [], True
    try:
        elf = Elf64(path)
    except Exception as exc:                      # noqa: BLE001
        return False, ["FAIL  %s: %s" % (path, exc)]

    lines.append("file        : " + path)
    lines.append("e_type      : %s" % ("ET_DYN(shared)" if elf.e_type == ELF_TYPE_DYN else "非共享库(%d)" % elf.e_type))
    machine = MACHINE_NAMES.get(elf.e_machine, "0x%X" % elf.e_machine)
    lines.append("e_machine   : " + machine)

    if elf.e_type != ELF_TYPE_DYN:
        ok = False
        lines.append("类型        : FAIL（必须是 ET_DYN）")
    if expect_machine and machine != expect_machine:
        ok = False
        lines.append("架构        : FAIL（期望 %s，实际 %s）" % (expect_machine, machine))

    aligns = elf.load_aligns()
    bad = [a for a in aligns if a < 0x4000 or a % 0x4000 != 0]
    lines.append("PT_LOAD     : %d 段, p_align = %s" % (len(aligns), [hex(a) for a in aligns]))
    if not aligns or bad:
        ok = False
        lines.append("16KB 对齐   : FAIL（每个 PT_LOAD 需 >=0x4000 且为 0x4000 整数倍）")
    else:
        lines.append("16KB 对齐   : PASS")

    soname, needed, textrel = elf.dynamic_info()
    exported, imported = elf.symbols()

    lines.append("SONAME      : " + (soname or "(无)"))
    lines.append("DT_NEEDED   : " + (", ".join(needed) if needed else "(无，不依赖任何 .so)"))
    lines.append("TEXTREL     : " + ("存在!! FAIL" if textrel else "无"))
    if textrel:
        ok = False
    lines.append("导出符号    : " + (", ".join(sorted(exported)) if exported else "(无)"))
    lines.append("未定义符号  : " + (", ".join(sorted(imported)) if imported else "(无)"))
    lines.append("size        : %d bytes" % len(elf.data))
    return ok, lines


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    all_ok = True
    for path in argv:
        expect = None
        if "arm64-v8a" in path or "aarch64" in path:
            expect = "AArch64"
        elif "x86_64" in path:
            expect = "x86-64"
        ok, lines = analyze(path, expect)
        all_ok = all_ok and ok
        print("\n".join(lines))
        print("-" * 60)
    print("总判定: " + ("PASS" if all_ok else "FAIL"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
