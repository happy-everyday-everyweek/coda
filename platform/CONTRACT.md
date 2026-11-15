# Coda 平台契约 v1（platform/）

本文件是 `platform/` 下所有模块必须遵守的最小契约。产品规格见
`docs/desktop-agent-platform/SPEC.md`，验收见 `ACCEPTANCE.md`。

## 1. 边界

- `core/` 是唯一的权威状态源：会话、回合、事件日志、命令收据、审批。
- `adapters/` 只负责「上游 Agent 协议 ↔ 统一事件/能力声明」，不保存会话权威状态。
- `clients/` 只读投影、只发命令；不得按 Agent 名称分支，不得伪造完成状态。
- `sync/` 只搬运已持久化的对象与命令收据，不产生新的权威状态。
- `pa/` 通过统一命令层协调 Agent，不直接操作适配器内部。

## 2. 协议

- JSON-RPC 2.0 风格信封（见 `contracts/rpc.mjs`），协议版本 `PROTOCOL_VERSION = 1`。
- 握手：客户端 `hello` → 核心回 `welcome`；版本不匹配返回 `VERSION_MISMATCH`。
- 订阅模型：`subscribe` 返回 `snapshot{seq}`，随后推送有序事件；每事件 `seq` 严格 +1。
- 断点续传：`resume{fromSeq}`；核心检测缺口并返回 `snapshot` 重新对齐。
- 所有时间戳为 ISO-8601 UTC 字符串；所有 ID 使用 `contracts/ids.mjs` 的前缀格式。

## 3. 持久化与命令

- 命令必须先持久化收据（`accepted`）再执行外部 I/O；外部结果写回后才可 `succeeded/failed`。
- 命令状态机：`accepted → dispatched → succeeded|failed|expired|superseded|unknown`。
- 结果未知（`unknown`）是合法状态：超时/崩溃后不得直接改成成功或失败。
- 事件写入与投影更新必须在同一事务边界内（`core/journal.mjs`）。
- 重放同一 `commandId` 幂等：重复投递返回原收据，不产生新副作用。

## 4. 统一事件（`contracts/events.mjs`）

`message | reasoning | tool | plan | error | subagent | status | interaction | terminal | artifact | notification | session | turn | run`

- 适配器只能发出上述类型；原始上游帧只能进诊断日志，不得直接进 UI。
- 事件必须带 `sessionId`（除全核心级事件）、`seq`、`ts`。

## 5. 能力声明（`contracts/capabilities.mjs`）

- 能力是「Agent 实例 × 版本 × 执行环境」的运行期事实，不是产品名布尔值。
- 不支持的能力必须显式声明 `false` 或 `"unsupported"`，界面显示「不支持/未知」，不得伪装。
- 能力矩阵由 `adapters/capability-matrix.mjs` 生成，UI 只读矩阵。

## 6. 适配器接口（`contracts/adapter.mjs`）

适配器实现 `ProviderAdapter`：`probe / capabilities / createSession / send / steer / interrupt / resume / fork / dispose / events`。
真实 Agent 适配必须有可复现 fixture；没有 fixture 的能力只能标 `UNVERIFIED`。

## 7. 测试

- 所有模块用 `node --test`（零外部依赖）。
- 契约测试放 `test/`，fixture 放 `fixtures/`，脱敏要求见 SPEC 14.3。
- 任何 PR 必须能跑 `npm test`。

## 8. 模块入口（实现约定）

| 入口 | 导出 | 责任 |
|---|---|---|
| `core/index.mjs` | `createCore({dataDir, adapters, clock})` → `{handleRpc(msg), subscribe(fn), close(), diagnostics(), state}` | 唯一权威状态、RPC、事件广播 |
| `adapters/index.mjs` | `createMockAdapter(opts)`、`createAcpAdapter(opts)`、`buildCapabilityMatrix(instances)` | 适配器工厂与能力矩阵 |
| `sync/index.mjs` | `createSyncService({storage, identity})` | 配对、加密帧、游标、命令队列 |
| `security/index.mjs` | `loadSecrets(path)`、`redact(text)`、`normalizeWorkspacePath(p)`（re-export 自 `core/workspace.mjs`，路径规则只在后者实现） | 秘密、脱敏、路径再导出 |
| `pa/index.mjs` | `createPaRunner({core, store})` | PA 单会话与协调工具 |
| `clients/tui/main.mjs` | CLI：`node clients/tui/main.mjs --core stdio:<cmd>` | TUI 客户端 |
| `clients/web/server.mjs` | `node clients/web/server.mjs --port 0` | 本地 Web UI + RPC 桥 |

核心实现细节（SQLite schema、表结构）属于 `core/` 内部，客户端与适配器不得直接读数据库。

## 9. PA 数据形状基准

- PA 消息/资料以 `pa/schema.mjs` 为唯一基准：`paId/sourceNode/seq/role/content/attachments/ts/hlc/replyTo`。
- `core/domain.mjs` 与 `sync/` 中的 PA 相关字段必须显式映射到该基准，不得各自定义一套排序或去重规则。
- 全局顺序统一按 `pa/schema.mjs` 的 HLC 比较：物理毫秒 → 计数器 → 节点 ID → 消息 ID；墙钟只用于展示。
- PA 查询走 `core.snapshot` 只读投影；读操作不强制命令化（命令只用于有副作用的动作）。
