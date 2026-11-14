# Agent 支持与能力矩阵

> 本文是发布能力矩阵的初始模板，所有条目在真实证据产生前均为 `UNVERIFIED`。
> 禁止把“可登录模型”“可打开 TUI”“文本输出可解析”标成 Agent 会话已支持。

## 状态定义

| 状态 | 含义 |
|---|---|
| UNVERIFIED | 尚未验证，默认状态 |
| RESEARCHING | 已找到候选接口，正在固定版本和协议 |
| EXPERIMENTAL | 可以运行，但能力或稳定性未达到首版 Supported 要求，不得进入默认发布列表 |
| SUPPORTED | 支持版本、基础门禁、所有声明能力、真实冒烟和回归证据齐备 |
| UNAVAILABLE | 官方没有可用可编程接口；必须经过规格变更，不可作为首版完成 |

## 基础能力

图例：`Y` = 已验证支持；`N` = 上游明确不支持，且有降级/说明；`TBD` = 未验证。

| Agent | 入口/协议 | 支持版本 | 创建 | 流式 | 停止 | 恢复 | 排队 | 插话 | 审批 | 提问 | 子 Agent | 附件/Diff | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Claude Code | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Codex | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| DeepSeek Harness | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Qoder | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| TRAE | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| WorkBuddy（wb） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| CodeBuddy（cb） | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Cursor | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Antigravity | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Grok Build | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| OpenCode | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Gemini CLI | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| GitHub Copilot | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Kiro CLI | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Pi Coding Agent | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Hermes Agent | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| OpenClaw | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Kimi CLI / Kimi Code | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| fx | 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| 其他 ACP Registry Agent | ACP | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |
| Coda Coding Agent | Coda 宿主桥 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | UNVERIFIED |

## 每个 Agent 的详细记录模板

```markdown
### <Agent 名称>

- 状态：UNVERIFIED
- 官方接口来源：
- 官方文档版本/日期：
- 已验证支持版本：
- 不兼容版本：
- 安装发现：
- 登录/账号隔离：
- 执行环境：
- 创建/恢复方式：
- 停止/插话策略：native | stop-restart | unavailable
- 审批/提问：
- 模型/推理/权限选项：
- 子 Agent/后台任务：
- 附件/Diff/生成物：
- 协议 fixture：
- 真实冒烟证据：
- 已知限制：
- 发布状态：UNVERIFIED
```

## 平台参考实现覆盖（2026-11-07 平台切片）

本节只记录 `platform/` 参考实现的证据，不改变上方真实 Agent 的 `UNVERIFIED` 状态。

