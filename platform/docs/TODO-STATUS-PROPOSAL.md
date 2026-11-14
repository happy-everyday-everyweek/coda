# TODO 状态建议表（给 Lead）

- 状态：**历史快照，已被 `docs/desktop-agent-platform/TODO.md` 的现行状态取代**。
- 用途：供 Lead 在合并后**一次性**更新 `docs/desktop-agent-platform/TODO.md` 的状态列。**本文件不修改 `TODO.md`。**
- 编制时点：2026-11-07 21:10 (+08:00)
- 判定依据：`platform/CONTRACT.md`、`platform/package.json`、`platform/contracts/*.mjs`、`platform/core/*.mjs`、`platform/adapters/*.mjs`、`platform/pa/*.mjs`、`platform/security/*.mjs`、`platform/clients/shared/*.mjs`、`platform/test/contracts/contracts.test.mjs`，以及该时点 `platform/` 的完整文件清单
- **关键限制**：本会话 `bash` 被环境拒绝（无可用沙箱后端），**未执行任何测试**。所有 `DONE` 仅为「文件存在 + 静态审查」，不具备 `ACCEPTANCE.md` 1.3 要求的 `run_id` 证据。

## 1. 状态词表（本表用）

| 建议状态 | 含义 | 写入 `TODO.md` 时 |
|---|---|---|
| `DONE` | 交付物文件存在且静态可核对；对应验收仍可能未 PASS | 可写 DONE，但 PR 必须附证据路径 |
| `DONE（待 CI 确认）` | 交付物存在，但 DoD 要求「CI 通过」，本会话无法执行 | 建议先写 `IN PROGRESS`，CI 绿后改 DONE |
| `IN PROGRESS` | 部分交付物存在，任务未完成 | 可直接写 |
| `BLOCKED` | 依赖环境/上游不可用，本机无法完成 | 可直接写，须附原因 |
| `UNVERIFIED` | **不确定**：检查时点无交付物，但可能有其他子代理已认领/已提交 | **不得**直接写；Lead 核实后定 `OPEN` 或 `DONE` |

## 2. 文档与架构决策（DOC）

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| DOC-101 | P0 | DONE | `SPEC/ACCEPTANCE/TODO/README` 均存在（v0.2） | 否 |
| DOC-102 | P0 | DONE | `docs/adr/ADR-0001`（Lead 交付）；GPUI 许可前置见 `docs/LICENSE-AUDIT.md` 第 5 节 | **是**：ADR-0001 未固定 GPUI revision，若按字面要求算未完成 |
| DOC-103 | P0 | DONE | `docs/adr/ADR-0002`（本次交付，已按 `pa/schema.mjs` 的 HLC 模型校正） | 否 |
| DOC-104 | P0 | DONE | `docs/adr/ADR-0003` | 否 |
| DOC-105 | P0 | DONE | `docs/adr/ADR-0004` | 否 |
| DOC-106 | P0 | DONE | `docs/adr/ADR-0005` | 否 |

