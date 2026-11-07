# Coda 平台客户端（TUI / Web UI）

本目录是 `docs/desktop-agent-platform/SPEC.md` §12、`CONTRACT.md` §8 的**薄客户端**参考实现：

- 只通过 `core.handleRpc(msg)` / `core.subscribe(fn)` 或 `--core stdio:<cmd>` 访问核心；
- 不读核心数据库、不 import `core/` 内部模块、不按 Agent 名称分支；
- 状态只来自 `snapshot + 有序事件`，发送只走 `core.command`；
- TUI 与 Web 共用 `shared/projection.mjs` 的 canonical 投影，契约测试比较两者等价性。

## 文件

```text
clients/
  shared/rpc-client.mjs     JSON-RPC v1 客户端（hello / subscribe / resume / command / 超时 / 缺口）
  shared/projection.mjs     快照+事件 → 只读投影（canonical 可比较）
  shared/mock-core.mjs      `--mock` 用确定性 Mock Core（仅实现 handleRpc/subscribe）
  tui/main.mjs              TUI 入口、script 驱动、交互模式
  tui/views.mjs             终端/Diff/附件/远程来源/状态文本视图
  web/server.mjs            本地 HTTP + SSE + 配对令牌校验 + RPC 桥
  web/public/index.html     Web UI 壳
  web/public/app.mjs        浏览器端薄客户端（无构建、无外部依赖）
  GUI-BLOCKED.md            GUI-101..107 阻塞证据与后续设计
```

## 运行

```bash
# TUI：连接外部核心
cd platform
node clients/tui/main.mjs --core stdio:"node core/index.mjs"

# TUI：离线演示（内置 Mock Core）
node clients/tui/main.mjs --mock

# TUI：可编程驱动（测试/脚本）
node clients/tui/main.mjs --mock --script '[{"action":"send","text":"检查构建"},{"action":"projection"}]'

# Web：本机随机端口；启动时打印 LISTENING <port> 与 PAIRING_TOKEN <token>
node clients/web/server.mjs --mock --port 0

# Web：连接外部核心
node clients/web/server.mjs --core stdio:"node core/index.mjs" --port 0
```

浏览器打开打印出的 `URL`，输入 `PAIRING_TOKEN`（或直接打开 `URL?token=<token>`）。
刷新页面会从 `/api/state` + SSE snapshot 恢复；关闭浏览器不会停止核心。

## Script 驱动（TUI）

`--script` 接受内联 JSON、JSON 文件路径，或 `{ "steps": [...] }`。每一步输出一行 NDJSON：

```json
[
  {"action":"select","sessionId":"ses_local"},
  {"action":"send","text":"检查构建"},
  {"action":"wait","until":{"assistantReply":true}},
  {"action":"answer","interactionId":"int_approval","optionId":"allow"},
  {"action":"model","model":"claude-opus"},
  {"action":"render","view":"terminal","id":"term_1","maxLines":20},
  {"action":"render","view":"diff","id":"art_diff"},
  {"action":"render","view":"attachments","sessionId":"ses_remote"},
  {"action":"saveArtifact","id":"art_diff","path":"/tmp/diff.patch"},
  {"action":"saveTerminal","id":"term_1","path":"/tmp/term.log"},
  {"action":"stop"},
  {"action":"projection"}
]
```

输出记录：`event`（含 `delta`）、`send`（失败时带 `draftRetained`）、`answer`（重复时 `duplicate:true`）、
`stop`、`model`、`render`、`saved`、`error`、`projection`（canonical，供契约测试比较）。

非 TTY 且未提供 `--script` 时，TUI 打印提示并以 0 退出，不读取 stdin、不启动终端循环。

## 协议假设（需要核心实现对照）

`contracts/` 只固定了信封、方法名、事件类型和命令状态机；下列**载荷形状**是本参考实现与内联
Mock Core 采用的契约测试形状，**不是 `core/` 当前的实现**。真实核心的差异逐条列在下一节；
接入真实 core 前必须按那一节适配，不得按本节假设声称端到端通过。

1. `core.hello` params：`{ protocolVersion, clientType, clientVersion }`；welcome：`{protocolVersion, coreVersion, ...}`。
2. `core.subscribe` params：`{ fromSeq, sessionId, clientType }`。
   - `fromSeq = 0` 返回 `{ seq, snapshot }`；
   - `fromSeq > 0` 且事件缓冲连续时返回 `{ seq, events, resumed:true }`；
   - 缺口时返回 `{ seq, snapshot, gap:true }`。
3. `core.resume` 同上；客户端也接受直接返回 snapshot 对象（含 `sessions`）。
4. `core.command` params：`{ commandId, kind, target, payload, source, ttlMs? }`，
   result 至少含 `{ commandId, state }`，失败时 `{ state:"failed", error:{code,message} }`。
