# 国产 Agent 公开可编程接口调研（AGT-114..117 / 127..129）

> 对应 SPEC §7.1 / §7.2 / §19，TODO AGT-114、AGT-115、AGT-116、AGT-117、AGT-127、AGT-128、AGT-129。
> 目标：判断每个 Agent **是否有公开可编程入口**，以及入口是 ACP / app-server / HTTP / SDK / 仅 CLI 文本。
> 原则：**不编造接口**。查不到写 `UNKNOWN` 并列已尝试检索词；只用官方文档 / 官方仓库 / 云厂商文档。

- 调研时间：2026-11-07
- 调研方式：`web_search` + `web_fetch`，只取官方文档站与官方仓库（GitHub 部分页面在本环境不可达，见文末「检索过程与不可达项」）
- **环境限制（影响结论强度）**：本会话无可用 sandbox 后端，`bash` 全部被拒，**无法在本机安装/运行任何 CLI 做实测**。因此本文所有"协议证据"均来自官方文档文本，未经过本地 `--help` 验证；`READY` 表示"文档已足以直接写适配器"，仍需 CI probe 做一次 schema 复核。
- 与 `REFERENCE-PROJECTS.md` 的关系：那篇的结论是"国产 6 家公开接口未确认"。本文把其中 **4 家（Qoder / TRAE / CodeBuddy / Kimi）确认为已有官方可编程入口**，1 家（WorkBuddy）确认无官方入口，另 2 家（OpenClaw / fx）**归属不是国产**。

---

## 0. 结论总表

| # | Agent | 归属 | 主可编程入口 | Linux 安装 | 需要登录 | 结论 | 优先级 |
|---|---|---|---|---|---|---|---|
| 1 | **Qoder**（AGT-114） | 阿里云 / 通义云启（国产） | ACP `--acp` + headless `-p --output-format stream-json` + 官方 Agent SDK（TS/Py/Java） | `curl -fsSL https://qoder.com.cn/install \| bash`（国内）、`https://qoder.com/install`（国际）、npm `@qoder-ai/qodercli`（国际） | 是，**但有 PAT**（`QODERCN_PERSONAL_ACCESS_TOKEN`） | **READY** | P0 |
| 2 | **TRAE / SOLO**（AGT-115） | 字节跳动（国产） | ACP `traecli acp serve` / `--acp`；`traecli exec --json`（JSONL）；`mcp-server`；`app-server` | `sh -c "$(curl -fsSL https://trae.cn/trae-cli/install_v2.sh)"` | 是，**且有套餐门槛**（企业版旗舰版） | **NEEDS-PROBE**（门槛在账号，不在协议） | P1 |
| 3 | **WorkBuddy**（AGT-116） | 腾讯云（国产） | 官方：只有 WMA 云 OpenAPI / A2A / MCP + IM Bot 远程任务；**无官方 CLI** | **无官方 Linux CLI 安装方式** | 是（桌面端登录 / 腾讯云账号） | **BLOCKED** | 暂缓 |
| 4 | **CodeBuddy**（AGT-117） | 腾讯（国产，国内/国际双版） | **HTTP API (Beta) `codebuddy --serve`** + **ACP `codebuddy --acp`** + Python/TS SDK | npm `@tencent-ai/codebuddy-code`；brew；curl `https://copilot.tencent.com/cli/install.sh`（国内） | 是，**支持 API Key 免交互** | **READY** | P0（最先） |
| 5 | **Kimi Code CLI**（AGT-128） | 月之暗面（国产） | `kimi acp` + `kimi -p --output-format stream-json` + `kimi web`（本地 REST + WS，带 OpenAPI/AsyncAPI） | `curl -fsSL https://code.kimi.com/kimi-code/install.sh \| bash`；npm `@moonshot-ai/kimi-code`；brew `kimi-code` | 是（Kimi OAuth device code） | **READY** | P0 |
| 6 | **OpenClaw**（AGT-127） | **非国产**：开源项目，作者 Peter Steinberger（原 Clawdbot/Moltbot），2026-02 作者加入 OpenAI | ACP `openclaw acp`（stdio → Gateway WS）+ Gateway HTTP（OpenAI 兼容 / OpenResponses / Tools Invoke） | `curl -fsSL https://openclaw.ai/install.sh \| bash`；npm `openclaw` | 本地 Gateway onboard + 模型凭据 | **READY**（非国产，见 §6） | P2（参考实现） |
| 7 | **fx**（AGT-129） | **非国产**：Vercel Labs（`github.com/vercel-labs/fx`） | ACP `fx acp` + `fx ask` | `curl -fsSL https://fx.sh/setup.sh \| bash` | 是（Vercel / Codex / Grok / AI Gateway key） | **READY**（已有 `acp-profiles.mjs` 配置，与官方文档一致） | P2（已接） |

一句话读法：
**CodeBuddy 与 Qoder 是"官方把控制面直接开放"的两家；Kimi 三条路任选；TRAE 协议齐全但被企业套餐挡住；WorkBuddy 官方没有可编程入口；OpenClaw / fx 根本不属于国产清单。**

---

## 1. Qoder（AGT-114，阿里云 / 通义云启）

| 项目 | 结论 |
|---|---|
| 官方文档 | 国内 <https://docs.qoder.cn/cli/overview>、<https://docs.qoder.cn/cli/acp>、<https://docs.qoder.cn/cli/sdk/overview>；国际 <https://docs.qoder.com/cli/installation>、<https://docs.qoder.com/cli/acp>；阿里云镜像 <https://www.alibabacloud.com/help/zh/lingma/using-the-cli>、<https://www.alibabacloud.com/help/zh/lingma/qoder-cli-cn-get-started-quickly> |
| 产品线 | Qoder CN（原名通义灵码 Lingma，2026-05-20 更名）：IDE / JetBrains 插件 / **CLI** / **Agent SDK** / Cloud Agents / QoderWake |
| 可执行文件 | 国内 **`qoderclicn`**（安装文档与 quickstart 用这个名字），但 <https://docs.qoder.cn/cli/acp> 的 ACP 章节写作 **`qodercn --acp`** → **文档自相矛盾，必须两个名字都探测**；国际为 **`qoder`**（`qoder --acp`） |

### 可编程入口（三条，均官方文档化）

1. **Headless / Print 模式**（`-p` / `--print`）
   - `--output-format text|json|stream-json`
   - 会话：`-c`（继续）、`-r <session-id>`（恢复）、`--resume`、`-w <dir>`、`--add-dir`、`--worktree [name]`
   - 权限：`--permission-mode default|accept_edits|auto|bypass_permissions|dont_ask`、`--yolo` / `--dangerously-skip-permissions`
   - 工具：`--allowed-tools` / `--disallowed-tools` / `--tools` / `--allowed-mcp-server-names`
   - `--max-turns`
