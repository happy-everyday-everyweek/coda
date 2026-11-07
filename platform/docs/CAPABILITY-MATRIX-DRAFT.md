# Agent 能力矩阵草案与生成规则（QA-106）

> 本文件是 `docs/desktop-agent-platform/SUPPORT-MATRIX.md` 的**草案与生成规则**，不替代发布矩阵。
> 发布矩阵由 Lead 统一更新；本文件任何行都不能直接当作「已支持」结论。
> 规则依据：[ADR-0005](./adr/ADR-0005-agent-capability-matrix.md)；契约依据：`contracts/capabilities.mjs`。

- 对应任务：QA-106（生成并发布能力矩阵）、AGT-103（能力矩阵生成与校验）
- 对应验收：QA-006、ADP-017、DOC-003、AGT-ACP-GENERIC
- 更新（2026-11-07 晚）：`tools/capability-matrix.mjs` 已能从 `fixtures/adapters/probes/*.json` 与真实录制自动生成矩阵，输出 `capability-matrix.json` 与 `CAPABILITY-MATRIX-GENERATED.md`；CI 用 `--check` 防止证据漂移。真实 Agent 的成功回合仍缺账号，因此所有 release 最高 `experimental`，本草案继续只作规则说明。

## 1. 生成规则

### 1.1 数据来源（唯一来源）

| 来源 | 用途 | 当前状态 |
|---|---|---|
| `adapters/capability-matrix.mjs` 的 `buildCapabilityMatrix(instances)` | 生成矩阵（CONTRACT 第 5 节入口） | 库已实现（纯函数，返回 JSON）；无 CLI/Markdown 渲染 |
| `adapters/fixtures.mjs`（`createFixture` / `replayFixture` / `buildFixtureIndex`） | 录制、脱敏、回放与 fixture 索引 | 已实现；**无真实录制数据** |
| `fixtures/instances.json` | 实例 × 精确版本 × 执行环境 × 探测结果 | 未创建 |
| `fixtures/probes/*.json` | `probe()` 脱敏输出（安装路径、版本、登录态未知） | 未创建 |
| `fixtures/recordings/*.ndjson` | 真实协议录制（脱敏），每个声明能力至少一份 | 未创建 |
| `test/capability-matrix.test.mjs` | 结构校验 | 未创建（仅有 `test/contracts/contracts.test.mjs`） |

手工编辑 `SUPPORT-MATRIX.md` 只允许作为过渡；一旦生成器可用，手改内容必须在下次生成时被覆盖或被明确标注为人工附注。

### 1.2 行与列

- 行：`(provider, 精确版本, 执行环境)`。环境取值 `host` / `WSL:<distro>` / `android`。同一产品跨环境拆行，不合并。
- 列：`CAPABILITY_KEYS` 的 12 类 —— `session`、`thread`、`turn`、`streaming`、`tools`、`approvals`、`plan`、`subagent`、`context`、`checkpoint`、`identity`、`attachments`。
- 单元格词表（与 `SUPPORT-MATRIX.md` 现有图例一致，不得自造）：

| 记号 | 含义 | 判定 |
|---|---|---|
| `Y` | 已验证支持 | 有 fixture + 真实冒烟 + 版本固定，且对应 ADP 测试通过 |
| `N` | 上游明确不支持 | 有上游文档/命令输出证据，且已声明降级路径与 UI 呈现 |
| `TBD` | 未验证 | 默认值；缺任何一项证据即为 `TBD` |

- 状态列（对应 `SUPPORT-MATRIX.md` 的状态词表）：`UNVERIFIED` / `RESEARCHING` / `EXPERIMENTAL` / `SUPPORTED` / `UNAVAILABLE`。

### 1.3 状态推导（可机器判定）