| 实例 | 适配器 | 版本 | 环境 | 状态 | 证据 |
|---|---|---|---|---|---|
| mock-agent | mock | 1.0.0 | host | experimental（Test-only，仅契约测试） | `platform/adapters/mock.mjs`、`platform/test/adapters/` |
| acp-generic | acp | 协议 v1（fake ACP server） | host | experimental（Test-only，无真实 Agent 录制） | `platform/adapters/acp.mjs`、`platform/fixtures/adapters/` |
| claude-code | claude-stream-json | CI 探测 2.1.319 | host | experimental（真实 init/assistant/result 帧 + 网关支撑真实回合已通过；真实服务商凭据未接） | `fixtures/adapters/live-turns/`、`fixtures/adapters/claude-stream-json-noauth.fixture.json`、`platform/adapters/claude-stream-json.mjs` |
| codex | codex-app-server / codex-exec | CI 探测 0.167.0 | host | experimental（initialize 握手 + 网关支撑的真实回合已通过；app-server 会话方法按参考项目方法表实现，真实服务商凭据未接） | `fixtures/adapters/live-turns/`、`fixtures/adapters/probes/codex.json`、`platform/adapters/{codex-app-server,codex-exec}.mjs` |
| deepseek-harness | deepseek-harness | 0.2.2-rc.1 | host | experimental（真实录制失败路径；成功路径无模型凭据） | `fixtures/adapters/deepseek-harness-headless-error.fixture.json`、`platform/adapters/deepseek-harness.mjs` |
| gemini | acp-profile | CI 探测 0.67.0 | host | experimental（ACP initialize 握手已验证；session/new 因缺 API key 失败，已录制） | `fixtures/adapters/gemini-acp-handshake.fixture.json`、`platform/adapters/acp-profiles.mjs` |
| opencode | acp-profile（原生 HTTP+SSE 待接） | CI 探测 1.19.5 | host | experimental（ACP 握手 + 网关支撑的真实回合已通过；原生 HTTP 适配未接） | `fixtures/adapters/live-turns/`、`fixtures/adapters/opencode-acp-flow.fixture.json`、`platform/adapters/acp-profiles.mjs` |
| grok / hermes / fx / antigravity | acp-profile | 未安装 | host | UNVERIFIED（ACP 命令来自 Zeron README；未在本环境安装验证） | `platform/adapters/acp-profiles.mjs`、`platform/docs/REFERENCE-PROJECTS.md` |
| qwen | OpenAI 兼容 CLI（适配器未实现） | CI 实测 0.27.0 | host | IN PROGRESS（网关支撑真实回合已通过；统一事件适配器未实现） | `fixtures/adapters/live-turns/qwen.*`、`fixtures/adapters/probes/qwen.json` |
| copilot / auggie | 各自 CLI | CI 探测版本见 probes | host | IN PROGRESS（入口已验证，未实现适配器） | `fixtures/adapters/probes/*.json` |
| crush | OpenAI 兼容 CLI（适配器未实现） | CI 实测 0.100.0 | host | IN PROGRESS（网关支撑真实回合已通过；统一事件适配器未实现） | `fixtures/adapters/live-turns/crush.*`、`fixtures/adapters/probes/crush.json` |
| qoder | headless（Claude 同族） | CI 实测 1.1.85 | host | experimental（headless 入口已验证；无真实回合录制；ACP 待复核） | `fixtures/adapters/probes/qoder-cn.json`、`platform/adapters/headless-profiles.mjs` |
| codebuddy | acp-profile / headless / http | CI 实测 2.181.0 | host | experimental（ACP/headless + 网关支撑真实回合均已通过；HTTP 按完整 OpenAPI 实现 job 派发/SSE/停止/回复，真实调用需 API key） | `fixtures/adapters/live-turns/`、 `fixtures/adapters/codebuddy-headless-stream-json.fixture.json`、`platform/adapters/codebuddy-http.mjs` |
| openclaw | acp-profile | CI 实测 2026.11.1 | host | experimental（`openclaw acp` 存在；需 Gateway 凭据） | `fixtures/adapters/probes/openclaw.json` |
| fx | acp-profile | CI 实测 0.0.16 | host | experimental（ACP initialize 返回需 Vercel AI Gateway 凭据，已录门槛帧） | `fixtures/adapters/fx-acp-auth-gate.fixture.json` |
| kimi | acp-profile / headless | CI 实测 2.5.2 | host | experimental（ACP 握手 + 网关支撑真实回合已通过；真实服务商凭据未接） | `fixtures/adapters/live-turns/`、`fixtures/adapters/kimi-acp-auth-gate.fixture.json`、`platform/adapters/kimi-headless.mjs` |
| trae | — | — | — | BLOCKED（企业版旗舰套餐门槛） | `platform/docs/DOMESTIC-AGENTS.md` §2 |
| workbuddy | — | — | — | BLOCKED（官方无本地可编程入口） | `platform/docs/DOMESTIC-AGENTS.md` §3 |
| coda 宿主桥（AGT-130） | Android TCP+NDJSON | 第一版 | Android | IN PROGRESS（受认证桥 + 桌面客户端 + Android 反向客户端/远端会话页 + 假节点测试；待真机双端验收） | `app/src/main/java/com/coda/mobileui/core/NodeBridge.kt`、`platform/sync/android-node.mjs`、`platform/docs/ANDROID-NODE-BRIDGE.md` |

生成与校验规则见 `platform/docs/adr/ADR-0005-agent-capability-matrix.md` 与
`platform/adapters/capability-matrix.mjs`；界面只能通过 `supportsCapability()` 读取矩阵。

## 更新规则

1. 由对应 `AGT-1xx` 任务负责更新，不以 UI 名称替代产品入口。
2. 每次发布必须写明精确版本；不得只写“最新版”。
3. `SUPPORTED` 必须有 `ACCEPTANCE.md` 中 ADP 基础门禁、专项记录和可复现证据。
4. 上游删除/更改接口时，状态回退并创建迁移任务。
5. `PASS` 的测试只证明某版本；升级后需重新验证受影响能力。
6. 适配器声明的降级必须反映到 UI 和发布说明，不能只留在本文。