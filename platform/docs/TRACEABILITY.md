# 需求—任务—测试追溯表（QA-108）

- 对应任务：QA-108
- 对应验收：QA-008（P1），并为 DOC-002 / DOC-004 提供人工核对依据
- 编制日期：2026-11-07（证据快照时点 21:10 +08:00）
- 数据来源：`docs/desktop-agent-platform/ACCEPTANCE.md`（需求 ID）、`TODO.md`（任务 ID）、`platform/CONTRACT.md` 第 8 节（模块入口）、`platform/` 实际文件

> 本表只做**映射与证据登记**，不做验收判定。任何「PASS」必须来自 `artifacts/acceptance/<run-id>/` 的可复现证据；本表当前 **PASS 数量为 0**。

## 1. 证据等级与状态定义

| 等级 | 含义 | 是否可作为 PASS 依据 |
|---|---|---|
| E4 | 真实设备/真实 Agent 冒烟，含 run_id、commit、版本、日志 | 是 |
| E3 | 自动化测试已执行通过（`node --test` 输出 + commit） | 是（限其覆盖范围） |
| E2 | 测试文件存在但未执行 | 否 |
| E1 | 仅有代码/文档存在（静态审查） | 否 |
| E0 | 无任何证据 | 否 |

> 本次编制的证据上限是 **E2**：编写环境为 Android proot，无可用沙箱后端，`bash` 被环境拒绝，因此**没有执行任何测试**。

状态列用词：

| 本表状态 | 含义 |
|---|---|
| `UNVERIFIED` | 仅有设计或映射，未被任何证据证明 |
| `PARTIAL-STATIC` | 已有代码/契约/文档（E1/E2），但未执行验证 |
| `BLOCKED` | 依赖环境或前置实现不可用（如三平台桌面、真实 Agent、Rust 工具链） |
| `GAP` | 验收 ID 在 `TODO.md` 中**没有对应任务** |
| `PASS` | 有 E3/E4 证据（当前 0 项） |

## 2. 当前证据索引（E1/E2）

| 证据 | 覆盖的验收 | 等级 |
|---|---|---|
| `platform/CONTRACT.md` | CORE-001/002/006 的设计约束、DOC-002 | E1 |
| `platform/contracts/{ids,rpc,events,commands,capabilities,adapter}.mjs` | CORE-006、CORE-101、CORE-105（契约）、ADP-017、ADP-001 | E1 |
| `platform/test/contracts/contracts.test.mjs`（8 个用例） | CORE-006、ADP-017、CORE-102 | E2（未执行） |
| `platform/core/domain.mjs` | CORE-102、SESS-001、WORK-001 | E1 |
| `platform/core/journal.mjs` | CORE-003、CORE-004、CORE-103（快照/续传） | E1 |
| `platform/core/receipts.mjs` | CORE-105、SYNC-006、REL-003、CORE-108（恢复） | E1 |
| `platform/core/supervisor.mjs`、`platform/adapters/runtime.mjs` | CORE-109、ADP-013 | E1 |
| `platform/core/workspace.mjs` | CORE-110、WORK-001、WORK-002、SEC-002 | E1 |
| `platform/core/terminal.mjs` | CORE-111、TERM-001 | E1 |
| `platform/adapters/base.mjs` | AGT-101、ADP-001、ADP-017 | E1 |
| `platform/adapters/interactions.mjs` | AGT-108、ADP-010、ADP-011 | E1 |
| `platform/adapters/capability-matrix.mjs` | AGT-103、ADP-017、UI-003 | E1 |
| `platform/adapters/fixtures.mjs` | AGT-106、QA-003（工具就绪，无数据） | E1 |
| `platform/adapters/acp.mjs` | AGT-104、AGT-ACP-GENERIC（协议层） | E1 |
| `platform/pa/{schema,merge,store,runner,audit,tools}.mjs` | PA-001、PA-002、PA-006、PA-009 | E1 |
| `platform/security/redact.mjs` | SEC-001（脱敏部分） | E1 |
| `platform/clients/shared/{projection,rpc-client,mock-core}.mjs` | QA-002、CORE-106（部分）、UI-001（部分） | E1 |
| `platform/docs/adr/ADR-0001..0005` | DOC-002、DOC-003、DOC-103~106 | E1 |
| `platform/docs/{LICENSE-AUDIT,CAPABILITY-MATRIX-DRAFT,TRACEABILITY,RELEASE-NOTES-DRAFT,KNOWN-LIMITATIONS,TODO-STATUS-PROPOSAL}.md` | QA-006、QA-007、QA-008 | E1 |

