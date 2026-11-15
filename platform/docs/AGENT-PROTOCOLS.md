# Agent 接入协议清单（Zeron / T3 Code → Coda 适配器）

> 目的：把两个参考项目里**每个 Agent 的接入协议**落到可实现清单，供 `platform/adapters/` 补适配器。
> 证据规则：只写能从参考项目源码/文档核对到的事实；推测一律标「推断」。行尾括号是来源路径。
> 来源缩写：**Z** = `zeronsh/zeron`（main，2026-11 抓取，路径相对仓库根）；**T** = `pingdotgg/t3code`（main）；**C** = 本仓库 `platform/`。
> 概览与选型见 [REFERENCE-PROJECTS.md](./REFERENCE-PROJECTS.md)；能力键/状态词表见 [ADR-0005](./adr/ADR-0005-agent-capability-matrix.md)。

## 0. 一页总表

| Agent | 传输 | 启动命令 | 回合结束判定 | Z 驱动 | T 驱动 |
|---|---|---|---|---|---|
| Claude Code | stream-json over stdio | `claude --print --input-format stream-json --output-format stream-json --verbose …` | `result` 帧 | `crates/harness/src/claude/` | `apps/server/src/provider/Drivers/ClaudeDriver.ts` |
| Codex | JSON-RPC 2.0 / stdio | `codex app-server` | `turn/completed` / `turn/failed` / `turn/aborted` | `crates/harness/src/codex/` | `.../Drivers/CodexDriver.ts` |
| OpenCode | HTTP + SSE | `opencode serve --port <p> --hostname 127.0.0.1` | 该会话的终态帧（`session.status{idle}`） | `crates/harness/src/opencode/` | `@t3tools/provider-opencode/server` |
| Cursor | 钉版 SDK shim | `node <shim.mjs>` | `ev:"turn"` 的 `status` | `crates/harness/src/cursor/` | `@t3tools/provider-cursor/server` |
| Pi | JSONL RPC（非 JSON-RPC） | `pi --mode rpc --no-themes` | `agent_settled` | `crates/harness/src/pi/` | `@t3tools/provider-pi/server` |
| Grok | ACP v1 | `grok --no-auto-update agent --no-leader stdio` | `session/prompt` 的 `stopReason` | `crates/harness/src/acp/` | `@t3tools/provider-grok/server` |
| Hermes | ACP v1 | `hermes acp` | 同上 | 同上 | 仅 `AcpRegistryDriver` |
| fx | ACP v1 | `fx acp` | 同上 | 同上 | `@t3tools/provider-fx/server` |
| Antigravity | ACP v1（归档 1.1.1） | `agy_acp_server` | 同上 | 同上 | `.../Drivers/AntigravityDriver.ts` |
| Devin | ACP v1 | `devin acp` | 同上 | 同上 | 仅 `AcpRegistryDriver` |
| Muse | MSP：JSON-RPC 2.0 / stdio | `muse serve --trust-workspace` | `turn/completed.terminal` | `crates/harness/src/muse/` | `@t3tools/provider-muse/server` |

T3 内置驱动逐字（`T apps/server/src/provider/builtInDrivers.ts`）：`CodexDriver, ClaudeDriver, CursorDriver, GrokDriver, FxDriver, OpenCodeDriver, AntigravityDriver, KiroDriver, PiDriver, MuseDriver, AcpRegistryDriver`。**Hermes/Devin 在 T3 没有专属驱动**。

## 1. 安装命令（Z `crates/harness/README.md`，2026-09-21 用 `curl -fsSL` 对官方文档核对）

| Agent | Linux / macOS | 原生 Windows |
|---|---|---|
| Claude Code | `curl -fsSL https://claude.ai/install.sh \| bash` | `irm https://claude.ai/install.ps1 \| iex` |
| Codex | `curl -fsSL https://chatgpt.com/codex/install.sh \| sh` | `irm https://chatgpt.com/codex/install.ps1 \| iex` |
| Cursor | `curl https://cursor.com/install -fsS \| bash` | `irm 'https://cursor.com/install?win32=true' \| iex` |
| OpenCode | `curl -fsSL https://opencode.ai/install \| bash` | `npm.cmd install -g @opencode/cli` |
| Pi | `curl -fsSL https://pi.dev/install.sh \| sh` | `npm.cmd install -g --ignore-scripts @earendil-works/pi-coding-agent` |
| Grok | `curl -fsSL https://x.ai/cli/install.sh \| bash` | `npm.cmd install -g @xai-official/grok` |
| Hermes | `curl -fsSL https://hermes-agent.nousresearch.com/install.sh \| bash` | `irm https://hermes-agent.nousresearch.com/install.ps1 \| iex` |
| Devin | `brew install --cask devin-cli`（brew 可解析时），否则 `curl -fsSL https://cli.devin.ai/install.sh \| bash` | `irm https://static.devin.ai/cli/setup.ps1 \| iex` |
| Muse Code | `curl -fsSL https://dev.meta.ai/install.sh \| sh` | `irm https://dev.meta.ai/install.ps1 \| iex` |
| fx | `curl -fsSL https://fx.sh/setup.sh \| bash` | **无原生安装器**（手动） |
| Antigravity | 无 shell 命令，用已校验的 1.1.1 归档 | 同归档（按架构） |

