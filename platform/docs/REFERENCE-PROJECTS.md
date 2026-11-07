# 参考项目研究（T3 Code / Zeron）

> 对应 SPEC §19。目的：**能借用参考实现的就不自己逆向**；只对没有公开可编程接口的国产 Agent 做协议勘察。
> 两个仓库均为 MIT 许可：T3 Code（pingdotgg/t3code，TypeScript）、Zeron（zeronsh/zeron，Rust）。
> 许可影响见 `LICENSE-AUDIT.md`（只借鉴设计与协议事实，不复制代码）。

## 1. T3 Code（`pingdotgg/t3code`，TS，MIT）

定位：**agent harness control surface** —— 与 Coda 的目标几乎同构。控制本机已安装的
Claude Code / Codex / Cursor / Grok Build / OpenCode / Antigravity / fx。

可借鉴：

- 服务端按 provider 拆适配器：`apps/server/src/provider/`（`ClaudeProvider.ts`、`CodexProvider.ts`、
  `AntigravityProvider.ts`、`GrokProvider.ts`、`KiroProvider.ts`、`OpenCode2Client…`），
  每个 provider 再拆 `Drivers/`（安装、home 目录、模型目录、版本探测）。
- 公共驱动层：provider-core 的 snapshotProbe / driver / model manifest；能力、模型、版本是
  **运行期探测结果**，不是产品名硬编码。
- 认证独立成服务（`ProviderAuthService`、`ProviderCredentialStore`、CodexChatGptAuth 等），
  宿主不复制 Agent 自己的账号体系。
- 会话数据与进程分离：关闭界面不结束会话；有后台服务（`t3 service install`）。

我们采用：provider 目录按 Agent 拆分、驱动层负责安装/版本/模型目录、认证独立。
我们没采用：其产品形态、Connect/云依赖、具体 UI。

## 2. Zeron（`zeronsh/zeron`，Rust + GPUI，MIT）

定位：本地优先的编码 Agent 控制面，Rust 引擎 + GPUI 视口，headed/headless 同一协议，
可选多设备同步。

可借鉴（直接对应我们的 SPEC 5/6/11）：

- **引擎/视口进程边界**：`zeron`（UI）与 `zeron-engine`（后台引擎）分离；关掉 App 引擎继续跑；
  UI 断线自动重连；引擎更新是事务式（先试运行新版本，失败回滚 SQLite）。
- **空间 = (设备, 目录)**，路径不是全局身份；`AgentEvent` 统一事件 + `RunControls`（审批/提问）。
- **逐 Agent 原生驱动**（`crates/harness/src/<agent>/`，README 记录了安装命令与协议）：
  - **Claude Code**：直接跑 `claude` CLI 的 stream-json；权限走 `--permission-prompt-tool stdio`
    控制通道；`--permission-mode` 映射访问模式；stdin 追加 user 行实现 steer；`result` 帧即回合结束；
    子 Agent 帧带 `parent_tool_use_id`，必须单独归类。
  - **Codex**：`codex app-server` JSON-RPC 2.0 over stdio：`initialize`（clientInfo +
    `capabilities.experimentalApi`）→ `initialized`；`thread/start` / `thread/resume`；
    `turn/start`（prompt/model/effort/sandboxPolicy/approvalPolicy/collaborationMode）；
    通知 `agentMessage/reasoning` delta、item 生命周期、`thread/tokenUsage/updated`、
    `turn/completed|failed|aborted`；审批 `item/*/requestApproval`；steer `turn/steer`；
    停止 `turn/interrupt`；版本钉住 0.153.4（我们 CI 观测到 0.167.0，需按新 schema 复核）。
  - **OpenCode**：弃用 ACP，改原生 HTTP+SSE：`opencode serve --port <free> --hostname 127.0.0.1` +
    `OPENCODE_SERVER_PASSWORD`；`/api/session`、`/api/session/{id}/prompt`、`/api/event`（2.x）与
    `/session/...`、`/global/event`（1.x）；问题走 question.reply / form。
  - **ACP 通用**（仅给 ground-up ACP 的 Agent）：Grok `grok agent stdio`、Devin `devin acp`、
    Hermes `hermes acp`、fx `fx acp`、Antigravity `agy_acp_server`；`initialize` →
    `session/new` / `session/load`；`session/prompt` 的 `stopReason` 结束回合；
    `session/update` 归一化；`session/cancel` 停止；steering 用 `_session/steering`。
  - **Pi / Cursor / Muse**：分别有原生驱动（`pi`、`cursor`、`muse` MSP）。
- **安装命令表**（README 已核对 2026-09-21）：Claude/Codex/Cursor/OpenCode/Pi/Grok/Hermes/Devin/
  Muse/fx/Antigravity 的官方安装方式，可直接抄进我们的安装器设计。
- 测试方法：录真实会话为 fixture + fake-<agent> 回放（与我们 `platform/fixtures/` 的做法一致）。

我们采用：引擎/视口分离、`AgentEvent` 统一事件、逐 Agent 原生驱动、ACP 只用于真 ACP Agent、
安装命令表、录制回放测试。
我们没采用：Loro CRDT/Cloudflare Durable Objects、WorkOS 账号、GPUI 具体实现（GUI 仍按 SPEC 走
Rust + GPUI，本环境无法构建）。

## 3. 对当前适配任务的影响

| TODO | Agent | 结论 |
|---|---|---|
| AGT-111 | Claude Code | 原生 stream-json 驱动（参考 Zeron）：无需逆向，需真实会话录制 |
| AGT-112 | Codex | app-server JSON-RPC（参考 Zeron 方法表 + CI schema）：无需逆向 |
| AGT-121 | OpenCode | 原生 HTTP+SSE（参考 Zeron，不用 ACP）：无需逆向 |
| AGT-119/120/126/129 | Antigravity/Grok/Hermes/fx | ACP 统一接入（参考 Zeron 的命令与握手）：无需逆向 |
| AGT-118/125 | Cursor/Pi | 原生 CLI 驱动，需补 CI 协议勘察 |
| AGT-114/117/128 | Qoder/CodeBuddy/Kimi | **国产，官方有 ACP（Qoder 另有 headless stream-json，CodeBuddy 另有 HTTP API）**：按 ACP 画像接入，CI 复核 |
| AGT-115 | TRAE/SOLO | 协议齐全但企业版旗舰套餐才可用 CLI：BLOCKED（商业门槛） |
| AGT-116 | WorkBuddy | 官方无本地可编程入口（只有 WMA 云）：BLOCKED |
| AGT-127/129 | OpenClaw/fx | **不是国产**（开源 / Vercel Labs）：ACP 画像已接 |

## 4. 本轮实现进展（2026-11-07）

- 已按参考实现落地：DSH headless、Codex app-server（schema 校准）与 exec --json、
  Claude stream-json、ACP 画像（gemini/opencode/grok/hermes/fx/antigravity/qoder/codebuddy/kimi/openclaw）、
  Qoder/CodeBuddy headless 画像、安装目录（INST-101/AGT-105 支撑）。
- 已实现 AGT-107/C-14 的排队语义：忙碌时 `turn.send mode=queue` 持久化为 queued 事件，
  当前回合结束后由核心按序派发（重启后需新回合触发，已知限制）。
- 真实回合录制仍缺凭据：Codex/Claude/CodeBuddy 有失败路径真实帧，成功回合需账号。