**未发现**：`sync/` 目录、`core/index.mjs`、`adapters/index.mjs`、`security/index.mjs`、`pa/index.mjs`、`clients/tui/`、`clients/web/`、`artifacts/`、任何 `fixtures/recordings/`、除契约外的任何测试文件。

## 3. P0 追溯表

### 3.1 文档与可追溯性（DOC）

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| DOC-001 | P0 | DOC-101 | 文档存在、链接有效、版本日期一致 | 四份文档存在，v0.2 | PARTIAL-STATIC |
| DOC-002 | P0 | DOC-101、QA-108 | 自动脚本或人工核对表 | 本表（人工）；无自动脚本 | PARTIAL-STATIC |
| DOC-003 | P0 | DOC-102、DOC-104、DOC-106、QA-106 | 能力矩阵审查 | `SUPPORT-MATRIX.md` 全 UNVERIFIED；ADR-0005 与草案已定规则 | PARTIAL-STATIC |
| DOC-004 | P0 | — | 变更记录 | 无变更记录文件 | GAP |

### 3.2 核心（CORE）

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| CORE-001 | P0 | CORE-107、INST-101 | 进程/端口/数据库路径 | 无单实例实现；设计见 ADR-0003 §1 | BLOCKED |
| CORE-002 | P0 | CORE-107、GUI-107 | 关闭 UI 前后任务日志 | 无 UI、无服务 | BLOCKED |
| CORE-003 | P0 | CORE-102、CORE-104 | 重启前后快照 diff | `core/journal.mjs` 有快照/续传/迁移；无恢复测试 | PARTIAL-STATIC |
| CORE-004 | P0 | CORE-104、INST-105 | 迁移测试 | `journal.mjs` 的 `MIGRATIONS` + `schema_migrations`；无测试 | PARTIAL-STATIC |
| CORE-005 | P0 | CORE-108 | 崩溃注入日志 | `AgentSupervisor` 是 Agent 进程监督，非核心服务退避 | UNVERIFIED |
| CORE-006 | P0 | CORE-101、CORE-103 | 版本握手日志 | `rpc.mjs` + 契约测试（未执行） | PARTIAL-STATIC |
| CORE-007 | P0 | CORE-107、GUI-107 | Agent 事件序列 | 无实现 | BLOCKED |

### 3.3 安装与后台生命周期（LIFE）

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| LIFE-001 | P0 | INST-101 | 安装日志、服务状态 | 无安装器 | BLOCKED |
| LIFE-002 | P0 | INST-101 | 目录树/进程/任务对比 | 无安装器 | BLOCKED |
| LIFE-003 | P0 | INST-101 | 三安装组合测试 | 无安装器 | BLOCKED |
| LIFE-004 | P0 | INST-102/103/104、CORE-107 | 重启前后服务状态 | 无服务实现；映射见 ADR-0003 §2；三平台不可用 | BLOCKED |
| LIFE-005 | P0 | — | 状态时间线 | 无任务、无实现 | GAP |
| LIFE-006 | P0 | INST-105 | 升级日志、会话状态 | 无实现；设计见 ADR-0003 §4 | BLOCKED |
| LIFE-007 | P0 | INST-101、INST-106 | 卸载前后清单 | 无实现 | BLOCKED |
| LIFE-008 | P0 | INST-106 | 交互截图、数据检查 | 无实现 | BLOCKED |

