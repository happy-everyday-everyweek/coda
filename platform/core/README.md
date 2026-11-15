# 共享核心参考实现（CORE-101..CORE-114）

零外部依赖的 Node.js 24 ESM 参考实现。契约以 `platform/CONTRACT.md` 与
`platform/contracts/*.mjs` 为准；本目录只实现，不修改契约。

## 模块

| 文件 | 任务 | 责任 |
|---|---|---|
| `errors.mjs` | CORE-101 | 错误类型与契约 RPC 错误码映射 |
| `domain.mjs` | CORE-102 | Device/Environment/Agent/Workspace/Session/Run/Turn/Event/Interaction/Command/Artifact/PA 校验与工厂 |
| `journal.mjs` | CORE-104 | SQLite schema、迁移、事务、全局 seq、投影、断点回放 |
| `receipts.mjs` | CORE-105 | 命令收据幂等、过期/取代、结果未知 |
| `server.mjs` | CORE-103 | JSON-RPC 分派、订阅/快照/续传、命令执行、事件载荷归一化 |
| `eventbus.mjs` | CORE-103 | 已持久化事件的扇出 |
| `index.mjs` | CORE-101/103 | `createCore`、`createMockCore`、stdio 入口 |
| `lifecycle.mjs` | CORE-107 | 单实例 lockfile、优雅关闭、重启入口 |
| `recovery.mjs` | CORE-108 | 崩溃恢复（未完成回合/命令 → unknown）、有界退避 |
| `supervisor.mjs` | CORE-109 | Agent 进程监督（stdout/stderr、超时、退出、临时环境） |
| `workspace.mjs` | CORE-110 | 路径规范化、Git 仓库/分支/worktree 探测与降级 |
| `terminal.mjs` | CORE-111 | 管道终端、环形回放、detach/重连、resize 明确降级 |
| `artifacts.mjs` | CORE-112 | 内容寻址 sha256、分块读取、断点续传元数据 |
| `notifications.mjs` | CORE-113 | 完成/失败/审批/连接 → 通知记录 + 日志适配 |
| `diagnostics.mjs` | CORE-114 | 有界日志、脱敏、健康检查、导出 |
| `mock.mjs` | CORE-106 | MockAdapter、可脚本化场景、三界面可比对的规范化转写 |

## 核心入口

```js
import { createCore } from "./core/index.mjs";

const core = createCore({ dataDir, adapters, clock });
await core.handleRpc({ jsonrpc: "2.0", id: 1, method: "core.hello", params: { protocolVersion: 1, clientType: "tui" } });
const off = core.subscribe((event) => { /* 已持久化事件，seq 严格 +1 */ });
await core.close();
```

- `dataDir` 默认 `os.tmpdir()/coda-core/<uuid>`（stdio 入口默认 `~/.coda/core`）。
- `adapters` 为空时自动注入 `MockAdapter`，便于三界面契约测试。
- `lock` 默认 `true`：同一 `dataDir` 第二个核心会抛 `CONFLICT`（CORE-001）。
- `clock` 可注入，测试用固定时间即可得到确定性事件序列。

## RPC 形状（v1）

| 方法 | 响应 |
|---|---|
| `core.hello` | `{protocolVersion, coreVersion, clientType, capabilities}`；版本不匹配 → `VERSION_MISMATCH` |
| `core.subscribe {fromSeq?, sessionId?}` | `fromSeq=0` → `{seq, snapshot}`；连续 → `{seq, events, resumed:true}`；缺口 → `{seq, snapshot, gap:true}` |
| `core.resume {fromSeq}` | 同上（连续回放 / 缺口快照），额外带 `latestSeq`、`hasMore` |
| `core.snapshot` | `{seq, snapshot}` |
| `core.command` | `{commandId, kind, state, receipt, result, deduped, sessionId?, turnId?, terminalId?, artifactId?, interactionId?, ...}` |
| `core.diagnostics` | 版本、schema、计数、适配器、终端、恢复报告、健康 |
| `core.capabilityMatrix` | `{matrix:[{instanceId, provider, declaration}]}` |

`snapshot` 有界并覆盖客户端刷新所需：`sessions`（含 `node/environment/workspace/agent/capabilities/models`
展开）、`messages`、`interactions`、`terminals`（含最近 20k 字符回放）、`artifacts`、
`notifications`、`models`、`capabilities`。

命令失败走 JSON-RPC error，收据在 `error.data.receipt`；失败不会被写成 `succeeded`。
`interaction.answer` 同时接受 `target.interactionId` 与 `target.requestId`，可省略 `sessionId`。

### 事件载荷

- `message`：`{message:{id,sessionId,role,text,streaming,order,delta,turnId}, messageId, delta}`
  （同一 turn 的 delta 与收尾消息共用一个 message id）。
- `interaction`：`{interaction:{id,status,state,kind,prompt,options,answer,...}, interactionId, requestId}`
- `turn`：`{turn:{id,sessionId,state,...}}`
- `status`：`{status:{sessionId,state,detail?,model?}, sessionId, state}`
- `terminal`：`{terminal:{id,sessionId,title,status,...}, chunk?}`
- `artifact`：`{artifact:{id,kind,node,environment,mime,size,sha256,diff?/content?,...}}`
- `notification`：`{notification:{...}}`

## 暂时超前的 RPC（需要 Lead 合并契约）

`contracts/rpc.mjs` 的 `METHODS` 目前没有终端回放与成果读取方法（本任务禁止改 contracts），
参考实现已按下列**临时方法名**实现并有测试覆盖；建议 Lead 把它们加入 `METHODS`：

| 方法 | 用途 |
|---|---|
| `core.terminal.read {terminalId, fromOffset?, maxBytes?}` | 有界回放（TERM-001 重连） |
| `core.terminal.list` | 终端与回放摘要 |
| `core.artifact.stat {artifactId}` | 元数据/分块信息 |
| `core.artifact.readChunk {artifactId, index}` | 分块下载（ART-002） |
| `core.artifact.get {artifactId, maxBytes?}` | 小成果内容 + 截断标记 |

## 已知限制（诚实声明）

1. **没有真 PTY**：终端用管道模拟；`resize` 返回 `supported:false`，全屏 TUI/信号语义与 PTY 不同。
2. **模型目录未知**：`models.options` 为空，`current` 来自会话记录；真实模型发现属于适配器能力。
3. **排队未实现**：`turn.send` 忽略 `payload.mode="queue"`，忙碌时仍直接发送（不静默丢弃）。
4. **认证**：`createCore({authToken})` 时 `hello` 必须带 `params.auth.token`；stdio 客户端默认不带，需部署层注入。
5. **stdio 单实例**：`node core/index.mjs` 使用 `~/.coda/core` 默认目录并遵守单实例锁；第二个进程会明确失败而不是静默共写。
6. 恢复只能把未确认回合标 `unknown`；向上游核对需要适配器 `probe`（`recoverCore({probe})` 已预留）。

## 测试

```bash
cd platform
node --test "test/core/**/*.test.mjs"
```
