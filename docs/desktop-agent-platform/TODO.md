# 实施待办

> 对应规格：[SPEC.md](./SPEC.md) v0.2
> 对应验收：[ACCEPTANCE.md](./ACCEPTANCE.md)
> 能力矩阵：[SUPPORT-MATRIX.md](./SUPPORT-MATRIX.md)
> 特性分支：`feat/desktop-agent-platform`

本文是可执行任务清单，不替代产品规格和验收标准。开发 Agent 每次只认领一个或多个相互紧密依赖的任务；完成实现不等于完成验收，必须有对应证据。

> 状态更新时间：2026-11-07（平台切片 `platform/`）。本轮 `DONE` 的判定 = 实现 + 本地契约/单元测试通过，证据见 `artifacts/acceptance/` 下最新 run；`CI` 与 `G0–G5` 发布验收仍需在真实 CI、真机与真实 Agent 环境执行，不得据此直接发布。`BLOCKED` 的原因见 `platform/docs/SPEC-CHANGE-PROPOSAL.md`、`platform/*/BLOCKED.md` 与 `platform/clients/GUI-BLOCKED.md`。

---

## 1. 执行规则

### 1.1 开始前必须读

1. `SPEC.md` 的第 2、4、5、8、10、11、14、16 节。
2. 所认领任务映射的 `ACCEPTANCE.md` 条目。
3. 相关 Agent 的官方协议、版本、许可证和安装方式。

### 1.2 任务状态

- `OPEN`：可认领。
- `IN PROGRESS`：已认领并写明分支/PR。
- `BLOCKED`：记录阻塞原因、上游版本和下一步；不得自行改为“部分完成”。
- `DONE`：代码、测试、文档、证据齐备，且对应验收 PASS。
- `DEFERRED`：仅限非 P0 且经过规格变更批准。

### 1.3 完成定义（Definition of Done）

每项任务完成必须同时满足：

- [ ] 代码或文档在独立 PR 中，基于 `dev`，目标分支说明清楚。
- [ ] 对应验收 ID 已在 PR 描述中列出。
- [ ] 单元/契约/集成测试按任务要求新增，且失败可复现。
- [ ] 不泄露 Token、密钥、用户数据；测试数据脱敏。
- [ ] 用户可见错误、能力降级和已知限制已记录。
- [ ] 版本、支持矩阵或迁移说明已同步更新。
- [ ] CI 通过；无法在 CI 跑的真机/真 Agent 测试有手工证据与运行环境。
- [ ] 没有用“功能看起来正常”替代 `ACCEPTANCE.md` 的预定义判定。

### 1.4 推荐工作流

```text
认领任务 → 确认验收 ID → 编写/更新契约测试
→ 实现最小链路 → 注入失败场景 → 证据归档
→ 更新能力矩阵/文档 → PR → 审查 → 合并
```

任何 Agent 不得在 `BLOCKED` 的接口研究任务未完成时，凭空实现一个“兼容层”并标记为支持。

---

## 2. P0 关键路径

按依赖实施，顺序可并行但不能跳过前置：

```text
DOC-101
  ↓
CORE-101 / CORE-102 / CORE-103 / CORE-104
  ↓
AGT-101 / AGT-102 / AGT-103 / CORE-105
  ↓
AGT-111（Claude Code） / AGT-112（Codex） / WSL-101..104
  ↓  （网关支撑的真实回合已验证：claude/codex/opencode/qwen/kimi，见 platform/fixtures/adapters/live-turns/）
  ↓
GUI/TUI/WEB / LIFE / PLAT
  ↓
其余 Agent 专项适配
  ↓
PA / SYNC / Coda 节点
  ↓
ART / SEC / REL / PERF / QA / 发布
```

第一条可演示纵向链路应为：

> Windows 或 Linux 上的 GUI/TUI → 共享核心 → WSL/宿主机 Claude Code 或 Codex → 流式消息 → 停止/恢复 → 关闭 UI 后任务继续。

