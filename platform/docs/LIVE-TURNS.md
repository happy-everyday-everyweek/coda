# 真实 Agent 回合：本地 LLM 网关方案

> 目的：解决「适配器只能验证 `--version` 与握手，真实回合缺模型凭据」的问题。
> 做法：启动一个兼容 Anthropic Messages / OpenAI Chat / OpenAI Responses 的本地网关，
> 把真实 Agent CLI 指向它，跑完整回合并记录证据。

## 1. 为什么可行

大多数 Agent CLI 支持自定义 `base_url` 或 provider 配置：

| Agent | 配置方式 |
|---|---|
| Claude Code | `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN` |
| Codex CLI | `~/.codex/config.toml` 的 `model_providers.*.base_url`，`wire_api = "responses"` |
| OpenCode | `opencode.json` 的 `provider.*.options.baseURL`（OpenAI 兼容） |
| Qwen Code | `OPENAI_BASE_URL` + `OPENAI_API_KEY` + `OPENAI_MODEL` |
| Kimi Code | `kimi provider add <registry>` 导入自定义 `api.json` |

网关不需要真实模型：固定回复 `CODA_GATEWAY_OK` 就足以验证协议字段、SSE 解析、
回合生命周期与退出码。它验证的是 **Agent 与网关协议**，不是模型能力。

## 2. 组成

- `platform/tools/mock-llm-gateway.mjs`：零依赖 HTTP/SSE 网关
  - `POST /v1/messages`（Anthropic）
  - `POST /v1/chat/completions`（OpenAI Chat）
  - `POST /v1/responses`（OpenAI Responses，Codex 用）
  - `GET /api.json`（Kimi 自定义 registry）
  - `GET /v1/models`、`GET /health`
- `platform/tools/live/<agent>.sh`：每个 Agent 的安装、配置、运行脚本
- `.github/workflows/agent-live.yml`：五 Agent 矩阵，上传 transcript 与网关日志
- `platform/fixtures/adapters/live-turns/`：脱敏后的真实回合证据

## 3. 边界（必须保留）

1. **不是真实模型服务商**：模型后端是固定文本；不能据此宣称已接通 OpenAI/Anthropic/Kimi 等。
2. **不覆盖模型行为**：工具循环、长上下文、推理、计费、限流都未验证。
3. **不能替代真实凭据回归**：拿到真实 key 后仍需跑一次真实服务商回合并录制。
4. 证据表里写「网关支撑真实回合」，不要写成「真实模型回合」。

## 4. 结果（2026-11-08，run 42433802182）

| Agent | 版本 | 协议 | 结果 |
|---|---|---|---|
| Claude Code | 2.1.319 | Anthropic Messages SSE | PASS |
| Codex CLI | 0.167.0 | OpenAI Responses SSE | PASS |
| OpenCode | 1.19.5 | OpenAI Chat SSE | PASS |
| Qwen Code | 0.27.0 | OpenAI Chat SSE | PASS |
| Kimi Code | 2.5.2 | registry + OpenAI Chat SSE | PASS |
| CodeBuddy | 2.181.0 | OpenAI Chat SSE（`/chat/completions`） | PASS |
| Crush | 0.100.0 | OpenAI Responses SSE | PASS |

详见 `platform/fixtures/adapters/live-turns/README.md`。

Claude Code、Codex、Kimi 还通过了**适配器级**验证：`platform/adapters/{claude-stream-json,codex-exec,kimi-headless}.mjs`
直接驱动真实 CLI，产生 `message`/`status`/`tool` 统一事件（见 `<agent>.adapter.json`）。

## 5. 扩到更多 Agent

新增 `platform/tools/live/<agent>.sh`（可复用 `lib.sh` 的 `install_npm`/`start_gateway`/`finish`），
加入 workflow matrix，并在 `summary.json` 记录版本与协议。判定条件保持：
transcript 含 `CODA_GATEWAY_OK` 且退出码 0。
