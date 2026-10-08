# 内置 Agent 内核源码（zcode-kernel）

本目录存放 Coda 内置 Agent 内核的源码，与 `app/src/main/assets/core/` 下的运行时载荷一一对应，纳入本仓库跟踪，便于审计与重新打包。

## 来源与基线

- 上游项目：zCode 开源项目，版本 tag `v3.14.3`，commit `29628c9acdb81b703bbd4080c207a0e7ce5e276e`。
- 许可：Apache-2.0（见本目录 `LICENSE`、`NOTICE.md`）。
- 本目录由该 tag 的干净克隆复制而来，已排除 `node_modules/`、`dist/`、`build/`、`.git/` 等构建与缓存产物。

## 相对上游的改动

1. `apps/zcode-cli/packages/cli/src/sea-runtime-tools.ts`：SEA 运行时目标白名单加入 `"android"`。这是让内核能够在 Android aarch64 上解析运行时工具目标的必要改动，也是当前 `assets/core/zcode` 内嵌脚本相对上游的唯一差异（逐字节校验为 +11 字节）。
2. 品牌改写：把模型可见的系统提示词与工具描述中的 `ZCode` 改为 `Coda`，涉及 CLI prefix、Agent identity、Desktop context、Explore/General-purpose 子代理、system-reminder、会话引用与工具描述等位置。

## 与 assets/core 载荷的对应关系

| 载荷文件 | 来源 |
| --- | --- |
| `assets/core/zcode.cjs` | 内核 bundle（`pnpm run build:zcode` 产物中的 `zcode/agent/zcode.cjs`） |
| `assets/core/zcode` | SEA 可执行文件（node 基础镜像 + zcode.cjs 注入，`apps/zcode-cli` 的 `build:sea`） |
| `assets/core/provider/zcode-builtin.json` | 内核内置 provider 清单（与上游一致，未改动） |

## 重新打包

内核构建依赖 node/pnpm、仓库内 `node_modules` 与已生成的 `dist`。设备端构建流程记录在项目根 `docs/kernel-build.md`。