安装器契约（Z 同文件）：Unix 走 `sh -c`；Windows `irm` 走 `powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command`；子进程收到 `CI=1`、`NONINTERACTIVE=1`、`TERM=dumb`、无 stdin/TTY、无继承的 `ZERON_*`；超时 15 分钟；**不自动提权**（需 sudo 的安装器会失败）。npm 兜底：`@openai/codex`、`@opencode/cli`、`@earendil-works/pi-coding-agent`、`@xai-official/grok`。

## 2. Claude Code

- 启动（Z `claude/mod.rs` `launch_args`）：`claude --print --input-format stream-json --output-format stream-json --verbose --include-partial-messages --replay-user-messages --thinking-display summarized --permission-prompt-tool stdio`，之后拼 model/effort/mode/settings。
- `--permission-prompt-tool stdio` 是**未文档化**的开关：注释明说「absent from `claude --help`」，只在 2.1.228 实测通过（Z `claude/mod.rs` 模块头）。
- 模型/档位：`--model <id>`，1M 上下文用 id 后缀 `sonnet[1m]`；`--effort low|medium|high|xhigh|max`；**Ultrathink 不是 flag**，是 prompt 前缀 `Ultrathink:\n`（Z `claude/catalog.rs`、`mod.rs`）。
- 权限：`--permission-mode bypassPermissions|acceptEdits|default|plan`（`plan` 优先于 access）；auto-approve 追加 `--dangerously-skip-permissions`；设置走 `--settings <JSON>`（`fastMode`/`alwaysThinkingEnabled`/`ultracode`）。
- 会话：resume 用 **`--resume=<session_id>`**（等号形式）；源码中**没有** `--session-id`/`--continue`/`--fork-session`（Z `claude/mod.rs`）。
- 输入（stdin）：首帧 `{"type":"control_request","request_id":"zeron_initialize","request":{"subtype":"initialize","perTaskStopAffordance":true}}`；提示是 `{"type":"user","uuid":…,"message":{"role":"user","content":…},"parent_tool_use_id":null}`（Z `claude/wire.rs`）。
- 输出帧 `type` 白名单：`system`、`stream_event`、`assistant`、`user`、`rate_limit_event`、`result`、`control_request`、`control_response`、`command_lifecycle`（Z `claude/wire.rs`）。
- **回合结束 = `result` 帧，且是唯一来源**：`subtype=="success"`、非 interrupted、`terminal_reason` 不是 `aborted_streaming|aborted_tools` → Completed，否则 Interrupted；`is_error` 或 `api_error_status` 为 429/529 → Errored（Z `claude/normalize.rs`）。
- 停止回合：`control_request{request subtype:"interrupt", cancel_queued:true}`，**进程不退**；整个 runtime 中断才 2s SIGTERM / 3s SIGKILL（Z `claude/wire.rs`、`mod.rs`）。
- steer：往 stdin 再写一条 user 行，多带 `"priority":"now"|"next"`；有未闭合工具调用时强制 `next`（`now` 会打断 MCP 工具）（Z `claude/wire.rs`）。
- 审批：`control_request` 的 `subtype:"can_use_tool"` → `control_response` 的 `{"behavior":"allow","updatedInput":…}` 或 `{"behavior":"deny","message":…}`；**AskUserQuestion 也走 can_use_tool**，答案写进 `updatedInput.answers`（Z `claude/mod.rs`）。
- 版本：**没有写死的最低/固定版本**；只有注释锚点 2.1.228（模块头）与 2.1.211 / 2.1.280 / 2.1.286 / 2.1.319（Z `claude/mod.rs`）。C 实测 2.1.319（`C fixtures/adapters/claude-stream-json-noauth.fixture.json`）。
- 坑：带 `parent_tool_use_id` 的帧必须包成 `AgentEvent::Subagent`，不能并入父流；`system/init` 会重复出现（后台子 Agent 结束时），只发一次 SessionStarted；stdout EOF 即退出，回合未关则补 `Done{Errored}`；停止后 3s 内迟到的 `result` 整条丢弃（Z `claude/normalize.rs`、`mod.rs`）。

## 3. Codex

