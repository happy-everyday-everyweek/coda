# 依赖许可审核（QA-107）

- 对应任务：QA-107
- 对应验收：QA-007（另有 SEC-001 的秘密/日志边界、DOC-003 的能力诚实性弱相关）
- 审核日期：2026-11-07
- 审核对象：`platform/`（本期新增的跨端平台切片）、`contracts/`、仓库根许可、Android 侧内核载荷
- 审核方法：仓库内文件静态审查（`LICENSE`、`package.json`、`THIRD-PARTY-NOTICES.md`、各内核许可文件）。**未联网核验上游许可文本，未运行任何许可扫描工具**（本机无可用沙箱后端，bash 被拒；见文末「局限」）。

## 1. 结论摘要

| 范围 | 结论 | 说明 |
|---|---|---|
| `platform/` 运行时依赖 | **零外部依赖（静态审查通过）** | `platform/package.json` 无 `dependencies` / `devDependencies`；测试用 `node --test`，不引入测试框架 |
| `platform/contracts/` | **仅使用 Node 内置模块** | `node:crypto`（`randomUUID`）、`node:test` / `node:assert`（测试）；无第三方代码复制 |
| 仓库根（Android App） | **AGPL-3.0** | 根 `LICENSE` 为 GNU AGPL v3；`README.md` 第 26–28 行声明 |
| 内核载荷（`kernel/zcode`） | **Apache-2.0（自带 LICENSE）+ 第三方 NOTICE** | `kernel/zcode/LICENSE` 为 Apache License 2.0；第三方声明见 `kernel/zcode/THIRD-PARTY-NOTICES.md`（约 5 万行，由 `node scripts/licenses.mjs notices` 生成） |
| 内核载荷（`kernel/maibot-lite`） | **GPL-3.0 + AGPL-3.0 混合，需核实** | `kernel/maibot-lite/LICENSE` 为 GPL-3.0；`src/A_memorix` 默认 AGPL-3.0，另有面向 MaiBot 项目的 GPL-3.0 附加授权（`LICENSE-MAIBOT-GPL.md`）；适用范围需 Lead/法务确认 |
| GPUI / Rust 引入 | **仅 CI 冒烟引入，未发布二进制；前置检查清单见第 5 节** | `platform/gui-smoke/` 固定 zui rev 编译验证；许可/NOTICE 未完成，`QA-007` 的 GPUI 部分仍为 `UNVERIFIED` |
| 总体 `QA-007` | **UNVERIFIED（部分静态 PASS）** | platform/ 部分可给出结论；GPUI revision、NOTICE 汇总、Android 侧逐项审核未完成 |

**禁止表述**：在完成第 6 节全部检查项之前，不得在任何发布说明中写「许可已全部审核通过」。

## 2. `platform/` 依赖盘点

### 2.1 运行时依赖

`platform/package.json` 字段实读结果：

- `"type": "module"`、`"private": true`、`"engines": { "node": ">=22" }`
- `scripts`：`test`（`node --test test/`）、`test:core`、`tui`、`web`、`bench`
- **不存在** `dependencies`、`devDependencies`、`peerDependencies`、`bundledDependencies`

因此 `platform/` 的直接与传递依赖树为空；`npm install` 不产生 `node_modules`（`.gitignore` 已忽略之）。这是当前唯一的硬结论，可随 CI 的 `npm ls --all` 输出复核。

### 2.2 Node 内置模块使用

| 模块 | 使用位置 | 性质 |
|---|---|---|
| `node:crypto` | `contracts/ids.mjs`（`randomUUID`）；后续同步层计划用 X25519 / ChaCha20-Poly1305 | Node 运行时自带，非第三方依赖 |
| `node:test` / `node:assert/strict` | `test/contracts/contracts.test.mjs` | 测试运行器，随 Node 分发 |
| `node:sqlite` | `core/journal.mjs`、`pa/store.mjs` | Node 22+ 内置；若未来替换为 `better-sqlite3` 等原生包，**必须**重新走本审核（新增 native 依赖） |

许可含义：上述代码由本项目编写，采用与仓库一致的 AGPL-3.0；Node.js 本身是 MIT，作为运行时被调用不改变本项目许可，但**分发 Node 二进制**时必须随附其许可与版权声明。

### 2.3 已分发的 Node 二进制载荷

`kernel/zcode/third-party/runtime/` 下存在 3 份许可文本：

- `node-22.16.0-LICENSE.txt`
- `node-24.14.0-LICENSE.txt`
- `node-24.18.0-LICENSE.txt`

处置要求：随安装包保留对应版本的许可文本与版权行，不得裁剪；版本与载荷实际二进制一致（本轮未逐字比对哈希，列为待办）。

## 3. Android 侧（本仓库根）

| 项 | 证据 | 处置 |
|---|---|---|
| 项目整体许可 | `LICENSE`（AGPL-3.0 全文）、`README.md` 许可段 | 所有新增 `platform/` 代码默认 AGPL-3.0，提交时不得添加更严格或冲突的头部许可 |
| 内核第三方组件 | `kernel/zcode/THIRD-PARTY-NOTICES.md`、`kernel/zcode/third-party/inventory.json`、`third-party/native-search/sources.json` | 安装包必须携带 NOTICE 的可读副本；新增组件须重新生成 NOTICE（生成器：`scripts/licenses.mjs notices`） |
| 内置 Linux 环境 / PRoot / Chaquopy 等 | `README.md` 致谢段列出的上游 | 二进制分发需各自的许可文本；本轮未逐项核对，`UNVERIFIED` |
| MaiBot Lite 内核 | `kernel/maibot-lite/LICENSE`（GPL-3.0）、`src/A_memorix/LICENSE`、`src/A_memorix/LICENSE-MAIBOT-GPL.md`、`src/llm_models/LICENSE` | **需核实**：A_memorix 默认 AGPL-3.0，附加 GPL-3.0 授权仅面向 MaiBot 项目；Coda 作为下游是否落入该授权范围，须由 Lead/法务确认，不得在文档中默认其适用 |