### 3.4 平台与界面

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| PLAT-001/002/003 | P0 | INST-102/103/104、WSL-105 | 三平台验收报告 | 无对应系统、无安装包 | BLOCKED |
| UI-001 | P0 | CORE-103、TUI-101、GUI-101 | 三端对照记录 | 有 `clients/shared` 投影与 RPC 客户端；无 UI | BLOCKED |
| UI-002 | P0 | GUI-102 | 录屏、滚动状态 | 无 GUI | BLOCKED |
| UI-003 | P0 | AGT-103、TUI-102、GUI-101 | 能力矩阵与操作记录 | `capability-matrix.mjs` 的 `supportsCapability` 已提供唯一判断入口 | PARTIAL-STATIC |
| WEB-001 | P0 | WEB-101、INST-107 | 浏览器端到端记录 | 无 `clients/web/server.mjs` | BLOCKED |
| WEB-002 | P0 | WEB-101 | 未授权请求测试 | 无实现；模型见 ADR-0004 | BLOCKED |
| TUI-001 | P0 | TUI-101 | TUI 无 GUI 环境测试 | 无 `clients/tui/main.mjs` | BLOCKED |

### 3.5 WSL

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| WSL-001 ~ WSL-004 | P0 | WSL-101~104 | 环境注册、进程/cwd、隔离、离线 | 无实现；本机无 WSL | BLOCKED |

### 3.6 适配器通用门禁（ADP）

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| ADP-001 | P0 | AGT-102、AGT-105 | 探测无副作用 | `adapters/base.mjs` 的 `sideEffectFreeProbe`；无真实产物验证 | PARTIAL-STATIC |
| ADP-002 | P0 | SEC-104 | 账号隔离测试 | 无实现 | BLOCKED |
| ADP-003 | P0 | AGT-111..130 | 协议录制与 UI 状态 | 无适配器、无录制 | BLOCKED |
| ADP-004 ~ ADP-009 | P0 | AGT-107 | 恢复/停止/排队/插话 | 无 AGT-107 实现 | BLOCKED |
| ADP-010、ADP-011 | P0 | AGT-108 | 审批/提问回放 | `adapters/interactions.mjs`（归一化、过期、来源）；无回放测试 | PARTIAL-STATIC |
| ADP-012 | P0 | AGT-109 | 能力矩阵对照 | 无实现 | BLOCKED |
| ADP-013 | P0 | AGT-102、CORE-109 | 故障注入报告 | `runtime.mjs` + `base.mjs` 的错误分类；无故障注入 | PARTIAL-STATIC |
| ADP-014 | P0 | — | 生命周期回放 | 无显式任务映射 | GAP |
| ADP-015、ADP-016 | P0 | AGT-110 | 附件/路径、双环境对比 | 无实现 | BLOCKED |
| ADP-017 | P0 | AGT-101、AGT-103、DOC-106 | 能力矩阵与测试报告 | `capabilities.mjs`、`capability-matrix.mjs`、ADR-0005 | PARTIAL-STATIC |
| ADP-018 | P0 | AGT-105 | 升级测试 | 无实现 | BLOCKED |
| ADP-019 | P1 | CORE-114、AGT-106 | 日志抽样 | `security/redact.mjs` + `fixtures.mjs` 脱敏规则 | PARTIAL-STATIC |

### 3.7 首版 Agent 专项（AGT-<NAME>）

共同现状：`adapters/` 有基类、ACP、fixture 工具，但**没有任何真实 Agent 的安装、录制或冒烟**；`SUPPORT-MATRIX.md` 全部 `UNVERIFIED`。

| 验收 ID | 优先级 | 对应任务 | 状态 |
|---|---|---|---|
| AGT-CLAUDE、AGT-CODEX、AGT-DSH、AGT-QODER、AGT-TRAE、AGT-WB、AGT-CB、AGT-CURSOR、AGT-ANTIGRAVITY、AGT-GROK、AGT-OPENCODE、AGT-GEMINI、AGT-COPILOT、AGT-KIRO、AGT-PI、AGT-HERMES、AGT-OPENCLAW、AGT-KIMI、AGT-FX | P0 | AGT-111~129、AGT-106、QA-103 | BLOCKED |
| AGT-CODA | P0 | AGT-130、SYNC-109 | BLOCKED |
| AGT-ACP-GENERIC | P0 | AGT-104、AGT-131 | BLOCKED（有 ACP 适配器代码，无真实产品验证） |