## 3. 共享核心（CORE）

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| CORE-101 | P0 | DONE（待 CI 确认） | `package.json`、`CONTRACT.md` §1/§8、`core/errors.mjs`、`contracts/ids.mjs` | 契约测试是否通过 |
| CORE-102 | P0 | DONE（待 CI 确认） | `core/domain.mjs`：Device/Environment/AgentDefinition/AgentInstance/Workspace/Session/Run/Turn/Event/InteractionRequest/Command/Artifact/PaProfile/PaMessage/SyncCursor 的 create+validate | 无 |
| CORE-103 | P0 | IN PROGRESS | 有 `core/journal.mjs`（快照/续传）、`core/eventbus.mjs`、`clients/shared/rpc-client.mjs`、`clients/shared/projection.mjs`；**缺** `core/index.mjs` 统一入口与握手实现 | 是否有入口在另一分支 |
| CORE-104 | P0 | DONE（待 CI 确认） | `core/journal.mjs`：`MIGRATIONS`、`schema_migrations`、`transaction()`、事件/投影表 | 迁移测试是否存在 |
| CORE-105 | P0 | DONE（待 CI 确认） | `core/receipts.mjs`：`accept/dispatch/settle/markUnknown/supersede/expireDue/recoverAfterCrash` | 无 |
| CORE-106 | P0 | IN PROGRESS | `clients/shared/mock-core.mjs`（确定性 Mock Core）已有；**缺** 核心侧 Mock Adapter 工厂与可重复场景集 | 是否算满足「Mock Core / Mock Adapter」双要求 |
| CORE-107 | P0 | UNVERIFIED | 无单实例锁/服务注册代码；`core/supervisor.mjs` 是**Agent 进程**监督器，不是核心服务生命周期 | 是否已有实现 |
| CORE-108 | P0 | IN PROGRESS | 有 `ReceiptStore.recoverAfterCrash`、`AgentSupervisor`；**缺** 崩溃注入与有界退避策略实现 | 是否还有 `core/` 文件未提交 |
| CORE-109 | P0 | DONE（待 CI 确认） | `core/supervisor.mjs` + `adapters/runtime.mjs`（环境白名单、启动、超时、截断、终止） | 无 |
| CORE-110 | P0 | DONE（待 CI 确认） | `core/workspace.mjs`：`normalizeWorkspacePath`、`resolveInsideWorkspace`、`inspectGit`、worktree 解析 | Git 需真实仓库验证 |
| CORE-111 | P0 | DONE（待 CI 确认） | `core/terminal.mjs`：`TerminalService`（PTY、回放、容量上限） | 无 |
| CORE-112 | P0 | IN PROGRESS | 有 `core/domain.mjs` 的 Artifact 结构、`clients/shared/projection.mjs` 的 artifact 投影；**缺** 分块/哈希/断点续传服务 | 是否另有实现 |
| CORE-113 | P0 | IN PROGRESS | 有 `NOTIFICATION_TYPES` 与 `journal` 的 `notifications` 表；**缺** 通知服务与系统通知适配 | 无 |
| CORE-114 | P0 | IN PROGRESS | 有 `security/redact.mjs`（脱敏）；**缺** 有界日志、导出与崩溃报告 | 无 |

## 4. 适配器（AGT）

### 4.1 通用层

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| AGT-101 | P0 | DONE（待 CI 确认） | `contracts/adapter.mjs` + `adapters/base.mjs`（`BaseAdapter`、`AdapterError`、能力值校验、错误码映射） | 契约测试是否通过 |
| AGT-102 | P0 | DONE（待 CI 确认） | `adapters/runtime.mjs`：环境白名单、秘密环境变量过滤、`spawnProcess`/`runProcess`/`terminateProcess`/`truncateText` | 无 |
| AGT-103 | P0 | IN PROGRESS | `adapters/capability-matrix.mjs` 已实现库（生成 + 校验 + `supportsCapability`）；**缺** CLI、Markdown 渲染与测试 | 是否补 CLI 才算完成 |
| AGT-104 | P0 | IN PROGRESS | `adapters/acp.mjs`：`AcpAdapter`、`JsonRpcConnection`、`acpCapabilityDeclaration`；**未** 对任何真实 ACP 产品验证 | 无真实产品证据 |
| AGT-105 | P0 | UNVERIFIED | 未发现安装发现/版本探测/所有权校验模块 | 是否已有 |
| AGT-106 | P0 | IN PROGRESS | `adapters/fixtures.mjs`：录制、脱敏规则、回放、fixture 索引；**无真实录制数据** | 无 |
| AGT-107 | P0 | UNVERIFIED | 未发现排队/插话降级/停止→恢复→追加的状态机（`contracts/commands.mjs` 只是命令层） | 是否在其他分支 |
| AGT-108 | P0 | DONE（待 CI 确认） | `adapters/interactions.mjs`：审批/提问归一化、原生选项、过期、子 Agent 来源 | 无 |
| AGT-109 | P0 | UNVERIFIED | 未发现模型/推理/权限选项发现模块 | 是否已有 |
| AGT-110 | P0 | UNVERIFIED | 未发现附件/Diff/产物归属适配模块 | 是否已有 |

### 4.2 首版 Agent 专项

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| AGT-111 ~ AGT-130 | P0 | BLOCKED | 无真实 Agent 安装、无桌面环境、本机无联网；无法完成 TODO 5.2 的「官方接口 + 固定版本 + 真实录制 + 冒烟」 | 是否接受仅交付「接口调研记录」并保持 BLOCKED（TODO 5.4 要求记录阻塞证据） |
| AGT-131 | P1 | UNVERIFIED | 依赖 AGT-104；无实现 | 在 P1 延期名单内 |