- 启动只加一个参数 `app-server`（Z `codex/mod.rs` `spawn_app_server`）；可执行解析 `CODEX_EXECUTABLE` → PATH → login-shell PATH → `~/.local/bin` 等常见目录。**没有** `--sandbox`/`-c` 这类 CLI flag。
- 握手：`initialize{clientInfo{name:"zeron-native",…}, capabilities{experimentalApi:true}}`，可选 `capabilities.optOutNotificationMethods:["turn/diff/updated","item/fileChange/outputDelta"]`（被拒则去掉该字段重试一次）→ `initialized`（Z `codex/mod.rs`）。C 的 0.167.0 实测返回 `{userAgent,codexHome,platformFamily,platformOs}`（`C fixtures/adapters/codex-app-server-initialize.fixture.json`）。
- 会话/回合方法（逐字）：`thread/start`（`cwd`、`approvalPolicy`、`sandbox` kebab 值、`model`、`serviceTier`、`config` 点号覆盖、`approvalsReviewer:"user"`）、`thread/resume{threadId}`（archived 先 `thread/unarchive`）、`turn/start`、`turn/steer{expectedTurnId,clientUserMessageId}`、`turn/interrupt{turnId}`、`thread/compact/start`、`review/start`、`thread/backgroundTerminals/list|terminate`（Z `codex/mod.rs`）。
- `turn/start` 参数：`threadId`、`input[]`（`{type:"text"}` 或 `{type:"skill"}`）、`approvalPolicy`、`sandboxPolicy{type:"readOnly"|"workspaceWrite"|"dangerFullAccess"}`、`summary:"auto"`、`model`、`effort`、`serviceTier`、`collaborationMode{mode:"plan"|"default",…}`、`approvalsReviewer`（Z `codex/mod.rs`）。
- 通知：`turn/started`、`item/started`、`item/completed`、`item/agentMessage/delta`（`delta` 与 `textDelta` 两种拼写都要收）、`item/commandExecution/outputDelta`、`item/reasoning/textDelta|summaryTextDelta`、`thread/tokenUsage/updated`、`turn/plan/updated`、`turn/completed|failed|aborted`、`error`（带 `willRetry`）（Z `codex/mod.rs`、`normalize.rs`）。
- 结束帧真实形状：`{"method":"turn/completed","params":{"threadId":"th-1","turn":{"id":"t-1","status":"completed"}}}`（Z `tests/fixtures/codex/v2-lifecycle.jsonl`）。
- **回合结束判定**：`turn/completed|failed|aborted` 三者之一，且只结算「当前在飞的那个 turn」（`TurnRouter::settles`）；重复/陈旧 id 记入队列后丢弃。进程 EOF **不是**回合结束，走 crash 报告（Z `codex/mod.rs`）。
- 停止：`turn/interrupt{turnId}` 只结束回合，进程与子线程留着；`stops_turn_in_place() -> true`（Z `codex/mod.rs`）。
- 审批：服务端→客户端请求 `item/commandExecution/requestApproval`、`item/fileChange/requestApproval`、`item/permissions/requestApproval`；应答 `{"id":101,"decision":"accept"|"decline"}`，权限授予 `{"permissions":{…},"scope":"turn"}`（Z `codex/mod.rs`、`tests/fixtures/fake-codex.sh`）。
- MCP elicitation：方法 `mcpServer/elicitation/request`，只有 `mode:"form"` 会问，其余回 `{"action":"decline"}`；未知服务端请求回 `-32601`（Z `codex/elicitation.rs`）。
- 版本：驱动按 **codex-cli 0.153.4** 验证（模块头 VERSION PIN），`MIN_CODEX_VERSION = 0.146.0`，旧版直接报错（Z `codex/mod.rs`）。
- 坑：`turn/start` 的响应**先于** `turn/started` 到达，此时 `turn/interrupt` 会被拒（"no active turn to interrupt"）→ 必须等 `turn/started` 再停；`turn/steer` 被拒就排队到下一回合；`item/completed` 可能重复，需去重；命令可能活过自己的回合（`processId` 是 Codex 的 exec session，不是 OS pid）（Z `codex/mod.rs`）。
- **版本差（重要）**：Z 钉 0.153.4，C CI 实测 **0.167.0**（`C fixtures/adapters/probes/codex.json`），`turn/*` 字段需按新 schema 复核。

## 4. OpenCode

