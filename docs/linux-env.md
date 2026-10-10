# 内置 Linux 终端环境设计

## 1. 目标与约束

目标是让 Coda 在设备内自带一套完整 Linux 用户空间：离线可起 shell，能跑 git、gh、curl、python3 这类常用工具，且沿用现有"拓展 + 终端分类"的契约，不推倒重来。

几条硬约束决定了实现手段：

应用进程拿不到 `/dev/kvm`，跑不了 Firecracker 型微虚拟机；进程级快照（CRIU）需要 root。所以"亚毫秒启动"这条路上的实现手段用不上，能借鉴的只是它的分层思路：把一次性开销压在装配阶段，运行期只做入口。

内核是 Node 进程（`zcode app-server --stdio`），终端由它用 node-pty 起。终端进程的身份、可执行权限、环境变量都由内核决定，改动要落在内核与宿主两侧。

`targetSdk=28`，应用数据目录里的 ELF 可以直接 `execve`（现在就已经在 exec `files/core/zcode`），这是选择"proot + 文件系统内 rootfs"的前提。

## 2. 参照实现

本机装着的 Operit 跑着同一类环境，直接读了它的实现，结论如下。

引导层只有一个目录 `files/usr/bin`，里面真正独立的二进制只有四个：`proot`、`busybox`、`bash`、`file`，外加一个 2 字节的 `sudo` 占位；其余约 37 个名字全是 busybox 的硬链接。这些二进制本身不占引导目录的空间，它们是指向 APK 原生库目录的符号链接（`/data/app/.../lib/arm64/liboperit_proot.so` 等），也就是说 Operit 用 jniLibs 携带可执行文件，`usr/bin` 只是给 PATH 用的软链层。

rootfs 是一个 proot-distro 格式的预置镜像 `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz`，64,133,552 字节，解压到 `files/usr/var/lib/proot-distro/installed-rootfs/ubuntu`，装完即删包。

引导脚本 `common.sh` 的做法值得照抄的有四处。安装用目录锁加 pid 文件互斥，最多等 120 秒，解压到临时目录再整体 `mv` 就位，最后写一个 `.installed_ok` 标记。运行前先探测 proot 能否启动、再探测 `--link2symlink` 是否支持，两次探测都通过才开始拼参数。绑定用"逐项探测再追加"的策略，每个待绑路径先拿当前的绑定组合试跑一次 proot，成功才把这一项加进去，失败就静默跳过，这样宿主上某些路径缺失不会导致整个环境起不来。proot 的参数为 `-0 -r <rootfs> --link2symlink -w /root`，认证与就绪状态靠往 stdout 打 `LOGIN_SUCCESSFUL` 与 `TERMINAL_READY` 两个标记回传给 UI。

另外它用 `setup_fake_sysdata.sh` 在 rootfs 内伪造 `/proc` 里 Android 受限的条目（`stat`、`loadavg`、`uptime`、`version`、`vmstat`、`sys/kernel/cap_last_cap`、`sys/fs/inotify/max_user_watches`），启动时按"宿主上不存在才绑假文件"的规则补上。

## 3. 选型结论

### 3.1 rootfs 选 Alpine minirootfs

对照 Operit 的 Ubuntu Noble（64 MB 压缩包，只能运行期下载），Alpine 的决定性优势是体积。

`alpine-minirootfs-3.24.2-aarch64.tar.gz` 为 3.8 MB，小到可以直接进 APK 资源，实现真正的"内置、离线可用"，这是 Ubuntu 路线做不到的。

包可用性已核实：main 仓库的 `APKINDEX` 里有 apk-tools、bash、ca-certificates、coreutils、curl、git、nano、openssh、python3、tar；community 仓库有 github-cli。也就是说目标工具全都能用 apk 装。

代价是 musl libc，glibc-only 的预编译二进制跑不了。gh 是 Go 静态二进制，不受影响；git 走 apk 装。这个代价可以接受。

### 3.2 执行层选 PRoot

无需 root，Termux 与 Operit 两条实机路线都验证过。引导集只有四个文件、合计约 314 KB，这是它相对任何虚拟机方案的核心优势。

