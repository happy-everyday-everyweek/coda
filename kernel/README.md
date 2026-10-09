# 内置 Agent 内核源码

Coda 内置的 Agent 内核源码，与 `app/src/main/assets/core/` 的运行时载荷对应

## 来源

- 上游 zCode 开源项目，tag `v3.14.3`，commit `29628c9`
- 许可 Apache-2.0，见 `LICENSE`、`NOTICE.md`

## 改动

- `apps/zcode-cli/packages/cli/src/sea-runtime-tools.ts` — SEA 运行时目标白名单加入 `android`
- 品牌改写 — 系统提示词与工具描述里的 `ZCode` 改为 `Coda`

## 载荷

- `assets/core/zcode.cjs` — 内核 bundle
- `assets/core/zcode` — SEA 可执行文件
- `assets/core/provider/zcode-builtin.json` — 内置 provider 清单

## 构建

- 构建前先重建内核二进制载荷：`tools/build-core-payload.sh`，细节见 `docs/kernel-build.md`
