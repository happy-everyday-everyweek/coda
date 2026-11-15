# ADR-0003：Windows / macOS / Linux 用户级后台服务、登录启动、升级与回滚

- 状态：Accepted（文档决策已定；三平台实现与验收为 BLOCKED，见「影响」）
- 日期：2026-11-07
- 对应任务：DOC-104
- 对应验收：CORE-001、CORE-002、CORE-005、CORE-007、LIFE-001、LIFE-004、LIFE-006、LIFE-007、PLAT-001、PLAT-002、PLAT-003、QA-005
- 关联文档：[ADR-0001](./ADR-0001-shared-core-runtime.md)、[CONTRACT.md](../../CONTRACT.md)、[KNOWN-LIMITATIONS](../KNOWN-LIMITATIONS.md)

## 背景

`SPEC.md` 6.2 要求：关闭窗口、退出 TUI、关闭浏览器标签或退出 GUI 都不得停止核心、Agent 会话、终端或同步；三平台都必须提供**用户级**后台服务与自动启动；核心异常退出要有界退避重启，不得无限快速重启，也不得盲目重放副作用不明的命令；升级不得无声终止正在运行的 Agent。
`SPEC.md` 6.3 要求卸载 UI 不删数据，卸载核心前列出运行中任务并要求明确确认。
`CONTRACT.md` 第 1 节规定 `core/` 是唯一权威状态源，第 8 节规定 B/S 之外的核心入口为独立进程（`core/index.mjs`）。

现状证据（2026-11-07 文件快照）：`platform/` 已有 `core/` 模块（日志、收据、领域对象、Agent 进程监督等），但**没有核心服务入口、单实例锁或服务注册代码**；`core/supervisor.mjs` 监督的是 Agent 子进程，不是核心自身的生命周期。本机为 Android 上的 Ubuntu proot，没有 Windows/macOS 桌面，也没有可用沙箱后端，因此 `PLAT-001..003`、`LIFE-004`、`LIFE-006` 无任何可执行证据，状态为 `BLOCKED`（环境不可用），不得标记完成。

## 决策

### 1. 服务形态：用户级、每用户一个核心

1. 三平台一律注册**用户级**（非系统级/非管理员）后台服务，随用户登录启动，随用户注销退出；不安装守护整个系统的 root/daemon 服务。
2. 核心是独立进程，客户端（TUI/GUI/Web UI）只连接不托管；UI 进程退出不影响核心（CORE-002、CORE-007）。
3. 单实例：启动时先取用户数据目录下的独占锁（`core.lock`，含 pid + 启动时间 + 版本），再绑定本地 IPC 端点（Unix domain socket / Windows 命名管道）。已有实例在服务时，第二实例**降级为客户端连接**并退出守护逻辑，绝不创建第二套数据库（CORE-001）。
4. 允许的例外：`--foreground` 调试模式仍走同一把锁，避免调试实例与正式实例并存。

### 2. 平台映射（实现约定，均未验证）

| 平台 | 注册位置 | 登录启动 | 状态/日志 |
|---|---|---|---|
| Windows | 计划任务（Task Scheduler，当前用户，登录时触发，`LeastPrivilege`）或每用户服务包装器 | 任务触发器 `At log on` | 任务历史 + 核心自有滚动日志 |
| macOS | `~/Library/LaunchAgents/<id>.plist`（LaunchAgent，`RunAtLoad`） | LaunchAgent 自动加载 | `launchctl print gui/<uid>/<id>` + 核心日志 |
| Linux | `systemd --user` unit（`default.target`），无 systemd 时退化为 XDG autostart + 监督脚本 | `systemctl --user enable` | `systemctl --user status` + journal/核心日志 |

1. 数据目录（不随 UI 卸载删除）：Windows `%LOCALAPPDATA%\CodaPlatform`；macOS `~/Library/Application Support/CodaPlatform`；Linux `$XDG_DATA_HOME/coda-platform`（默认 `~/.local/share/coda-platform`）。
2. 运行时端点：Unix 上 `<dataDir>/core.sock`（权限 0600、仅属主可读写）；Windows 上 `\\.\pipe\coda-platform-<userSid>`，ACL 仅当前用户。**不默认监听 TCP 端口**。
3. 登录启动注册失败（企业策略、无 systemd、LaunchAgents 被禁用）时，安装器必须显示明确失败原因和修复命令，而不是静默降级为「手动启动」（LIFE-004）。