## 5. 安装与平台（INST）

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| INST-101 | P0 | UNVERIFIED | 无安装器；设计见 ADR-0003 §2 | 是否已认领 |
| INST-102 | P0 | BLOCKED | 无 Windows 环境 | 是否接受「设计+脚本，验证 BLOCKED」 |
| INST-103 | P0 | BLOCKED | 无 macOS 环境 | 同上 |
| INST-104 | P0 | BLOCKED | proot 无 systemd user session | 同上 |
| INST-105 | P0 | UNVERIFIED | 无升级/迁移/回滚实现；流程见 ADR-0003 §4 与 `RELEASE-NOTES-DRAFT.md` §4 | 是否已认领 |
| INST-106 | P1 | UNVERIFIED | 无卸载交互实现 | P1 延期名单内 |
| INST-107 | P0 | UNVERIFIED | 无 Web 资产安装/托管实现 | 是否已认领 |

## 6. WSL

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| WSL-101 ~ WSL-105 | P0 | BLOCKED | 无 Windows/WSL，无法枚举发行版、验证路径/PTY/Git | 是否接受全部 BLOCKED |
| WSL-106 | — | **不存在** | `TODO.md` §11 引用 `WSL-101`~`WSL-106`，§6.2 只定义到 105 | **是**：补行或修正表述 |

## 7. 界面（GUI / TUI / WEB / UI-101）

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| GUI-101 ~ GUI-107 | P0/P1 | BLOCKED | 无 Rust/GPUI 工具链（ADR-0001 §3 记录），无 `Cargo.toml`、无 GUI 代码 | 是否接受整体 BLOCKED |
| TUI-101 | P0 | UNVERIFIED | 无 `clients/tui/main.mjs`；有 `clients/shared/` 可复用 | 是否已认领 |
| TUI-102 | P0 | UNVERIFIED | 无 TUI 终端/Diff/附件实现 | 是否已认领 |
| WEB-101 | P0 | UNVERIFIED | 无 `clients/web/server.mjs`；安全模型见 ADR-0004 | 是否已认领 |
| WEB-102 | P0 | UNVERIFIED | 无 Web 终端/Diff/审批实现 | 是否已认领 |
| UI-101 | P0 | IN PROGRESS | `clients/shared/mock-core.mjs` + `projection.mjs` + `rpc-client.mjs` 已具备「同一 Mock Core」的共用层；**缺** 三个 UI | 是否允许先跑两处客户端契约测试 |

## 8. Personal Agent（PA）

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| PA-101 | P0 | DONE（待 CI 确认） | `pa/schema.mjs`：`PA_SCHEMA_VERSION`、`createPaProfile`、`createPaMessage`、单会话不变量 | 旧 Android 资料映射是否需另证 |
| PA-102 | P0 | IN PROGRESS | `pa/schema.mjs`（HLC、全序）+ `pa/merge.mjs`（并集/去重/冲突/缺口）已实现；**缺** 合并测试与同步接入 | 无 |
| PA-103 | P0 | DONE（待 CI 确认） | `pa/runner.mjs`（独立 SQLite、不依赖 core、LLM 注入）+ `pa/store.mjs` | 无 |
| PA-104 | P0 | DONE（待 CI 确认） | `pa/tools.mjs`（只经统一命令层协调 Agent） | 无 |
| PA-105 | P0 | IN PROGRESS | `pa/audit.mjs`：`AUTONOMY_NOTICE`/`AUTONOMY_NOTICE_VERSION`/`createAuditLog`/`describeAutonomyControls`；**缺** 首启 UI 与确认记录 | 文案是否先评审（见 `RELEASE-NOTES-DRAFT.md` §3） |
| PA-106 | P0 | UNVERIFIED | 需 UI 才能测「绕过 PA 直接操作 Agent」 | 是否已认领 |
| PA-107 | P0 | UNVERIFIED | 无 Android 同步接入；无 Android 构建环境 | 是否已认领 |
| PA-108 | P1 | UNVERIFIED | 无旧会话迁移实现 | P1 延期名单内 |

## 9. 同步（SYNC）

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| SYNC-101 ~ SYNC-108、SYNC-111、SYNC-112 | P0 | UNVERIFIED | `sync/` 目录不存在；无配对、发现、加密帧、增量、命令队列实现 | 是否已认领 |
| SYNC-109 | P0 | BLOCKED | 依赖 Android 宿主桥（AGT-130），本机无 Android 构建环境 | 是否接受 BLOCKED |
| SYNC-110 | P1 | UNVERIFIED | 无中继实现 | P1 延期名单内 |