---

## 3. 文档与架构决策

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| DOC-101 | DONE | P0 | 提交 `SPEC.md`、`ACCEPTANCE.md`、`TODO.md`、`README.md` 基线 | 无 | DOC-001~003 |
| DOC-102 | DONE | P0 | ADR：核心语言/运行时、进程边界、UI 协议、GPUI 版本固定与许可证 | 无 | DOC-003, QA-007 |
| DOC-103 | DONE | P0 | ADR：PA 单会话数据模型、命令幂等、离线同步与快照策略 | CORE-102 | PA-002, SYNC-005, SYNC-013 |
| DOC-104 | DONE | P0 | ADR：Windows/macOS/Linux 用户级后台服务、升级与回滚 | 无 | LIFE-004, LIFE-006 |
| DOC-105 | DONE | P0 | ADR：Web UI 托管、本地访问、配对后的远程访问安全模型 | SYNC-101 | WEB-001, WEB-002 |
| DOC-106 | DONE | P0 | ADR：Agent 能力矩阵、支持版本、降级和 Experimental 标注规则 | AGT-101 | ADP-017, AGT-ACP-GENERIC |

---

## 4. 共享核心基础

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| CORE-101 | DONE | P0 | 建立核心 workspace、模块边界、错误类型和公共 ID 类型 | DOC-102 | CORE-006 |
| CORE-102 | DONE | P0 | 定义 Device / Environment / Workspace / Agent / Session / Run / Turn / Event / Command / Artifact / PA 结构 | DOC-102 | CORE-003, SESS-001, WORK-001 |
| CORE-103 | DONE | P0 | 本地 UI RPC、认证握手、版本协商、订阅/快照/断点回放 | CORE-101, CORE-102 | CORE-006, UI-001 |
| CORE-104 | DONE | P0 | SQLite schema、迁移、事务边界、事件日志和投影 | CORE-102 | CORE-003, CORE-004 |
| CORE-105 | DONE | P0 | 持久命令收据、去重、过期/取代、结果未知状态机 | CORE-104 | SYNC-006, REL-003 |
| CORE-106 | DONE | P0 | Mock Core / Mock Adapter 与可重复协议场景 | CORE-103, CORE-104 | QA-002 |
| CORE-107 | DONE | P0 | 核心单实例、后台服务生命周期、退出/重启/自动启动接口 | DOC-104 | CORE-001, CORE-002, LIFE-004 |
| CORE-108 | DONE | P0 | 崩溃注入、监督重启、日志边界和未完成回合恢复 | CORE-104, CORE-107 | CORE-005, REL-001 |
| CORE-109 | DONE | P0 | Agent 进程监督器：启动、环境清理、stdout/stderr 分离、退出/信号处理 | CORE-101 | ADP-013, REL-002 |
| CORE-110 | DONE | P0 | 工作区规范化、Git 仓库、分支与 worktree 服务 | CORE-102 | WORK-001, WORK-002 |
| CORE-111 | DONE | P0 | Terminal 服务：PTY、回放、detach、重连和容量上限 | CORE-104 | TERM-001 |
| CORE-112 | DONE | P0 | Artifact 服务：上传、块下载、SHA-256、断点续传、元数据 | CORE-104 | ART-001~003 |
| CORE-113 | DONE | P0 | 通知服务与系统通知适配，区分完成、审批、失败、连接状态 | CORE-107 | UI-005 |
| CORE-114 | DONE | P0 | 有界诊断日志、脱敏、导出和崩溃报告 | CORE-109 | SEC-001, ADP-019 |

---

## 5. Agent 适配基础设施

