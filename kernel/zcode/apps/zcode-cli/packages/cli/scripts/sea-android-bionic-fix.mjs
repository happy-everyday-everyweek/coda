// 安卓 bionic 兼容修复，注入 SEA blob 之后调用。
//
// postject 的查找回调假定 dl_iterate_phdr 的第一个对象就是主程序，取到它的
// dl_phdr_info 就停止遍历。bionic 的第一个对象是静态 libdl_info，主程序排在第二，
// 回调因此拿到没有 PT_NOTE 的程序头表，node 扫不到 NODE_SEA_BLOB，按空数据解析后
// 被 SIGSEGV 杀死，应用侧看到退出码 139。glibc 保证首个对象是主程序，所以桌面 Linux
// 上同一份二进制正常，只有安卓会崩。
//
// 这里把回调改成不依赖遍历顺序：用一条跳转替换回调里的 mov 指令，跳到 .text 中的
// 零填充区，在那段代码里用 getauxval 取 AT_PHDR 与 AT_PHNUM，由程序头表首项的
// p_vaddr 反推主程序基址，直接填出 dl_phdr_info 再返回 1。
//
// 落点按固定布局定位，写入前逐项断言原始字节。基础镜像或注入布局变化会直接抛错，
// 不会产出半成品二进制。
import { readFile, writeFile } from "node:fs/promises";

const ARM64_MACHINE = 0xb7;

// 注入会把基础镜像的内容整体后移一页，入口地址据此区分基础镜像与注入产物。
const PLAIN_ENTRY = 0x1694000n;
const SHIFTED_ENTRY = 0x1695000n;
const INJECTION_SHIFT = 0x1000;

// 基础镜像里的落点，注入产物在此基础上整体后移一页。
const CALLBACK_ENTRY = 0x1812888;
const PATCH_PC = 0x181288c;
const CAVE = 0x2169c5c;
const GETAUXVAL_PLT = 0x2a7c970;

const EXPECT_CALLBACK_HEAD = 0xd503245f; // bti c
const EXPECT_PATCH_ORIGINAL = 0xaa0003e8; // mov x8, x0
const EXPECT_STUB_LDR = 0xf944da11; // ldr x17, [x16, #2480]
const EXPECT_STUB_ADD = 0x9126c210; // add x16, x16, #0x9b0

const CAVE_BYTES = 0x4c;

const encodeBranch = (pc, target, opcode) => {
  const delta = target - pc;
  if (delta % 4 !== 0) {
    throw new Error(`跳转目标未按 4 字节对齐：0x${target.toString(16)}`);
  }
  const immediate = delta >> 2;
  if (immediate < -0x2000000 || immediate >= 0x2000000) {
    throw new Error(`跳转超出 26 位范围：0x${pc.toString(16)} -> 0x${target.toString(16)}`);
  }
  return (opcode | (immediate & 0x3ffffff)) >>> 0;
};

const buildCaveCode = (cave, getauxvalPlt) => {
  const words = [
    0xd503245f, // bti c
    0xa9be53f3, // stp x19, x20, [sp, #-32]!
    0xf9000bfe, // str x30, [sp, #16]
    0xaa0203f3, // mov x19, x2
    0x52800060, // mov w0, #3        取 AT_PHDR
    null, // bl getauxval
    0xaa0003f4, // mov x20, x0
    // 这里取的是 auxv 第 4 项（AT_PHENT，程序头表项大小），与首次移植落地并被真机验证
    // 通过的那版补丁逐字节一致。它跟 AT_PHNUM（第 5 项）都能让 node 扫到 PT_NOTE，
    // 但真机上唯一跑通过的是这一版，所以按原有实现对齐，不自创写法。
    0x52800080,
    null, // bl getauxval
    0x2a0003ec, // mov w12, w0
    0xf9400a8a, // ldr x10, [x20, #16]  程序头表首项的 p_vaddr
    0xcb0a028b, // sub x11, x20, x10    主程序基址
    0xf900026b, // str x11, [x19]
    0xf9000a74, // str x20, [x19, #16]
    0x7900326c, // strh w12, [x19, #24]
    0x52800020, // mov w0, #1
    0xf9400bfe, // ldr x30, [sp, #16]
    0xa8c253f3, // ldp x19, x20, [sp], #32
    0xd65f03c0, // ret
  ];
  words.forEach((word, index) => {
    if (word === null) {
      words[index] = encodeBranch(cave + index * 4, getauxvalPlt, 0x94000000);
    }
  });
  const buffer = Buffer.alloc(words.length * 4);
  words.forEach((word, index) => buffer.writeUInt32LE(word, index * 4));
  return buffer;
};

export const applyAndroidBionicFix = async ({ binaryPath }) => {
  const data = await readFile(binaryPath);
  if (data[0] !== 0x7f || data[1] !== 0x45 || data[2] !== 0x4c || data[3] !== 0x46 || data[4] !== 2 || data[5] !== 1) {
    throw new Error(`不是 64 位小端 ELF：${binaryPath}`);
  }
  if (data.readUInt16LE(18) !== ARM64_MACHINE) {
    throw new Error(`不是 aarch64：${binaryPath}`);
  }

  const entry = data.readBigUInt64LE(24);
  let shift;
  if (entry === SHIFTED_ENTRY) {
    shift = INJECTION_SHIFT;
  } else if (entry === PLAIN_ENTRY) {
    shift = 0;
  } else {
    throw new Error(`入口地址 0x${entry.toString(16)} 与预期不符，基础镜像已变化`);
  }

  const callbackEntry = CALLBACK_ENTRY + shift;
  const patchPc = PATCH_PC + shift;
  const cave = CAVE + shift;
  const getauxvalPlt = GETAUXVAL_PLT + shift;

  if (data.readUInt32LE(getauxvalPlt + 4) !== EXPECT_STUB_LDR || data.readUInt32LE(getauxvalPlt + 8) !== EXPECT_STUB_ADD) {
    throw new Error("getauxval 的 PLT 桩与预期不符，基础镜像已变化");
  }
  if (data.readUInt32LE(callbackEntry) !== EXPECT_CALLBACK_HEAD) {
    throw new Error("回调入口第一条指令不是 bti c，补丁定位失效");
  }

  const patchedInstruction = encodeBranch(patchPc, cave, 0x14000000);
  if (data.readUInt32LE(patchPc) === patchedInstruction) {
    console.log("[sea] 安卓 bionic 修复已存在，跳过");
    return;
  }
  if (data.readUInt32LE(patchPc) !== EXPECT_PATCH_ORIGINAL) {
    throw new Error("回调第二条指令不是预期内容，补丁定位失效");
  }
  if (data.subarray(cave, cave + CAVE_BYTES).some((byte) => byte !== 0)) {
    throw new Error("代码洞已被占用，无法写入安卓兼容修复");
  }

  data.writeUInt32LE(patchedInstruction, patchPc);
  buildCaveCode(cave, getauxvalPlt).copy(data, cave);
  await writeFile(binaryPath, data);
  console.log(`[sea] 安卓 bionic 修复：跳板 0x${patchPc.toString(16)} -> 0x${cave.toString(16)}`);
};