- 启动：`opencode serve --port <free> --hostname 127.0.0.1`；端口先 bind `127.0.0.1:0` 取内核分配再 `agent_ports::reserve`，`EADDRINUSE` 可重试 4 次（间隔 250ms）（Z `opencode/mod.rs`）。
- 鉴权：HTTP **Basic** `base64("opencode:{password}")` 每个请求都带；密码由我们生成（`OPENCODE_PASSWORD` / `OPENCODE_SERVER_PASSWORD` 同一 uuid v4，`OPENCODE_SERVER_USERNAME=opencode`，`OPENCODE_CLIENT=zeron`）（Z `opencode/mod.rs`）。
- 就绪：`/api/health` 150ms 轮询，默认 300s 超时（`ZERON_OPENCODE_STARTUP_TIMEOUT_SECS` 可覆盖）；stdout 出现 `server listening on` 立即探测（Z 同文件）。
- 端点（逐字）：V1(1.x) `POST /session`、`POST /session/{id}/prompt_async`、`POST /session/{id}/abort`、`GET /session/{id}`、`GET /session/status`、`GET /provider`、`GET /command`；V2(2.x) `POST /api/session`、`POST /api/session/{id}/prompt`、`POST /api/session/{id}/interrupt`、`POST /api/session/{id}/agent|model`、`GET /api/session/active`、`GET /api/model|agent|command`（Z、T `opencode/tests.rs`）。
- 目录作用域：header `x-opencode-directory`（百分号编码）；V1 另带 `?directory=`（V2 严格校验会拒未知参数）。
- SSE：一条全局总线，2.x `GET /api/event`、1.x `GET /global/event`；帧为 `data: {json}`，2.x 另有注释心跳（Z、T）。
- 事件名：1.x `session.status`、`session.idle`、`session.error`、`session.interrupted`、`message.part.updated`、`message.part.delta`、`permission.asked`、`question.asked`、`session.created`、`server.heartbeat`；2.x `session.execution.started|succeeded|failed|interrupted`、`session.text.started|delta|ended`、`session.reasoning.*`、`session.tool.input.started|called|success|failed`、`session.usage.updated`、`permission.asked`、`form.created`。**没有 `permission.updated`**（Z、T）。
- 回合法：以「该会话的终态帧」为准——1.x `session.status{idle}` 与 `session.idle`；2.x `session.execution.succeeded→session.idle`、`interrupted→session.interrupted`、`failed→session.error`。且必须先见过 busy/retry 或本端已 abort，才承认 idle，避免上一回合的残留 idle 结算新回合（Z `opencode/mod.rs`）。
- 停止：中止会话，1.x `POST /session/{id}/abort`、2.x `POST /api/session/{id}/interrupt`；子会话先 `GET /session/{root}/children` 再逐个 abort（预算 15s）。
- 恢复：直接复用 id，`GET /session/{id}` 失败则新建；2.x 再用 `POST /api/session/{id}/agent` 纠正遗留的 plan agent。
- steer：`SteeringMode::StepBoundary`——排队，等当前回合 idle 后作为下一条 prompt 发出；无工具运行时先 abort 抢占（5s 超时）。
- 审批：`permission.asked` → `POST /permission/{id}/reply`，full access 回 `{"reply":"once"}`，supervised 问人，yes→`once` 否则 `reject`；**2.0.4+ 键名改为 `decision`**（Z、T）。
- 提问：1.x `POST /question/{id}/reply {"answers":[["No"]]}`；2.x `POST /api/session/{id}/form/{formID}/reply {"answer":{"q0":"Yes"}}`（T）。
- 版本：**没有写死依赖版本**，启动探测 semver（能解析 `opencode v2.0.11`），`major>=2` 即 V2；`TESTED_SINCE = "1.14"`；实测基线 1.18.31 / 2.0.3 / 2.0.11 / 2.0.24 / 2.1.2。
- **为什么弃用 ACP**（原文在 `Z opencode/mod.rs` 顶部 module docs）：opencode 自己的 ACP 层「settles a prompt on the FIRST `session.status{idle}` it observes after subscribing, uncorrelated with the submitted turn, and it ignores `session.error` and `session.idle` entirely」；且「drops all subagent traffic」。→ `C adapters/acp-profiles.mjs` 里给 opencode 登记的 `opencode acp` 应降为实验路径，主链路走 HTTP+SSE。

## 5. Cursor