### 5.1 通用适配层

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| AGT-101 | DONE | P0 | 定义 `ProviderAdapter` 接口、统一事件、能力声明、错误模型 | CORE-102 | ADP-017 |
| AGT-102 | DONE | P0 | 适配器进程运行时：隔离环境、PATH、超时、终止、输出截断 | CORE-109 | ADP-001, ADP-013 |
| AGT-103 | DONE | P0 | 能力矩阵生成与校验；UI 不得按 Agent 名称分支 | AGT-101 | ADP-017, UI-003 |
| AGT-104 | DONE | P0 | 通用 ACP 适配器与 Registry 发现/登录边界 | AGT-101, AGT-102 | AGT-ACP-GENERIC, ADP-001~019 |
| AGT-105 | DONE | P1 | 安装发现、版本检查、升级后重新探测和所有权校验 | AGT-101 | ADP-001, ADP-018 |
| AGT-106 | DONE | P0 | 真实协议 fixture 录制、脱敏、回放和失败场景库 | AGT-101 | QA-003 |
| AGT-107 | DONE | P0 | 会话与回合状态机：排队、插话降级、停止、恢复、结果未知 | AGT-101, CORE-105 | ADP-004~009 |
| AGT-108 | DONE | P0 | 审批/提问归一化、原生选项 ID、过期与子 Agent 来源 | AGT-101, CORE-105 | ADP-010~011 |
| AGT-109 | DONE | P0 | 模型、推理档位、权限模式能力与选项发现 | AGT-101 | ADP-012 |
| AGT-110 | DONE | P0 | 附件、Diff、生成物与执行环境归属适配 | AGT-101, CORE-112 | ADP-015~016, ART-001 |

### 5.2 每个 Agent 的固定子清单

每个 `AGT-1xx` 任务都必须完成以下子项，并在 PR 中链接证据：

- [ ] 找到官方可编程接口与受支持版本，记录文档链接和许可证。
- [ ] 记录安装/登录路径，验证状态探测无副作用。
- [ ] 实现发现、创建、发送、流式、停止、恢复。
- [ ] 实现忙碌排队；无原生插话时实现“停止→恢复→追加消息”。
- [ ] 实现审批、提问或明确记录上游不支持。
- [ ] 实现模型/推理/权限选项的能力发现或明确降级。
- [ ] 记录子 Agent、附件、Diff、生成物支持情况。
- [ ] 录制至少一次真实会话，提供脱敏 fixture。
- [ ] 生成能力矩阵和已知限制。
- [ ] 更新 `ACCEPTANCE.md` 第 8 节的支持版本和证据。

### 5.3 首版 Agent 专项任务