5. snapshot：`{ seq, sessions[], messages[], interactions[], terminals[], artifacts[], models{current,options}, capabilities, notifications[] }`。
6. 事件 `payload`：
   - `session {session}` / `message {message, delta?}` / `status {sessionId,state,detail?}`
   - `turn {turn:{id,sessionId,state}}` / `interaction {interaction}` / `terminal {terminal|terminalId,chunk}`
   - `artifact {artifact}` / `model {model|current,options}` / `notification {notification}` / `error {error}`。
7. `session.capabilities` 是会话/实例级能力对象；`false`/`"unsupported"` 显示“不支持”，
   `"experimental"` 显示“实验性”，缺省显示“未知”，不伪装成支持。

> 若 `core/` 采用不同载荷，只需改 `shared/projection.mjs` 的适配层；TUI/Web 视图与测试断言保持。

## 与核心实现的差异（2026-11-07 核对）

核对对象：`core/server.mjs`、`core/index.mjs`、`core/journal.mjs`、`core/mock.mjs`（只读，未修改 core）。
结论：**TUI/Web 与内联 Mock Core 的契约测试全绿；但真实 core 的 RPC 载荷与本目录上述“最小约定”有多处不一致，
当前不能仅靠 `--core stdio:` 直接获得完整 UI。** 以下差异需要 core 作者/Lead 决定由哪一侧适配：

| # | 主题 | 本目录假设 / 客户端行为 | `core/` 实际实现 | 影响 |
|---|---|---|---|---|
| C-01 | `core.subscribe` / `core.resume` 回放 | `subscribe` 在 `fromSeq>0` 连续时返回 `{seq,events}`；`resume` 返回 `{seq,events}` 或 `{seq,snapshot}` | `subscribe` **总是返回 snapshot 并忽略 `fromSeq`**；`resume` 返回 `{type:"replay",fromSeq,latestSeq,nextSeq,events}` 或 `{type:"snapshot",reason,fromSeq,seq,snapshot}`（replay 无顶层 `seq`，单次最多 1000 条） | 客户端带 `fromSeq` 打开也只会得到新快照；续传必须改用 `core.resume`；客户端未按 `nextSeq<latestSeq` 继续翻页，超 1000 条会留下缺口 |
| C-02 | 全局快照内容 | `{seq,sessions[],messages[],interactions[],terminals[],artifacts[],models,capabilities,notifications[]}` | 只有 `{version,seq,generatedAt,sessions[]}`；单会话快照（`params.sessionId`）才有 `runs/turns/interactions/artifacts/events` | **刷新后消息/审批/终端/成果会丢失**（除非后续有事件补回）；Web 刷新恢复在真实 core 上不完整 |
| C-03 | 会话字段 | `node/environment/agent/workspace` | `nodeId/environmentId/agentInstanceId/workspaceId`，且无环境/Agent/工作区对象展开 | 客户端显示“未知节点/环境/Agent” |
| C-04 | 消息事件 | `payload.message = {id,sessionId,role,text,...}`，delta 追加到同 id | 适配器事件是 `payload={role,text,delta,turnId}`，**没有 message id，也没有 `message` 包装** | 投影缺少 id 会丢弃 → 真实 core 的消息不显示；流式合并也无法定位消息 |
| C-05 | 审批/提问事件 | `payload.interaction` 或 `payload.interactionId/id` | `payload={requestId,state,interactionKind,...}`，或 `{request:{...}}` | 仅 `request` 变体可被识别；`requestId` 变体被忽略 |
| C-06 | 审批回答命令 | `core.command(kind="interaction.answer", target={interactionId}, payload={answer})` | 要求 `target.sessionId` + `target.requestId`（或 `payload.requestId`），并调用适配器 `answerInteraction` | **真实 core 会返回 INVALID_PARAMS/NOT_FOUND；当前 Web/TUI 回答不可用** |
| C-07 | 终端输出 | `terminal` 事件带 `terminal/chunk`，投影累积并限长 | `terminal` 事件只有 `{terminalId,action:"open"|"resize"|"close",pty}`；PTY 输出存在 `TerminalService` 环形缓冲，**没有 RPC/事件对外回放** | 真实 core 下终端视图无输出；`TERM-001` 需要 core 增加有界回放 RPC/事件 |
| C-08 | 成果下载/Diff | 成果含 `content`/`diff`，Web `/api/artifacts/:id/download` 直接返回 | 成果记录是 `{nodeId,sha256,size,mime,metadata,blobPath,chunkSize,chunkCount,available}`，无 `content`/`diff`/`node`/`environment`；RPC 只有 `artifact.put`，**没有下载/分块读取入口** | Web 下载得到空文件；Diff 无法展示；`ART-001/002` 需要 core 暴露下载 RPC |
| C-09 | 模型 | 快照 `models:{current,options}`；`model.set` 触发 `model` 事件 | 全局快照无 models；`model.set` 只发 `session` 事件（更新 `session.model`）；无模型目录 RPC | 模型下拉为空、当前模型不会进入全局 `models.current` |
| C-10 | 能力声明 | 快照 `capabilities` + `session.capabilities` | 全局快照无 capabilities；`welcome` 只有 `{snapshot,resume,events}`；明细在 `core.capabilityMatrix`（客户端未调用） | 客户端显示“能力: 未知”，UI-003 的能力降级无数据来源 |
| C-11 | 事件推送方法名 | in-process/stdio 都按 JSON-RPC `core.event` 通知处理 | `core.subscribe(fn)` 直接回调**裸事件对象** `{id,seq,kind,sessionId,payload,ts}`；`server.mjs` 不含传输层 | in-process 已自动包装；**stdio 桥必须逐行输出 `{"jsonrpc":"2.0","method":"core.event","params":{"event":...}}`**，否则事件被忽略 |
| C-12 | hello 认证 | 只发 `protocolVersion/clientType/clientVersion` | core 配置 `authToken` 时要求 `params.auth.token`（或 `params.token`） | 带认证的核心会返回 UNAUTHORIZED；Web 的配对令牌只保护 HTTP，不会传给核心 |
| C-13 | 命令 params / 错误 | 扁平 `{commandId,kind,target,payload,source}`；失败可能返回 `{state:"failed",error}` | `normalizeCommandParams` 同时接受扁平与 `{command:{...}}`；成功响应 `{commandId,kind,state,receipt,result,deduped}`；**失败走 JSON-RPC error，收据在 `error.data.receipt`** | params 兼容；客户端 catch 能保留草稿，但不会把 `receipt` 解析成发送结果 |
| C-14 | 排队 | `turn.send` 的 `payload.mode="queue"` 由核心处理 | core `turn.send` 只读 `text/attachments`，忽略 `mode`；排队语义未实现 | 队列按钮在真实 core 上等同直接发送 |
| C-15 | 停止 | 任意选中会话都可发 `turn.stop` | 无活动回合时返回 NOT_FOUND | 空闲会话点停止会报错，UI 需先判断 active turn |
| C-16 | 命令幂等重试 | 发送失败保留草稿，但重发生成新 commandId | core 按 commandId 幂等；只有同 id 重投才不重复副作用 | 草稿重发在“结果未知”场景可能重复执行；需要 UI 复用原 commandId |
| C-17 | 通知 | 快照 `notifications[]` | 全局快照无 notifications；通知以 `notification` 事件推送（`payload.notification` 可被客户端识别） | 刷新前的历史通知丢失，只有新事件可见 |

