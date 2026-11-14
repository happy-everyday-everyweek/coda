# 协议勘察证据（CI 生成）

来源：公开仓库 `happy-everyday-everyweek/coda-agent-probes` 的 GitHub Actions（workflow `agent-probes`）。
最近一轮：run 42378028498（2026-11-07）。

- 只包含 `--version` / `--help` / 公开文档化 JSON-RPC、ACP 握手观察；无账号、无 API Key、无登录；
- 输出已在 CI 中脱敏；文件与版本绑定，升级后需重跑；
- 这些是「观测证据」，不是能力承诺；能力声明仍以真实会话录制为准。

| Agent | 包版本 | 已验证入口 |
|---|---|---|
| claude | 2.1.319 | `-p` + `--output-format/--input-format stream-json`、`--resume`、`--session-id` |
| codex | 0.167.0 | `app-server`（initialize 返回 userAgent/platform）；`exec --json` 真实 JSONL；完整 schema 见 `codex-schema/` |
| gemini | 0.67.0 | `--acp`（ACP initialize 成功；session/new 需要 API key） |
| opencode | 1.19.5 | `acp`（initialize + session/new 匿名成功；prompt 需要登录）；`serve` |
| qwen | 0.26.9 | `--input-format/--output-format stream-json`、`serve` |
| copilot | 1.0.107 | `-p/--prompt`、sessions/mcp/lsp |
| auggie | 0.37.0 | `-p --output-format json` |
| crush | 0.100.0 | `server`、`--session` |

`codex-schema/` 是 `codex app-server generate-json-schema --out` 的完整产物子集
（ClientRequest.json / ServerNotification.json），用于钉住方法名与参数；版本升级后必须重抓。
