#!/usr/bin/env python3
"""核对 SEA 载荷的结构。

重建出的 `assets/core/zcode` 是「node 基础镜像 + 追加上去的内核 blob」。这里只做结构核对：
ELF 是否 64 位可执行、可加载段是否落在文件内、节头表是否完整、内嵌的 blob 节是否铺到文件尾、
fuse 是否已打开。重建后跑一次，注入写坏时当天就能看到，不会把一个起不来的二进制打进 APK。

已知可用的载荷在仓库里就有一份，先拿它对一遍再上 CI。
"""
import struct
import sys

MACHINE_AARCH64 = 0xB7
MACHINE_X86_64 = 0x3E

# 安卓 bionic 兼容修复在注入产物里的落点：回调入口的首条指令、被跳板替换的指令、
# 承载修复代码的零填充区。注入会把基础镜像的内容整体后移一页，这里用的是产物地址。
BIONIC_PATCH_ANCHOR = 0x1813888
BIONIC_PATCH_PC = 0x181388C
BIONIC_PATCH_CAVE = 0x216AC5C
BTI_C = 0xD503245F


def branch_encoding(pc, target):
    return 0x14000000 | (((target - pc) >> 2) & 0x3FFFFFF)


def main(path):
    with open(path, "rb") as f:
        data = f.read()
    size = len(data)
    print("== 载荷结构核对 %s" % path)
    print("   字节数 %d" % size)
    if data[:4] != b"\x7fELF":
        return report(["不是 ELF 文件"])
    if data[4] != 2:
        return report(["不是 64 位 ELF"])

    e_machine = struct.unpack_from("<H", data, 18)[0]
    e_entry, e_phoff, e_shoff = struct.unpack_from("<QQQ", data, 24)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 58)
    print(
        "   机器=0x%x 入口=0x%x 程序头=%d 节头=%d 节头表偏移=%d"
        % (e_machine, e_entry, e_phnum, e_shnum, e_shoff)
    )
    if e_machine not in (MACHINE_AARCH64, MACHINE_X86_64):
        print("   提示：机器类型既不是 aarch64 也不是 x86_64")

    problems = []
    loads = []
    notes = []
    for i in range(e_phnum):
        head = struct.unpack_from("<IIQQQQQQ", data, e_phoff + i * e_phentsize)
        if head[0] == 1:
            loads.append((head[2], head[5]))
        elif head[0] == 4:
            notes.append((head[2], head[5]))
        if head[2] + head[5] > size:
            problems.append("第 %d 个程序头越过文件尾" % i)
    loaded_end = max((o + l for o, l in loads), default=0)
    print("   可加载段 %d 个，覆盖到 %d" % (len(loads), loaded_end))

    blob_note = None
    for off, length in notes:
        if length > 1_000_000:
            blob_note = (off, length)
    if blob_note is None:
        problems.append("没有覆盖内核 blob 的 note 段，注入没有写进去")
    else:
        off, length = blob_note
        print("   blob note 段 偏移=%d 长度=%d 结束于 %d" % (off, length, off + length))
        if off + length > size:
            problems.append("blob note 段越过文件尾，文件被截断")
        elif not any(o <= off and o + l >= off + length for o, l in loads):
            problems.append("blob note 段没有被可加载段覆盖，运行期读不到")
        else:
            name, desc_len = find_note(data, off, off + length, b"NODE_SEA_BLOB")
            if name is None:
                problems.append("note 段里没有 NODE_SEA_BLOB 记录，node 找不到内核 blob")
            else:
                print("   note %s 数据 %d 字节" % (name.decode("latin1"), desc_len))
        # 节头表只要落在 note 段之后即可，注入工具会按需要留出对齐间隙。
        if e_shoff < off + length:
            problems.append("节头表落在 blob note 段里面，注入布局异常")

    if not e_shoff or e_shoff + e_shnum * e_shentsize > size:
        problems.append("节头表越出文件尾")
    else:
        str_off = struct.unpack_from("<Q", data, e_shoff + e_shstrndx * e_shentsize + 24)[0]
        names = []
        for i in range(e_shnum):
            head = struct.unpack_from("<IIQQQQIIQQ", data, e_shoff + i * e_shentsize)
            name_off = head[0]
            end = data.find(b"\x00", str_off + name_off)
            name = data[str_off + name_off:end].decode("utf-8", "replace")
            names.append(name)
            if head[4] + head[5] != loaded_end:
                print("   节 %s 偏移=%d 长度=%d" % (name, head[4], head[5]))
        print("   节数 %d，节头表结束于 %d" % (len(names), e_shoff + e_shnum * e_shentsize))
        if e_shoff + e_shnum * e_shentsize != size:
            print("   提示：节头表未铺到文件尾，尾部还有 %d 字节" % (size - e_shoff - e_shnum * e_shentsize))

    fuse_at = data.find(b"NODE_SEA_FUSE_")
    if fuse_at < 0:
        problems.append("缺少 NODE_SEA_FUSE 标记")
    else:
        fuse = data[fuse_at:fuse_at + 80].split(b"\x00")[0].decode("latin1")
        print("   fuse %s" % fuse)
        if not fuse.endswith(":1"):
            problems.append("fuse 没有打开，node 不会进入单文件模式")

    # 安卓 bionic 兼容修复：跳板与修复代码必须就位，缺了内核在 Android 上启动即被信号杀死。
    if size > BIONIC_PATCH_CAVE + 4:
        anchor = struct.unpack_from("<I", data, BIONIC_PATCH_ANCHOR)[0]
        patch = struct.unpack_from("<I", data, BIONIC_PATCH_PC)[0]
        cave_head = struct.unpack_from("<I", data, BIONIC_PATCH_CAVE)[0]
        if anchor != BTI_C:
            problems.append("回调入口不是 bti c，补丁定位与基础镜像不符")
        elif patch != branch_encoding(BIONIC_PATCH_PC, BIONIC_PATCH_CAVE):
            problems.append("回调里没有跳板，内核在 Android 上会以 139 退出")
        elif cave_head != BTI_C:
            problems.append("修复代码没有写入代码洞")
        else:
            print("   bionic 修复：跳板与修复代码就位")
    else:
        problems.append("文件太短，读不到 bionic 修复的落点")

    return report(problems)


def find_note(data, start, end, wanted):
    """在 note 段里按 ELF note 的排布逐条查找，命中时返回名字与数据长度。"""
    p = start
    while p + 12 <= end:
        namesz, descsz, _ = struct.unpack_from("<III", data, p)
        if namesz == 0 and descsz == 0:
            return (None, 0)
        name = data[p + 12:p + 12 + namesz].rstrip(b"\x00")
        desc = p + 12 + ((namesz + 3) // 4) * 4
        if name == wanted:
            return (name, descsz)
        p = desc + ((descsz + 3) // 4) * 4
    return (None, 0)


def report(problems):
    for p in problems:
        print("!! " + p)
    print("== 核对结果：%s" % ("通过" if not problems else "未通过"))
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("用法：verify-sea-payload.py <可执行文件>")
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