| ID | 状态 | 优先级 | Agent / 目标 | 依赖 | 验收 |
|---|---|---|---|---|---|
| AGT-111 | IN PROGRESS | P0 | Claude Code 专项适配 | AGT-101~110 | AGT-CLAUDE, ADP-001~019 |
| AGT-112 | IN PROGRESS | P0 | Codex 专项适配 | AGT-101~110 | AGT-CODEX, ADP-001~019 |
| AGT-113 | IN PROGRESS | P0 | DeepSeek Harness 接口调研与适配 | AGT-101~110 | AGT-DSH, ADP-001~019 |
| AGT-114 | IN PROGRESS | P0 | Qoder Agent 接口调研与适配 | AGT-101~110 | AGT-QODER, ADP-001~019 |
| AGT-115 | BLOCKED | P0 | TRAE / SOLO Agent 接口调研与适配 | AGT-101~110 | AGT-TRAE, ADP-001~019 |
| AGT-116 | BLOCKED | P0 | WorkBuddy（wb）接口调研与适配 | AGT-101~110 | AGT-WB, ADP-001~019 |
| AGT-117 | IN PROGRESS | P0 | CodeBuddy（cb）国内/国际接口调研与适配 | AGT-101~110 | AGT-CB, ADP-001~019 |
| AGT-118 | IN PROGRESS | P0 | Cursor Agent/CLI 调研与适配 | AGT-101~110 | AGT-CURSOR, ADP-001~019 |
| AGT-119 | IN PROGRESS | P0 | Antigravity ACP/账户差异适配 | AGT-101~110, AGT-104 | AGT-ANTIGRAVITY, ADP-001~019 |
| AGT-120 | IN PROGRESS | P0 | Grok Build 适配 | AGT-101~110 | AGT-GROK, ADP-001~019 |
| AGT-121 | IN PROGRESS | P0 | OpenCode 适配 | AGT-101~110 | AGT-OPENCODE, ADP-001~019 |
| AGT-122 | IN PROGRESS | P0 | Gemini CLI 适配 | AGT-101~110 | AGT-GEMINI, ADP-001~019 |
| AGT-123 | IN PROGRESS | P0 | GitHub Copilot 可编程 Agent 入口适配 | AGT-101~110 | AGT-COPILOT, ADP-001~019 |
| AGT-124 | IN PROGRESS | P0 | Kiro CLI 适配 | AGT-101~110 | AGT-KIRO, ADP-001~019 |
| AGT-125 | IN PROGRESS | P0 | Pi Coding Agent 适配 | AGT-101~110 | AGT-PI, ADP-001~019 |
| AGT-126 | IN PROGRESS | P0 | Hermes Agent 适配 | AGT-101~110 | AGT-HERMES, ADP-001~019 |
| AGT-127 | IN PROGRESS | P0 | OpenClaw 可编程入口调研与适配 | AGT-101~110 | AGT-OPENCLAW, ADP-001~019 |
| AGT-128 | IN PROGRESS | P0 | Kimi CLI / Kimi Code 可编程入口适配 | AGT-101~110 | AGT-KIMI, ADP-001~019 |
| AGT-129 | IN PROGRESS | P0 | fx 适配 | AGT-101~110 | AGT-FX, ADP-001~019 |
| AGT-130 | IN PROGRESS | P0 | Coda Android Coding Agent 受认证宿主桥（已实现：TCP+NDJSON 鉴权桥、设置页开关、桌面客户端、Android 反向客户端与远端会话页；待真机双端验收） | CORE-101, SYNC-101 | AGT-CODA, ADP-001~019 |
| AGT-131 | IN PROGRESS | P1 | 未知 ACP Registry Agent 的自动发现与能力提示 | AGT-104 | AGT-ACP-GENERIC |

### 5.4 接口调研阻塞处理

若官方没有稳定可编程接口，任务状态必须改为 `BLOCKED`，记录：

- 官方文档/问题链接；
- 已安装版本、尝试的命令和原始输出；
- 是否存在稳定 ACP、MCP、app-server 或 CLI 控制协议；
- 是否需要上游适配层；是否涉及许可证问题；
- 是否有可复现实验环境。

不得以 UI 自动化、截图 OCR 或打开终端后模拟键盘作为首版“完整支持”的主链路。可以作为 `Experimental` 单独提案，但不能绕过 G3。

---

## 6. 平台、安装与界面

### 6.1 安装/后台

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| INST-101 | IN PROGRESS | P0 | UI 安装器统一发现/安装/复用核心；三种界面可共存 | CORE-107 | LIFE-001~003, LIFE-007 |
| INST-102 | BLOCKED | P0 | Windows 用户级后台安装、登录启动、退出 UI 不停止 | DOC-104, CORE-107 | PLAT-001, LIFE-004 |
| INST-103 | BLOCKED | P0 | macOS 后台项/登录启动、退出 UI 不停止 | DOC-104, CORE-107 | PLAT-002, LIFE-004 |
| INST-104 | BLOCKED | P0 | Linux user service、登录启动、退出 UI 不停止 | DOC-104, CORE-107 | PLAT-003, LIFE-004 |
| INST-105 | IN PROGRESS | P0 | 核心/UI 升级、迁移、回滚与运行任务保护 | CORE-104, CORE-107 | LIFE-006, QA-005 |
| INST-106 | IN PROGRESS | P1 | 卸载 UI/核心的交互与数据保留策略 | INST-101 | LIFE-007~008 |
| INST-107 | IN PROGRESS | P0 | Web UI 本地资产安装、托管和版本管理 | CORE-103 | LIFE-009, WEB-001 |

