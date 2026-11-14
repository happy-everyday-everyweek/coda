# ADR-0005：Agent 能力矩阵、支持版本、降级与 Experimental 标注规则

- 状态：Accepted（规则已定；真实 Agent 证据全部 UNVERIFIED，生成器未实现）
- 日期：2026-11-07
- 对应任务：DOC-106
- 对应验收：ADP-017、ADP-018、UI-003、AGT-ACP-GENERIC、QA-003、QA-006、DOC-003
- 关联文档：[CAPABILITY-MATRIX-DRAFT](../CAPABILITY-MATRIX-DRAFT.md)、[TRACEABILITY](../TRACEABILITY.md)、`contracts/capabilities.mjs`、`contracts/adapter.mjs`

## 背景

`SPEC.md` 8.1 规定能力声明是「Agent 实例 × 版本 × 执行环境」的**运行期事实**，不是按产品名写死的全局布尔值；8.2 给出统一能力与降级路径的对照表，并明确禁止行为（例如不得为「看起来支持」而伪造结构化计划、不得仅改 UI 表示已停止）。
`SPEC.md` 7.3 规定只有满足 8 项条件（固定版本、账号路径、创建/恢复、事件、停止结算、逐项能力验证、回放 + 真实冒烟、降级可见）才能标「已支持」。
`TODO.md` 5.2/5.4 要求每个 `AGT-1xx` 任务记录接口、版本、许可证；接口不稳定时必须 `BLOCKED` 并留证据，禁止用 UI 自动化充当首版主链路。
`ACCEPTANCE.md` 第 8 节要求 `Supported` 才能进入首版默认列表，`Experimental` 必须在 UI 与矩阵中明确提示。

已落地契约：`contracts/capabilities.mjs` 固定 12 个能力键（`CAPABILITY_KEYS`：session / thread / turn / streaming / tools / approvals / plan / subagent / context / checkpoint / identity / attachments），并规定 `supports()` 只在 `true` 或 `"experimental"` 时为真、`supportState()` 映射为 `supported|experimental|unsupported|unknown`。

现状证据（2026-11-07 文件快照）：`adapters/capability-matrix.mjs` 已实现纯函数矩阵生成与校验（`buildCapabilityMatrix` / `checkCapabilityMatrix` / `supportsCapability`），`adapters/acp.mjs` 与 `adapters/base.mjs` 提供适配器基类；**但**没有真实 Agent 的 fixture 或冒烟记录，也没有 Markdown 渲染与 CLI 入口。`docs/desktop-agent-platform/SUPPORT-MATRIX.md` 全部行为仍为 `UNVERIFIED`。因此本 ADR 只定义规则与标注方式，**不产生任何 Supported 结论**。

## 决策

### 1. 矩阵的行与列

1. 行 = `(provider, 精确版本, 执行环境)` 三元组；执行环境取值 `host` | `WSL:<distro>` | `android`。同一产品在不同环境是**不同行**，不得合并（WSL-003：Windows 与 WSL 同名 Agent 不相通）。
2. 列 = `CAPABILITY_KEYS` 的 12 类；每类允许带子项（例如 `approvals.command`、`approvals.fileWrite`、`turn.steer`），子项未声明按 `unknown` 处理。
3. 单元格取值只允许：`true`（已验证支持）、`"experimental"`、`false` / `"unsupported"`（上游明确不具备且已有降级或说明）、`unknown`（未验证，**默认值**）。
4. 矩阵由 `adapters/capability-matrix.mjs` 的 `buildCapabilityMatrix(instances)` 生成，UI 只读矩阵，不得按产品名分支（CONTRACT 第 5 节、AGT-103、UI-003）。

### 2. 状态判定（与 SUPPORT-MATRIX 状态词表对齐）

| 矩阵证据 | SUPPORT-MATRIX 状态 | 可进入默认发布列表 |
|---|---|---|
| 每个声明能力都有 fixture + 真实冒烟 + 版本固定 | `SUPPORTED` | 是 |
| 有真实接口但能力/稳定性不达标，或仅有部分能力验证 | `EXPERIMENTAL` | 否（需规格变更） |
| 找到候选接口，仍在固定版本/协议 | `RESEARCHING` | 否 |
| 未做任何真实验证 | `UNVERIFIED` | 否 |
| 官方无可用可编程接口（须有文档/命令输出证据） | `UNAVAILABLE` | 否（须规格变更） |

推导规则（机器可判定的部分）：

1. 任一行出现 `unknown`，该行**不得**为 `SUPPORTED`。
2. 声明 `true` 的能力必须在 `fixtures/` 有对应脱敏录制且冒烟记录含 commit/日期；缺一即降为 `EXPERIMENTAL`。
3. 某行基础门禁（ADP-003 创建、ADP-005 停止、ADP-004 恢复）任一 `BLOCKED`/`FAIL`，整行不得 `SUPPORTED`，且必须进发布阻塞清单（ACCEPTANCE 1.1）。
4. 版本升级后旧证据失效：`ADP-018` 要求重新探测；未重新验证的行回退为 `UNVERIFIED`，不得沿用旧矩阵。