### 3. 崩溃监督

1. 监督者（服务管理器或核心自带 supervisor 模式）按有界指数退避重启：`1s → 2s → 4s → … → 上限 60s`；连续失败达到阈值后进入 `crash-loop` 状态、上报诊断并**停止重启**（CORE-005），不做 0 延迟重启。
2. 每次重启记录退出码、信号、最后一条事件序号和原因分类（SPEC 9.10 的错误分类）。
3. 重启后不自动重放 `unknown` 命令；未确认回合标记为中断/未知，等用户核对（REL-001、REL-003）。
4. 系统休眠/关机/被杀进程属于真实中断：重启后状态必须是「中断/未知 + 最后已知时间」，不得声称后台仍在运行（SPEC 6.2、LIFE-005）。

### 4. 升级与回滚

1. **版本化安装目录**：`versions/<semver>/` + 指针文件（Windows/macOS 用 `current.json`，Linux 用 `current` 符号链接），新版本解包到新目录，写指针是唯一的切换点。
2. **安全点升级**：升级前查询活跃回合、终端与传输任务（`core.diagnostics`）。有活跃任务时：
   - 默认等待安全点（回合结束/空闲）并在 UI 提示「升级将在当前任务结束后进行」；
   - 用户可显式选择「立即升级」，此时必须提示「正在运行的任务将中断并标记为未知」；
   - 两种情况都不得静默 kill（LIFE-006、QA-005）。
3. **数据迁移**：schema 迁移在核心启动时执行，迁移前自动备份旧库（`db/backup-<schemaVersion>-<ts>.sqlite`）；迁移失败则**不启动写入**、保留旧库并给出可回退路径（CORE-004、REL-004）。
4. **回滚**：保留上一版本目录与旧库备份。回滚 = 切回旧指针 + 使用迁移前备份；若新版本已写入不向后兼容的数据，回滚器必须拒绝并说明（不得「尽力降级」破坏数据）。回滚后核心版本与 UI 版本不匹配时按 `PROTOCOL_VERSION` 协商给出明确错误（CORE-006）。
5. 升级过程本身要有退出码与日志，失败时保持旧版本可服务（不能出现「两个版本都没了」）。

### 5. 卸载边界

1. 卸载某个 UI：只删该 UI 的安装目录与注册项，核心、其他 UI、会话、附件、终端回放全部保留（LIFE-007）。
2. 卸载核心：先列出运行中的回合与终端，默认「保留数据」，需明确确认才停服务并删除数据目录（LIFE-008）。
3. 「删除会话」「撤销设备」「停止核心」「停止某个 Agent 回合」是四个不同操作，文案与确认流程不得互相顶替（SPEC 6.3、SESS-003、SEC-008）。

## 影响

- 任务 `CORE-107`、`INST-102`、`INST-103`、`INST-104`、`INST-105` 的全部验收项依赖真实桌面环境；当前环境为 Android proot，只能产出设计文档与（未来的）单元级模拟，**三平台证据为 BLOCKED**。
- 实现必须把「服务注册」与「核心逻辑」解耦：服务注册失败不能让核心无法前台运行，反之核心崩溃不能拖垮 UI 进程。
- 与 ADR-0004 的联动：Web UI 的本地访问依赖 `core.sock`/命名管道，不依赖额外 TCP 端口；远程访问另走配对加密通道。
- 与 SPEC 15.2 的联动：核心空闲资源预算（PERF-004）在服务形态下才有意义，必须在三平台实测，当前 `NOT RUN`。

## 备选方案

1. **系统级服务（Windows 服务 / systemd system / LaunchDaemon）**：能开机即启动，但需要管理员权限，且与「单用户、多可信设备」模型和卸载体验冲突；放弃。
2. **GUI 进程内嵌核心，关闭窗口即退出**：实现最快，但直接违反 SPEC 5.4 与 6.2（关闭 UI 后任务继续）和 CORE-002；放弃。
3. **无监督者，靠用户手动重启**：无法满足 CORE-005 的有界退避与退出原因记录；放弃。
4. **原地覆盖升级（无版本目录）**：无法回滚，升级失败即不可用；放弃。