### 3.8 会话、工作区、终端、成果

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| SESS-001 | P0 | CORE-102 | 快照对比 | `core/domain.mjs` 的 Session/Workspace 结构；无列表实现 | PARTIAL-STATIC |
| SESS-002 | P0 | PA-106 | UI 端到端 | 无 UI | BLOCKED |
| SESS-003 | P0 | — | 状态操作矩阵 | 无任务 | GAP |
| SESS-004 | P0 | — | 数据库与 UI 检查 | 无任务 | GAP |
| WORK-001 | P0 | CORE-110 | 双环境测试 | `core/workspace.mjs` 的 `workspaceIdentity`/规范化；无双环境验证 | PARTIAL-STATIC |
| WORK-002 | P0 | CORE-110、SEC-102 | 恶意路径测试 | `isPathInside`/`resolveInsideWorkspace`；无测试 | PARTIAL-STATIC |
| TERM-001 | P0 | CORE-111、GUI-105 | 输入输出记录 | `core/terminal.mjs` 的 `TerminalService`；无 UI/PTY 实测 | PARTIAL-STATIC |
| TERM-002 | P0 | — | 双节点隔离测试 | 无任务 | GAP |
| ART-001 ~ ART-003 | P0 | CORE-112、SYNC-112 | 按需传输/断点/离线 | 仅有 Artifact 结构与投影；无传输服务 | BLOCKED |

### 3.9 Personal Agent（PA）

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| PA-001 | P0 | PA-101、PA-103 | 存储与配置隔离 | `pa/schema.mjs`（无 Session/Turn/Agent 字段）+ `pa/runner.mjs`（独立 SQLite） | PARTIAL-STATIC |
| PA-002 | P0 | PA-101、PA-102、PA-107、DOC-103 | 双端 ID 对照 | HLC + `assertSinglePaSession` + ADR-0002；无双端验证 | PARTIAL-STATIC |
| PA-003 | P0 | PA-106 | UI/数据检查 | 无 UI | BLOCKED |
| PA-004 | P0 | PA-106 | 独立路径测试 | 无 UI | BLOCKED |
| PA-005 | P0 | PA-105 | 首启截图与文案快照 | `pa/audit.mjs` 的 `AUTONOMY_NOTICE` + 发布说明草案；无 UI 截图 | PARTIAL-STATIC |
| PA-006 | P0 | PA-104、PA-105 | 审计日志 | `createAuditLog` + `summarizeForAudit`；无运行证据 | PARTIAL-STATIC |
| PA-007 | P0 | PA-105 | 设置检查 | `describeAutonomyControls`（仅 high、无暂停开关）；无 UI | PARTIAL-STATIC |
| PA-008 | P0 | PA-103、PA-107、SYNC-109 | 断连双端测试 | `runner.mjs` 可 core=null 独立运行；无跨端 | BLOCKED |
| PA-009 | P0 | PA-102、SYNC-104 | 并发同步测试 | `mergePaMessages` 确定性合并；无并发测试 | PARTIAL-STATIC |
| PA-010 | P0 | — | 数据与 UI 检查 | 无显式任务 | GAP |

### 3.10 分布式同步（SYNC）

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| SYNC-001 ~ SYNC-005 | P0 | SYNC-101/102/103/104/105/108 | 配对、发现、加密、增量、离线 | 无 `sync/` 目录 | BLOCKED |
| SYNC-006 | P0 | CORE-105、SYNC-106 | 命令重复注入 | `core/receipts.mjs` 幂等收据；无跨端投递 | PARTIAL-STATIC |
| SYNC-007 | P0 | SYNC-107 | 审批竞争 | 无实现 | BLOCKED |
| SYNC-008 | P0 | SYNC-106 | 离线命令 | 无队列实现；语义见 ADR-0002 §4 | BLOCKED |
| SYNC-009 | P0 | PA-102、SYNC-104 | 并发测试 | 合并规则已实现；无同步层 | PARTIAL-STATIC |
| SYNC-010 | P0 | SYNC-101、SEC-103 | 撤销测试 | 无实现 | BLOCKED |
| SYNC-011 ~ SYNC-014 | P0 | SYNC-103/104/108/111/112 | 无中心/不镜像/收敛/版本 | 无实现；收敛规则见 ADR-0002 §2 | BLOCKED |

