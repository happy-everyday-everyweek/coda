# Coda 平台参考实现（`platform/`）

跨端 Agent 控制与协同平台的**参考实现切片**：共享核心、适配器、PA、同步、安全与客户端。
产品规格、验收与任务在 `../docs/desktop-agent-platform/`；模块契约见 [`CONTRACT.md`](./CONTRACT.md)。

> 当前状态（2026-11-08）：**开发预览，不可发布**。参考实现可运行、契约测试全绿（383 项），
> 19 个 Agent 的适配器与探测证据已落地；其中 7 个已通过本地 LLM 网关跑通真实回合，但真实服务商凭据仍缺；Windows/macOS/Linux 安装器、
> WSL、GPUI 产品 GUI 受本机与 CI 环境限制；Android 受认证宿主桥第一版已实现（设置页开关 + 桌面客户端 + 假节点测试），待真机验收。GPUI 的软件渲染可行性已在
> `.github/workflows/gpui-soft.yml` 中单独验证（不阻塞主流水线）。任务状态见
> `../docs/desktop-agent-platform/TODO.md`，证据见 `../artifacts/acceptance/`。
> G0–G5 发布门槛**未通过**，不得据此发布。

## 1. 边界（摘自 CONTRACT.md 第 1 节）

| 模块 | 唯一责任 | 不得做 |
|---|---|---|
| `core/` | 唯一权威状态：会话、回合、事件日志、命令收据、审批 | 不依赖客户端/适配器内部 |
| `adapters/` | 上游协议 ↔ 统一事件/能力声明 | 不持久化会话权威状态 |
| `clients/` | 只读投影、只发命令 | 不按 Agent 名称分支、不伪造完成状态 |
| `sync/` | 搬运已持久化对象与命令收据 | 不产生新的权威状态 |
| `pa/` | 通过统一命令层协调 Agent | 不直接操作适配器内部 |
| `security/` | 秘密、脱敏、路径与权限范围 | 不各自实现路径规则（见 CONTRACT §9） |

## 2. 目录结构

```text
platform/
├── CONTRACT.md            # 模块契约 v1
├── package.json           # 零外部依赖；npm test 用 glob 发现测试
├── contracts/             # 协议/事件/命令/能力/适配器接口（唯一共享层）
├── core/                  # 核心：domain/journal/receipts/server/supervisor/terminal/artifacts/…
├── adapters/              # 适配器：base/runtime/acp/lifecycle/interactions/options/attachments/fixtures
├── sync/                  # 配对/加密/游标/命令队列/收敛/性能基准
├── pa/                    # PA 单会话：schema(HLC)/merge/store/runner/tools/audit/migration
├── security/              # secrets/redact/paths/scopes
├── clients/
│   ├── shared/            # rpc-client/projection/mock-core
│   ├── tui/               # TUI-101/102
│   └── web/               # WEB-101/102（本地 HTTP + SSE + 令牌）
├── test/                  # contracts/core/adapters/clients/sync/security/pa
├── fixtures/              # 脱敏 fixture 与 fake ACP server
├── tools/acceptance.mjs   # 验收证据收集器
└── docs/                  # ADR、许可、矩阵、追溯、发布草案
```

## 3. 模块入口（CONTRACT §8）

| 入口 | 导出 |
|---|---|
| `core/index.mjs` | `createCore({dataDir, adapters, clock})` → `{handleRpc, subscribe, close, diagnostics, state}`；也可 `node core/index.mjs --data-dir …` 起 stdio 核心 |
| `adapters/index.mjs` | `createMockAdapter`、`createAcpAdapter`、`buildCapabilityMatrix` |
| `sync/index.mjs` | `createSyncService({storage, identity})` 等 |
| `security/index.mjs` | `loadSecrets`、`redact`、`normalizeWorkspacePath`（再导出 core/workspace.mjs） |
| `pa/index.mjs` | `createPaRunner({core, store})` |
| `clients/tui/main.mjs` | `--mock` 或 `--core "stdio:<cmd>"`，`--script '<json>'` |
| `clients/web/server.mjs` | `--port 0`，打印 `LISTENING <port>`，需要一次性令牌 |

## 4. 运行与测试

```bash
cd platform

# 全量测试（Node 24；383 项，含端到端）
npm test
# 或分模块
node --test "test/core/**/*.test.mjs"
node --test "test/adapters/**/*.test.mjs"
node --test "test/clients/**/*.test.mjs"
node --test "test/sync/**/*.test.mjs" "test/security/**/*.test.mjs"
node --test "test/pa/**/*.test.mjs"

# 验收证据（写到 ../artifacts/acceptance/<run-id>/）
node tools/acceptance.mjs

# TUI 端到端（真实核心，stdio）
node clients/tui/main.mjs --core "stdio:node core/index.mjs --data-dir /tmp/coda-core" \
  --script '[{"action":"create","title":"demo"},{"action":"send","text":"hi"},{"action":"wait","until":{"assistantReply":true}}]'

# Web UI（本地 + 令牌）
node clients/web/server.mjs --port 0

# 性能基准
node -e "import('./sync/perf.mjs').then(m=>m.runBenchmarks({quick:true}).then(console.log))"
```

