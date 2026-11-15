# ADR-0002：PA 单会话数据模型、命令幂等、离线同步与快照

- 状态：Accepted（文档决策已定；实现与验收状态见「影响」一节与各节标注）
- 日期：2026-11-07
- 对应任务：DOC-103
- 对应验收：PA-002、PA-009、SYNC-005、SYNC-006、SYNC-009、SYNC-013
- 关联文档：[ADR-0001](./ADR-0001-shared-core-runtime.md)、[CONTRACT.md](../../CONTRACT.md)、`contracts/commands.mjs`、`contracts/ids.mjs`、`contracts/events.mjs`、[TRACEABILITY](../TRACEABILITY.md)

## 背景

`SPEC.md` 10.4 要求：桌面与 Android 呈现**同一身份、同一连续会话**的 PA；两端可同时发送，消息全部保留并按确定性规则合并；同一后台命令由命令 ID 去重。
`SPEC.md` 11.1 要求设备自治、局域网优先、无强制中心服务，禁止用整库最后写入覆盖冲突。
`SPEC.md` 11.5 要求命令持久化收据、幂等投递，并允许「结果未知」作为合法终态；`SPEC.md` 11.6 要求离线可读已同步内容、未同步内容明确标注需要原节点在线。

`platform/contracts/commands.mjs` 已固定命令状态机与幂等边界（`accepted → dispatched → succeeded|failed|expired|superseded|unknown`），`platform/contracts/ids.mjs` 已固定 `pa_` / `pm_` / `cmd_` / `cur_` 前缀。本 ADR 解决的是这些契约**之上**的数据模型与同步策略，不改变契约本身。

现状证据（2026-11-07 文件快照）：`platform/` 已有 `contracts/`、`core/`（errors / eventbus / domain / journal / receipts / supervisor / workspace / terminal）、`adapters/`、`pa/`（schema / merge / store / runner / audit / tools）、`security/redact.mjs`、`clients/shared/` 与一份契约测试；**仍缺** `sync/` 与多数 `index.mjs` 入口，且本会话无法执行任何测试。因此：PA 数据模型与合并规则以本文为准（已由 `pa/schema.mjs`、`pa/merge.mjs` 落地）；同步、快照与端到端验收仍为 `UNVERIFIED`。

## 决策

### 1. PA 单会话数据模型

1. **单一会话**：每个用户只有一个 `PAProfile`（`pa_`），不按端、不按设备建第二条会话。PA 消息不挂在 `Session` 下，也不使用 `ses_` 前缀。
2. **追加日志**：`PAMessage`（`pm_`）只追加、不原地改写正文；编辑与撤回以「新消息 + 指向旧消息的墓碑/替代关系」表达，保证任一端都能重放出同一结果。
3. **必备字段**（与已落地的 `pa/schema.mjs`（`createPaMessage` / `validatePaMessage`）保持一致；全部参与同步，缺一即为无效消息）：

   | 字段 | 说明 |
   |---|---|
   | `id` | `pm_` + UUID，**由来源节点生成**，同步与合并过程中不得重写 |
   | `paId` | 所属 `pa_`；首版恒为唯一 PA |
   | `sourceNode` | 来源节点 `dev_`，同时是 HLC 的节点部分（校验强制 `hlc.node === sourceNode`） |
   | `role` | `user` \| `assistant` \| `system`；PA 自动产生时另记审计决定者（`pa`/`user`），对应 PA-006 |
   | `hlc` | 混合逻辑时钟 `物理毫秒(15 位):计数器(6 位):节点ID`，**全局排序主键** |
   | `seq` | 来源节点内递增序号，用于缺口检测与游标，不单独决定全局顺序 |
   | `ts` | 由 HLC 物理部分推导的 ISO-8601 UTC 时间（`tsForHlc`），仅用于展示，不参与排序 |
   | `replyTo` | 被回复的 `pm_`，可空；PA 回复必须携带，避免语境错配（SPEC 10.4） |
   | `content` / `attachments` | 文本；附件只存 `art_` 引用与元数据，大内容不进消息体 |
   | `origin` | `local` \| `sync` \| `migration`，用于迁移与诊断，不参与排序 |
   | `schemaVersion` | 消息 schema 版本（当前 `PA_SCHEMA_VERSION = 1`），供迁移（CORE-004） |

4. **人设与配置**：PA 人设、个人资料、模型/规划模型选择随单会话同步；API Key、OAuth Token 等秘密**默认不同步**，只留原节点（SPEC 10.4 / 11.4 / SEC-001）。
5. **不复制 Agent 过程**：Agent 的工具过程、审批与消息留在其原会话，PA 侧只保留协调结论与引用（PA-010）。

### 2. 确定性合并规则

1. 全序固定为 `(hlc.physical, hlc.counter, hlc.node, id)` 的码点序比较（`pa/schema.mjs` 的 `comparePaMessages`，不使用 `localeCompare`），结果与到达顺序、本机时钟、系统 locale 无关（SYNC-013）。
2. HLC 推进（`createHybridClock`）：本地发消息取「物理时钟与上次物理时钟的较大者」，同一毫秒内递增计数器，计数器溢出则物理 +1；收到远端 HLC 时用 `observe()` 抬升本地时钟。任一节点对同一消息集合产生相同顺序。
3. 同时发送不互斥、不锁对端输入；两端消息都保留（PA-009）。
4. 合并策略（`pa/merge.mjs`）：消息集合取并集并按 `id` 去重；同一 `id` 内容分叉时按规范化 JSON（对象键码点序）取较小者并记入 `idConflicts`，与输入顺序无关；同节点同 `seq` 不同 `id` 只报告 `seqConflicts`，**不丢弃任何消息**；`replyTo` 目标缺失只标 `orphan`，消息保留。**首版不做 PA 正文协同编辑**。
5. 合并是纯函数：`mergePaMessages({ local, incoming })` 不读不写数据库，返回确定性排序、`nextCursor`（最大 HLC）与冲突报告（`idConflicts`/`seqConflicts`/`orphans`/`gaps`），可用回放测试证明收敛。

