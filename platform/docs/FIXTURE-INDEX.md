# Agent 证据索引（自动生成，勿手改）

> probes=14 recordings=12 handwritten=3。
> 规则：SPEC 14.3 脱敏；录制必须 redaction.applied=true；手写 fixture 必须说明来源。

| Agent | 版本 | 类型 | 任务 | 帧/探测数 | 路径 |
|---|---|---|---|---|---|
| acp-generic | - | handwritten | AGT-104 | 9 | fixtures/adapters/acp-happy-path.fixture.json |
| acp-generic | - | handwritten | AGT-104 | 5 | fixtures/adapters/acp-protocol-error.fixture.json |
| acp-generic | - | handwritten | AGT-104 | 8 | fixtures/adapters/acp-stop-denied-permission.fixture.json |
| auggie | 0.37.0 | probe | - | 3 | fixtures/adapters/probes/auggie.json |
| claude-code | 2.1.319 | recording | AGT-111 | 3 | fixtures/adapters/claude-stream-json-noauth.fixture.json |
| claude | 2.1.319 | probe | AGT-111 | 5 | fixtures/adapters/probes/claude.json |
| codebuddy | 2.181.0 | recording | AGT-117 | 2 | fixtures/adapters/codebuddy-acp-auth-gate.fixture.json |
| codebuddy | 2.181.0 | recording | AGT-117 | 5 | fixtures/adapters/codebuddy-headless-stream-json.fixture.json |
| codebuddy | 2.181.0 | probe | - | 7 | fixtures/adapters/probes/codebuddy.json |
| codex | 0.167.0 | recording | AGT-112 | 2 | fixtures/adapters/codex-app-server-initialize.fixture.json |
| codex | 0.167.0 | recording | AGT-112 | 14 | fixtures/adapters/codex-exec-noauth.fixture.json |
| codex | 0.167.0 | probe | AGT-112 | 10 | fixtures/adapters/probes/codex.json |
| copilot | 1.0.107 | probe | AGT-123 | 3 | fixtures/adapters/probes/copilot.json |
| crush | 0.100.0 | probe | - | 2 | fixtures/adapters/probes/crush.json |
| deepseek-harness | 0.2.2-rc.1 | recording | AGT-113 | 6 | fixtures/adapters/deepseek-harness-headless-error.fixture.json |
| fx | - | recording | AGT-129 | 1 | fixtures/adapters/fx-acp-auth-gate.fixture.json |
| fx | - | probe | - | 4 | fixtures/adapters/probes/fx.json |
| gemini | 0.67.0 | recording | AGT-122 | 2 | fixtures/adapters/gemini-acp-handshake.fixture.json |
| gemini | 0.67.0 | probe | AGT-122 | 4 | fixtures/adapters/probes/gemini.json |
| kimi | 2.5.2 | recording | AGT-128 | 2 | fixtures/adapters/kimi-acp-auth-gate.fixture.json |
| kimi | 2.5.2 | recording | AGT-128 | 1 | fixtures/adapters/kimi-headless-stream-json.fixture.json |
| kimi | - | probe | - | 8 | fixtures/adapters/probes/kimi.json |
| openclaw | 2026.11.1 | probe | - | 4 | fixtures/adapters/probes/openclaw.json |
| opencode | 1.19.5 | recording | AGT-121 | 3 | fixtures/adapters/opencode-acp-flow.fixture.json |
| opencode | 1.19.5 | recording | AGT-121 | 3 | fixtures/adapters/opencode-acp-handshake.fixture.json |
| opencode | 1.19.5 | probe | AGT-121 | 7 | fixtures/adapters/probes/opencode.json |
| qoder-cn | - | probe | - | 6 | fixtures/adapters/probes/qoder-cn.json |
| qwen | 0.26.9 | probe | - | 4 | fixtures/adapters/probes/qwen.json |
| trae | - | probe | - | 4 | fixtures/adapters/probes/trae.json |