## 5. 已实现 / 阻塞概览

**已实现并有测试**：契约层、CORE-101..114、AGT-101..110（含 ACP + fake server + fixture）、
TUI-101/102、WEB-101/102、UI-101、PA-101..106/108、SYNC-101..108/111/112、
SEC-101..104、REL-101/102、PERF-101、ADR-0001..0005、追溯/发布/许可文档。

**明确阻塞（环境或真实上游缺失）**：真实 Agent 成功回合缺凭据（Claude Code/Codex/Kimi 等
只能验证握手与错误路径）、WorkBuddy 无本地入口、TRAE 企业门禁、INST-102..104、
WSL-101..105、GUI-101..107（软件渲染可行，产品 GUI 未做）、PA-107、SYNC-109、PERF-102、QA-104。原因与解除条件见 `docs/SPEC-CHANGE-PROPOSAL.md`、
`adapters/BLOCKED.md`、`pa/BLOCKED.md`、`sync/BLOCKED.md`、`clients/GUI-BLOCKED.md`。

**已知限制**：无真 PTY（resize unsupported）；排队回合在当前回合结束后由核心派发，但**重启后需有新回合触发才会继续派发**；
模型目录为空（需适配器能力发现）；core 认证令牌需部署层注入；快照与事件有界（20k/100 条等上限）。

## 6. 构建产物与 CI

| 工作流 | 触发 | 产物 |
|---|---|---|
| `.github/workflows/platform.yml` | `platform/**` 变更 | ubuntu/macos 全量、windows 冒烟 |
| `.github/workflows/build.yml` | 任意 push/PR | `coda-platform-<ver>.tgz`、Web 资产、发布清单（Actions 产物） |
| `.github/workflows/gpui-soft.yml` | `platform/gui-smoke/**` | GPUI + 软件 Vulkan 出帧冒烟（失败不阻塞） |
| `.github/workflows/agent-live.yml` | `platform/tools/live/**` | 7 个真实 Agent 的完整回合（本地网关） |
| `.github/workflows/dev-prerelease.yml` | `dev` 分支 | dev-latest 预发行，附平台三件套 |
| `.github/workflows/release.yml` | `v*` Tag | 正式发行，附平台三件套 |

打包复用 `.github/actions/build-platform`：先校验能力矩阵/证据索引，再生成 Web 资产、
`release-manifest.json`、`npm pack`。`package.json` 的 `files` 与清单覆盖范围一致，
避免测试和文档混入发布包。

## 7. TUI 视觉参考与仿冒鉴别

TUI 布局参考社区插件 **`ccch1mneyyy/dsh-TUI`**（npm `@deepseek-harness-tui/dsh-tui`，MIT）。
它是 DSH 官方**推荐**的社区插件，**不是官方包**；npm 上存在 `@tomowang/dsh-tui`、
`@huiliyi37/dsh-tianshu-tui`、`@xp266/dshtui` 等仿冒/变体，引用前先核对仓库与发布者。
本项目只借鉴布局、配色语义与信息层级，不复制其代码。

## 8. 文档

ADR-0001（核心语言）、ADR-0002（PA/HLC 同步）、ADR-0003（后台服务）、ADR-0004（Web 访问）、
ADR-0005（能力矩阵）；`SPEC-CHANGE-PROPOSAL.md`（v0.3 规格变更）、`LICENSE-AUDIT.md`、
`CAPABILITY-MATRIX-DRAFT.md`、`TRACEABILITY.md`、`RELEASE-NOTES-DRAFT.md`、
`KNOWN-LIMITATIONS.md`、`TODO-STATUS-PROPOSAL.md`、`LIVE-TURNS.md`、`ANDROID-NODE-BRIDGE.md`。

## 9. 贡献规则

1. 先读 `CONTRACT.md` 与对应 ADR，再改代码；跨模块形状变更必须同步 `contracts/`。
2. 每个任务按 `TODO.md` 的 DoD 交付：实现 + 测试 + 证据；`BLOCKED` 必须写原因与解除条件。
3. 不得伪造能力：没有真实二进制/录制的 Agent 只能 UNVERIFIED/BLOCKED。
4. 测试零外部依赖、端口 0、临时目录隔离；提交前跑 `npm test`。