- **不 spawn `cursor-agent`**：启动 `node <shim.mjs>`，shim 由 `include_str!` 内嵌、物化进受管 npm 安装；CLI 只用于 `installed()`/`executable_path()` 探测（Z `cursor/mod.rs`）。
- 版本钉死：`const CURSOR_SDK_PIN: &str = "@cursor/sdk@1.0.32";`（按 1.0.31 typings 核对）（Z `cursor/mod.rs`）。
- 不用 `cursor-agent -p --trust`：注释说它没有权限门，「never used」。
- stdin op（逐字）：`run`、`user`、`steer`、`interrupt`、`stop`；`run` 帧字段 `prompt`、`cwd`、`model`、`modelOptions`、`resume`、`mcp`、`storeDir`、`images`、`silenceTimeoutMs`（Z `cursor/shim.mjs`）。
- stdout 事件（`ev`）：`ready`、`steered`、`text`、`thinking`、`tool`（`phase:"start"|"end"`）、`usage`、`turn`、`fatal`；目录/登录另用 `models`、`auth-url`、`logged-in`。
- 回合结束：`ev:"turn"` 的 `status` ∈ `finished|error|cancelled` → Completed/Errored/Interrupted。
- 停止：`op:"stop"` 只取消本回合、agent 留着接下一 prompt（12s 不 settle 就按 Interrupted 收尾并弃用 shim）；`op:"interrupt"` 取消整个 run，2s/3s 后 TERM→KILL。
- steer：`op:"steer"`；ack 只有 `revert_to_followup` / `complete_delivered`。
- 审批：**没有审批通道**——`askQuestion`、`generateImage` 在创建 agent 时被 `disallowedTools` 禁用，注释说明提问会永久阻塞 run（Z `cursor/shim.mjs`）。
- 坑：SDK 凭据与 `cursor-agent login` 相互独立（需 `CURSOR_API_KEY` 或 `~/.cursor/sdk/auth.json`）；SDK 默认 store 是按 workspace 加锁的 SQLite（并发报 "database is locked"），故每 run 独立 store；大图内联会让 send 永久静默（单图 4 MiB / 合计 8 MiB 上限，实测 14.5 MB 时 400s+ 无更新）；`silenceTimeoutMs` 默认 10 分钟；`get_models` 有 30/分钟限流；未连接时 `Agent.create` 也可能成功，鉴权失败要到 run 才暴露（Z `cursor/mod.rs`、`shim.mjs`）。

## 6. Pi

- 协议：**JSONL 单行帧，不是 JSON-RPC 2.0**；请求自带 `id`（`zeron-N`），响应 `{"type":"response","id","success","data"|"error","command"}`（Z `pi/rpc.rs`）。
- 启动：`pi --mode rpc --no-themes`；发现/探测进程再加 `--no-session`。不是 `pi rpc` 子命令，也**不装不启 `pi-acp`**（Z `pi/mod.rs`、`docs/pi.md`）。
- 版本：**下限** Pi ≥ 0.85.1（低于即报错），`PROTOCOL.md` 写「targets Pi >= 0.85.1」（Z `pi/mod.rs`、`pi/PROTOCOL.md`）。
- 请求方法（`type` 逐字）：`prompt`、`get_state`、`get_entries`、`get_commands`、`get_available_models`、`get_available_thinking_levels`、`set_model`、`set_thinking_level`、`set_steering_mode`、`set_follow_up_mode`、`clear_queue`、`abort`、`compact`、`get_session_stats`、`set_session_name`、`export_html`、`set_auto_compaction`。
- 事件（逐字）：`agent_start`、`agent_settled`、`agent_end`、`message_start`、`message_update`（内层 `assistantMessageEvent.type`=`text_delta`/`thinking_delta`）、`message_end`、`tool_execution_start`、`tool_execution_end`、`queue_update`、`compaction_end`、`auto_retry_end`、`extension_error`、`extension_ui_request`、`response`。
- **回合结束 = `agent_settled`，`agent_end` 不算**（重试/压缩/扩展命令还在跑）；settled 后必发 `get_state`，用 `isStreaming`/`isCompacting`/`pendingMessageCount` 定论。状态：`message_end.stopReason` 为 `error`→Errored（`errorMessage`）、`aborted`→Interrupted、否则 Completed（Z `pi/PROTOCOL.md`、`normalize.rs`）。
- 停止：先 `clear_queue` 再 `abort`，2s 宽限后 TERM/KILL 进程组。steer：`prompt` + `"streamingBehavior":"steer"`，follow-up 用 `"followUp"`。
- 审批/提问：`extension_ui_request` 的 `select`/`confirm`/`input`/`editor` 映射成提问；回答走 `extension_ui_response` 的 `value`/`confirmed`/`cancelled`；`confirm` 选项固定 `"Yes"`/`"No"`；`timeout` 毫秒，负值视为已过期（Z `pi/ui.rs`）。
- 会话：恢复用 `--session <绝对文件>`；只有主机记录过「空会话证明」才用 `--session-id`/`--session-dir`/`--provider`/`--model`/`--thinking` 重建，且任何输入前撤销该证明（Z `pi/sessions.rs`）。
- 坑：`set_steering_mode` 会写进 Pi **全局**设置，故只在全局与项目都未配时才选 `all`；stdout 可能混入依赖的 `console.log`，不可解析行只 warn 不结束回合；单帧 >32 MiB 整帧丢弃后继续读；孤立 UTF-16 代理转义替换成 `\ufffd`；Pi 延迟到首条 assistant 消息才写 session 文件；idle 但 `pendingMessageCount>0` 时没有 settle 事件，每 250ms 重询、最多 15s（Z `pi/rpc.rs`、`mod.rs`）。

