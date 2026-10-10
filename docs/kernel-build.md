# 内核载荷构建

`app/src/main/assets/core/` 下的运行时载荷不是纯静态文件：其中可执行文件 `zcode` 是
「Android aarch64 的 node 基础镜像 + 内核 bundle 注入」的产物。内核源码变更后必须重编，
否则应用跑的还是旧内核。

## 载荷构成

| 文件 | 角色 | 是否随源码变更 |
| --- | --- | --- |
| `core/zcode` | SEA 可执行文件，应用以 `zcode app-server --stdio` 启动 | 是，需重编 |
| `core/zcode.cjs` | 内核 bundle，也用于远程 agent 场景 | 是，需重编 |
| `core/node` | SEA 基础镜像，Android aarch64 node v24.18.0 | 否，固定跟踪 |
| `core/lib/*.so` | 上述 node 的共享库，运行时经 `LD_LIBRARY_PATH` 加载 | 否，固定跟踪 |
| `core/rg` | 原生搜索工具 | 否，固定跟踪 |
| `core/provider/zcode-builtin.json` | 内核内置 provider 清单 | 否，固定跟踪 |
| `core/payload.txt` | 载荷清单，记录各载荷文件的字节数与 sha256 | 是，随重建生成 |

## 重建流程

一条命令：

```bash
tools/build-core-payload.sh
```

它按顺序做三件事，全部在 `kernel/zcode` 内完成：

1. `pnpm install --frozen-lockfile` 安装内核依赖。
2. 构建 workspace 依赖包，再用 esbuild 把 CLI 打成单文件 `apps/zcode-cli/packages/cli/dist/zcode.cjs`。
3. 调内核自己的 `scripts/build-sea.mjs`，把 bundle 与内嵌资源注入 node 基础镜像：
   `--target linux-arm64 --node-binary linux-arm64=app/src/main/assets/core/node`，
   产物回填到 `assets/core/zcode` 与 `assets/core/zcode.cjs`。

可选环境变量：`SKIP_INSTALL=1` 跳过依赖安装，`DRY_RUN=1` 只打印将执行的命令，
`CODA_NODE_BASE` 指定其他基础镜像，`CODA_SEA_TARGET` 改目标名。

回填时会一并写出 `assets/core/payload.txt`。应用解包后按这份清单核对字节数与 sha256，清单一变
就重新解包，因此换了载荷不会继续沿用上一版解出来的内核二进制，半截文件也会在启动前被发现。
清单本身是构建产物，不入库。

## 为什么目标名是 linux-arm64

内核 `scripts/sea-targets.mjs` 的目标白名单只含桌面平台，不含 android。Android aarch64 与
linux-arm64 同为 arm64 ELF，差异只在运行时识别：内核已打补丁，`sea-runtime-tools.ts` 的
平台白名单加入 `"android"`，设备上按 `process.platform` 正常运行。基础镜像则通过
`--node-binary` 直接替换成 Android 的 node，因此产物是 bionic 可执行的。

## 不重建基础镜像的原因

Android 的 node 不来自 nodejs.org 官方发行（官方不提供 Android 目标），是独立准备好的
bionic 构建，连同 `lib/*.so` 一起固定在仓库中。它们与内核源码无关，重建无收益，反而会引入
不可复现的下载源。内核侧的变更只影响 bundle 与 SEA，二者由上面的脚本重编。

## CI 中的位置

两条工作流都先跑本脚本再打包，顺序不能颠倒：

- `.github/workflows/release.yml`：打 `v*` Tag 时重建载荷，然后 `assembleRelease`，产出正式发行版本。
- `.github/workflows/dev-prerelease.yml`：`dev` 分支变更时重建载荷，然后 `assembleDebug`，
  更新那一个固定的预发行版本 `dev-latest`。
