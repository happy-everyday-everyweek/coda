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
        print("   blob note 偏移=%d 长度=%d 结束于 %d" % (off, length, off + length))
        if off + length > size:
            problems.append("blob note 越过文件尾，文件被截断")
        elif not any(o <= off and o + l >= off + length for o, l in loads):
            problems.append("blob note 没有被可加载段覆盖，运行期读不到")
        elif e_shoff != off + length:
            problems.append("节头表没有紧跟 blob note，注入布局异常")
        elif b"NODE_SEA_BLOB" not in data[off:off + 512]:
            problems.append("note 段开头没有 NODE_SEA_BLOB 记录，node 读不到内核 blob")

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

    return report(problems)


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
