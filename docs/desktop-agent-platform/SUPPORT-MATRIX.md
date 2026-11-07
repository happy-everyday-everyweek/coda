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
| claude-code | claude-code | 未安装 | host | BLOCKED | `platform/adapters/BLOCKED.md` |
| codex | codex | 未安装 | host | BLOCKED | `platform/adapters/BLOCKED.md` |
| 其余 17 个 Agent（AGT-113..130） | — | — | — | UNVERIFIED（本切片未实现） | `platform/docs/CAPABILITY-MATRIX-DRAFT.md` |

生成与校验规则见 `platform/docs/adr/ADR-0005-agent-capability-matrix.md` 与
`platform/adapters/capability-matrix.mjs`；界面只能通过 `supportsCapability()` 读取矩阵。

## 更新规则

1. 由对应 `AGT-1xx` 任务负责更新，不以 UI 名称替代产品入口。
2. 每次发布必须写明精确版本；不得只写“最新版”。
3. `SUPPORTED` 必须有 `ACCEPTANCE.md` 中 ADP 基础门禁、专项记录和可复现证据。
4. 上游删除/更改接口时，状态回退并创建迁移任务。
5. `PASS` 的测试只证明某版本；升级后需重新验证受影响能力。
6. 适配器声明的降级必须反映到 UI 和发布说明，不能只留在本文。