2. **ACP**：`qodercn --acp`（国际 `qoder --acp`），stdio JSON-RPC，可作为 ACP server 被 Zed 等客户端拉起。官方列出了能力矩阵：内置工具 ✅、Subagent ✅、MCP（stdio/SSE/Streamable HTTP）✅、权限配置 ✅、上下文压缩 ✅、图像 ✅、文件操作/Terminal ✅；两种运行模式（默认 / Bypass Permissions）。ACP 侧斜杠命令仅 `/init` `/memory` `/about` `/help`。
3. **Qoder Agent SDK**（比裸 CLI 更稳）：`@qodercn-ai/qodercn-agent-sdk`（TS）、`qodercn-agent-sdk`（Py）、`cn.qoder.sdk:qodercn-agent-sdk`（Java）。SDK **内置兼容版本的 CLI 运行时**，TS/Py 无需单独装 CLI；通信走本地 stdio 协议，审批通过 `canUseTool` 回调交给宿主。

另有 `qoderclicn mcp add/list/remove`（作 MCP 客户端）与 Hooks（`PreToolUse`/`PermissionRequest`）。

### 安装 / 登录

- 安装（国内）：`curl -fsSL https://qoder.com.cn/install | bash`；升级 `qoderclicn update` 或 `... | bash -s -- --force`
- 安装（国际）：`curl -fsSL https://qoder.com/install | bash`；npm 兼容路径 `npm i -g @qoder-ai/qodercli`（需 Node ≥ 20，官方标注"新装不推荐"）
- 平台：macOS / Linux / Windows（Windows arm64 不支持），arm64 + amd64
- 登录：TUI `/login` 或 **环境变量 `QODERCN_PERSONAL_ACCESS_TOKEN`**（PAT 取自 `https://qoder.com.cn/account/integrations`）。**CI 友好**：非交互会话直接用该变量认证；若同时 `/login`，以 `/login` 的 token 为准。

### 协议证据（原文要点）

- 权限归宿表明确区分四种运行环境：TUI → 弹窗；**Headless(`-p`) → `ask` 自动转 `deny`**；SDK（stdio）→ `canUseTool` 回调；**ACP → 发 `requestPermission` RPC 给 IDE**。这直接对应我们 SPEC 的"审批"能力位。
- 非默认权限模式只在**可信目录**生效，目录不可信时回退 `default` → 与 Coda 的 workspace 信任模型语义一致。
- ACP 客户端（Zed）配置样例：`{"agent_servers":{"Qoder CLI CN":{"type":"custom","command":"qodercn","args":["--acp"]}}}`。

### 结论：READY

建议路线：**优先 SDK（语义最完整）→ 次选 ACP（复用我们的通用适配器）→ 兜底 headless stream-json**。
待实测：`qoderclicn` vs `qodercn` 真实 bin 名；PAT 在无 `~/.qoder-cn` 配置的全新容器里是否够用；国际版 PAT 变量名（`UNKNOWN`）。

---

## 2. TRAE / SOLO（AGT-115，字节跳动）

| 项目 | 结论 |
|---|---|
| 官方文档 | <https://docs.trae.cn/cli_about-trae-code-cli-2>（TRAE CLI 2.0 全套）、[快速开始](https://docs.trae.cn/cli_get-started-with-trae-cli-2)、[命令行参数](https://docs.trae.cn/cli_command-line-parameters)、[ACP](https://docs.trae.cn/cli_agent-client-protocol)、[套餐类型](https://docs.volcengine.com/docs/trae-cn/billing-overview?lang=zh) |
| 可执行文件 | `traecli`（TRAE CLI 2.0） |

### 可编程入口（比预期完整）

- **ACP**：`traecli acp serve` 或 `traecli --acp`；`traecli [全局参数] acp serve [ACP 参数]`
  - 全局：`-p/--profile <PROFILE>`（`$TRAE_HOME/<profile>.traecli.toml`）、`--permission-mode default|auto|bypass_permissions`、`-c/--config key=value`（点号路径，可多次）
  - ACP 参数：`-y/--yolo`（跳过确认并关沙箱）、`--dangerously-bypass-hook-trust`
- **非交互**：`traecli exec --json`（**事件 JSONL 流**）、`-o/--output-last-message <file>`、`--output-schema <file>`（JSON Schema 约束最终响应）、`--ephemeral`、`--skip-git-repo-check`
- **沙箱/审批**：`-s/--sandbox read-only|workspace-write|danger-full-access`；`-a/--ask-for-approval never|on-request|untrusted|on-failure`
- **其它子命令**：`review`（`--uncommitted/--base/--commit`）、`resume`、`fork`、`mcp`、**`mcp-server`（把 CLI 暴露成 MCP server，stdio）**、`app-server`（远程连接，`--remote ws://host:port`）、`apply`、`sandbox`、`doctor`、`features`、`update`
- 会话恢复：`traecli resume [SESSION_ID] [--last] [--all]`、`fork`

### 安装 / 登录

- Linux/macOS：`sh -c "$(curl -fsSL https://trae.cn/trae-cli/install_v2.sh)"`；Windows `install_v2.ps1`（建议 WSL2）
- 登录：TUI 引导，或 **PAT**：
  ```bash
  export TRAECLI_PERSONAL_ACCESS_TOKEN="<token>"
  traecli login --with-trae-pat
  # 自定义域
  export TRAECLI_HOST="https://api.example.com"
  traecli login status | traecli logout
  ```

### 硬门槛（关键风险）

官方"使用限制"原文：**「仅 TRAE 企业版的旗舰版套餐客户可使用 TRAE CLI 2.0。」**
TRAE 企业版：团队版 149 元/席/月，**旗舰版 259 元/席/月、起购 3 席**。→ **CI 里不可能用免费账号跑通冒烟**。

### SOLO

- SOLO 是 TRAE 内的自主工作模式（Agent 自动规划执行），**没有独立的 API/CLI 入口**；公开资料中"SOLO 已并入 TRAE 产品线"的说法只出现在第三方实测文档里（原文在本环境不可达），故记为 **UNKNOWN**，不作依据。
- 结论：**SOLO 不做独立适配**，其能力经 TRAE CLI/IDE 承载；若 Lead 要求，"SOLO 支持"= "TRAE CLI 支持 + 在 UI 文案说明这是同一后端"。

### 结论：NEEDS-PROBE