### 建议的处理顺序

1. **C-06**（审批/提问回答）先对齐：target 增加 `sessionId` + `requestId`，客户端按 core 字段适配；否则审批链路不可用。
2. **C-04/C-05**（消息与 interaction 事件载荷）对齐，或客户端在事件适配层生成稳定 message id、识别 `requestId`/`request`。
3. **C-02/C-03**（快照/字段）补齐：全局快照需要 messages/interactions/terminals/artifacts/models，或客户端首次加载后按 session 调 `subscribe({sessionId})` 合并。
4. **C-07/C-08**（终端回放、成果下载）需要 core 新增有界 RPC；这是 TERM-001、ART-001/002 的硬前置。
5. **C-11/C-12** 属于传输/部署约定：stdio 桥统一包装 `core.event`；需要认证时把核心 token 与 Web 配对令牌分开管理。

> 在 core 未调整前，`--mock` / 内联 Mock Core 是可用路径；真实 core 集成不得声称 UI-001/WEB-001/WEB-102 已通过。
> 本目录不会为了“看起来能用”去猜 core 的私有字段；以上差异保持显式。

## Web 安全模型

- 默认 `--host 127.0.0.1`；远程请求即使携带令牌也默认 403，需显式 `--allow-remote`。
- `/api/state`、`/api/events`、`/api/command`、`/api/answer`、终端/成果接口全部要求配对令牌
  （`Authorization: Bearer`、`X-Coda-Token`、`?token=` 或 cookie）。
- 令牌只用于本机控制面；明文局域网 HTTP 不自动获得管理权限。真正的跨设备远程应走
  `SYNC-101/103` 的配对与加密连接，而不是把 HTTP 端口暴露出去。
- `POST` 请求校验 `Origin`；静态资源（HTML/JS/CSS）不含核心数据。

## 已知限制

- 没有真实核心时可用的只有 `--mock`；Mock Core 不是产品核心，不写入任何持久状态。
- 真实 core 与本文“协议假设”存在多处差异（见上一节 C-01..C-17）；当前 TUI/Web 的完整投影只在
  Mock Core / 内联契约测试场景成立，不能据此声称真实核心端到端已通过。
- Web 端“审批/提问去重”在服务端进程内做一层，跨设备最终以核心状态为准。
- TUI 的“外部打开终端”只给命令提示，不自动执行外部程序。
- Diff/终端输出均按客户端上限截断（终端 20k 字符，Diff 20k 字符）；完整内容通过成果下载。
- GUI-101..107 因本机无 Rust/GPUI 工具链全部 BLOCKED，见 `GUI-BLOCKED.md`。