### 3.11 安全（SEC）

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| SEC-001 | P0 | SEC-101、CORE-114 | 日志扫描、同步抓包 | `security/redact.mjs` + `security/secrets.mjs`（0600/0700、原子写）；无导出过滤验证 | PARTIAL-STATIC |
| SEC-002 | P0 | SEC-102 | 恶意请求测试 | `core/workspace.mjs` 的包含关系校验；无测试 | PARTIAL-STATIC |
| SEC-003 | P0 | SEC-104 | 双账号测试 | 无实现 | BLOCKED |
| SEC-004 | P0 | SEC-103 | 授权矩阵测试 | 无实现 | BLOCKED |
| SEC-005 | P0 | PA-105 | 审计报告 | `pa/audit.mjs`；无运行证据 | PARTIAL-STATIC |

### 3.12 可靠性与性能

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| REL-001 | P0 | CORE-108、REL-101 | 崩溃注入 | `recoverAfterCrash` 存在；无注入测试 | PARTIAL-STATIC |
| REL-002 | P0 | CORE-109、REL-101 | 故障矩阵 | `supervisor`/`runtime` 有终止与退出处理；无测试 | PARTIAL-STATIC |
| REL-003 | P0 | CORE-105、REL-101 | 结果未知测试 | `markUnknown` + `settle` 约束 | PARTIAL-STATIC |
| REL-004 | P0 | CORE-104、REL-101 | 迁移失败注入 | `MIGRATIONS` 存在；无失败注入 | PARTIAL-STATIC |
| PERF-001 ~ PERF-005 | P0 | PERF-101、GUI-102、PERF-102 | 启动/延迟/空闲/大文件 | 无基准脚本、无 GUI | BLOCKED |

### 3.13 构建、CI 与发布

| 验收 ID | 优先级 | 对应任务 | 期望测试/证据 | 当前证据 | 状态 |
|---|---|---|---|---|---|
| QA-001 | P0 | QA-101 | CI artifact + 安装记录 | 无 CI 配置 | BLOCKED |
| QA-002 | P0 | CORE-106、UI-101、QA-102 | 契约测试报告 | 契约测试（E2）+ `mock-core.mjs`/`projection.mjs`/`rpc-client.mjs`；无三 UI | PARTIAL-STATIC |
| QA-003 | P0 | AGT-106、QA-103 | fixture 索引 | `adapters/fixtures.mjs` 有索引工具；无真实 fixture | BLOCKED |
| QA-004 | P0 | QA-104 | E2E 报告 | 无实现 | BLOCKED |
| QA-005 | P0 | INST-105、QA-105 | 发布清单 | 无实现 | BLOCKED |
| QA-006 | P0 | QA-106、QA-109 | 发布文档审查 | 矩阵草案、发布说明草案、已知限制已交 | PARTIAL-STATIC |
| QA-007 | P0 | QA-107 | 许可报告 | `LICENSE-AUDIT.md`（platform/ 静态审核）；GPUI/Android 待完成 | PARTIAL-STATIC |

## 4. P1 / P2 补充映射