许可兼容性说明（基于许可证文本的通行理解，**非法律意见**）：

- AGPL-3.0 项目可以组合 GPL-3.0 组件，组合后的整体按 AGPL-3.0 分发（AGPL-3.0 第 13 节允许与 GPLv3 作品链接/组合）。
- Apache-2.0 与 GPLv3/AGPLv3 兼容（Apache-2.0 专利条款可被 GPLv3 吸收），但分发时须保留 Apache-2.0 的 NOTICE 与归属声明。
- AGPL-3.0 的关键义务：**若通过网络向用户提供服务**，须向这些用户提供对应源码（第 13 节）。本项目若把核心作为局域网/远程服务运行，必须能给出源码获取方式；这是产品义务，不只是仓库义务。

## 4. 许可与「无外部依赖」的边界

`platform/` 目前零依赖，但以下变化会**立即**触发重新审核，需在 PR 中显式声明：

1. 引入任何 npm 包（含 `better-sqlite3`、`ws`、测试框架、打包器）。
2. 引入 Rust crate（GPUI、tokio、serde、`cargo` 生态）或复制上游源码片段。
3. 复制参考项目（T3 Code、Zeron、Zed/ACP 文档示例）的代码，而非仅借鉴思想——文档借鉴不产生许可义务，代码复制产生。
4. 引入字体、图标、截图、音频等资产（资产许可常与代码许可不同）。
5. 打包 Node/Electron/GPUI 二进制或安装器（涉及再分发义务与 NOTICE 汇总）。

## 5. GPUI / Rust 引入前置检查清单（部分执行：仅 CI 冒烟）

`SPEC.md` 5.4 指定 GUI 采用 Rust + GPUI；`TODO.md` 的 `GUI-101` 与 `QA-007` 都要求先固定版本与许可。在**任何** Rust 代码进入仓库前，必须完成。当前只新增了不发布的 `platform/gui-smoke/`（CI 验证用），第 1 项已完成，其余仍未完成：

- [x] 固定 GPUI 与所有直接 crate 的**精确 revision**（git rev 或 `=x.y.z`），禁止 `*` 或浮动分支。
      `gpui` / `gpui_platform` = `zeronsh/zui@b564a34413bb7ef1da04121c4a287758f94993d9`。
- [ ] 记录每个 crate 的 `license` 字段与 `LICENSE` 文件路径；`cargo metadata --format-version 1` 输出入库。
- [ ] 运行 `cargo deny check licenses`（或 `cargo-license`）并把配置与输出归档到 `artifacts/acceptance/<run-id>/`。
- [ ] 核对是否存在 `source-available` / 非 OSI 许可 / 商用限制 / 附加条款（"commons clause"、"SSPL"、"BUSL"）。
- [ ] 确认 GPUI 自身许可与本项目 AGPL-3.0 的兼容方向，并确认是否需要随二进制分发其 NOTICE。
- [ ] 确认静态/动态链接方式对「提供源码」义务的影响（Rust 默认静态链接，须保留全部归属声明）。
- [ ] 生成并提交 Rust 依赖的 `NOTICE`/`THIRD-PARTY-NOTICES` 增补段，与现有 Android NOTICE 合并策略一致。
- [ ] 记录 Rust 工具链版本（`rust-toolchain.toml`），并把「工具链不可得」的构建环境限制写进发布说明。
- [ ] 若引入 Zed 上游代码，逐文件核对文件头许可，不得整目录复制。
- [ ] 在 `KNOWN-LIMITATIONS.md` 中登记「GPUI 许可审核未完成前不得发布 GUI 安装包」。

## 6. 待办与阻塞

| 编号 | 待办 | 阻塞原因 | 责任 |
|---|---|---|---|
| LIC-01 | 汇总 `platform/` 的 NOTICE（当前零依赖，暂无内容） | 无内容可汇总；依赖引入后补充 | 实现者 |
| LIC-02 | 核实 `kernel/maibot-lite` 与 `src/A_memorix` 的附加授权是否覆盖 Coda | 需上游授权文本与法务判断，超出工程判断 | Lead / 法务 |
| LIC-03 | 逐项核对内置载荷（PRoot、Alpine、Chaquopy、ripgrep、ugrep、jieba 等）的许可与 NOTICE 覆盖 | 未执行；体积大且需上游文本 | Lead |
| LIC-04 | 校验 `node-*.19/14/22-LICENSE.txt` 与实际载荷版本一致 | 未执行 | 实现者 |
| LIC-05 | GPUI/Rust 引入前的第 5 节清单 | 已引入 CI 冒烟 crate（未发布）；除 revision 固定外，许可/NOTICE/deny 检查未完成 | 未来 GUI 任务 |
| LIC-06 | 在发布说明中加入「源码获取方式」（AGPL 第 13 节） | 依赖发布渠道确定 | QA-109 |

## 7. 局限与声明

- 本审核**只做静态文件阅读**，未运行 `npm ls`、`cargo deny`、`license-checker` 等工具，也未联网核对上游许可证原文；因此结论限定为「截至审核日、仓库内可读文本所示」。
- 本审核不是法律意见。第 3 节关于 GPL/AGPL 组合的说明仅用于工程决策，最终以法务结论为准。
- `QA-007` 的最终判定必须由 `ACCEPTANCE.md` 第 14 节的证据要求支撑（命令、环境、结果、日志）；当前无此类证据，状态为 `UNVERIFIED`。