### 6.2 WSL

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| WSL-101 | BLOCKED | P0 | 枚举 WSL 发行版、状态探测、环境 ID 与执行入口 | CORE-102 | WSL-001 |
| WSL-102 | BLOCKED | P0 | WSL 工作区、路径转换、cwd、环境变量和 Agent 启动 | WSL-101, CORE-110 | WSL-002~003 |
| WSL-103 | BLOCKED | P0 | WSL Shell/PTY、Git、Diff、附件与产物归属 | WSL-102, CORE-111 | WSL-004, WSL-006 |
| WSL-104 | BLOCKED | P0 | WSL 离线/关闭/恢复的负向测试 | WSL-102 | WSL-004~005 |
| WSL-105 | BLOCKED | P0 | Windows GUI/TUI 的 WSL 端到端演示 | WSL-102, UI-101 | PLAT-001, QA-004 |

### 6.3 GUI（GPUI）

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| GUI-101 | IN PROGRESS | P0 | GPUI 应用壳、核心连接、设备/会话导航与错误态（CI 软件渲染冒烟已通过，产品壳未做） | CORE-103, DOC-102 | UI-001, UI-003 |
| GUI-102 | BLOCKED | P0 | 虚拟化会话流、Markdown、工具分组、流式跟随与历史浏览 | GUI-101 | UI-002, PERF-002~003 |
| GUI-103 | BLOCKED | P0 | Composer、发送/排队/插话/停止、草稿、附件 | GUI-102, AGT-107 | ADP-003, ADP-007~009, UI-004 |
| GUI-104 | BLOCKED | P0 | 审批/提问面板、原生选项、过期与多端结果 | GUI-101, AGT-108 | ADP-010~011, SYNC-007 |
| GUI-105 | BLOCKED | P0 | 终端、Diff、成果和通知界面 | GUI-101, CORE-111~113 | TERM-001, ART-001, UI-005 |
| GUI-106 | BLOCKED | P1 | 键盘、IME、缩放、减少动态效果和可访问性 | GUI-101 | A11Y-001~003 |
| GUI-107 | BLOCKED | P0 | GUI 端到端：关闭应用不停止核心，重启恢复页面 | GUI-101 | CORE-002, CORE-007 |

### 6.4 TUI / Web UI

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| TUI-101 | DONE | P0 | TUI 会话列表、流式消息、Composer、停止/审批核心流程 | CORE-103 | TUI-001, UI-001 |
| TUI-102 | DONE | P0 | TUI 终端、Diff、附件操作和远程节点 | TUI-101 | UI-003, TERM-001, ART-001 |
| WEB-101 | DONE | P0 | 本地 Web UI、核心 RPC、刷新恢复与安全访问 | CORE-103 | WEB-001~002 |
| WEB-102 | DONE | P0 | Web 终端、Diff、审批和文件成果 | WEB-101, CORE-111~112 | ART-001, TERM-001, SYNC-007 |
| UI-101 | DONE | P0 | 三界面相同 Mock Core 契约测试 | CORE-106 | QA-002, PLAT-004 |

---

