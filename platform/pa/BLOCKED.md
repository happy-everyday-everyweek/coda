# PA-108 阻塞说明：Android PA 接入桌面同步层与真机验证

状态：**BLOCKED**（不是部分完成，不得当作已支持）

> 编号说明：本文件按 Lead 的任务书写为 PA-108；`docs/desktop-agent-platform/TODO.md` 第 7 节中
> PA-107 是「Android PA 接入桌面同步层」，PA-108 是「旧 Android PA 会话迁移与回滚验证」。
> 本仓库交付的 `platform/pa/migration.mjs` 实现的是 TODO PA-108 的内容（Lead 任务书编号 PA-107）；
> 本文件对应的正是 TODO PA-107（Lead 任务书编号 PA-108）。两个编号不要混用。

## 1. 结论

Android 端 PA 接入桌面同步层、以及「同一 PA 身份 / 同一条连续会话」的双端真机验证，
在当前环境无法完成，也不能用参考实现假装完成。原因如下。

## 2. 阻塞原因（可复现事实）

1. **没有 Android 设备、模拟器或 APK**
   - 当前运行在 proot Ubuntu 环境，无法启动 Android 模拟器，也没有 adb 设备。
   - 无法安装 APK、无法触发 Android PA 首启、无法录制真机日志。
2. **没有真机日志/双端抓包**
   - PA-002（双端同一身份、同一连续会话）需要双端 ID 对照与同步抓包。
   - PA-008（电脑离线时手机 PA 仍可对话并显示电脑侧最后已知状态）需要至少一台手机 + 一台电脑，
     并截断网络/关闭电脑侧核心。
   - 没有这些证据，任何「已接入」结论都不可复核。
3. **桌面同步层已有参考实现，但没有 Android 客户端、也没有与 PA 完成对接**
   - `platform/sync/` 已有配对/设备身份、加密帧、传输、快照/增量、命令队列与 PA 追加日志
     （`sync-service.mjs`、`cursors.mjs`、`storage.mjs`）。
   - 但同步层的 PA 消息形状是 `{ id, author, lamport, text, replyTo, createdAt, contentHash }`
     （`sync/cursors.mjs#paOrderKey`），本 PA 模块的消息形状是
     `{ id, paId, sourceNode, seq, role, content, attachments, replyTo, ts, hlc }`；
     `platform/core/domain.mjs` 还有第三套 PA 形状（`profileId, sourceNodeId, text, createdAt`）。
     三套字段名/排序基准（lamport vs HLC）尚未统一，不能直接宣称 PA-002 已打通。
   - `platform/sync/BLOCKED.md` 也把 Android 节点（SYNC-109）标为 BLOCKED。
4. **Android 端只有本地 JSON/SharedPreferences，没有同步客户端**
   - `app/src/main/java/com/coda/mobileui/personal/CodaBotStore.kt` 只把消息写到
     `filesDir/coda-bot/messages.json`，profile 写到 `SharedPreferences("coda_bot_profile")`。
   - 没有导出入口、没有设备 ID、没有与桌面核心的握手/游标/命令收据协议。
5. **不能以单机测试替代双端验收**
   - 本仓库的 `platform/test/pa/` 可以证明合并算法、迁移、审计和命令收据是确定性的；
   - 但它不能证明 Android 真机上的连续会话、离线可用和最后已知状态。

## 3. 已完成、可作为接入前置的参考实现（不等于 PA-108 完成）

- `schema.mjs`：PAProfile / PAMessage，全局 ID、sourceNode、replyTo、seq、HLC；
- `merge.mjs`：跨端去重、并发保留、同序号冲突报告、replyTo 解析、幂等重放；
- `migration.mjs`：旧 Android 会话 dry-run / 导入 / 回滚（本次 PA-107 交付物）；
- `tools.mjs`：协调能力只走 `core.command`；查询走 `core.snapshot`；
- `audit.mjs`：首启文案、一次性确认、行动审计、无额外自主档位/暂停开关。

这些是 Android 接入同步层时可以复用的数据结构与合并规则，但**没有** Android 侧代码、
同步对接代码或真机证据。

## 4. 解除阻塞所需（验收证据）

1. 可用的 Android 设备/模拟器与 adb 访问；安装当前 Coda APK 的能力。
2. 先统一 PA 消息 schema（Lead 决策）：
   - 字段名（`sourceNode` vs `author`/`sourceNodeId`、`content` vs `text`、`ts` vs `createdAt`）；
   - 排序基准（HLC vs lamport）；
   - 归属（`paId` vs `profileId`）。
   建议以 `platform/pa/schema.mjs` 为桌面/Android 共用 schema，同步层做字段映射而不是另造第三套。
3. Android PA 增加：
   - 稳定 `PAProfile.id` 与 `sourceNode`（设备 ID）；
   - 消息导出/导入（或直接同步）并保留全局 `id`、`replyTo`、排序字段；
   - 同步客户端：握手、快照、增量、命令收据。
4. 双端测试脚本与真实日志：
   - 两端各发一条 PA 消息，消息都保留且顺序一致；
   - 同一 `commandId` 重复投递只执行一次；
   - 关闭电脑/断网后手机 PA 仍可本机对话，恢复后收敛；
   - 截图/抓包/数据库导出（脱敏后）。
5. 对应验收条目：PA-002、PA-008、PA-009、SYNC-005、SYNC-009、SYNC-013。

## 5. 下一步（解除阻塞后的建议顺序）

1. 先做 PA schema 与 sync `paOrderKey` 的映射（或统一到 HLC），再落 SYNC-104 的 PA 增量同步。
2. 把 `merge.mjs` 作为双端共同的合并规则，不在 Android 端另写一套排序。
3. Android 端只做「产生/接收 PAMessage + 调用统一命令层」，不复制桌面核心状态。
4. 用双端 fixture 先跑通收敛测试，再上真机；真机通过前保持 BLOCKED。

## 6. 明确没有做的事

- 没有修改 Android 代码；
- 没有伪造 Android 日志或双端截图；
- 没有把单机 `node --test` 结果当成 PA-108 的真机验收；
- 没有在本模块内复制 sync 的传输逻辑或另造一个假 Android 节点。