## 7. ACP 族：Grok / Hermes / Devin / fx / Antigravity

ACP 只保留给 ground-up 的 ACP Agent；claude/codex/cursor/pi 都已改为原生驱动（Z `acp/mod.rs` 模块注释、`lib.rs`）。

- 骨架：JSON-RPC 2.0 over stdio，protocol v1；`initialize`（`protocolVersion: 1`，拒掉 fs/terminal 能力）→ `session/new`（参数逐字 `{"cwd": home, "mcpServers": []}`）；恢复走 `session/load`（失败回退新会话，load 重放的历史被丢弃）。
- 启动（逐字）：Grok `grok --no-auto-update agent --no-leader stdio`；Hermes `hermes acp`；Devin `devin acp`；fx `fx acp`；Antigravity `agy_acp_server`（Z `acp/mod.rs` 各 `*_spec`）。**注意 `C adapters/acp-profiles.mjs` 里写的 `grok agent stdio` 少了两个 flag。**
- 版本/包：Grok npm 兜底 `@xai-official/grok@1.0.4`；Antigravity 归档 pin `antigravity-acp` `1.1.1`（`https://dl.google.com/agy-extensions/releases/<os>/agy-acp-server-agy_acp_server_1.1.1-<platform>.zip`，每平台单独 sha512）；Hermes/Devin/fx 无 npm 兜底（Python/uv 或原生安装）。
- EXECUTABLE 覆盖：`GROK_EXECUTABLE`、`HERMES_EXECUTABLE`、`DEVIN_EXECUTABLE`、`FX_EXECUTABLE`（registry agent 为空串）。
- 回合结束：`session/prompt` 的 response `stopReason`——`cancelled`→Interrupted、`refusal`→Errored、其余→Completed。Grok 另有 `_x.ai/session/prompt_complete` 被当作**权威**结束，`prompt_stall` 30s 无响应报卡死（Z `acp/mod.rs` `grok_spec`）。
- 停止：`session/cancel`（prompt 以 `cancelled` 收敛），再 SIGTERM→SIGKILL。超时常量：停止 12s、`session/load` 90s、握手 120s、控制请求 15s。
- steer 模式：Grok / Devin = `StepBoundary`（抢占生成，立即），Hermes / fx / registry = `TurnBoundary`；注释明确 Grok/Devin/Hermes **没有** `_session/steering` 扩展，声明了扩展的 agent 才拿到中途注入（Z `acp/mod.rs`）。
- 审批：option kind 全集 `allow_once`/`allow_always`/`reject_once`/`reject_always`；**两套相反策略**——自动批按 `allow_always` > `allow_once` > 第一个选；人工答「是」只选 `allow_once`（绝不给 `allow_always`），拒绝优先 `reject_once`，无可用选项回 `{"outcome":{"outcome":"cancelled"}}`（Z `acp/normalize.rs` vs `acp/permission.rs`，有专门单测）。
- 按 `toolCall.kind` 分派：`read`/`search`/`think` 不问；`edit`/`delete`/`move` 在 auto-accept-edits 下放行；其余（`execute`/`fetch`/other/无 kind）要问（Z `acp/permission.rs`）。
- 通知：`session/update` → `agent_message_chunk`/`agent_thought_chunk` 出文本，`user_message_chunk`（load 回放）丢弃，`tool_call(_update)`/`plan`/`available_commands_update`/`usage_update` 有映射，`current_mode_update`/`config_option_update`/`session_info_update` 是 no-op；工具只有 `completed`/`failed` 才结算（Z `acp/normalize.rs`）。
- 模型/档位：ACP 没有 per-prompt model 字段，靠 `session/set_config_option`（category `model`/`thought_level`）。静态 fallback：Grok `grok-4.5`（Low/Medium/High）、Devin `swe-1-7-medium`/`claude-fable-5-1-high`/`adaptive`、Hermes `hermes-4-405b`/`hermes-4-70b`（无 effort 阶梯）、fx 无内置模型（Z `acp/mod.rs`）。
- access_modes：Grok/Devin/Hermes 仅 `FullAccess`；fx 与 registry agent 是 `Supervised + FullAccess`。
- 各 Agent 坑：Grok 的 `--no-auto-update` 必须放子命令**前**、`--no-leader` 在 `agent` 上（leader 模式会挂 `~/.grok/leader.sock`，僵死 leader 表现为完全静默）；Grok 被取消且未产出的 prompt 会从历史消失（`drops_unstarted_cancelled_prompt=true`，需重发）；Hermes 与 Grok 在 stdin EOF 后**不退出**，要特殊 teardown（Z `acp/mod.rs`）。
- Devin 子代理：`clientCapabilities._meta["cognition.ai/subagentSupport"]`，事件 `cognition.ai/subagent_started` / `subagent_context.parentAgentId` / `subagent_completed`（Z `acp/subagent_devin.rs`）。Antigravity 用 `GEMINI_HOME`（默认 `~/.gemini`）定位凭据，设置在 `$GEMINI_HOME/antigravity-acp/settings.json`（Z `acp/antigravity_paths.rs`）。
- 登录：`initialize` 时 `clientCapabilities` 带 `auth={terminal:true}`、`_meta["terminal-auth"]=true`、`elicitation={url:{}}`；调用顺序 `initialize` → `session/new`，遇 `auth_required` 才 `authenticate{methodId}`，再 `session/new` 验证。
- **版本钉住的真相**：`acp_registry.rs` **不是**版本表，它读 agentclientprotocol.com 的 `registry.json` 并按平台装 binary/npx/uvx；`@agentclientprotocol/claude-agent-acp 0.66.0` 在当前源码中**找不到**，`codex-acp 1.1.14` 只出现在 `adapter_install.rs` 的单测样例里。`docs/research/acp.md` 里那两个版本号只描述 2026-08 的过渡阶段。

