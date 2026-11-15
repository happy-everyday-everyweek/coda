# Personal Agent（PA）参考实现

本目录实现 `docs/desktop-agent-platform/SPEC.md` 第 10 / 13.1 / 14 节的 PA 参考实现，
对应 `TODO.md` 第 7 节与 `ACCEPTANCE.md` 的 PA-001~PA-009、SEC-005。

## 1. 模块

| 文件 | 任务（Lead 编号 / TODO 编号） | 职责 |
|---|---|---|
| `schema.mjs` | PA-101 / PA-101 | PAProfile、PAMessage、HLC、确定性排序、单会话不变量 |
| `merge.mjs` | PA-102 / PA-102 | 消息 ID 去重、reply_to 解析、seq 冲突报告、幂等重放 |
| `store.mjs` | PA-103 支撑 | node:sqlite 独立库：资料/消息/审计/确认/迁移记录 |
| `runner.mjs` | PA-103 / PA-103 | 桌面本地运行器；`core`、`llm` 均可注入；无 Coding Agent 也能工作 |
| `tools.mjs` | PA-104 / PA-104 | 查询/创建/发送/停止/回答审批；变更只走 `core.command`，查询读 `core.snapshot` |
| `audit.mjs` | PA-105 / PA-105 | 首启文案、一次性确认、行动审计、无额外自主档位/暂停开关 |
| `migration.mjs` | PA-107 / PA-108 | 旧 Android PA 会话 dry-run / 导入 / 回滚 |
| `BLOCKED.md` | PA-108 / PA-107 | Android 接入桌面同步层 + 真机验证：BLOCKED |
| `index.mjs` | — | 模块入口，导出 `createPaRunner({core, store})` |

> Lead 任务书把「旧 Android 会话迁移」编号为 PA-107、把「Android 接入同步层」编号为
> PA-108；`TODO.md` 的编号相反。实现与 `BLOCKED.md` 均按 Lead 任务书编号，且在两处都写了对照。

## 2. 单会话与确定性排序

- 一个 `PAProfile` 只有一条连续会话；`PAMessage.paId` 必须等于该 profile 的 ID。
  `assertSinglePaSession()` 会拒绝把另一个 PA 的消息并入。
- 每条消息有：全局 `id`（`pm_`）、`sourceNode`（`dev_`）、`replyTo`、`seq`、`role`、
  `content`、`attachments`（`art_` 引用）、`ts`、`hlc`、`schemaVersion`、`origin`。
- 排序规则（跨端一致，与输入顺序/本机时钟/locale 无关）：
  1. HLC 物理毫秒（15 位）；
  2. HLC 计数器（6 位）；
  3. HLC 节点 ID（码点序）；
  4. 消息 ID（码点序）。
- HLC 节点必须等于 `sourceNode`（schema 强制），所以排序键唯一。
- 合并是集合并集：不同节点的并发消息全部保留；同 ID 去重；同节点同 `seq` 的不同消息
  **不丢弃**，只在 `seqConflicts` 中报告，顺序仍由 HLC+ID 唯一确定。

## 3. 命令层边界

- `tools.mjs` 只 import `contracts/commands.mjs` 与 `contracts/rpc.mjs`，不 import `adapters/`、`core/`、`clients/`。
- 变更动作：`session.create` / `turn.send` / `turn.stop` / `interaction.answer` 均由
  `createCommand()` 构造，经 `core.handleRpc("core.command", {command})` 投递，返回
  `{commandId, state, receipt, duplicate}` 收据。
- 查询会话：读 `core.snapshot` 投影（只读核心状态，不碰适配器）。这是读路径，不产生命令；
  如果要求「查询也必须是命令」，需要在 `contracts/commands.mjs` 增加 `session.list` 类命令
  （本次未修改 contracts/，见最终报告）。
- 每次 PA 自动派发/审批都写审计：`actor` 明确区分 `pa` / `user`，并记录来源节点、目标、
  参数摘要（秘密字段脱敏）、结果和错误。

## 4. 独立性

- `runner.mjs` 不依赖 Coding Agent：`core = null` 时仍可对话、存储、迁移。
- LLM 通过 `llm` 注入（函数或 `{complete/reply/generate}`）。测试使用脚本化 fake，不联网。
- 数据目录独立（默认 `<dataDir>/pa.sqlite`），不读写 Agent 的库/配置。
- `close()` 只关闭 PA 自己的资源；不会停止 Agent，也不会向核心派发命令。

## 5. 测试

```bash
cd platform
node --test test/pa/
```

覆盖：schema 校验、HLC/排序、重复/乱序/分区合并、同序号冲突、reply_to/orphan、
命令层收据与失败审计、审批 actor 区分、首启确认、迁移 dry-run/导入/回滚/失败重试、
PA 与直接 Agent 的独立性、PA 模块 import 边界。

## 7. 跨模块字段对照（待 Lead 统一）

同一分支已有两套 PA 数据形状；Android 接入前必须决定唯一 schema，或在 sync/core 边界做映射。

| 语义 | 本模块 `pa/schema.mjs` | `core/domain.mjs` | `sync/sync-service.mjs` |
|---|---|---|---|
| 归属 | `paId` | `profileId` | （无，设备级日志） |
| 来源节点 | `sourceNode` | `sourceNodeId` | `author` |
| 正文 | `content` | `text` | `text` |
| 时间 | `ts`（ISO） | `createdAt`（ISO） | `createdAt`（ISO） |
| 排序 | `hlc` + `seq` | `seq` | `lamport` |
| 角色/附件 | `role` / `attachments` | 无 | 无 |

建议：以本模块 `schema.mjs` 作为桌面/Android 共用 schema（它包含 SPEC 10.4 要求的
`sourceNode`、`replyTo`、`seq`、角色、内容、附件、时间），sync/core 增加显式映射；
不要在三处各自演进。

## 8. 已知限制 / 未完成

- 真机双端验证（PA-002 / PA-008 / PA-009 的设备侧证据）阻塞，见 `BLOCKED.md`。
- 查询不是命令（见 §3），是否补 `session.list` 命令需 Lead 决定。
- 跨模块 PA schema 尚未统一：`sync/cursors.mjs` 用 `author/lamport/text/createdAt`，
  `core/domain.mjs` 用 `profileId/sourceNodeId/text/createdAt`，本模块用
  `sourceNode/hlc/content/ts`。Android 接入前需要 Lead 定一个映射（见 `BLOCKED.md` §4）。
- 长期自动记忆提炼和「目标」不在首版（SPEC 10.4），本实现没有用空开关占位。
