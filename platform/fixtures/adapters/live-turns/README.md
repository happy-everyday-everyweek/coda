# 网关支撑的真实 Agent 回合证据

这里记录的不是 `--version` 探测，而是**真实 Agent CLI 二进制**通过真实协议栈完成的完整回合：
CLI 启动 → 请求本地 LLM 网关 → 解析流式响应 → 输出结果 → 退出码 0。

- 工作流：`.github/workflows/agent-live.yml`
- 网关：`platform/tools/mock-llm-gateway.mjs`（零依赖）
- 提示词：`Reply with exactly CODA_GATEWAY_OK. Do not use tools.`
- 判定：transcript 中出现 `CODA_GATEWAY_OK` 且进程退出码为 0

## 覆盖范围

| Agent | 版本 | 协议 | 结果 |
|---|---|---|---|
| Claude Code | 2.1.319 | Anthropic Messages SSE + stream-json | PASS |
| Codex CLI | 0.167.0 | OpenAI Responses SSE + JSONL | PASS |
| OpenCode | 1.19.5 | OpenAI Chat Completions SSE | PASS |
| Qwen Code | 0.27.0 | OpenAI Chat Completions SSE + stream-json | PASS |
| Kimi Code CLI | 2.5.2 | 自定义 registry + OpenAI Chat SSE | PASS |
| CodeBuddy | 2.181.0 | OpenAI Chat SSE（`/chat/completions`） | PASS |
| Crush | 0.100.0 | OpenAI Responses SSE | PASS |

运行记录：[42433802182](https://github.com/happy-everyday-everyweek/coda/actions/runs/42433802182)（七个矩阵任务全部 success）。

其中 Claude Code、Codex、Kimi 还额外跑了**适配器级**验证：用 `platform/adapters/` 里的
`claude-stream-json.mjs` / `codex-exec.mjs` / `kimi-headless.mjs` 驱动真实 CLI，断言收到 `message` 与 `status`
统一事件，报告见 `<agent>.adapter.json`。
每个目录文件：

- `<agent>.transcript.txt`：CLI 原始输出的脱敏副本
- `<agent>.gateway.jsonl`：网关收到的请求摘要
- `<agent>.version.txt`：CLI 版本
- `<agent>.adapter.json`：适配器级回合报告（claude/codex/kimi）
- `summary.json`：运行 ID、版本、协议与边界说明

## 边界（不要误读）

1. 模型后端是固定回复的本地网关，**不是真实模型服务商**；
2. 它证明的是「Agent 能跑完整回合、协议字段/流式解析/退出路径正确」，不证明模型质量、工具循环或长上下文；
3. 不能据此把适配器标成「已接通真实服务商」；真实凭据下的回归仍需要单独证据。

## 复跑

```bash
node platform/tools/mock-llm-gateway.mjs --port 18080 &
bash platform/tools/live/claude.sh   # 或 codex/opencode/qwen/kimi
```

或触发 `Agent Live Turns` 工作流。脚本会自动安装对应 CLI（npm 镜像）、启动网关、
运行回合并把证据写到 `live-turns/<agent>/`。