### 3.3 引导集清单（已逐个核实）

| 文件 | 大小 | 来源 |
| --- | --- | --- |
| `proot` | 247,488 B | `packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb` |
| `loader` | 18,136 B | 同包的 `usr/libexec/proot/loader` |
| `libtalloc.so.2.5.0` | 33,592 B | `libtalloc_2.5.0_aarch64.deb` |
| `libandroid-shmem.so` | 14,432 B | `libandroid-shmem_0.7_aarch64.deb` |

三个 ELF 的解释器都是 `/system/bin/linker64`，能在 Android 上直接跑，不需要另带 bionic。三个 ELF 的 RUNPATH 都指向 `/data/data/com.termux/files/usr/lib`，要改成 `$ORIGIN/../lib`，复用之前处理 busybox 时已经跑通的那套 `patchelf --set-rpath` 流程。

proot 内置了两个默认 loader 路径（`/data/data/com.termux/files/usr/libexec/proot/loader` 与 `loader32`），同时支持 `PROOT_LOADER` 环境变量覆盖，所以 loader 放在哪里都行，用环境变量指过去即可。`loader32` 是 32 位用的，aarch64 单架构不需要，可以不带。

## 4. 分层架构

### 4.1 随包内置

引导集放 `assets/linux/bin/` 与 `assets/linux/lib/`，rootfs 归档在仓库里是 gzip 压缩的 `assets/linux/rootfs.tar.gz`，装配时释放到 `files/linux/`。已打出的 APK 里这一项的资产名是 `assets/linux/rootfs.tar`，内容也已经是不带 gzip 的 tar，说明打包链路改过它的名字与形态。装配因此按候选名逐个尝试，并读首两个字节判断是否还需要解压，两种形态都能用。

需要留意的一点：assets 里的文件在 APK 中默认是压缩的，安装后没有可执行位，必须靠释放后的 `chmodTree` 补上。Operit 用 jniLibs 就是为了省掉这一步。两条路都可行，先按 Coda 现有的一致性走 assets；如果实测出现权限或加载失败，再把 proot、loader 挪到 jniLibs 做成 `lib*.so`。

### 4.2 首次装配

rootfs 解压到 `files/linux/rootfs/`，照 Operit 的安装流程做：目录锁加 pid 互斥、解压到临时目录再整体 `mv`、写 `.installed` 标记。解压用 Android 自带的 `GZIPInputStream` 加一段 tar 解析即可，不引入依赖。装配只在首次发生，之后每次开终端都跳过。

解压后注入三样东西。`etc/resolv.conf` 写公共 DNS。`etc/apk/repositories` 指向国内镜像，供后续 `apk add`。`root/.bashrc` 与 `/etc/profile.d/` 里设好 PATH、TERM、PS1。再按 Operit 的做法预置 fake sysdata。

### 4.3 终端接入（内核改动）

现在的链路是 `resolveTerminalShell()` 取一个可执行文件，`spawnTerminalProcess()` 用 `nodePty.spawn(shell, [], opts)` 起它，args 恒为空。跑 proot 必须传参数，所以要在"可执行文件"之外再表达一段前缀参数。

Bash 工具侧同理。`bash-shell-provider.ts` 解析出 `{file, shell, loginShell}`，`createShellProviderCommand` 把它编成 `args: ["-c","-l",command]`、`file: provider.file`；进程适配器按这组参数 spawn。要让命令跑在 rootfs 里，argv 需要变成"proot 参数 + 进入 rootfs 后的解释器 + `-c command`"。

改动方案是给 provider 增加两个可选字段：`prefixArgs`（proot 的 `-0 -r ... --link2symlink -b ... -w /root`）与 `entryArgs`（rootfs 内的解释器与固定参数，例如 `/bin/bash -l`）。最终 argv 为 `prefixArgs + entryArgs + ["-c", command]`，登录模式由 `entryArgs` 里的 `-l` 表达。终端面板侧同样把这两个字段透传，`spawnTerminalProcess` 改为接受 args。