## 7. Personal Agent

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| PA-101 | DONE | P0 | 盘点 Coda 现有 PA 数据；设计桌面/Android 共用资料与会话 schema | CORE-102 | PA-001~002 |
| PA-102 | DONE | P0 | PA 单会话消息 ID、来源节点、reply_to、确定性合并 | DOC-103 | PA-002, PA-009, SYNC-009 |
| PA-103 | DONE | P0 | 桌面 PA 本地运行器与独立存储，不依赖 Coding Agent | CORE-103 | PA-001, PA-008 |
| PA-104 | DONE | P0 | PA 协调工具：查询/创建/发送/停止 Agent 会话，必须走统一命令层 | AGT-107 | PA-006 |
| PA-105 | DONE | P0 | 高度自主首启提示、行动审计、无额外模式/暂停开关 | PA-104 | PA-005~007, SEC-005 |
| PA-106 | DONE | P0 | 直接使用 Agent 的路径与 PA 独立性测试 | GUI-101, TUI-101 | PA-003~004 |
| PA-107 | BLOCKED | P0 | Android PA 接入桌面同步层，保持同一连续会话 | PA-102, SYNC-104 | PA-002, PA-008 |
| PA-108 | DONE | P1 | 旧 Android PA 会话迁移与回滚验证 | PA-101 | CORE-004, PA-002 |

---

## 8. 同步与 Coda 双向控制

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| SYNC-101 | DONE | P0 | 设备身份、密钥、二维码/短码配对、设备撤销 | DOC-103 | SYNC-001~002, SYNC-010 |
| SYNC-102 | DONE | P0 | 局域网发现、手动地址、连接重试和路由选择 | SYNC-101 | SYNC-001 |
| SYNC-103 | DONE | P0 | 端到端加密传输、帧格式、版本协商 | SYNC-101 | SEC-007, SYNC-014 |
| SYNC-104 | DONE | P0 | PA 单会话与设备目录增量同步、快照与游标 | PA-102, SYNC-103 | SYNC-003~005, SYNC-009 |
| SYNC-105 | DONE | P0 | 会话元数据、活跃消息窗口、在线订阅和按需历史 | SYNC-104, CORE-104 | SYNC-003~004 |
| SYNC-106 | DONE | P0 | 命令投递、收据、过期/取代、目标离线队列 | CORE-105, SYNC-103 | SYNC-006, SYNC-008 |
| SYNC-107 | DONE | P0 | 审批/提问跨端同步与单结果竞争 | AGT-108, SYNC-106 | SYNC-007 |
| SYNC-108 | DONE | P0 | 分区、重复、乱序、分叉、重连收敛测试 | SYNC-104~107 | SYNC-005, SYNC-013 |
| SYNC-109 | BLOCKED | P0 | Coda Android 节点受认证宿主桥与双向控制（双向代码均已完成，缺真机端到端验收） | AGT-130, SYNC-103 | AGT-CODA, PA-008 |
| SYNC-110 | OPEN | P1 | 可选加密中继/内网穿透，不成为硬依赖 | SYNC-103 | SYNC-011, SYNC-015 |
| SYNC-111 | DONE | P0 | 无中心服务拓扑验收：断公网仍可局域网工作 | SYNC-102 | SYNC-011 |
| SYNC-112 | DONE | P0 | 成果/附件按需传输，不触发工作区镜像 | CORE-112, SYNC-103 | ART-001~003, SYNC-012 |

---

## 9. 安全、可靠性与性能

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| SEC-101 | DONE | P0 | 平台安全存储、秘密生命周期、日志脱敏和诊断导出过滤 | CORE-114 | SEC-001, SEC-006 |
| SEC-102 | DONE | P0 | 工作区路径规范化、符号链接与远程文件授权 | CORE-110, SYNC-103 | SEC-002, WORK-002 |
| SEC-103 | DONE | P0 | UI/远程客户端/外部 Agent 的权限范围与撤销 | SYNC-101 | SEC-004, SYNC-010 |
| SEC-104 | DONE | P0 | 多账号实例隔离、ambient credentials 清理 | AGT-102 | SEC-003, ADP-002 |
| REL-101 | DONE | P0 | 核心重启、Agent 崩溃、结果未知和终端恢复 | CORE-108, CORE-111 | REL-001~004 |
| REL-102 | DONE | P0 | 故障注入 harness：断网、断电模拟、旧版本、Agent 退出 | CORE-106, CORE-108 | REL-001~004 |
| REL-103 | DONE | P1 | 长稳与资源泄漏测试：重连、终端、附件、长会话 | REL-101 | REL-005, PERF-006 |
| PERF-101 | DONE | P0 | 性能基准框架与指标归档：启动、事件延迟、空闲资源、大文件 | CORE-107, CORE-112 | PERF-001~005 |
| PERF-102 | BLOCKED | P0 | GPUI 长列表/流式渲染 profiling 与预算检查 | GUI-102 | PERF-002~003 |