## 10. 安全、可靠性与性能

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| SEC-101 | P0 | IN PROGRESS | `security/redact.mjs`（脱敏）+ `security/secrets.mjs`（0600/0700 文件存储、原子写入）；**缺** OS Keychain 接入与诊断导出过滤验证 | 是否需接系统钥匙串才算完成 |
| SEC-102 | P0 | DONE（待 CI 确认） | `core/workspace.mjs`：路径规范化、`isPathInside`、`resolveInsideWorkspace`、Windows 路径处理 | 需恶意路径测试 |
| SEC-103 | P0 | UNVERIFIED | 无权限范围/撤销实现；权限划分见 ADR-0004 §3 | 是否已认领 |
| SEC-104 | P0 | UNVERIFIED | 无多账号隔离/凭据清理实现 | 是否已认领 |
| REL-101 | P0 | IN PROGRESS | `ReceiptStore.recoverAfterCrash`、`Journal`、`AgentSupervisor` 提供部分恢复能力；**缺** 端到端恢复测试 | 无 |
| REL-102 | P0 | UNVERIFIED | 无故障注入 harness | 是否已认领 |
| REL-103 | P1 | UNVERIFIED | 无长稳测试 | P1 延期名单内 |
| PERF-101 | P0 | UNVERIFIED | `package.json` 有 `bench` 脚本，但 `test/perf/bench.mjs` 不存在 | 是否已认领 |
| PERF-102 | P0 | BLOCKED | 依赖 GPUI | 是否接受 BLOCKED |

## 11. 发布与质量（QA）

| ID | 优先级 | 建议状态 | 依据 | 需 Lead 核实 |
|---|---|---|---|---|
| QA-101 | P0 | BLOCKED | 无 CI 配置、无三平台构建环境 | 是否已有外部 CI |
| QA-102 | P0 | IN PROGRESS | 有 `mock-core.mjs`、`projection.mjs`、`rpc-client.mjs` 与 1 份契约测试；**缺** 三 UI 与 Mock Core 契约测试套件 | 无 |
| QA-103 | P0 | BLOCKED | 无真实 Agent fixture 与冒烟环境 | 是否接受 BLOCKED |
| QA-104 | P0 | BLOCKED | 无安装器/WSL/同步实现，E2E 无从执行 | 是否接受 BLOCKED |
| QA-105 | P0 | UNVERIFIED | 无升级/回滚/签名流程；草案见 `RELEASE-NOTES-DRAFT.md` §4 | 是否已认领 |
| QA-106 | P0 | IN PROGRESS | `docs/CAPABILITY-MATRIX-DRAFT.md`（规则+草案）已交；生成器 CLI 与真实证据缺失 | 草案能否算部分交付 |
| QA-107 | P0 | IN PROGRESS | `docs/LICENSE-AUDIT.md`（platform/ 静态审核 + GPUI 前置清单）；GPUI/Android 逐项未完成（LIC-02~06） | Android 逐项是否留在本任务 |
| QA-108 | P1 | IN PROGRESS | `docs/TRACEABILITY.md` 已交（P0 覆盖 + 10 个 GAP）；无执行证据 | 是否接受为 P1 部分交付 |
| QA-109 | P0 | IN PROGRESS | `RELEASE-NOTES-DRAFT.md` + `KNOWN-LIMITATIONS.md` 已交；依赖 PA-105、QA-105 | 是否先评审首启文案 |

## 12. 统计（人工计数，±5 误差）

| 建议状态 | 约数 | 主要构成 |
|---|---|---|
| DONE / DONE（待 CI 确认） | 21 | DOC 6、CORE 7（101/102/104/105/109/110/111）、AGT 4（101/102/108/…）、PA 3（101/103/104）、SEC 1 |
| IN PROGRESS | 15 | CORE 103/106/108/112/113/114、AGT 103/104/106、PA 102/105、SEC 101、REL 101、UI-101、QA 102/106/107/108/109 |
| BLOCKED | 42 | AGT 111~130（20）、INST 102~104（3）、WSL 101~105（5）、GUI 101~107（7）、SYNC-109、PERF-102、QA-101/103/104 |
| UNVERIFIED | 其余 | 需 Lead 逐项核实 |

## 13. 交付物级证据索引（供 DONE 复核）