协议侧完全够用（ACP + exec JSONL + app-server，形状与 Codex/Claude 同族），**唯一阻塞是账号等级**。
CI 探测分两级：
1. **无凭证级**（任何 CI 都能跑）：装 + `--version` + `--help` + `acp --help` + `exec --help`，拿到版本与子命令面。
2. **有凭证级**（需企业旗舰版 token）：`traecli --acp` 的 `initialize`/`session/new`，以及 `traecli exec --json "Reply with exactly one word: pong"`。

---

## 3. WorkBuddy（AGT-116，腾讯）

| 项目 | 结论 |
|---|---|
| 官方文档 | <https://cloud.tencent.com/document/product/1831>(WorkBuddy Enterprise 文档树)；[WorkBuddy 简介](https://cloud.tencent.cn/document/product/1831/134384)；[助理（远程任务）](https://cloud.tencent.cn/document/product/1831/134392)；[WorkBuddy Managed Agents 产品介绍](https://cloud.tencent.cn/document/product/1831/134407)；国际 <https://www.workbuddy.ai/docs/zh/>（与 CodeBuddy 同站，无独立 CLI 章节） |
| 形态 | 腾讯出品的**全场景 AI 工作台桌面端**（workbuddy.cn）：自然语言下任务 → 自主规划 → 交付文档/表格/PPT；连接器、专家、技能、资料库 |

### 官方可编程入口：只有"云"，没有"本地"

1. **WMA（WorkBuddy Managed Agents）**：云端托管智能体运行服务，内置 **WorkBuddy Harness 内核** + 独立沙箱（1 Session : 1 沙箱）。
   - 对外开放协议：**OpenAPI / ADK / A2A / MCP / IM Bot**，网络形态支持公网 / VPC / 私有化；腾讯云控制台公测（`console.cloud.tencent.com/workbuddy-enterprise`）。
   - **但这是"在腾讯云上跑 Agent"，不是"接管本机 WorkBuddy 桌面会话"**，与 SPEC §7.3"在指定工作区创建会话、恢复本产品创建的会话"不是同一层。
2. **助理（远程任务）**：在桌面端 GUI 里绑定微信 / 企微 / QQ / 钉钉 / 飞书 Bot，手机发消息 → 电脑执行。
   - 工作目录固定为"助理专属文件夹"，**全程只有一个会话、历史不可清空**；**不是公开 HTTP 契约**，只暴露 IM Bot 事件。
