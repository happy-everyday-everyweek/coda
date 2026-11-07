# 适配器 BLOCKED 记录

> 对应任务：AGT-111（Claude Code）、AGT-112（Codex）
> 对应验收：AGT-CLAUDE、AGT-CODEX、ADP-001..019
> 记录时间：2026-11-07
> 结论：**两者均为 BLOCKED，只交付 probe/协议探测骨架，不声明任何已支持能力。**

## 0. 为什么必须记 BLOCKED

`docs/desktop-agent-platform/TODO.md` 5.4 规定：没有稳定可编程接口证据时，任务状态必须为 `BLOCKED`，
并记录命令、原始输出、协议形态、是否需要上游适配层、是否有可复现实验环境。
`SPEC.md` 第 8 节与 `ACCEPTANCE.md` 第 8 节规定：没有真实二进制 + 录制 fixture 的能力只能标
`UNVERIFIED`，`Experimental` 必须显式提示，只有 `Supported` 能进首版默认支持列表。

## 1. 证据状态（如实记录）

**本记录不包含任何真实命令输出。** 原因是：实现本任务的执行环境无法运行子进程
（`bash` 被沙箱策略拒绝），因此 `which` / `--version` / `--help` 都**未在本机执行**。
为避免伪造证据，下面只记录「待执行命令 + 判定标准 + 采集后应写入的字段」。

| Agent | 命令 | 是否已执行 | 原始输出 | 判定标准 |
|---|---|---|---|---|
| Claude Code | `which claude` | 否 | — | 输出绝对路径即视为已安装 |
| Claude Code | `claude --version` | 否 | — | 输出语义版本号（`X.Y.Z`），记录进支持版本表 |
| Claude Code | `claude --help` | 否 | — | 含 `--output-format` 与 `stream-json` 才判定 stream-json 可用 |
| Codex | `which codex` | 否 | — | 输出绝对路径即视为已安装 |
| Codex | `codex --version` | 否 | — | 输出语义版本号 |
| Codex | `codex --help` | 否 | — | 含 `app-server` 子命令才判定 app-server 可用 |

代码中已有的对应探测实现（可被 Lead 在有二进制的机器上直接调用）：

```bash
node -e 'import("./platform/adapters/claude-code.mjs").then(async m=>{const a=new m.ClaudeCodeAdapter({instanceId:"ai_claude_probe"});console.log(JSON.stringify(await a.probe(),null,2))})'
node -e 'import("./platform/adapters/codex.mjs").then(async m=>{const a=new m.CodexAdapter({instanceId:"ai_codex_probe"});console.log(JSON.stringify(await a.probe(),null,2))})'
```

两条命令都只运行 `--version` / `--help`，不创建会话、不启动 MCP、不触发登录（ADP-001）。

## 2. 协议形态（需按实际版本复核）

| Agent | 首选入口 | 依据 | 风险 |
|---|---|---|---|
| Claude Code | CLI `--output-format stream-json`（stdio 流式 JSON） | 官方 CLI 文档 https://docs.anthropic.com/en/docs/claude-code （**需复核**） | 参数名与事件形状随版本变化；权限模式与提问通道未验证 |
| Codex | `codex app-server`（JSON-RPC over stdio） | 官方仓库 https://github.com/openai/codex （**需复核**） | 回合 ID、审批响应、恢复与后台自动工作的语义未验证 |

二者都不需要额外上游适配层即可在理论上接入，但是否有稳定 ABI 必须用固定版本实测，
不能从文档推断（`SPEC.md` 第 7.3 节）。

## 3. 本次已实现 / 未实现

已实现（可测、无副作用）：

- `platform/adapters/claude-code.mjs`：`probe()` 做 PATH 查找 + `--version` + `--help` 特征检测；
  能力声明全部 `unverified`；`createSession/send/steer/interrupt/resume/fork` 一律抛
  `AdapterError(code="BLOCKED")`，错误详情指向本文件。
- `platform/adapters/codex.mjs`：同上，协议探测特征为 `app-server`。
- 两者都能被 `buildCapabilityMatrix` 接受，并**必须**以 `release: "blocked"` +
  `blockedReason` 出现在矩阵中；校验器会拒绝「blocked 实例声明 supported 能力」。

未实现（因此不得声称支持）：

- 会话创建/恢复、发送、流式事件、停止、插话降级、审批/提问、模型与权限选项、
  子 Agent、附件、Diff、真实 fixture 录制。

## 4. 解除 BLOCKED 的判定标准

以下全部满足才能把 AGT-111 / AGT-112 改为 `IN PROGRESS` 并最终 `DONE`：

1. 在受控机器上安装固定版本，并把 `--version` 原始输出写进支持版本表。
2. 用真实二进制完成一次「创建 → 流式 → 停止 → 恢复」并录制原始帧。
3. 原始帧脱敏后转成统一事件 fixture，放入 `platform/fixtures/adapters/`，
   `source` 必须为 `recording`（`validateFixture` 会强制 `redaction.applied === true`）。
4. 逐项把能力声明从 `unverified` 改为 `true`/`experimental`，并在
   `ACCEPTANCE.md` 第 8 节记录冒烟日期与 commit。
5. 矩阵 `release` 从 `blocked` 改为 `experimental`，再通过 ADP-001..019 后改 `supported`。

## 5. 与本次其它任务的关系

- AGT-104（通用 ACP）与 AGT-106（fixture 回放）已实现，但只在**人工构造的 fake ACP server**
  上验证。`fixtures/adapters/*.fixture.json` 的 `source` 均为 `handwritten`，
  不能用作任何真实 Agent 的支持证据。
- AGT-113..AGT-130（DeepSeek Harness / Qoder / TRAE / WorkBuddy / CodeBuddy / Cursor /
  Antigravity / Grok / OpenCode / Gemini / Copilot / Kiro / Pi / Hermes / OpenClaw / Kimi / fx /
  Coda 宿主桥）本次**未实现**，仍为 `OPEN`，不因本文件而改变状态。