宿主把拓展声明的启动信息编成环境变量注入内核，与现有 `ZCODE_TERMINAL_*` 风格保持一致，例如 `ZCODE_TERMINAL_LAUNCH_EXEC`、`ZCODE_TERMINAL_LAUNCH_PREFIX`、`ZCODE_TERMINAL_LAUNCH_ENTRY`。

有三个实现细节必须按这里说的做，否则会踩坑。

proot 必须用清空 `LD_LIBRARY_PATH` 的方式启动。内核给终端环境带的 `LD_LIBRARY_PATH` 指向 `core/lib`（Node 运行时自己的库），继承下去会让动态加载器先去那里找，可能撞库。因为 proot 与两个依赖库的 RUNPATH 已改成 `$ORIGIN` 相对形式，清空之后仍能自洽解析。

`setResolvedShellLoginMode` 与 `applyResolvedShellCommand` 目前靠 `args[0] === "-c"` 判断"这是不是 shell provider 生成的命令"。插入 `prefixArgs` 后这个判定会失效，要改成按显式标志判断。

绑定列表用"逐项探测"生成，不要写死。宿主上 `/sdcard`、`/data/local/tmp` 这类路径在不同 Android 版本与权限状态下不一定存在，任何一项缺失都不该让终端起不来。

### 4.4 gh 认证

Coda 已有设备流 OAuth，令牌存在 `github_bridge` 偏好里。装配完成或令牌更新时，把令牌同步进 rootfs 形成 `root/.config/gh/hosts.yml`，或直接从 stdin 灌 `gh auth login --with-token`。后者更稳，不依赖文件格式。git 侧写 `root/.gitconfig`，凭据助手指向 `gh auth git-credential`，这样 `git push` 和 `gh` 共用同一份认证。

令牌轮换要接在 `CoreRuntime.start()` 现在注入 `ZCODE_TERMINAL_*` 的那一步旁边，保证每次拉起内核时 rootfs 内的凭据与 app 侧一致。

## 5. 精简策略

Alpine 基础镜像本身已经很小，可裁的部分是文档与初始化设施：`usr/share/man`、`usr/share/doc`、`usr/share/info`、`lib/firmware`、`etc/init.d`、`etc/periodic`。保留 `apk`、`busybox`、`bin/sh`，否则装不了包也起不了 shell。

若要预装工具再打成最终镜像，apk 缓存目录 `var/cache/apk` 要清掉。

## 6. 体积与耗时（实测）

引导集四个文件合计 313,648 B。最终镜像 `rootfs.tar.gz` 为 27,240,987 B（sha256 `57a8e58e…b9226708`），内含 1910 个成员：1015 个常规文件、141 个目录、754 个符号链接。

装配解压在首次启动完成，之后每次开终端是 `execve(proot)` 加 proot 自身的 ptrace 初始化。

## 7. 改动清单（已落地）

宿主侧（Kotlin）。新增 `app/src/main/java/com/coda/mobileui/core/LinuxEnv.kt`，负责装配与启动参数生成：释放引导集、解归档 rootfs、探测 proot 与 `--link2symlink`、逐项绑定宿主目录、写 `root/.config/gh/hosts.yml` 与 `root/.gitconfig`、输出注入内核的环境变量。`CoreRuntime.kt` 的注入段改为"优先内置 Linux 环境，装配或自检失败退回拓展声明的终端"。`app/src/main/assets/linux/` 放入 `bin/proot`、`bin/loader`、`lib/libtalloc.so.2`、`lib/libandroid-shmem.so`、`rootfs.tar.gz`、`version.txt`。原先的 busybox 终端拓展 `app/src/main/assets/extensions/coda.terminal` 已删除。

内核侧。`bash-shell-provider.ts` 增加启动器解析与 `prefixArgs`/`entryArgs`；`execution-command.ts` 把 argv 形状显式化成 `ResolvedShellLaunch`，`setResolvedShellLoginMode` 与 `applyResolvedShellCommand` 不再靠 `args[0] === "-c"` 反推；`node-execution-adapter-process.ts` 对启动器包裹的 shell 跳过终端初始化快照；`terminalService.ts` 的 `spawnTerminalProcess` 接受 args，并在容器化启动时清空 `LD_LIBRARY_PATH`。

