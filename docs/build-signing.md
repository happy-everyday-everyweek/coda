# 出包签名

## 为什么固定签名

CI 每次都在全新 runner 上跑，Gradle 会当场生成一把临时 debug keystore。签名一次一换，设备上装了上一版就装不上下一版，只能卸载重装；本地出的包与 CI 出的包签名也不一致。所以本仓库自带一把固定密钥，本机与 CI 共用。

密钥文件 `keystore/coda-debug.jks`（别名 `androiddebugkey`，口令 `android`，有效期到 2056 年），证书 SHA-256 摘要：

```
05011c837e7fd75ae390c9f5d627f4618324676896ff0fbe04146607db841c35
```

它是调试用途的密钥，仅为让包与包之间签名一致，不用于对外发行的安全承诺。

## 各处的接线

`app/build.gradle.kts` 的 `signingConfigs` 在密钥文件存在时把 `debug` 配置指向它；`release` 在没有外部密钥（`CODA_KEYSTORE_PATH` 等环境变量）时复用它，所以本地与 CI 出的包签名相同。

可选的正式发行密钥走环境变量：CI 从 secrets 的 `CODA_KEYSTORE_BASE64`、`CODA_KEYSTORE_PASSWORD`、`CODA_KEY_ALIAS`、`CODA_KEY_PASSWORD` 还原 `CODA_KEYSTORE_PATH`。这套配置只在仓库 secrets 里存在时才生效，没有时自动回落到仓库内固定密钥。换用外部密钥会让摘要变化，届时需要同步改工作流里的期望值。

## 签名方案

`signingConfigs` 里显式打开了 v1、v2、v3 三个方案（`enableV1Signing`、`enableV2Signing`、`enableV3Signing`）。

原因：`minSdk 26` 时 AGP 默认只写 v2 签名，不生成 v1（JAR 签名，即 `META-INF/*.RSA`）。这样的包在 Android 8 以上能正常安装，但只认 v1 的检查工具（`jarsigner -verify`、部分 APK 签名查看器）会把它判成“未签名”。三个方案全开后，任何工具看到的签名者都是上面那把固定密钥。

用 apksigner 核对时，正确的输出应同时满足：v1 为 true、v2 为 true、v3 为 true，且第一签名者证书 SHA-256 摘要为 `05011c83…841c35`。只出现 v2 为 true 而 v1 为 false，说明签名方案开关没生效。

## 核对

`.github/workflows/dev-prerelease.yml` 与 `release.yml` 在收集产物后都有一步“核对签名”：用 apksigner 打印证书并取第一签名者的 SHA-256 摘要，与 `CODA_SIGNER_SHA256` 比对，不一致直接失败。签名换了，流水线当场报错，不会等到装不上才发现。

## 换签名的一次性代价

签名换了，设备上已装的旧包无法覆盖安装，需要先卸载再装。卸载会一并删掉应用数据，包括内核载荷解包结果与供应商配置，重装后需要重新解包并重新填一次供应商。