---

## 10. 发布、文档与质量

| ID | 状态 | 优先级 | 任务与产出 | 依赖 | 验收 |
|---|---|---|---|---|---|
| QA-101 | IN PROGRESS | P0 | 三平台 CI 构建、测试和安装包 artifacts | INST-102~104 | QA-001 |
| QA-102 | DONE | P0 | Mock Core 契约测试覆盖三 UI | CORE-106, UI-101 | QA-002 |
| QA-103 | DONE | P0 | 所有 Agent fixture 与真实冒烟测试索引 | AGT-106, AGT-111~130 | QA-003 |
| QA-104 | BLOCKED | P0 | 跨端 E2E：安装复用、WSL、PA、断连、命令去重、审批竞争 | INST, WSL, PA, SYNC | QA-004 |
| QA-105 | OPEN | P0 | 升级/回滚/签名/未签名发布流程 | INST-105 | QA-005 |
| QA-106 | DONE | P0 | 生成并发布 Agent 能力矩阵、支持版本、已知限制 | AGT-103 | QA-006 |
| QA-107 | DONE | P0 | 依赖许可、GPUI revision、Coda AGPL 与 NOTICE 审核 | DOC-102 | QA-007 |
| QA-108 | DONE | P1 | 验收证据索引与需求-任务-测试追溯报告 | QA-101~107 | QA-008 |
| QA-109 | DONE | P0 | 发布说明、风险提示、首启文案和回滚说明 | PA-105, QA-105 | QA-006 |

---

## 11. 发布阻塞清单

以下任一项未完成，不得发布首版：

- [ ] `DOC-101`~`DOC-106`
- [ ] `CORE-101`~`CORE-114`
- [ ] `AGT-101`~`AGT-110`，以及 `AGT-111`~`AGT-130` 的 G3 证据
- [ ] `INST-101`~`INST-105`、`INST-107`
- [ ] `WSL-101`~`WSL-105`
- [ ] `GUI-101`~`GUI-105`、`GUI-107`
- [ ] `TUI-101`、`TUI-102`
- [ ] `WEB-101`、`WEB-102`
- [ ] `UI-101`
- [ ] `PA-101`~`PA-107`
- [ ] `SYNC-101`~`SYNC-109`、`SYNC-111`~`SYNC-112`
- [ ] `SEC-101`~`SEC-104`
- [ ] `REL-101`、`REL-102`
- [ ] `PERF-101`、`PERF-102`
- [ ] `QA-101`~`QA-107`、`QA-109`

以下 P1 任务可在首版延期，但必须先有规格变更和批准记录：

`AGT-105`、`AGT-131`、`INST-106`、`GUI-106`、`PA-108`、`SYNC-110`、`REL-103`、`QA-108`。

其他 P1 任务若未在上列延期名单中，默认仍属于首版范围，不得静默延期。

---

## 12. Agent 领取与回报模板

认领任务时在 PR/分支中填写：

```markdown
### 任务
- ID:
- 目标:
- 依赖:
- 对应验收:
- 支持版本/环境:
- 计划:
```

完成后填写：

```markdown
### 结果
- 变更:
- 验收执行:
- 证据路径/CI:
- 已知限制:
- 能力矩阵变更:
- 回滚方式:
```

禁止只写“已完成”。只有验收 PASS 且证据可访问，任务才可以标为 DONE。