## 8. Muse（Muse Code / MSP）

- 协议：Muse Session Protocol = newline 分隔的 JSON-RPC 2.0 over stdio，即 `@muse-code/sdk` 讲的 wire（Z `crates/harness/README.md`、`muse/rpc.rs`）；单帧上限 10 MiB。
- 启动：`muse serve --trust-workspace`，不沙箱时加 `--disable-sandbox`；只读场景（列模型/生成标题）用 `muse serve --disable-shell --disable-write --no-session-log`（Z `muse/host.rs`）。
- 环境：先 `env_remove("META_API_KEY")`、`MUSE_NO_AUTO_UPDATE=1`，再叠加 run 自带 env（`META_API_KEY` 会被有意覆盖回来）。
- 握手：`initialize` 带 `clientInfo{name,title,version}`、`capabilities.requestedCapabilities`（会话态 `["sessionMcp"]`、只读态 `[]`）、`experimentalApi`；校验 `result.schema.version == 1` 后发 `initialized`。
- 方法（逐字）：`session/start`（`sessionId`、canonical `workspaceRoot`、`providerId:"meta"`、`approvalMode`）、`session/resume`（+`excludeItems:true`，紧跟 `session/setApprovalMode`）、`turn/start`、`turn/interrupt{sessionId,turnId}`、`model/list`、`session/setModel`、`approval/decide`、`userInput/answer`、`userInput/cancel`（Z `muse/session.rs`）。
- id 语义：非 query 方法自动注入客户端 UUIDv7 `commandId`；**新 turn 的 id 就是它 `turn/start` 的 commandId**，ack 前已确定（Z `muse/rpc.rs`）。
- steer：**没有 `turn/steer`**——后续提示都是 `turn/start` 带 `ifBusy:"steer"`（首条 `"queue"`）；`mod.rs` 注释里的 `turn/steer` 是文档与代码不一致（Z `muse/mod.rs` vs `muse/session.rs`）。
- 回合结束：`turn/completed` 的 `params.terminal`——`completed`→Completed、`cancelled`→Interrupted、其余→Errored；后台 subagent 唤醒的 turn 以 `turn/started` 且 turnId 不属于本方识别（Z `muse/normalize.rs`、`session.rs`）。
- 通知：`turn/started`、`item/started|updated|completed|delta`、`turn/completed`、`session/tokenUsage`、`session/contextUsage`、`approval/requested|updated|resolved`、`userInput/requested|settled`、`view/gap`、`usage/changed`（后者账号级、无 sessionId）。
- 审批：模式逐字 `denyUnmatched`/`promptUnmatched`/`allowAll`；优先级 `approved`+scope `once` → 任意 approved → `denied` → `abort`。
- 提问：选项取 `options[].label`，多选看 `selection.mode == "multiple"`，自由文本截 500 字符；无答案发 `userInput/cancel`，reason 逐字 `The user dismissed the questions without answering.`。
- 版本：源码**没有**硬编码 Muse 版本校验，注释/测试只提 1.4 / 1.4.x；模型兜底 `muse-spark-1.2`。
- 坑：`workspaceRoot` 必须 canonical（否则拒绝）；`workspaceRoots` 有粘性，只允许首条提示携带；`model/list` 为空时提示去 `muse` 里 `/login`；`view/gap` 表示事件流丢了片段。

## 9. 对 Coda 适配器的 5 条最关键结论

