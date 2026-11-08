# BLOCKED：SYNC-109 / SYNC-110

本文件记录**本机无法验证、因此没有实现**的两项同步任务。按 TODO §1.2，
状态为 `BLOCKED`，不是「部分完成」；没有用假实现冒充通过。

## SYNC-109 — Coda Android 节点受认证宿主桥与双向控制

**状态：BLOCKED**

**原因**

1. Android 宿主桥第一版已在仓库内实现（`app/.../NodeBridge.kt` +
   `platform/sync/android-node.mjs`，协议见 `docs/ANDROID-NODE-BRIDGE.md`），
   但没有真机/模拟器可启动 App、验证前台服务、认证与双向控制。
2. 需要真机上的 Android 前台服务、通知权限、Keystore/StrongBox 与进程存活策略；
   proot Ubuntu 模拟器不能代表这些行为。
3. 验收条目 AGT-CODA / PA-008 需要真实 Android 节点参与，本机没有对端可测。

**已经具备、可供 Android 节点复用的部分（均已测试）**

- `identity.mjs`：ed25519 设备身份、deviceId/指纹、二维码/短码配对、设备撤销；
- `crypto.mjs`：X25519 + HKDF + ChaCha20-Poly1305 加密帧、序号防重放、版本协商；
- `transport.mjs`：局域网 TCP 通道（Android 端可复用 NDJSON 分帧约定）；
- `sync-service.mjs`：快照/增量、命令队列、审批竞争、成果按需传输；
- `android-node.mjs`：Android 宿主桥的桌面客户端（令牌认证、快照、发送、停止、审批、事件流）。

**明确没有做的事**

- 没有伪造 Android 桥实现、没有 stub 一个「假 Coda 节点」并标为通过；
- 没有把桌面端进程当作 Android 节点。

**下一步（需要 Lead/Android 侧）**

1. 取得 Coda Android 节点的宿主桥接口与版本（TODO 要求记录上游协议、版本、许可证）。
2. 在真机验证：受认证宿主、后台存活、Keystore 私钥不可导出、断连重连。
3. 用本模块的配对/加密/游标接口接入，补 AGT-CODA、PA-008 证据。

## SYNC-110 — 可选加密中继 / 内网穿透

**状态：BLOCKED（未实现；按 SPEC 11.2 保持非硬依赖）**

**原因**

1. 没有可用的中继服务、域名、证书或 NAT 穿透环境；本机不联网，无法验证
   「中继只转发端到端密文、无法读取业务明文」（SEC-007）。
2. 任何中继实现都必须配合部署与抓包证据；在无网络环境里写一个本地假中继证明不了
   穿透与不可读性，反而会制造「已实现」的假象。
3. 规格要求中继不可用时核心仍保持本地与局域网功能（SYNC-015），因此本层选择
   **不引入中继依赖**。

**当前行为（已验证）**

- 没有中继也能完成配对、加密同步、命令与成果传输（见 `test/sync/convergence.test.mjs`、
  `no-center.test.mjs`）；
- `discovery.mjs` 将非局域网地址标为 `wan` 路由，但没有内置任何官方中继地址；
- 若未来接入中继，唯一允许的载荷是 `crypto.mjs` 产生的密文帧（帧头仅含版本/序号）。

**下一步（需要 Lead）**

1. 选定中继方案与部署（自建/第三方），明确许可证与数据保留策略。
2. 用两个不同 NAT 后的节点做抓包验证：中继侧只能看到密文、无业务字段。
3. 补 SEC-007 / SYNC-015 证据，并把「中继不可用」降级路径写入运维文档。

## 相关但未阻塞的条目

- SYNC-111/112（无中心拓扑、成果按需传输）已在本机用内存/回环通道验证；
- 真实「断公网」的物理隔离证据仍需要两台同 LAN 设备，见 `test/sync/no-center.test.mjs`
  的说明与 `platform/sync/perf.mjs` 的输出。