1. 任一行存在 `TBD` → 该行不得为 `SUPPORTED`。
2. 基础门禁任一失败/阻塞（ADP-003 创建、ADP-005 停止、ADP-004 恢复）→ 整行不得 `SUPPORTED`，并进发布阻塞清单。
3. 声明 `Y` 但 `fixtures/recordings/` 无对应文件 → 自动降为 `TBD`，生成器应报错退出而非静默降级。
4. 版本含 `latest` / `*` / 空 → 生成失败（必须精确版本）。
5. 升级后未重新探测（ADP-018）→ 旧行回退 `UNVERIFIED`，证据作废。

### 1.4 Experimental / Test-only 标注

- `createMockAdapter` 产生的行：`Test-only`，状态 `EXPERIMENTAL`，**禁止**出现在发布矩阵输出中（生成器用 `--include-test-only` 才输出）。
- `createAcpAdapter` 产生的行：仅描述 ACP 协议层能力；未在真实产品验证前 `EXPERIMENTAL`（AGT-ACP-GENERIC）。
- 真实 Agent：无 fixture 与冒烟记录一律 `UNVERIFIED`。
- UI 要求：`EXPERIMENTAL` 能力必须显示「实验性」徽标与限制入口（UI-003、ADR-0005 第 4 节）。

## 2. 草案表（按 `ACCEPTANCE.md` 第 8 节首版名单）

> 列说明：`入口/协议` 与 `目标支持版本` 在接口调研完成前写「待验证」；所有能力列一律 `TBD`，这是**正确状态**，不是遗漏。

| Agent | AGT 记录 | 入口/协议 | 目标支持版本 | 创建 | 流式 | 停止 | 恢复 | 排队 | 插话 | 审批 | 提问 | 子 Agent | 附件/Diff | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Claude Code | AGT-CLAUDE | 待验证（官方 CLI 控制/流式） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Codex | AGT-CODEX | 待验证（app-server / JSON-RPC） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| DeepSeek Harness | AGT-DSH | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Qoder | AGT-QODER | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| TRAE | AGT-TRAE | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| WorkBuddy（wb） | AGT-WB | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| CodeBuddy（cb） | AGT-CB | 待验证（国内/国际入口） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Cursor | AGT-CURSOR | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Antigravity | AGT-ANTIGRAVITY | 待验证（ACP/官方接口） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Grok Build | AGT-GROK | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| OpenCode | AGT-OPENCODE | 待验证（官方/ACP） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Gemini CLI | AGT-GEMINI | 待验证（ACP/官方 CLI） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| GitHub Copilot | AGT-COPILOT | 待验证（可编程 Agent 入口） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Kiro CLI | AGT-KIRO | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Pi Coding Agent | AGT-PI | 待验证（Pi RPC/ACP） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Hermes Agent | AGT-HERMES | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| OpenClaw | AGT-OPENCLAW | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Kimi CLI / Kimi Code | AGT-KIMI | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| fx | AGT-FX | 待验证 | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| 其他 ACP Registry Agent | AGT-ACP-GENERIC | ACP | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Coda Coding Agent | AGT-CODA | 待验证（受认证宿主桥） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Mock Adapter（测试用） | — | `createMockAdapter` | 不适用 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | EXPERIMENTAL（Test-only，实现未落地） |
| 通用 ACP 适配器 | — | `createAcpAdapter` | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | EXPERIMENTAL（协议层，无真实产品验证） |

### 2.1 每个 Agent 的必填记录（Lead 发布前逐行补齐）

```markdown
### <Agent 名称>（AGT-<NAME>）
- 状态：UNVERIFIED
- 官方接口来源与文档日期：
- 目标支持版本（精确）：
- 不兼容版本：
- 安装发现方式（无副作用）：
- 登录/账号隔离：
- 执行环境：host | WSL:<distro> | android
- 创建/恢复方式（含供应方会话引用持久化）：
- 停止/插话策略：native | stop-restart | unavailable
- 审批/提问：原生请求 ID 映射方式
- 模型/推理/权限选项来源：
- 子 Agent/后台任务：
- 附件/Diff/生成物：
- fixture 路径与脱敏说明：
- 真实冒烟证据（run_id / commit / 日期 / 版本）：
- 已知限制：
- 发布状态：UNVERIFIED
```

