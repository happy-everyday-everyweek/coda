# Android 受认证宿主桥（AGT-130 第一版）

> 目标：把 Android Coda 已有的 Coding Agent 能力（`CoreRuntime` / `ZController`）作为
> 受认证的移动节点接入桌面核心，而不是在桌面端重造一套手机 Agent。
> 当前状态：**已实现并通过 JVM 单测 + 假节点协议测试；尚未在真机上验收**。

## 1. 传输与认证

- TCP + NDJSON：一行一个 JSON-RPC 2.0 风格对象。
- 监听：`0.0.0.0:17654`（可在设置页查看；端口冲突时启动失败并显示原因）。
- 认证：每条连接的首帧必须是 `node.hello`，`params.token` 与 App 私有
  SharedPreferences 中的令牌做常量时间比较；未认证连接只能调用 `hello`。
- 令牌：24 字节 `SecureRandom`，Base64URL；设置页可查看/复制/重置。
- 安全边界：令牌等同密码；不开放终端、附件、成果读取；公共 Wi-Fi 不应开启。

## 2. 方法

| 方法 | 参数 | 返回 |
|---|---|---|
| `node.hello` | `protocolVersion`, `token`, `clientName` | `protocolVersion`, `node`, `capabilities`, `sessions` |
| `node.snapshot` | — | `{ sessions: [...] }` |
| `node.session.new` | `workspace?` | `{ ok, sessionId }` |
| `node.session.open` | `sessionId` | `{ ok }` |
| `node.turn.send` | `sessionId`, `text` | `{ ok }`（流式内容走事件） |
| `node.turn.stop` | `sessionId` | `{ ok }` |
| `node.interaction.answer` | `requestId`, `allow` | `{ ok }` |
| `node.ping` | — | `{ pong: true }` |

事件（服务端主动推送）：

```json
{"jsonrpc":"2.0","method":"node.event","params":{"type":"message.delta","sessionId":"s1","messageId":"m1","text":"...","ts":1730000000000}}
```

事件类型：`state.changed`、`message.delta`、`reasoning.delta`、`turn.finished`、
`session.opened`、`interaction.request`、`notice`、`bridge.reset`。

## 3. 握手示例

```json
{"jsonrpc":"2.0","id":1,"method":"node.hello","params":{"protocolVersion":1,"token":"<设置页令牌>","clientName":"coda-desktop"}}
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"node":{"id":"...","name":"Coda Android","appVersion":"0.8.4-core","androidSdk":34,"model":"..."},"capabilities":{"sessions":true,"turns":true,"streaming":true,"approvals":true,"questions":true,"terminal":false,"attachments":false,"artifacts":false},"sessions":[]}}
```

## 4. 代码位置

- Android：`app/src/main/java/com/coda/mobileui/core/NodeBridge.kt`、`NodeBridgeService.kt`、
  `NodeBridgeProtocol.kt`；设置页「实验性功能 → 节点桥」。
- 桌面客户端：`platform/sync/android-node.mjs`（`AndroidNodeClient`）。
- Android 客户端（反方向：本机去连别的节点）：`core/NodeBridgeClient.kt`、
  `core/RemoteNodeSync.kt`；会话页 `RemoteSessionActivity.kt`。
- 测试：`app/src/test/.../NodeBridgeProtocolTest.kt`、`NodeBridgeClientTest.kt`、
  `DrawerOrderingTest.kt`、`platform/test/sync/android-node.test.mjs`。

## 5. 反方向：Android 连接其它节点

手机既能当被控节点，也能当控制端：

1. 输入框上方「运行地点 → 添加设备」填入名称、地址、端口、令牌，保存后立即握手，
   拉取对方 `node.snapshot` 与各会话正文，写入 `DeviceStore` 缓存。
2. 选中远端设备后发送：打开 `RemoteSessionActivity`，在对方节点上 `node.session.new`
   （未选工作区时用对方默认值）并 `node.turn.send`；流式 `message.delta` 直接回显，
   `turn.finished` 后重取一次权威消息。可停止、可应答审批（`interaction.request`）。
3. 抽屉里的远端缓存会话不再只读：点开同样进入 `RemoteSessionActivity`，
   在线时恢复真实会话并继续对话；连不上时回退为缓存只读展示。
4. 排序：本地与远端会话平铺在同一抽屉，按状态分层（审批 > 等待 > 运行 > 未读 > 空闲），
   同层内按最近活跃度加权；同设备同工作区最多 3 条相邻，不跨层聚合。

## 6. 构建与验证记录

- 2026-11-08：`Build` 工作流在 `feat/desktop-agent-platform` 全绿（compile + JVM 单测 + platform 契约测试），
  APK 由 `workflow_dispatch -f build_apk=true` 产出：run `42448322414`，artifact
  `coda-debug-42448322414-3e4c91830af92675d697b1f32cb8fa625645c53a`（约 218 MB，debug 签名）。
- 后续 APK 构建会自动发布到预发布 `android-latest`（资产 `app-debug.apk`），
  国内可直接用镜像下载：`https://ghfast.top/https://github.com/happy-everyday-everyweek/coda/releases/download/android-latest/app-debug.apk`。
- JVM 单测覆盖：协议解析、抽屉排序（分层/同层聚类上限 3）、节点客户端（握手、快照、正文、
  远端建会话/发送/停止/审批、流式事件、密钥错误）。
- 尚未在真机上安装该 APK 验收。

## 7. 仍未完成（不能声称 AGT-130 / SYNC-109 完成）

1. 真机验证：前台服务在后台/锁屏/杀进程后的行为、通知权限、Keystore/StrongBox。
2. 设备身份与配对：当前是预共享令牌，不是 ed25519 设备身份 + 二维码配对；
   应复用 `platform/sync/identity.mjs` 的配对/撤销模型。
3. 端到端：桌面核心 → 节点桥 → 内核 → 事件回传的完整 PA 单会话（SYNC-109 / PA-008）；
   Android → Android 的远端会话已实现但同样缺真机双端验收。
4. 断连重连、离线队列、多客户端并发与限流。

在这些完成并通过真机/双端测试前，`SYNC-109` 保持 `BLOCKED`，`AGT-130` 只能是
`IN PROGRESS`，不得写「Android 集成完成」。