### 3. 命令幂等

1. `commandId`（`cmd_`）由**发起节点**生成，随命令全生命周期不变；重试、重连、双端重跑都复用同一 ID。
2. 目标节点维护唯一索引 `UNIQUE(command_id)`，并在**执行任何外部 I/O 之前**写入 `accepted` 收据（CONTRACT 第 3 节）。重复投递直接返回原收据，不产生第二次副作用（SYNC-006）。
3. 状态推进只允许 `accepted → dispatched → 终态`；`unknown` 是合法终态，不得为了「看起来干净」改成成功或失败（REL-003）。
4. `expired` / `superseded` 由目标节点在**执行前**判定：`isExpired(cmd)` 基于 `expiresAt`，新命令通过 `supersedes` 取代尚未执行的旧命令。
5. 副作用已发生、结果未提交时崩溃：重启后**不得盲重试**，先查询上游会话/进程状态，确认不了就落 `unknown`（SPEC 11.5、REL-003）。

### 4. 离线队列与送达边界

1. 每台设备保存本地权威数据、PA 副本、同步游标和待发送命令（SPEC 11.1）。
2. 发给离线节点的命令保留在待同步队列，UI 显示「等待目标在线」，**不得显示已接收/已执行**（SYNC-008、场景 B）。
3. 送递语义为「至少一次投递 + 目标端按 commandId 去重」，不宣称端到端恰好一次；非幂等外部操作靠上游 ID 与状态核对避免重复（SPEC 11.5）。
4. 两端从未同时在线且无第三方可信副本时，消息和命令不保证送达，产品必须如实呈现（SPEC 11.2）。
5. 撤销设备后拒绝其新连接与新命令，但不因此把远端任务写成已停止（SYNC-010）。

### 5. 快照与游标

1. `SyncCursor`（`cur_`）按对端保存：`peerNode`、`lastSeq`（事件）、`lastHlc`（PA，取值为已见的最大 HLC）、`snapshotVersion`、`pendingAcks`。
2. 快照用于加速追平，**不替代日志**：`(hlc ≤ HLC_S)` 的状态压缩为快照，同时保留最近 `W` 条或 `T` 天日志用于补交与审计；窗口大小与保留期由实现配置并写入发布说明。
3. 快照必须带 `schemaVersion` 与生成时的 `lastSeq/lastHlc`；接收端发现缺口（事件 `seq` 不连续，或某 `sourceNode` 的 `seq` 出现洞，可用 `findSeqGaps` 检测）时只报告并要求重新快照对齐，**猜测补齐**是禁止行为。
4. 会话数据按 SPEC 11.6 分层：在线同步会话列表 + 最近窗口 + 活跃会话事件；打开旧会话时按需补取。已同步会话在节点离线后仍可回看（SYNC-004），未同步部分显示「需要原节点在线」。
5. 快照与日志的写入必须与投影更新处于同一事务边界（CONTRACT 第 3 节、CORE-104）。

## 影响

- `core/` 侧已落地命令收据（`core/receipts.mjs`，状态机复用 `contracts/commands.mjs`，含 `unknown` 结算与崩溃恢复）；**仍缺** `SyncCursor` 表与快照表。`sync/` 只搬运已持久化对象与收据，不产生权威状态（CONTRACT 第 1 节）。
- `pa/` 已落地消息 schema、HLC、合并、单会话 store 与本地运行器；客户端不得自行生成 `pm_`/`cmd_`（须经 `contracts/ids.mjs`），也不得在本地「合并后」再写回另一份权威副本。
- PA-101/PA-102/PA-103 已有实现文件（`pa/schema.mjs`、`pa/merge.mjs`、`pa/store.mjs`、`pa/runner.mjs`）；SYNC-103/104 与 `sync/` 无实现，故跨端路径状态仍为 `UNVERIFIED`。
- 验收上，PA-002/PA-009/SYNC-005/SYNC-006/SYNC-009/SYNC-013 需要分区、重复、乱序、分叉的网络测试（SYNC-108）作为证据；当前无任何可复现证据（未执行测试）。
- 明确不采用的语义：整库 LWW、把 `createdAt` 当排序依据、把离线命令显示为成功。这三项若出现在实现或被写入文案，视为违反本 ADR。

## 备选方案

1. **整库最后写入覆盖（LWW）**：实现最省事，但会静默丢消息，直接违反 SPEC 11.1 与 SYNC-013；放弃。
2. **CRDT 富文本协同编辑**：能支持正文并发编辑，但 SPEC 10.4 明确首版「以追加为主、不做复杂正文协同编辑」，增加复杂度而无验收需求；首版不做，架构不排除（保留 append-only 抽象）。
3. **官方中心服务承载 PA 会话**：与 SPEC 2.4 / 11.1「无强制中心服务」冲突，且引入账号与合规成本；放弃。
4. **每端一条 PA 会话，靠 UI 合并显示**：违反 SPEC 10.1「同一身份、同一连续会话」，会在断连时产生分叉真相；放弃。