## 3. 生成命令建议（**当前不可用**）

`adapters/capability-matrix.mjs` 目前是**库**（导出 `buildCapabilityMatrix` / `checkCapabilityMatrix` / `supportsCapability`），没有 CLI 参数解析，也没有 Markdown 渲染。下列命令是目标接口，执行前需先补 CLI（`AGT-103`）。

```js
// 当前唯一可用方式：作为库调用（无 CLI）
import { buildCapabilityMatrix, checkCapabilityMatrix } from "./adapters/capability-matrix.mjs";
const matrix = buildCapabilityMatrix(instances, { strict: true });   // 返回 JSON 结构
checkCapabilityMatrix(matrix, { strict: true });                     // 抛 CapabilityMatrixError
```

```bash
# 目标接口（CLI 未实现，直接执行会失败）
node adapters/capability-matrix.mjs --instances fixtures/instances.json --format json
node adapters/capability-matrix.mjs --instances fixtures/instances.json --format md --out /tmp/SUPPORT-MATRIX.draft.md
node --test test/capability-matrix.test.mjs
```

建议在 `platform/package.json` 增加（由 `AGT-103` 负责人提交，本文件不代改）：

```json
"matrix": "node adapters/capability-matrix.mjs --instances fixtures/instances.json --format md",
"matrix:check": "node --test test/capability-matrix.test.mjs"
```

校验断言清单（`test/capability-matrix.test.mjs` 应覆盖）：

1. 每个实例声明覆盖全部 12 个 `CAPABILITY_KEYS`（`makeCapabilityDeclaration` 已强制，测试需覆盖失败路径）。
2. 版本字符串非空且不是 `latest`/`*`。
3. 每个 `true` 单元格在 `fixtures/recordings/` 有对应文件。
4. `SUPPORTED` 行不含 `unknown`，且基础门禁三项均为支持。
5. `--include-test-only` 关闭时，Mock 行不出现在输出中。
6. 输出表格列顺序稳定（便于 diff 审查）。

## 4. 给 Lead 的合并说明

1. 本草案的表格**不包含**任何 `Y`/`N`，因此可直接替换 `SUPPORT-MATRIX.md` 的「基础能力」表而不抬高任何状态。
2. 建议 `SUPPORT-MATRIX.md` 增加一列 `AGT 记录`（如 `AGT-CLAUDE`），使矩阵与 `ACCEPTANCE.md` 第 8 节一一对应，便于 `QA-108` 追溯。
3. 建议在 `SUPPORT-MATRIX.md` 顶部保留「所有条目在真实证据产生前均为 UNVERIFIED」的原文，并追加一句「生成器 CLI 未实现前，表格为人工维护」。
4. Mock/ACP 两行是否进入发布文档由 Lead 决定；若保留，必须保留 `Test-only` / `协议层` 的限定语，避免被误读为产品支持。

## 5. 未完成项

| 项 | 阻塞原因 | 下一步 |
|---|---|---|
| 矩阵库 `adapters/capability-matrix.mjs` | 已实现（`buildCapabilityMatrix` / `checkCapabilityMatrix` / `supportsCapability`），纯函数无 I/O | 由 `AGT-103` 补 CLI 与 Markdown 渲染 |
| fixture 与录制 | `adapters/fixtures.mjs` 已提供录制/回放能力，但无真实 Agent 安装环境，无录制数据 | 需真实桌面环境 + 固定版本 |
| 真实冒烟 | 本机为 Android proot，无法运行桌面 Agent | BLOCKED（环境不可用） |
| 矩阵进入发布文档 | 需 Lead 评审与真实证据 | 保持 UNVERIFIED |