### 3. 降级规则

1. 降级路径以 SPEC 8.2 表为准，必须写成可执行语义，而不是文案。示例：
   - 无原生插话 → `turn.steer = "unsupported"`，执行「停止当前回合 → 保留部分输出 → 恢复原会话 → 追加新指令」，并把 `strategy: "stop-restart"` 写入会话详情与诊断（SPEC 8.3、ADP-009）。
   - 无原生结构化计划 → `plan = false`，只展示普通文本，**不**构造伪计划对象（ADP-011 同理适用于提问）。
   - 无原生 Diff → `attachments.diff = "unsupported"`，从所属工作区计算 Git diff，且不得跨环境取同名路径（ADP-016）。
2. 降级必须在三处一致：矩阵单元格、UI 呈现、发布说明。任一处缺失视为 `DOC-003` 不通过。
3. 禁止用降级掩盖失败：恢复失败必须报错，不得新建空会话顶替（SPEC 8.3 第 6 条）。

### 4. Experimental 标注规则

1. `"experimental"` 只用于「能跑通，但证据不足以支撑发布承诺」的能力，例如：仅有手工冒烟、只覆盖单版本、依赖未固定的上游行为、或用 ACP 通用适配尚未在真实产品上验证。
2. 标注要求：UI 在该能力旁显示「实验性」徽标并给出已知限制入口；矩阵写 `EXPERIMENTAL`；发布说明列入已知限制；不得作为默认推荐路径，也不得计入 G3 通过。
3. Mock / ACP 专用规则：
   - `createMockAdapter` 产出的行一律 `Test-only`，矩阵状态 `EXPERIMENTAL`，**永不**进入发布矩阵（不写成任何真实产品名）。
   - `createAcpAdapter` 产出的行在未做真实产品验证前一律 `EXPERIMENTAL`，且矩阵只描述「ACP 协议层能力」，不得把产品名与 ACP 行混写成 `Supported`（AGT-ACP-GENERIC）。
4. 真实 Agent 在首版默认一律 `UNVERIFIED`；没有 fixture 与冒烟记录时，任何「已支持」表述都属违规（SPEC 7.3、DOC-003）。

### 5. 生成与校验

1. 生成器输入：`fixtures/instances.json`（实例 × 版本 × 环境 × 探测结果）与 `fixtures/probes/*.json`（脱敏探测输出）。
2. 校验器至少断言：12 个键齐全（`makeCapabilityDeclaration` 已强制）、版本为精确 semver/日期而非 `latest`、每个 `true` 有 fixture 引用、`SUPPORTED` 行无 `unknown`、`Test-only` 行不在发布输出中。
3. 建议命令（**当前只有库、没有 CLI**：`buildCapabilityMatrix` 已实现，Markdown 渲染与 `--format md` 入口未实现，直接执行会失败；见 CAPABILITY-MATRIX-DRAFT 第 3 节）：
   - `node adapters/capability-matrix.mjs --instances fixtures/instances.json --format json`
   - `node adapters/capability-matrix.mjs --instances fixtures/instances.json --format md --out ../docs/desktop-agent-platform/SUPPORT-MATRIX.md`
   - `node --test test/capability-matrix.test.mjs`
4. 生成结果与人工审查双轨：生成器负责结构一致性，`QA-006` 负责人工确认「版本固定 + 证据可达 + 降级已写」。

## 影响

- `AGT-103`（矩阵生成与校验）已有 `adapters/capability-matrix.mjs` 实现；`AGT-106`（fixture 库）有 `adapters/fixtures.mjs`，但无真实录制。故「真实证据 → Supported」链路仍为 `UNVERIFIED`。
- `SUPPORT-MATRIX.md` 由 Lead 统一更新；本 ADR 只提供规则与草案（见 CAPABILITY-MATRIX-DRAFT），不得由子任务直接改写发布矩阵。
- 审查成本上升：新增 Agent 必须同时提交「定义 + 适配器 + 能力声明 + 测试 + 文档」五项，否则无法进入矩阵（SPEC 15.3）。
- 与发布门槛的关系：G3 要求首版名单全部 `SUPPORTED`；当前全部 `UNVERIFIED`，因此 G3 明确未通过。

## 备选方案

1. **按产品名维护布尔能力表**：实现简单，但违反 SPEC 8.1（运行期事实）与 ADP-017，且无法表达版本/环境差异；放弃。
2. **只用 ACP 通用适配器覆盖所有产品并标 Supported**：省去逐产品验证，但 SPEC 7.2/7.3 明确要求真实产品验证；最多标 `EXPERIMENTAL`；放弃。
3. **把 `unknown` 当「暂按支持」处理**：会让 UI 显示不存在的按钮并制造失败操作，违反 SPEC 5.1 原则 7（能力诚实）；放弃。
4. **矩阵只写文档不写生成器**：短期可行，但无法通过 QA-006 与 DOC-003 的「随版本发布且一致」要求；保留生成器为目标，文档过渡期由人工核对表代替。