3. **没有官方 CLI**：WorkBuddy 文档树里没有 CLI 章节；腾讯云 techpedia 有专文《WorkBuddy CLI 安装与使用指南》(<https://www.tencentcloud.com/techpedia/146483?lang=zh>)，**结论恰恰是"WorkBuddy 文档中没有确认一个名为 WorkBuddy CLI 的独立命令行产品"，并明确不要拿 CodeBuddy CLI 的命令改个名字用**。

### 社区逆向路线（有，但不可依赖）

- `workbuddy2api-panel`（含用户提到的 `linguo2625469/workbuddy2api-panel` 与 `jiangwanying/workbuddy2api-panel` fork）与同族 `workbuddy2api`、`codebuddy2openai`、`workbuddy-proxy` 等：把**"腾讯 WorkBuddy / CodeBuddy 账号（桌面端登录态）"转成 OpenAI 兼容 API**，带多账号池 / 积分任务 / 配置热更新 / Web 管理面板。
- 上游已于 **2026-10-21 停止维护**，fork 保留预编译二进制与 `ghcr.io/...` 镜像。
- **它证明的是"存在可被账号登录态驱动的 HTTP 接口"，不是"官方开放了接口"**：契约随时可变、依赖 cookie/session 抓取、有封号与合规风险。**Coda 不应把这类项目作为生产依赖**（SPEC §7.3 第 2 条"不静默串用其他账号"也违背）。

### 结论：BLOCKED（官方入口层面）

- 官方无 CLI / 无 ACP / 无本地 app-server / 无桌面端 SDK；远程任务只有 IM Bot；WMA 是云端另一层产品。
- **留给后续的唯一动作**（一次性、非产品化）：在一个装有 WorkBuddy 桌面端的机器上，看它是否在回环地址监听 HTTP 端口（社区项目能跑通说明大概率有本地或云端 HTTP 面）。这只是"可行性验证"，**不进 probe.config.json 的常规矩阵**。
- 若要覆盖"办公类 Agent"，建议改为接 **WMA 的 OpenAPI**（云端），并在能力矩阵里明确标注"云端 Agent，非本地会话"。

---

## 4. CodeBuddy（AGT-117，腾讯，国内 / 国际双版）

| 项目 | 结论 |
|---|---|
| 官方文档 | 国际 <https://www.codebuddy.ai/docs/cli/>（[安装](https://www.codebuddy.ai/docs/cli/installation)、[ACP](https://www.codebuddy.ai/docs/cli/acp)、[HTTP API](https://www.codebuddy.ai/docs/cli/http-api)）；中文 <https://www.workbuddy.ai/docs/zh/cli/>；腾讯云镜像 [ACP 协议集成](https://cloud.tencent.cn/document/product/1831/137021)、[HTTP API (Beta)](https://cloud.tencent.cn/document/product/1831/137064)、[安装和登录](https://cloud.tencent.cn/document/product/1831/137000) |
| 可执行文件 | `codebuddy` |

### 可编程入口（四层，文档最完整的一家）

1. **ACP（原生支持，stdio）**：`codebuddy --acp`
   - 认证扩展：`authenticate` 响应 `_meta["codebuddy.ai/userinfo"]` → `{userId,userName,userNickname}`
   - 工具代理：客户端声明 `fs.readTextFile`/`fs.writeTextFile`/`terminal` 时，文件与终端操作代理给客户端执行（与我们 ACP 适配器的 client capabilities 天然对齐）
   - `available_commands_update` 推斜杠命令（自动过滤 `/clear` `/exit` `/theme` `/config`）
   - **Agent Teams 扩展**：`session_info_update._meta["codebuddy.ai/teamUpdate"]`，事件含 `member_status_change` 等
2. **HTTP API (Beta)**：`codebuddy --serve --port 8080 --session-id my-session`
   - 两套公开接口：**REST `/api/v1/*`**（无状态，语义化版本承诺不破坏）与 **ACP over SSE `/api/v1/acp`**（有状态流式 JSON-RPC）
   - `GET /api/v1/health` → `{"data":{"status":"ok","uptime":..,"platforms":["generic","wecom","wechat-kf"]}}`
   - Swagger UI `/api/docs`；OpenAPI `/api/openapi.json`
   - 端点族（文档目录已列全）：系统 / 认证 / **Runs（Agent 执行）** / Webhooks / 会话 / **PTY（终端）** / Workers & Daemon / Jobs（后台智能体实例）/ Channels（远程控制）/ 文件系统（E2B 兼容）/ 进程管理（E2B 兼容）/ **ACP** / Checkpoint（内部）/ 插件 / 配置 / 工作目录 / 任务模板 / 使用统计 / 链路追踪 / 定时任务
   - 安全：所有请求（除豁免）必须带 **`X-CodeBuddy-Request: 1`**；CORS 白名单（回环也按端口校验）可用 `CODEBUDDY_CODE_CORS_ORIGINS` 扩展；`CODEBUDDY_DISABLE_REQUEST_VALIDATION=1` 可关校验
   - 内部 RPC `/internal/*` **不保证兼容**，别碰
3. **SDK**：Python / TypeScript（含 Hook、权限、会话、自定义工具、MCP 集成、示例项目）
4. **CLI 其它**：无头模式、Daemon 模式、Remote Control、Web UI、Workflow stdio 协议、Channels[Beta]、子代理、Agent Teams、Skills、Hooks、插件市场、Checkpoint、Worktree

### 安装 / 登录

- npm：`npm install -g @tencent-ai/codebuddy-code`（Node ≥ 18.20；亦支持 pnpm/yarn/bun）
- Homebrew：`brew tap Tencent-CodeBuddy/tap && brew install codebuddy-code`
- 原生二进制（Beta，无需 Node）：`curl -fsSL https://copilot.tencent.com/cli/install.sh | bash`；国内另一入口 `curl -fsSL https://www.codebuddy.cn/cli/install.sh | bash`；`codebuddy install` 可从 npm 版迁移
- **国内 / 国际两套接口**（原文）：`CODEBUDDY_API_KEY` 必须配 `CODEBUDDY_INTERNET_ENVIRONMENT`——
  **海外版：不设置（默认）｜中国版：`internal`｜iOA 版：`ioa`**。这就是 AGT-117 里"国内/国际两套"的准确落点，不是两个域名两套协议，而是**同一 CLI + 环境变量切换后端**。
- **CI 友好**：API Key 直接注入，无需浏览器登录。

### 结论：READY（最先做）

三条接入路径的现实排序：
- **HTTP API（`--serve`）**：最贴合 Coda"引擎/视口分离 + 多端复用"——一次 `--serve`，桌面与移动端共用；`/api/v1/acp` 还能直接吃 ACP 语义。
- **ACP（`--acp`）**：零改造复用现有通用适配器（`platform/adapters/`），但丢掉 Runs/PTY/Jobs 等 HTTP 端点。
- **SDK**：若要与 Coda 进程内嵌，最省协议工作量。

待实测：`--serve` 与 `--session-id` 的组合语义（是否是"serve 时预创建会话"）；Beta 端点的破坏性变更节奏；`X-CodeBuddy-Request` 在 SSE 长连接上的行为。

---

## 5. Kimi CLI / Kimi Code（AGT-128，月之暗面）

| 项目 | 结论 |
|---|---|
| 官方仓库 / 文档 | **<https://github.com/MoonshotAI/kimi-code>**（新）；<https://moonshotai.github.io/kimi-code/zh/>（文档）；旧 <https://github.com/MoonshotAI/kimi-cli> **已归档**（"Legacy Python Kimi CLI, no longer maintained"） |
| 可执行文件 | `kimi` |
| 陷阱 | PyPI 上的 `kimi-code`（1.52.0）**不是新 CLI**，只是旧 Python 包的别名，自 1.51.0 起运行只会打印迁移提示。别按 `pip install kimi-code` 接。 |

### 可编程入口（三条，互不冲突）

1. **Print 模式（JSONL）**：`kimi -p "..." --output-format stream-json`
   - 与 `--prompt` 绑定的硬规则：`--prompt` 不能与 `--yolo`/`--auto`/`--plan` 同用（非交互固定 `auto` 权限）；`--output-format` 只能配 `--prompt`
   - `stream-json` 语义（官方原文）：每行一个 JSON 对象；普通回复输出 Assistant 消息；调工具时**先输出带 `tool_calls` 的 Assistant 消息，再输出对应 Tool 消息，再继续 Assistant**；thinking **不写入 JSONL**；工具进度与"恢复会话"提示走 stderr
   - 运维友好：`--session [id]` / `--continue` / `--model` / `--agent` / `--skills-dir` / `--add-dir` / `--environment`
2. **ACP**：`kimi acp` —— stdio JSON-RPC，让 IDE 直接驱动 kimi 的会话与工具调用（配套文档 [kimi acp 子命令](https://moonshotai.github.io/kimi-code/zh/reference/kimi-acp.html)）
3. **本地服务**：`kimi web` —— **同一进程挂 REST + WebSocket + web UI**
   - `GET /openapi.json`（REST 契约）、`GET /asyncapi.json`（WS 契约）→ **可机器读取的协议自描述，最好的适配器输入**
   - 默认 bind `127.0.0.1:58627`（占用自动 +1），启动横幅打印 bearer token，持久化在 `~/.kimi-code/server.token`，`kimi web rotate-token` 轮换
   - `--dangerous-bypass-auth` 关闭全部 REST/WS 鉴权（仅限可信网络）；`--debug-endpoints` 挂 `/api/v1/debug/*`
   - `kimi server …` 已废弃（`kimi server kill` 例外）
4. 其它：`kimi login`（RFC 8628 device code，无 TUI）、`kimi provider`（管理供应商 / models.dev 目录，可接自定义 OpenAI 兼容端点）、`kimi export`（会话打包）、`kimi vis`

### 安装 / 登录

- `curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash`（单文件 CLI，**无需 Node.js**）
- `brew install kimi-code`；`npm install -g @moonshot-ai/kimi-code`
- 登录：`kimi login` 走 Kimi Code OAuth device-code（打印验证地址+用户码，轮询直到浏览器侧授权）；成功退出码 0，失败/取消 1
- **CI 的现实问题**：device code 需要人点一次浏览器。可行的替代是 `kimi provider` 配自定义模型 + API key（或 `KIMI_REGISTRY_API_KEY`），这样 CI 不依赖 Kimi 账号。

### 结论：READY

推荐：**`kimi web` + `/openapi.json`**（协议自描述，最适合写适配器）→ 或 **`kimi acp`**（复用通用适配器）→ 兜底 `-p --output-format stream-json`。
待 Lead 定：CI 里用 Kimi 账号（需要预置一次 device-code 后的 token）还是走 `provider` + 第三方 API key。

---

## 6. OpenClaw（AGT-127）—— 归属更正：**不是国产**

| 项目 | 结论 |
|---|---|
| 官方 | 文档 <https://docs.openclaw.ai/>（任意页面加 `.md` 取干净 Markdown，另有 `/llms.txt` 全站索引）；仓库 `github.com/openclaw/openclaw` |
| 归属 | 开源个人/社区项目，原名 Clawdbot → Moltbot → **OpenClaw**，作者 **Peter Steinberger**；2026-02 有公开报道"OpenAI 聘用 OpenClaw 创始人"。**不是中国大陆厂商产品**，只是文档多语言（含简中）且在国内开发者中流行。 |
| 形态 | 自托管 **Gateway** 进程 + 多渠道（Discord/Slack/Telegram/飞书/企微/QQ/微信…）消息入口 + 内置 Agent runtime |

### 可编程入口

1. **ACP**：`openclaw acp` —— ACP over stdio，**转发到 Gateway over WebSocket**；是"Gateway-backed ACP bridge"，**不是 ACP 原生运行时**
   - 已实现：`initialize` / `newSession` / `prompt` / `cancel` / `listSessions`（游标分页 + cwd 过滤）/ `resumeSession` / `closeSession`；thinking 以 `agent_thought_chunk` 流；`tool_call`/`tool_call_update` 部分；审批走 `session/request_permission`
   - **`loadSession` 部分支持**（只对 bridge 创建过的会话有事件账本，其余回退 transcript）
   - **明确不支持**：per-session `mcpServers`（非空即报错）、客户端 `fs/*`、`terminal/*`
   - 参数：`--url` / `--token` / `--token-file` / `--password(-file)` / `--session <key>` / `--session-label` / `--require-existing` / `--reset-session` / `--provenance`；环境变量 `OPENCLAW_GATEWAY_TOKEN`
   - 自带调试客户端 `openclaw acp client`
2. **MCP**：`openclaw mcp serve`（让外部 MCP 客户端直接对话 OpenClaw 渠道会话）
3. **Gateway 协议 + HTTP**：WebSocket Gateway 协议（含版本协商 N-1 窗口）、**OpenAI Chat Completions 兼容端点**、OpenResponses API、**Tools Invoke API**、入站 webhooks、`openclaw gateway call <method>`
4. A2A（JSON-RPC）、插件 SDK、Code Mode

### 安装 / 登录

- `curl -fsSL https://openclaw.ai/install.sh | bash`（Node 24.16+/26.1+，安装器缺 Node 时自己装）
- `npm install -g openclaw@latest --allow-scripts=openclaw`（npm 12 需显式允许生命周期脚本）；pnpm/bun/Docker/Nix 均可
- 运行：`openclaw onboard --install-daemon`（Linux 用 systemd user service）、`openclaw gateway status`、`openclaw doctor`

### 对 Coda 的特别价值 / 坑

- **价值**：它是目前唯一把"ACP 语义 ↔ 长驻 Gateway 会话"映射关系写清楚的实现，正好对应我们 SPEC §6 的"引擎/视口分离"与 §11 的会话归属。它的 compatibility matrix 是一份现成的"ACP 能做到什么、做不到什么"的现实清单。
- **坑（必须遵守）**：
  - **不要在 `session/new` 里塞 `mcpServers`**（T3 Code 就死在这条，官方文档点名）。
  - `openclaw acp` 的 `initialize.agentCapabilities.sessionCapabilities` 才声明 list/resume/close，能力探测要读它，别假设。
  - 停止语义：`max_tokens` / `cancelled` 的 stopReason 映射比原生 ACP 弱。

### 结论：READY（但请把它移出"国产"清单）

接入成本低（ACP stdio 一行命令），但要求机器上先有可用 Gateway 与模型凭据。

---

## 7. fx（AGT-129）—— 归属更正：**不是国产**

| 项目 | 结论 |
|---|---|
| 官方 | 文档 <https://fx.sh/docs>；源码 <https://github.com/vercel-labs/fx>；站点页脚作者标注 **Vercel Labs** |
| 归属 | **Vercel Labs（美国）开源项目**，Rust/native 单一进制，非国产。Zeron README 把它列进安装命令表，只是因为它有 ACP。 |
| 入口 | **ACP**：[ACP server](https://fx.sh/docs/using-fx/acp)，即 `fx acp`；一次性：`fx ask "..."`（[fx ask](https://fx.sh/docs/using-fx/fx-ask) 有脚本/结构化输出）；会话：`fx -c`、`fx -r`；`/permissions`（默认 `auto`）、skills、MCP |
| 安装 | `curl -fsSL https://fx.sh/setup.sh \| bash`；升级 `fx upgrade` |
| 登录 | 无凭据时进 "Welcome to fx"：Vercel 登录 / Codex 订阅 / Grok 订阅 / AI Gateway key 四选一 |
| 现有代码 | `platform/adapters/acp-profiles.mjs` 已有 `fx: { provider:"fx", command:"fx", args:["acp"], taskId:"AGT-129", docs:"https://fx.sh/docs" }` —— **与官方文档一致，无需改动** |
| 结论 | **READY**，AGT-129 可视为已完成协议勘察；剩余只是 CI 冒烟与版本固定 |

---

## 8. probe.config.json 片段（与 `coda-agent-probes` 现有 schema 一致）

与 `../coda-agent-probes/probe.config.json` 同构：`schemaVersion` + `agents[]`，每项 `{id, package, bin, commands[]}`，命令项支持 `cmd` / `timeoutMs` / `stdin` / `stdinLines` / `acpFlow` / `captureDir`。

约定：
- `package` 为 `null` 表示**不走 npm**（用官方 install 脚本装，`bin` 已在 PATH，probe runner 只跑命令）。
- ACP 探测复用现有 gemini/opencode 条目里的 `initialize` 帧形状。
- 需要凭证的条目（标 `needsAuth`）在无 token 时应被判为 `SKIP` 而非 `FAIL`——这条要改 runner，属新增约定，请 Lead 确认。

```json
{
  "schemaVersion": 1,
  "agents": [
    {
      "id": "qoder-cn",
      "package": null,
      "bin": "qoderclicn",
      "commands": [
        { "cmd": "qoderclicn --version", "timeoutMs": 20000 },
        { "cmd": "qoderclicn --help", "timeoutMs": 20000 },
        { "cmd": "qoderclicn -p --help", "timeoutMs": 20000 },
        { "cmd": "qodercn --version", "timeoutMs": 20000 },
        {
          "cmd": "qoderclicn --acp",
          "timeoutMs": 25000,
          "stdinLines": [
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":{\"readTextFile\":false,\"writeTextFile\":false}},\"clientInfo\":{\"name\":\"coda-probe\",\"version\":\"0.0.1\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"/tmp\",\"mcpServers\":[]}}"
          ]
        },
        {
          "cmd": "qoderclicn -p --output-format stream-json --permission-mode dont_ask \"Reply with exactly one word: pong\"",
          "timeoutMs": 90000
        }
      ]
    },
    {
      "id": "qoder-intl",
      "package": "@qoder-ai/qodercli",
      "bin": "qoder",
      "commands": [
        { "cmd": "qoder --version", "timeoutMs": 20000 },
        { "cmd": "qoder --help", "timeoutMs": 20000 },
        {
          "cmd": "qoder --acp",
          "timeoutMs": 25000,
          "stdin": "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":{\"readTextFile\":false,\"writeTextFile\":false}},\"clientInfo\":{\"name\":\"coda-probe\",\"version\":\"0.0.1\"}}}\n"
        }
      ]
    },
    {
      "id": "trae",
      "package": null,
      "bin": "traecli",
      "commands": [
        { "cmd": "traecli --version", "timeoutMs": 20000 },
        { "cmd": "traecli --help", "timeoutMs": 20000 },
        { "cmd": "traecli exec --help", "timeoutMs": 20000 },
        { "cmd": "traecli acp --help", "timeoutMs": 20000 },
        { "cmd": "traecli mcp-server --help", "timeoutMs": 20000 },
        { "cmd": "traecli login status", "timeoutMs": 20000 },
        {
          "cmd": "traecli --acp",
          "timeoutMs": 25000,
          "stdin": "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":{\"readTextFile\":false,\"writeTextFile\":false}},\"clientInfo\":{\"name\":\"coda-probe\",\"version\":\"0.0.1\"}}}\n"
        },
        {
          "cmd": "traecli exec --json --sandbox read-only \"Reply with exactly one word: pong\"",
          "timeoutMs": 120000
        }
      ]
    },
    {
      "id": "codebuddy",
      "package": "@tencent-ai/codebuddy-code",
      "bin": "codebuddy",
      "commands": [
        { "cmd": "codebuddy --version", "timeoutMs": 20000 },
        { "cmd": "codebuddy --help", "timeoutMs": 20000 },
        { "cmd": "codebuddy --acp --help", "timeoutMs": 20000 },
        {
          "cmd": "codebuddy --acp",
          "timeoutMs": 25000,
          "stdinLines": [
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":{\"readTextFile\":false,\"writeTextFile\":false}},\"clientInfo\":{\"name\":\"coda-probe\",\"version\":\"0.0.1\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"/tmp\",\"mcpServers\":[]}}"
          ]
        },
        {
          "cmd": "codebuddy --serve --port 8099 --session-id coda-probe",
          "timeoutMs": 15000
        }
      ]
    },
    {
      "id": "kimi",
      "package": "@moonshot-ai/kimi-code",
      "bin": "kimi",
      "commands": [
        { "cmd": "kimi --version", "timeoutMs": 20000 },
        { "cmd": "kimi --help", "timeoutMs": 20000 },
        { "cmd": "kimi acp --help", "timeoutMs": 20000 },
        { "cmd": "kimi web --help", "timeoutMs": 20000 },
        { "cmd": "kimi doctor", "timeoutMs": 30000 },
        {
          "cmd": "kimi acp",
          "timeoutMs": 25000,
          "stdinLines": [
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":{\"readTextFile\":false,\"writeTextFile\":false}},\"clientInfo\":{\"name\":\"coda-probe\",\"version\":\"0.0.1\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"/tmp\",\"mcpServers\":[]}}"
          ]
        },
        {
          "cmd": "kimi web --no-open --port 58640",
          "timeoutMs": 15000
        },
        {
          "cmd": "kimi -p --output-format stream-json \"Reply with exactly one word: pong\"",
          "timeoutMs": 90000
        }
      ]
    },
    {
      "id": "openclaw",
      "package": "openclaw",
      "bin": "openclaw",
      "commands": [
        { "cmd": "openclaw --version", "timeoutMs": 20000 },
        { "cmd": "openclaw --help", "timeoutMs": 20000 },
        { "cmd": "openclaw acp --help", "timeoutMs": 20000 },
        { "cmd": "openclaw gateway --help", "timeoutMs": 20000 },
        { "cmd": "openclaw doctor", "timeoutMs": 60000 },
        {
          "cmd": "openclaw acp",
          "timeoutMs": 25000,
          "stdin": "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":{\"readTextFile\":false,\"writeTextFile\":false}},\"clientInfo\":{\"name\":\"coda-probe\",\"version\":\"0.0.1\"}}}\n"
        }
      ]
    }
  ]
}
```

**WorkBuddy 故意不在矩阵里**：没有可安装的 CLI，任何"探测命令"都只能是猜测，违反本文"不编造"原则。若 Lead 要一次性验证，见 §3 末尾的手工步骤。

补充说明（写进 runner 前需确认）：
- `timeoutMs: 15000` 的 `--serve` / `kimi web` 条目属于"**启动即超时**"，预期被 runner 记为 TIMEOUT 但 stdout 里有 banner → 建议给 runner 加 `expectTimeoutStdout: true` 之类的判据，否则会误报。
- 所有 `-p` / `exec` 冒烟条目在没有凭证时都会失败，需 `needsAuth` 标记（见本节开头约定）：
  - `qoder-cn`: `QODERCN_PERSONAL_ACCESS_TOKEN`
  - `trae`: `TRAECLI_PERSONAL_ACCESS_TOKEN` + 企业旗舰版
  - `codebuddy`: `CODEBUDDY_API_KEY` + `CODEBUDDY_INTERNET_ENVIRONMENT`（`internal`=中国版）
  - `kimi`: 需先 `kimi login`（device code）或 `kimi provider add` 配 key
  - `openclaw`: 需先 `openclaw onboard` + 至少一个模型 provider 凭据

---

## 9. 建议优先级

### 第一梯队（本迭代就做，文档足够，可当"国产三件套"验收）

1. **CodeBuddy（AGT-117）** —— 唯一"HTTP API + ACP + SDK"三入口齐全、且 **API Key 就能在 CI 里跑**的国产 Agent。`codebuddy --serve` 的形态与我们"引擎长驻 + 多端复用"最贴；REST 有语义化版本承诺，比其他家的 Beta 更可控。国内/国际差异收敛成一个环境变量（`CODEBUDDY_INTERNET_ENVIRONMENT`），适配器只需一个 provider 参数。
2. **Qoder（AGT-114）** —— ACP 与 headless stream-json 都已文档化，且有**官方 SDK（TS/Py/Java）**可绕开 CLI 细节；PAT 让 CI 能无人值守。唯一要先解决的是 bin 名不一致（`qoderclicn` / `qodercn`）——适配器做"两名字探测 + 版本比对"即可。
3. **Kimi Code（AGT-128）** —— 装一个二进制就能跑，`kimi web` 还自带 `/openapi.json`、`/asyncapi.json`，**协议自描述**，写适配器成本最低；`kimi acp` 可直接吃现有通用适配器。风险只在 CI 认证（device code 需人点一次）。

### 第二梯队

4. **TRAE（AGT-115）** —— 协议侧比上面三家只强不弱（ACP + exec JSONL + app-server + mcp-server，四个入口），**但它被企业旗舰版套餐锁住**。建议：先在 CI 跑"无凭证级"探测（版本/子命令面），把适配器写好，等拿到旗舰版席位再做端到端冒烟。**不要**为此先做逆向。
5. **OpenClaw（AGT-127）** —— 非国产，但它的 ACP↔Gateway 兼容矩阵是现成的设计参考（尤其"per-session MCP 不支持"这条坑）。接入成本低，可当作 ACP 适配器的**第二个真机验证对象**（继 Gemini/OpenCode 之后）。

### 第三梯队

6. **fx（AGT-129）** —— 已有配置且与官方一致，属于"收尾"而非"调研"；只需固定版本 + CI 冒烟。
7. **WorkBuddy（AGT-116）** —— 官方 BLOCKED。若产品侧坚持要"办公 Agent"覆盖，改走 **WMA 云 OpenAPI**，并在 UI 明确"云端 Agent"；**不要**把 `workbuddy2api` 系列纳入生产链路。

### 依赖顺序建议

```
CodeBuddy(HTTP/ACP) ──┐
Qoder(ACP/SDK)     ───┼─→ 共用 ACP 适配器骨架（已有）→ Kimi(ACP) → OpenClaw(ACP 验证)
TRAE(ACP/exec)     ───┘                                   ↑
                                        WorkBuddy(WMA 云) 另开一条"云 Agent"分支
```

---

## 10. 未解决问题 / 需要 Lead 决策

1. **TRAE 的旗舰版席位能不能拿到？**（259 元/席/月，起购 3 席）
   拿不到 → AGT-115 只能"检测安装即接入"，不能做 CI 冒烟，也就进不了 SPEC §7.3 的"已支持"。这是钱的问题，不是协议的问题。
2. **CI 里用谁的账号？**
   Qoder/Kimi/TRAE 都要真实账号凭证（PAT 或 OAuth）。SPEC §7.3 要求"复用原账号，不静默串用其他账号"，那 CI 用的 token 属于谁？建议：建**专用 CI 账号**并在能力矩阵里标注"CI 验证账号 ≠ 用户账号"，但需要 Lead 认可这条解释。
3. **WorkBuddy 是否接受 BLOCKED？**
   替代方案只有"接 WMA 云 OpenAPI"，但那不是本地 Agent 会话，会破坏 §7.3 第 3/4 条（在指定工作区创建并恢复会话）。要么改 SPEC 给"云 Agent"单开一类，要么 AGT-116 挂起。
4. **AGT-127 / AGT-129 的归类要不要改？**
   OpenClaw（个人开源 + 作者已加入 OpenAI）与 fx（Vercel Labs）都不是国产。本文建议把它们从"国产协议勘察"移到"国际/开源"，同时 `TODO.md` 与 `REFERENCE-PROJECTS.md` 的表述需同步（本次任务只允许改本文件，故未动）。
5. **probe runner 是否接受两个新约定？**
   `package: null`（curl 安装、不走 npm）与 `needsAuth`（无凭证判 SKIP 而非 FAIL）、`expectTimeoutStdout`（`--serve`/`kimi web` 预期超时但要看 banner）。不加这三条，上面的片段跑起来会有一半是假失败。
6. **CodeBuddy 走 HTTP 还是 ACP？**
   HTTP 能力更全（Runs/PTY/Jobs/文件系统），但标 Beta；ACP 稳定但丢端点。建议 ACP 做底座、HTTP 做增强，需要架构确认。

---

## 附：检索过程与不可达项（可复现）

已尝试但**未能取到原文**的来源，相关结论一律标 `UNKNOWN`，不作依据：

| 目标 | 尝试 | 结果 |
|---|---|---|
| `happy-everyday-everyweek/workbuddy2api-panel` 与上游 `linguo2625469/workbuddy2api-panel` | 工作区 `glob **/workbuddy*`、`**/*workbuddy*/*` | **工作区中没有该目录**（可能未 clone） |
| 同上（GitHub 页面 / raw README / `r.jina.ai` 代理） | `github.com/...`、`raw.githubusercontent.com/...`、`r.jina.ai/https://github.com/...` | 全部 `fetch failed`（本环境到 GitHub 的取文通道不可用）。仅从搜索摘要得知：它把「腾讯 WorkBuddy / CodeBuddy 账号」转成 OpenAI 兼容 API、多账号网关 + Web 面板、上游 2026-10-21 停更 |
| `MoonshotAI/kimi-code` 仓库 README | `github.com/MoonshotAI/kimi-code`、raw | `fetch failed`；改用官方文档站 `moonshotai.github.io/kimi-code` 拿到全部命令细节 |
| OpenClaw docs 正文 | `docs.claw.so/...`（导航类站点） | 正文被导航截断；改用 `docs.openclaw.ai/*.md` 全部取到 |
| CodeBuddy 英文文档正文 | `www.codebuddy.ai/docs/cli/*` | SPA 壳，正文不渲染；改用腾讯云镜像 `cloud.tencent.cn/document/product/1831/*` 取到完整正文 |
| Qoder 国际版 PAT 环境变量名 | 检索 `qoder personal access token international` | 未在官方文档中出现 → `UNKNOWN` |
| TRAE 国际版（docs.trae.ai）CLI 章节 | `docs.trae.ai/cli_about-trae-code-cli-2` 等 | 被重定向到"TraeCode 与 TraeWork 合并"页，未取到独立 CLI 章节 → 国际版套餐要求 `UNKNOWN` |
| WorkBuddy 桌面端是否监听本地 HTTP 端口 | 检索 `workbuddy 本地端口/接口/逆向` | 只有社区项目与文章，无官方说明 → `UNKNOWN`（因此不进 probe 矩阵） |

主要检索词（供复核）：
`Qoder CLI 阿里 命令行`、`qodercli npm`、`TRAE CLI 2.0`、`traecli acp`、`CodeBuddy CLI 安装`、`codebuddy --acp`、`CodeBuddy HTTP API Beta`、`kimi-cli ACP`、`kimi-code CLI`、`WorkBuddy CLI`、`WorkBuddy Managed Agents`、`workbuddy2api`、`OpenClaw gateway protocol`、`openclaw acp`、`fx ACP`、`fx.sh install`。

---

### 变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-07 | 首版：7 个 Agent 逐一结论；4 家 READY（Qoder/CodeBuddy/Kimi/OpenClaw）、1 家 READY 待冒烟（fx）、1 家 NEEDS-PROBE（TRAE，账号门槛）、1 家 BLOCKED（WorkBuddy）；OpenClaw 与 fx 归属更正为非国产 |

## 11. Lead 决策（2026-11-07）

1. **TRAE（AGT-115）**：不采购旗舰版席位；先做无凭证安装探测（能装则检测、能 `--help` 则记录），
   CI 端到端冒烟暂缓。状态：`BLOCKED（商业门槛）`，解除条件见 §2 硬门槛。
2. **CI 账号**：协议勘察一律**无凭证**；不做真实账号冒烟，避免违反 SPEC §7.3 的「复用原账号、
   不静默串用」要求。未来如需冒烟，使用专用 CI 账号并在证据里标注「验证账号 ≠ 用户账号」。
3. **WorkBuddy（AGT-116）**：官方只有 WMA 云 OpenAPI/A2A/MCP 与 IM Bot，没有本地 CLI/ACP/app-server；
   `workbuddy2api` 是非官方桌面端网关且上游停更，**不作为生产依赖**。状态：`BLOCKED（无可编程本地入口）`。
4. **归类修正**：OpenClaw（AGT-127）与 fx（AGT-129）不是国产产品（OpenClaw 是开源项目，fx 属 Vercel Labs）；
   适配不受影响，`REFERENCE-PROJECTS.md` 已同步。
5. **probe runner 约定**：接受 `package: null`（curl 安装）、`needsAuth`（无凭证判 SKIP）、
   `expectTimeoutStdout`（`--serve`/`kimi web` 预期不退出，看 banner）三个字段。
6. **CodeBuddy（AGT-117）**：先 ACP（稳定），HTTP API（Beta）作为后续扩展；国内/国际只差
   `CODEBUDDY_INTERNET_ENVIRONMENT`，不拆两套适配器。
7. **优先级**：CodeBuddy → Qoder → Kimi（都只差 CI 复核 + 协议录制）。

## 12. CI 无凭证复核结果（2026-11-07，run 42378437823）

> 公开仓库 `happy-everyday-everyweek/coda-agent-probes`，矩阵安装 + `--version/--help` 探测；
> 证据文件：`platform/fixtures/adapters/probes/<agent>.json`。

| Agent | 安装结果 | 版本 | 观察到的入口 | 结论 |
|---|---|---|---|---|
| Qoder（qoder-cn） | ✅ curl 安装成功，bin `qodercn` | 1.1.85 | `-p`、`--output-format`、`--session-id`、`--resume`、`--permission-mode`；`--help` 未见 `--acp` | headless 可接；ACP 待升级/企业版复核 |
| CodeBuddy | ✅ npm 安装成功 | 2.181.0 | `--acp`、`--serve`（HTTP+SSE）、`--output-format/--input-format stream-json`、`--resume` | ACP 与 headless 双通道可接 |
| Kimi Code | ⚠️ 安装脚本执行成功但 `kimi` 不在 CI PATH | — | 未探测到 | 需补 PATH/安装目录探测（run11） |
| TRAE | ⚠️ 安装脚本未产出 `traecli` | — | — | 与企业版门槛一致：无凭证不可用 |
| OpenClaw | ✅ npm 安装成功 | 2026.11.1 | `openclaw acp`（Gateway 桥） | ACP 画像可接，需 Gateway 凭据 |
| fx | ✅ curl 安装成功 | 0.0.16 | `fx acp`（stdio） | 与现有 ACP 画像一致 |
| WorkBuddy | — | — | 官方无 CLI | BLOCKED（不采用非官方网关照生产） |

结论：Qoder/CodeBuddy 的 headless 适配器已按「Claude 同族 CLI」接入（`adapters/headless-profiles.mjs`），
OpenClaw/fx 走 ACP 画像；Kimi 待安装路径修复后复核。

## 13. CI 复核第二轮（run 42378826849，2026-11-07）

| Agent | 结果 | 证据 |
|---|---|---|
| Kimi Code | ✅ 安装到 `~/.kimi-code`（不在默认 PATH），版本 **2.5.2**；`kimi acp` 子命令存在 | `probes/kimi.json` |
| CodeBuddy | ACP `initialize` 成功（loadSession/promptCapabilities/mcp）；`session/new` 返回 `Authentication required`（缺 `CODEBUDDY_API_KEY`） | `fixtures/adapters/codebuddy-acp-auth-gate.fixture.json` |
| fx | ACP `initialize` 返回 `-32600`：需要 Vercel AI Gateway 登录或 `AI_GATEWAY_API_KEY` | `fixtures/adapters/fx-acp-auth-gate.fixture.json` |
| OpenClaw | 已安装 2026.11.1；`openclaw acp` 依赖本地 Gateway，无凭据时启动即退出 | `probes/openclaw.json` |
| Qoder | 1.1.85：`-p/--print` 非交互确认，`--output-format` 存在；`--help` 仍无 `--acp` | `probes/qoder-cn.json` |
| TRAE | 安装脚本未产出 `traecli`（企业版门槛一致） | `probes/trae.json` |

结论：国产三家的**协议门槛**都已摸清——Kimi/CodeBuddy/fx 的 ACP 握手可达但需要各自凭据；
Qoder 走 headless stream-json；WorkBuddy 官方无入口；TRAE 卡企业版。真实回合录制需要凭据，
在「CI 一律无凭证」的决策下只能做到握手/错误帧证据。

## 14. CodeBuddy headless 帧形状（run 42379101425）

`codebuddy -p --output-format stream-json "..."` 在无凭据下仍输出与 **Claude Code 同族** 的 JSONL：
`system(init)` → `system(status)` → `assistant` → `system(usage_update)` → `result(error_during_execution)`。
因此 `adapters/claude-stream-json.mjs` 的帧映射可直接复用于 CodeBuddy（`headless-profiles.mjs` 已标 protocolConfirmed）。
Qoder 的 headless 在无登录时不输出 JSONL（stderr 报 `NonInteractiveAuthRequiredError`），需登录后才可录制。
