# PR 审查配置

这里记录本仓库接入的自动审查，以及**需要你手动点几下**才能启用的部分（GitHub App 无法由代码仓库自行安装）。

## 一、已随仓库生效（不需要任何密钥）

| 工作流 | 内容 | 是否阻塞 |
| --- | --- | --- |
| `pr-lint.yml` | ktlint + detekt 结果经 reviewdog 发成 PR 行内评论，只报新增行 | 否（`fail-level=none`） |
| `pr-meta.yml` | 标题规范（Conventional Commits）、必须关联 Issue、依赖变更审查、gitleaks 密钥扫描、改动规模标签 | 仅「关联 Issue」失败会红 |
| `codeql.yml` | CodeQL 语义分析（public 仓库免费），push 与每周一跑 | 不参与 PR |

`pr-lint.yml` 的两个 jar 与 `tools/lint.sh` 用同一版本（ktlint 1.8.0、detekt 1.23.8），
本地跑出来的结论与 CI 一致。

`pr-meta.yml` 的 size 标签由工作流自己创建（`size/S`、`size/M`、`size/L`、`size/XL`），无需预建。

## 二、LLM 审查：三种取法

### 1. CodeRabbit（开源仓库免费，零配置）

在 GitHub Marketplace 安装 **CodeRabbit** App，授权本仓库即可。它对公开仓库免费，
装好后每个 PR 自动生成摘要与逐行建议，也能在 PR 里用 `@coderabbitai` 对话。
免费范围与额度以官方页面为准，安装需要仓库管理员权限。

### 2. Gemini Code Assist（个人免费额度，零配置）

在 Marketplace 安装 **Gemini Code Assist**（Google 提供）App 并授权仓库。
装好后 PR 里会自动出现 `gemini-code-assist` 的审查评论，也可以用 `/gemini review` 手动触发。
同样需要管理员在 GitHub 上安装，代码侧无需配置。

### 3. 自托管 PR-Agent（可选，需要一把 key）

`.github/workflows/pr-agent.yml` 已就绪：**没有 key 时整个 job 跳过，不会让 PR 变红**。
想启用时：

1. 去 Google AI Studio 申请 Gemini API key（有免费额度），或 DeepSeek 的 key；
2. 仓库 `Settings → Secrets and variables → Actions` 添加 `GEMINI_API_KEY`（或 `DEEPSEEK_API_KEY`）；
3. 之后每个 PR 会自动收到 `pr-agent` 的摘要、逐行建议与改动描述。

PR-Agent 是 MIT 协议、16k+ star 的开源项目；官方文档里也支持 Ollama 等本地模型，
但本地模型需要自建运行环境，成本更高，不建议放在 GitHub 托管的 runner 上。

## 三、日常流程

1. 从 `dev` 切分支：`git checkout -b fix/<issue>-<slug> dev`；
2. 改动完成后跑本地三套测试（app 单测 / tools / kernel）；
3. 推分支并开 PR，**PR 描述里写 `Closes #<issue>`**；
4. 等 `pr-lint` 与 `pr-meta` 的评论，把新增行的告警处理掉；
5. 需要 LLM 意见时，在 PR 里 `@coderabbitai review` 或 `/gemini review`；
6. 合并到 `dev`，再由 `dev` 合到 `main`。

## 四、与 OCR 的关系

OpenCodeReview 的模型额度用完后，上面这套是替代路径：规则类（ktlint/detekt/CodeQL/依赖/密钥）
完全不花模型费用，语义与设计类问题交给 CodeRabbit / Gemini 的免费额度。
