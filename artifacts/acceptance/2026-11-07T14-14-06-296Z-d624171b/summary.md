# 验收证据 2026-11-07T14-14-06-296Z-d624171b

- 时间：2026-11-07T14:14:06.296Z → 2026-11-07T14:14:27.113Z（20807 ms）
- 分支/提交：feat/desktop-agent-platform @ 9071eae
- 运行时：Node v24.22.1 / linux arm64
- 命令：`node --test "test/**/*.test.mjs"`（cwd: platform/）
- 结果：**FAIL**（exit=1）
- 计数：tests=357 pass=356 fail=1

## 失败项

- ✖ 勘察证据：8 个 agent 都有版本与入口记录，codex 的 initialize 握手是真实响应 (134.831308ms)
- ✖ failing tests:
- ✖ 勘察证据：8 个 agent 都有版本与入口记录，codex 的 initialize 握手是真实响应 (134.831308ms)

## 声明边界

本结果只代表参考实现的契约/单元测试；G0–G5 发布门槛、真机与真实 Agent 证据见 ACCEPTANCE.md，不得据此标记发布 PASS。
