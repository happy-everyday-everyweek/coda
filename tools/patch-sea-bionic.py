#!/usr/bin/env python3
"""给 SEA 单文件写入安卓 bionic 兼容修复。

问题：postject 的 ELF 查找回调假定 dl_iterate_phdr 的第一个对象就是主程序，
把它的 dl_phdr_info 整份拷走就停止遍历。bionic 的第一个对象是静态 libdl_info，
主程序排在第二个，于是拷到的是 libdl_info 的信息，其程序头表里没有 PT_NOTE，
node 扫描不到 NODE_SEA_BLOB，拿到空 blob 后按空数据解析，进程被 SIGSEGV 杀死，
退出码 139。glibc 保证第一个对象是主程序，所以同一份二进制在桌面 Linux 上正常。

修法：让回调不再依赖遍历顺序。用一条跳转替换回调里的 mov 指令，跳到 .text 中
一段零填充，在那里用 getauxval 取 AT_PHDR 与 AT_PHNUM，用程序头表首项的
p_vaddr 反推主程序基址，直接填出 dl_phdr_info 再返回 1。

注入会把整份内容后移一页，入口地址由 0x1694000 变成 0x1695000，脚本按入口地址
判定传入的是基础镜像还是注入产物，据此换算全部落点，两者都能处理。

脚本按固定布局定位，写入前逐项断言原始字节，底座或布局变化会直接报错退出，不会
写出半成品。
"""

import struct
import sys
from pathlib import Path

ELF_MACHINE_AARCH64 = 0xB7

# 入口地址用来判定是否存在注入位移。
NODE_ENTRY = 0x1694000
INJECTED_ENTRY = 0x1695000
SHIFT = 0x1000

# 基础镜像中的落点，注入产物在此基础上整体后移一页。
CALLBACK_ENTRY = 0x1812888
PATCH_PC = 0x181288C
CAVE = 0x2169C5C
GETAUXVAL_PLT = 0x2A7C970

EXPECT_ENTRY = 0xD503245F       # bti c
EXPECT_PATCH_ORIG = 0xAA0003E8  # mov x8, x0
EXPECT_STUB_LDR = 0xF944DA11    # ldr x17, [x16, #2480]
EXPECT_STUB_ADD = 0x9126C210    # add x16, x16, #0x9b0

CAVE_SIZE = 0x4C


def word(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def encode_branch(pc, target, opcode):
    delta = target - pc
    if delta % 4 != 0:
        raise SystemExit("跳转目标未按 4 字节对齐：0x%x" % target)
    imm = delta >> 2
    if not -0x2000000 <= imm < 0x2000000:
        raise SystemExit("跳转超出 26 位范围：0x%x -> 0x%x" % (pc, target))
    return opcode | (imm & 0x3FFFFFF)


def encode_b(pc, target):
    return encode_branch(pc, target, 0x14000000)


def encode_bl(pc, target):
    return encode_branch(pc, target, 0x94000000)


def resolve_shift(data):
    entry = struct.unpack_from("<Q", data, 24)[0]
    if entry == INJECTED_ENTRY:
        return SHIFT
    if entry == NODE_ENTRY:
        return 0
    raise SystemExit("入口地址 0x%x 与预期不符，基础镜像已变化" % entry)


def cave_code(cave, getauxval_plt):
    # 逐条列出，其中两条 bl 与位置相关，按实际地址回填。
    words = [
        0xD503245F,  # bti c
        0xA9BE53F3,  # stp x19, x20, [sp, #-32]!
        0xF9000BFE,  # str x30, [sp, #16]
        0xAA0203F3,  # mov x19, x2
        0x52800060,  # mov w0, #3        取 AT_PHDR
        None,        # bl getauxval
        0xAA0003F4,  # mov x20, x0
        0x528000A0,  # mov w0, #5        取 AT_PHNUM
        None,        # bl getauxval
        0x2A0003EC,  # mov w12, w0
        0xF9400A8A,  # ldr x10, [x20, #16]  程序头表首项的 p_vaddr
        0xCB0A028B,  # sub x11, x20, x10    主程序基址
        0xF900026B,  # str x11, [x19]
        0xF9000A74,  # str x20, [x19, #16]
        0x7900326C,  # strh w12, [x19, #24]
        0x52800020,  # mov w0, #1
        0xF9400BFE,  # ldr x30, [sp, #16]
        0xA8C253F3,  # ldp x19, x20, [sp], #32
        0xD65F03C0,  # ret
    ]
    for index, item in enumerate(words):
        if item is None:
            words[index] = encode_bl(cave + index * 4, getauxval_plt)
    return struct.pack("<%dI" % len(words), *words)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("用法：patch-sea-bionic.py <基础镜像或 SEA 单文件>")

    path = Path(sys.argv[1])
    data = bytearray(path.read_bytes())

    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("不是 64 位小端 ELF：%s" % path)
    if struct.unpack_from("<H", data, 18)[0] != ELF_MACHINE_AARCH64:
        raise SystemExit("不是 aarch64：%s" % path)

    shift = resolve_shift(data)
    callback_entry = CALLBACK_ENTRY + shift
    patch_pc = PATCH_PC + shift
    cave = CAVE + shift
    getauxval_plt = GETAUXVAL_PLT + shift

    if word(data, getauxval_plt + 4) != EXPECT_STUB_LDR or word(data, getauxval_plt + 8) != EXPECT_STUB_ADD:
        raise SystemExit("getauxval 的 PLT 桩与预期不符，基础镜像已变化")
    if word(data, callback_entry) != EXPECT_ENTRY:
        raise SystemExit("回调入口第一条指令不是 bti c，定位失效")

    current = word(data, patch_pc)
    if current == encode_b(patch_pc, cave):
        print("== bionic 修复：已应用，跳过")
        return

    if current != EXPECT_PATCH_ORIG:
        raise SystemExit("回调第二条指令不是预期内容，定位失效")

    if any(data[cave:cave + CAVE_SIZE]):
        raise SystemExit("代码洞已被占用，无法写入修复代码")

    data[patch_pc:patch_pc + 4] = struct.pack("<I", encode_b(patch_pc, cave))
    data[cave:cave + CAVE_SIZE] = cave_code(cave, getauxval_plt)
    path.write_bytes(data)

    print("== bionic 修复：跳板 0x%x -> 0x%x，修复代码写入 0x%x" % (patch_pc, cave, cave))


if __name__ == "__main__":
    main()