## 8. 已定决策

rootfs 预装 git、gh、curl、openssh-client、ca-certificates、bash、tzdata、nano、less、xz，零网络依赖，首次装配不需要联网。

内置 Linux 环境直接替换 busybox 终端拓展，不并存。终端分类不再提供 busybox 终端，`assets/extensions/coda.terminal` 已删除；保留的拓展兜底分支只在装配或自检失败时生效。

## 9. 内核契约

宿主通过环境变量声明启动方式，与既有 `ZCODE_TERMINAL_*` 同风格：

| 变量 | 含义 |
| --- | --- |
| `ZCODE_TERMINAL_SHELL` | 启动器可执行文件路径（这里即 proot） |
| `ZCODE_TERMINAL_DIALECT` | `posix` |
| `ZCODE_TERMINAL_LOGIN` | `0` 或 `1`，声明是否用登录 shell |
| `ZCODE_TERMINAL_PATH` | 容器内的 PATH，供宿主侧拼 PATH |
| `ZCODE_TERMINAL_LAUNCH_EXEC` | 真正 exec 的文件，缺省退回 `ZCODE_TERMINAL_SHELL` |
| `ZCODE_TERMINAL_LAUNCH_PREFIX` | JSON 字符串数组，启动器参数 |
| `ZCODE_TERMINAL_LAUNCH_ENTRY` | JSON 字符串数组，启动器之后到命令之前的固定 argv |

Bash 工具的最终 argv 是 `LAUNCH_EXEC + LAUNCH_PREFIX + LAUNCH_ENTRY + ["-c"] + (登录时追加 ["-l"]) + command`；终端面板是 `LAUNCH_EXEC + LAUNCH_PREFIX + LAUNCH_ENTRY + ["-l"]`。两个数组解析失败（不是字符串数组）时按没声明处理，退回原来的"直接跑 shell"。

entry 固定为 `/usr/bin/env -i HOME=/root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 PATH=<guest path> /bin/bash`，容器内不继承宿主的环境变量。

启动 proot 前必须清掉 `LD_LIBRARY_PATH`（内核用它指向 `core/lib`），否则动态加载器会先去找宿主库。因为 proot 与两个依赖库的 RUNPATH 都改成了 `$ORIGIN` 相对形式，清空后仍能自洽解析。

还有一处旁路必须挡住：终端初始化快照（`shell-init-snapshot`）会直接 `execFile(shellPath, ["-c","-l",script])`，启动器形态下这会变成 `proot -c -l script`，所以对启动器包裹的 shell 一律跳过快照。

## 10. 镜像制作与验证记录

rootfs 用 `apk.static` 在宿主上直接铺：`apk.static --root <dir> --arch aarch64 --initdb --no-cache --repository <main> --repository <community> add git github-cli curl openssh-client ca-certificates bash tzdata nano less xz`，44 个包、安装体积 71.6 MiB，装成 git 2.54.0、gh 2.97.0、curl 8.22.0、openssh 10.3p1。

打包时的坑：制作终端本身跑在 proot 里，proot 的 `--link2symlink` 会把任何 `link()` 静默改写成符号链接并留下 `.l2s.*` 中间条目，直接 tar 会报"符号链接层级过深"。正确做法是不执行任何硬链接操作，把归档里的硬链接条目改为逐条复制内容——tzdata 的 254 个硬链接就是这么重建的（`usr/share/zoneinfo` 下 603 个文件全是真实文件）。日后在 rootfs 内装包、复制大目录时都要避开 `link()`。

验证用 chroot 做，不依赖 proot：把 entry 那串 argv 直接交给 `chroot <rootfs>`，实测 `git version 2.54.0`、`gh version 2.97.0` 均在 PATH 上，`TZ=Asia/Shanghai` 解析为 `CST+0800`，`$SHELL` 保持 `/bin/bash`。

遗留：`docs/kernel-build.md` 是否需要精简，属历史遗留。
