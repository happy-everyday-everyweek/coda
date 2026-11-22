# 内置 Agent 内核源码

Coda 内置的 Agent 内核源码，与 `app/src/main/assets/core/` 的运行时载荷对应

## 来源

- 上游 zCode 开源项目，tag `v3.14.3`，commit `29628c9`
- 许可 Apache-2.0，见 `LICENSE`、`NOTICE.md`

## 改动

相对上游基线 `6df7a56`，`kernel/zcode` 的实际改动面是 **53 个文件、+4361/−484**（`git diff 6df7a56..HEAD --stat -- kernel/zcode`）。Apache-2.0 §4b 的修改声明以这个范围为准，不是下面这几行摘要。

功能性改动（按提交）：

- `sea-runtime-tools.ts` — SEA 运行目标把 `android` **归一为 `linux`**（不是「白名单加入 android」），修掉启动即退出的 target mismatch（a756a04）
- 安卓 bionic 兼容修复移入 SEA 构建流程，auxv 取值与真机验证版对齐（22eca21 / b35c0f5）
- 终端改为消费拓展声明，移除环境自动探测；`bash-shell-provider.ts` 改动 427 行（6c94df6 / c7cac3d）
- 会话改绑工作区：新增 `session/rebindWorkspace` 协议方法与存储原语，任务索引支持重新加键（ad02ff3 / d083acd / d8d87e8）
- 常驻池补只读的常驻事实查询（fb0343f）
- `session/read` 支持 `modelAvailability=all`，返回全量模型清单（8ca6748）
- 系统提示词改为可编辑模板，AGENTS.md 正名为指令文件；指令发现范围扩展（3fff855 / 932fbe3）
- 品牌改写 — 系统提示词与工具描述里的 `ZCode` 改为 `Coda`。已覆盖 workflow scheduler/expert 提示词、`/init` 内置命令提示词、read 工具的视频限制说明、plan-mode 提示词与 slash 命令帮助里的凭据库描述。**源码改完必须重建 `assets/core/zcode.cjs` 才会生效**（`tools/build-core-payload.sh`），bundle 里仍是旧文本时以 bundle 为准。

## 载荷

- `assets/core/zcode.cjs` — 内核 bundle
- `assets/core/zcode` — SEA 可执行文件
- `assets/core/payload.txt` — 载荷清单，记录各载荷文件的字节数与 sha256
- `assets/core/provider/zcode-builtin.json` — 内置 provider 清单

## 构建

- 构建前先重建内核二进制载荷：`tools/build-core-payload.sh`；安卓 bionic 兼容修复由内核自己的 SEA 构建写入，细节见 `docs/kernel-build.md`