| 验收 ID | 优先级 | 对应任务 | 状态 |
|---|---|---|---|
| LIFE-009 | P1 | INST-107 | BLOCKED |
| LIFE-010 | P1 | — | GAP |
| UI-004 | P1 | GUI-103 | BLOCKED |
| UI-005 | P1 | CORE-113、GUI-105 | BLOCKED |
| UI-006 | P1 | GUI-106 | BLOCKED |
| UI-007 | P2 | — | GAP |
| WSL-005 | P1 | WSL-104 | BLOCKED |
| WSL-006 | P1 | WSL-103 | BLOCKED |
| ADP-019 | P1 | CORE-114、AGT-106 | PARTIAL-STATIC |
| PA-011 | P1 | — | GAP |
| SYNC-015 | P1 | SYNC-110 | BLOCKED |
| SEC-006 | P1 | SEC-101 | BLOCKED |
| SEC-007 | P1 | SYNC-103、SYNC-110 | BLOCKED |
| SEC-008 | P1 | —（INST-106 相关） | GAP |
| REL-005 | P1 | REL-103 | BLOCKED |
| PERF-006 | P1 | REL-103 | BLOCKED |
| A11Y-001/002 | P1 | GUI-106 | BLOCKED |
| A11Y-003 | P2 | GUI-106 | BLOCKED |
| QA-008 | P1 | QA-108 | PARTIAL-STATIC（本表） |

## 5. 覆盖统计（编制时点）

| 指标 | 数值 | 说明 |
|---|---|---|
| P0 验收 ID 总数 | 约 130 | 含 21 个 `AGT-<NAME>` 专项 |
| 已映射到任务的 P0 验收 ID | 120 | 见第 3 节 |
| 无任务映射（P0） | 7 | DOC-004、LIFE-005、SESS-003、SESS-004、TERM-002、PA-010、ADP-014 |
| 有 E1/E2 静态证据的 P0 项 | 约 40 | 契约、core、adapters、pa、security、clients/shared |
| PASS（E3/E4） | **0** | 未执行任何测试，无 `artifacts/` |
| BLOCKED（环境/依赖） | 其余 | 三平台桌面、真实 Agent、Rust 工具链、`sync/` 缺失 |

> 统计为人工核对，允许 ±5 误差；`DOC-002` 的正式通过需要脚本化核对。

## 6. 缺口与需 Lead 决策项

1. **验收 ID 无任务**（P0）：`DOC-004`、`LIFE-005`、`SESS-003`、`SESS-004`、`TERM-002`、`PA-010`、`ADP-014`；P1/P2 另有 `LIFE-010`、`UI-007`、`PA-011`、`SEC-008`。按 `DOC-002` 判定，当前不满足。
2. **任务 ID 无表行**：`TODO.md` §11 引用 `WSL-101`~`WSL-106`，§6.2 只定义 `WSL-101`~`WSL-105`。
3. **P1 延期名单不全**：`ADP-019`、`WSL-005/006`、`SYNC-015`、`SEC-006/007`、`PERF-006`、`A11Y-001/002`、`LIFE-009/010`、`UI-004/005/006` 是 P1 但不在延期名单，按原文仍属首版范围。
4. **证据目录缺失**：`artifacts/acceptance/<run-id>/` 不存在，`ACCEPTANCE.md` 1.3 目前零实例。
5. **入口文件缺失**：`core/index.mjs`、`adapters/index.mjs`、`sync/index.mjs`、`security/index.mjs`、`pa/index.mjs`、`clients/tui/main.mjs`、`clients/web/server.mjs` 均不存在，CONTRACT §8 的入口表目前无法按名调用。
6. **测试覆盖极低**：仅一份契约测试（8 用例，未执行）；`core/`、`adapters/`、`pa/`、`security/` 无测试。
7. 上述第 1、3 项的可粘贴变更记录与逐项处理建议见 [`SPEC-CHANGE-PROPOSAL.md`](./SPEC-CHANGE-PROPOSAL.md) 第 2 节与第 5 节。

## 7. 维护方式

1. 每次合并 PR 时，由提交者更新对应行「当前证据」（路径 + run_id）。
2. 生成器就绪后（见 `CAPABILITY-MATRIX-DRAFT.md` §3），本节 3 可由脚本从 `ACCEPTANCE.md` / `TODO.md` / 测试标签（如 `AGT-003`）自动生成，人工只维护「状态」列。
3. 任何状态下调（PASS → FAIL/BLOCKED）必须附原因与 run_id。
