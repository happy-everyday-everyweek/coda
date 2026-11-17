# PR #13 阶段性代码与证据审查

> 审查对象：PR #13，head `c72b18ec3213ee3543ac3670b931d17d55c564cf`。  
> 性质：第二阶段规划前的阶段性静态审查，不是完整安全审计，不替代逐行 code review。  
> 处置：问题在第二阶段继续修复，**PR 保持开放，不合并**。

## 1. 已核对的正向事实

1. PR 的 `pull_request` 工作流 Platform、Agent Live Turns、Build、GPUI Software Render 均为 workflow success。
2. GPUI Software Render 的 `gui` job 在检查运行中为 failure，但 workflow 仍成功；配置为非阻塞检查。因此：
   - 可以说明软件渲染 CI 作为非阻塞诊断存在；
   - 不能表述为 GPUI 产品功能、性能或真实桌面验收通过。
3. 七个 Agent 的本地网关回合是“真实 CLI + mock LLM”证据，支持协议、SSE、进程与退出路径；不是实际服务商、模型质量、工具循环或订阅验收。
4. 核心、PA、同步、TUI/Web、Android 双向节点桥已有实质代码与测试，不应推倒重做；Rust 核心迁移应复用其契约和 golden fixtures。

## 2. P0：跨端命令幂等不能跨 Android 进程恢复

**位置**：`app/src/main/java/com/coda/mobileui/core/CommandReceipts.kt`。

实现和注释明确：收据只在内存，128 条 LRU；进程死亡或淘汰后相同 command ID 会成为新命令。

**影响**：断连重试、App 被杀重启、客户端超时重发可能重复创建会话、发送消息、停止或审批。它不满足规格 11.5 的持久收据与崩溃恢复，不能仅靠 JVM 进程内测试通过。

**要求**：S2-0401 改为外部 I/O 前持久化、重启恢复、上游状态核对、结果未知和合理的长期去重；补充真实杀进程/重投测试。

## 3. P0：Android 正式桥仍是明文 TCP + 对称令牌

**位置**：`NodeBridge.kt`、`NodeCredentials.kt`、`ANDROID-NODE-BRIDGE.md`。

- 监听 `0.0.0.0:17654`。
- 每设备对称 token、一次性数字配对码是过渡能力，不是非对称设备身份和端到端加密。
- 凭据尚不等同 Keystore 私钥保护。
- 真机后台/锁屏/杀进程未验收。

**影响**：同一 LAN 的嗅探/伪造/重放风险、设备撤销与密钥保护不足；不能宣称规格的可信配对和正式跨端安全已完成。

**要求**：S2-0402~0407 将 Android 接入正式设备身份、加密、scope、限流和真机生命周期验收；保留旧桥仅用于迁移，不作为最终协议。

## 4. P0：PA 存在三套数据结构与排序模型

**位置**：`platform/pa/README.md`、`platform/pa/BLOCKED.md`。

- PA 模块：`paId/sourceNode/content/hlc`。
- core domain：`profileId/sourceNodeId/text/seq`。
- sync：`author/text/lamport`。

**影响**：本地 PA 合并函数正确不等于跨端 PA 可互操作；目标、记忆若在三套模型上继续开发会扩大迁移成本与数据分叉。

**要求**：S2-01 先建立唯一 canonical contract、跨 Node/Rust/Kotlin golden fixtures、游标与迁移；S2-05 之后再接目标和记忆的跨端行为。

## 5. P0：第一阶段产品化缺口仍在

**位置**：`KNOWN-LIMITATIONS.md`、当前 TODO、安装/后台相关 ADR。

- 没有经三平台真实验收的安装器、登录启动、后台核心和升级/回滚。
- WSL 未实现。
- GPUI 产品界面不能用软件渲染冒烟替代完整交互/性能/安装验收。
- Android 真机双端未验收。

**影响**：当前是大量可测试参考实现，不是第一阶段完整可发布产品。

**要求**：按用户决策由第二阶段接管，但设独立 Gate，不能用第二阶段的目标/记忆/SSH 等新增功能抵消这些基础缺口。

## 6. P1：任务状态与验收状态混用

TODO 中不少条目在源码与局部测试存在时标 DONE，但 DoD 又要求 CI、真机、真实 Agent 与证据；限制文档同时说明这些未验收。

**影响**：开发 Agent 容易将“实现存在”误读为“发布完成”，追溯文档自相矛盾。

**要求**：S2-0003 分离 `implemented/tested/verified/released`；DONE 只在规定验收和证据完成后使用。

## 7. 真实 Agent 全量验收是第二阶段硬门槛

当前用户明确要求全部首版 Agent 做真实服务验收。故：

- mock LLM 网关通过不能关闭真实服务 Gate；
- TRAE 商业账号与 WorkBuddy 无本地可编程入口均为发布阻塞；
- 若无法解决，必须向用户请求范围变更，不得自行降级为“已尽力/实验完成”。

## 8. 第二阶段处置索引

| 审查项 | 任务 | Gate |
|---|---|---|
| Android 内存收据 | S2-0401 | S2-G4/G5 |
| 明文对称令牌桥 | S2-0402~0407 | S2-G4 |
| 三套 PA schema | S2-0101~0106 | S2-G1 |
| 安装/后台/WSL/GUI | S2-02/S2-03 | S2-G2/G3/G8 |
| 真机与 PA 连续性 | S2-04/S2-05 | S2-G4/G5 |
| 状态语义混用 | S2-0003 | S2-G0 |
| 全量 Agent 真实服务 | S2-11 | S2-G9 |

第二阶段详见 [SPEC-S2.md](./SPEC-S2.md)、[ACCEPTANCE-S2.md](./ACCEPTANCE-S2.md)、[TODO-S2.md](./TODO-S2.md)。