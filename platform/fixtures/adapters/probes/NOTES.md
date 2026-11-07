# 协议勘察证据（CI 生成）

来源：公开仓库 `happy-everyday-everyweek/coda-agent-probes` 的 GitHub Actions 运行
（workflow `agent-probes`，run 42375665593），由 `scripts/probe.mjs` 生成。

- 只包含 `--version` / `--help` / 公开文档化 JSON-RPC 握手的观测；
- 无账号、无 API Key、无登录；输出已在 CI 中脱敏；
- 文件与版本绑定，升级后需重跑；
- 这些是「观测证据」，不是能力承诺；适配器能力声明仍以真实会话录制为准。

| Agent | 包版本 | 已验证入口 |
|---|---|---|
| claude | 2.1.319 | `-p/--print` + `--output-format stream-json` + `--input-format stream-json` + `--resume` + `--session-id` + `--permission-mode` |
| codex | 0.167.0 | `codex app-server`（JSON-RPC initialize 握手返回 userAgent/platform），`app-server generate-json-schema/ts` |
| gemini | 0.67.0 | `--acp`（ACP 模式），`--output-format stream-json` |
| opencode | 1.19.5 | `opencode acp`（ACP server）、`opencode serve` |
| qwen | 0.26.9 | `--input-format/--output-format stream-json`、`qwen serve`（HTTP daemon） |
| copilot | 1.0.107 | `-p/--prompt`、sessions/mcp/lsp 子命令 |
| auggie | 0.37.0 | `-p/--print --output-format json`、ACP/serve 字样 |
| crush | 0.100.0 | `crush server`、`--session` |