| 证据 | 覆盖任务 |
|---|---|
| `platform/contracts/{ids,rpc,events,commands,capabilities,adapter}.mjs` | DOC-102（部分）、CORE-101、CORE-105（契约）、AGT-101 |
| `platform/test/contracts/contracts.test.mjs` | 契约层回归（未执行） |
| `platform/core/domain.mjs` | CORE-102 |
| `platform/core/journal.mjs` | CORE-104、CORE-103（部分） |
| `platform/core/receipts.mjs` | CORE-105、CORE-108（部分） |
| `platform/core/supervisor.mjs` + `platform/adapters/runtime.mjs` | CORE-109、AGT-102 |
| `platform/core/workspace.mjs` | CORE-110、SEC-102 |
| `platform/core/terminal.mjs` | CORE-111 |
| `platform/adapters/{base,interactions,capability-matrix,fixtures,acp}.mjs` | AGT-101/103/104/106/108 |
| `platform/pa/{schema,merge,store,runner,audit,tools}.mjs` | PA-101~PA-105 |
| `platform/security/{redact,secrets}.mjs` | SEC-101（部分） |
| `platform/clients/shared/{projection,rpc-client,mock-core}.mjs` | CORE-103（部分）、CORE-106（部分）、UI-101（部分） |
| `platform/docs/adr/ADR-0001..0005` | DOC-102~DOC-106 |
| `platform/docs/{LICENSE-AUDIT,CAPABILITY-MATRIX-DRAFT,TRACEABILITY,RELEASE-NOTES-DRAFT,KNOWN-LIMITATIONS}.md` | QA-106~QA-109 |

## 14. 缺口与风险（需 Lead 决策）

1. **入口文件缺失**：`core/index.mjs`、`adapters/index.mjs`、`sync/index.mjs`、`security/index.mjs`、`pa/index.mjs`、`clients/tui/main.mjs`、`clients/web/server.mjs` 都不存在。CONTRACT §8 定义的是「入口」，缺入口时模块无法按契约装配，也不能被 `npm run tui/web` 调用。
2. **`package.json` 脚本指向不存在的目标**：`test:core`（`test/core/` 不存在）、`tui`、`web`、`bench`（`test/perf/bench.mjs` 不存在）执行即失败。建议在补齐前先改脚本或加提示。
3. **`CONTRACT.md` §8 与实现不一致**：契约把 `normalizeWorkspacePath` 归到 `security/index.mjs`，实现里它在 `core/workspace.mjs`；`security/` 只有 `redact.mjs` 与 `secrets.mjs`。需 Lead 决定改契约还是加 re-export。
4. **测试覆盖极低**：仅 `test/contracts/contracts.test.mjs`；`core/`、`adapters/`、`pa/`、`security/` 无测试。`CORE-102`/`PA-101` 等「DONE」几乎没有回归保护。
5. **7 个 P0 验收 ID 无任务**：`DOC-004`、`LIFE-005`、`SESS-003`、`SESS-004`、`TERM-002`、`PA-010`、`ADP-014`（详见 `TRACEABILITY.md` §6）。逐项处理建议与可直接粘贴的 SPEC 版本记录见 `SPEC-CHANGE-PROPOSAL.md` §5.1。
6. **P1 延期名单与「默认仍属首版」条款冲突**：`ADP-019`、`WSL-005/006`、`SYNC-015`、`SEC-006/007`、`PERF-006`、`A11Y-001/002`、`LIFE-009/010`、`UI-004/005/006` 未在延期名单内，按原文应视为首版阻塞。
7. **`artifacts/acceptance/` 不存在**：无任何 run_id，`ACCEPTANCE.md` 1.3 零实例。
8. **`sync/` 完全缺失**：所有 SYNC 任务（含 P0 的 12 项）无代码，跨端能力为 0。
9. **`adapters/index.mjs` 未聚合**：`createMockAdapter` 在 CONTRACT §8 里有名字，但代码中只有 `clients/shared/mock-core.mjs`（客户端 Mock Core）与 `adapters/acp.mjs`，没有 `createMockAdapter` 导出。

## 15. 更新 `TODO.md` 的建议流程

```text
1. cd platform && npm test，把输出（commit + Node 版本 + 结果）存 artifacts/acceptance/<run-id>/。
2. DONE（待 CI）行：CI 绿 → DONE；红 → 保持 IN PROGRESS 并记录失败。
3. UNVERIFIED 行：查 PR/分支；有认领 → IN PROGRESS；已合并且有测试 → DONE；都没有 → OPEN。
4. BLOCKED 行补「阻塞原因 + 已验证环境 + 下一步」，不要只写 BLOCKED。
5. 核对 DOC-002（每个 P0 任务至少映射一个验收）与 DOC-004（变更记录），两者当前都不成立。
```