1. **回合结束必须绑定「协议自带的终止帧」，不能用静默超时兜底。** 各家终止信号不同（Claude `result`、Codex `turn/completed|failed|aborted`、Pi `agent_settled`、Cursor `ev:"turn"`、Muse `turn/completed.terminal`、OpenCode 会话终态帧），而 Zeron 两次踩坑都源于「静默结算」：ACP 静默兜底把还在忙的 agent 判完，OpenCode 的 ACP 层在第一个无关 idle 就结算。→ `contracts/events.mjs` 的 `turn` 事件应带 provider 级终止来源标记，`turn.terminalTrust` 只在能指出具体帧时置真。
2. **steer 是分级能力，不要做成布尔值。** Codex 有原生 `turn/steer`；Claude 是 stdin 追加 user 行 + `priority`；Muse 用 `turn/start` + `ifBusy:"steer"`；Cursor 用 `op:"steer"`；Pi 用 `streamingBehavior`；ACP 里只有声明 `_session/steering` 的 agent 能中途注入（Grok/Devin 是抢占式、Hermes/fx 只能 turn 边界）；OpenCode 只有 step 边界排队。→ 对应 ADR-0005 的 `turn.nativeSteer` 与 `stop-restart` 降级，并逐 Agent 记录 `steeringMode`。
3. **审批/提问不是所有 Agent 都有，且语义差别很大。** Cursor 直接禁用 `askQuestion`（会永久阻塞 run）；OpenCode 走 `permission.asked` + `question.asked`；Claude 复用 `can_use_tool`；Codex 是服务端→客户端请求 + MCP elicitation；Muse 是分阶段 `approval/decide`；ACP 里「自动批」和「人工答」策略**相反**（自动偏好 `allow_always`，人工只给 `allow_once`）。→ `approvals` 必须运行期探测，按产品名硬编码会像 `C adapters/claude-code.mjs` 现在这样只能全填 `unverified`。
4. **版本漂移才是真正的集成风险，握手后探测比版本号分支可靠。** Codex 参考钉 0.153.4 而我们实测 0.167.0；Claude 没有任何版本常量，只有 2.1.228/2.1.319 的注释锚点；OpenCode 靠 semver 探测 `major>=2` 切换 V1/V2 端点；Cursor 钉 `@cursor/sdk@1.0.32`；Pi 是下限 0.85.1；Muse 完全不校验。→ 适配器先握手，再按 `schema.version`/`protocolVersion`/能力字段分支，并把探测结果写进矩阵行。
5. **ACP 不能当万能层，但可以当兜底。** Zeron 把 claude/codex 转过 ACP 又退休，opencode 的 ACP 层明确在第一个无关 idle 结算并丢 subagent 流量。→ `C adapters/acp-profiles.mjs` 需两处修正：`opencode` 的 `acp` 降为实验路径，`grok` 补 `--no-auto-update` / `--no-leader`；Hermes/Devin/Antigravity/fx/Grok 仍走 ACP，但每家的扩展差异（steering、effort、resume 怪癖）要写进 profile。

## 10. 仍缺的证据

| 缺口 | 现状 | 需要什么 |
|---|---|---|
| 真实回合录制 | C 只有握手/无登录帧（`probes/*.json`、`*-handshake.fixture.json`、`codex-app-server-initialize`） | 每家一次完整「提示 → 流式 → 工具 → 结束」的脱敏录制 |
| Codex 0.167.0 会话方法 | 只验过 `initialize` | 用真实凭据跑 `thread/start`/`turn/start`，比对 0.153.4 schema |
| Claude 2.1.319 flag 漂移 | `--permission-prompt-tool stdio` 在 2.1.228 实测、未文档化 | 在 2.1.319 上复验该 flag 与 `--effort` 取值 |
| OpenCode 版本分支 | 1.x/2.x 事件名、`reply` vs `decision` 键名来自测试断言 | 2.0.4+ 实机验证 permission/question 回复键 |
| Cursor SDK 可用性 | 依赖 `@cursor/sdk@1.0.32` 与独立凭据 | 在无 GUI 的 Linux/手机上验证登录与首回合 |
| ACP 尾部未读到 | `acp/mod.rs` 342 KB，只拿到前 2333 行 | 在能 `git clone` 的机器上补 `session/set_mode`、`-32015`、stopReason 其余字面值 |
| Muse 实机 | 源码无版本校验，测试提 1.4.x | 一次真实 `muse serve` 握手 + 回合 |
| 文档漂移 | `CAPABILITY-MATRIX-DRAFT.md` 仍写「无真实 fixture」，但 `fixtures/adapters/probes/` 已有 8 份 | 更新草案与 `SUPPORT-MATRIX.md` 的证据列 |

> 备注：T3 的 `ClaudeDriver`/`CodexDriver` 等本次只确认存在与归属（`builtInDrivers.ts`），未逐行读；`codexLaunchArgs.ts` 两次抓取超时。T3 列的路径可作下一轮起点。
