# 模型供应商与网关配置审计

范围：Coda 移动端的模型选择器、手动 OpenAI/Anthropic 兼容供应商、OAuth 供应商、两套本机网关（CLIProxyAPI 与 WorkBuddy）。依据不是猜测，而是解包后的内核 bundle `app/src/main/assets/core/zcode.cjs` 里的 zod schema 与注册逻辑。

## 内核认什么

个人供应商文件路径由 `ZCODE_DATA_BASE_DIR` 推出，固定为 `<data>/.zcode/v2/provider_config.json`。顶层只接受 `schemaVersion` 与 `config`，`config` 只接受 `providerOrder`、`providerConfigRules`、`modelConfigRules`、`defaultModelSelection`。

`providerRules` 每一项只允许 `providerId`、`templateId`、`providerName`、`enabled`、`config`。规则对象与 `config` 都是 strict，多一个键整份文件解析失败。

`api.type` 的合法值只有 `anthropic-messages`、`openai-chat-completions`、`openai-responses`。`access.type` 只有 `api-key`、`zhipu-coding-plan-api-key`、`zhipu-account`，其中 `api-key` 的 `apiKey` 是非空必填。

模型要进入可选列表，内核的判断是 `providerExecutable && modelConfig.enabled === true && 无校验问题`。模型配置按 complete schema 校验，`properties` 必须同时具备 `requiresMfjsToolSchema`、`contextWindow`、`inputFormat`、`outputFormat`、`supportsToolCall`、`supportsJsonSchemaOutput`、`supportsNativeWebSearch`、`supportsMidConversationSystem`，`optionSpecs` 必须同时具备 `reasoningLevel` 与 `maxOutputTokens`。

## 已确认的根因

第一，旧实现把 `codaKind` 与 `codaOrigin` 直接写进每条供应商规则。内核 strict 解析遇到未知键会拒绝整份文件，于是所有供应商与模型一起消失，界面表现是核心在跑但模型列表为空。

第二，模型规则没有写 `enabled`。内核用 `=== true` 判定，缺省不是按启用处理，而是判为不可执行，模型同样不会出现。

第三，模型规则只写了 `contextWindow`，`properties` 与 `optionSpecs` 都不完整。即便供应商注册成功，模型也会被判为不可用，表现为能看见名字但选不了。

第四，OAuth 落库后没有让内核重读配置。内核只在启动时读一次供应商文件，授权成功但内核仍在跑旧配置，新供应商与模型不会出现在面板里，重启应用后才突然出现。

第五，取模型清单时按「哪个网关在跑」选网关，而不是按供应商归属选。两个网关同时运行时永远问 CodeBuddy，于是 Cursor、ZCode 等配置里被写入 CodeBuddy 的模型名，内核按 providerId 与 modelId 配对时一条都对不上。

第六，供应商里保存的 `baseUrl` 是授权当时的网关端口。网关端口会因占用从 8317 漂移到 18317 等，App 重启后也不会自动拉起网关，于是凭据还在、地址已失效。

第七，端点与密钥没有做入口校验。用户把完整的 `/v1/chat/completions` 贴进端点会与内核拼接的路径重复，本地地址缺 `http://` 会被 URL 校验拒绝，密钥留空会让整条供应商被跳过。

## 对应修复

`feb5b97` 把 Coda 分类信息迁到旁路文件 `coda-provider-meta.json`，内核文件保持纯净，并在读取到旧格式时就地重写一次。

`003c2d9` 显式写入 `enabled: true`，`41de0de` 把 `properties` 与 `optionSpecs` 补成完整声明。

`69a0c6c` 在 OAuth 落库后重启内核，使新配置立刻生效。

`2ce107b` 改为按供应商所属网关取模型，并在启动时对账，纠正历史上串写进来的别家模型。

`da078af` 启动时按已保存的 OAuth 供应商拉起对应网关，并把真实端口与调用密钥回写进供应商配置。

`460088f` 与后续提交规范化端点，去掉重复的接口后缀，为本地地址补 `http://`，并为空密钥写入显式占位值。

## 验证方式与剩余风险

以上结论来自对内核 bundle 的静态取证，修复只验证到 CI 编译与打包，没有在真机上执行一次完整的「授权、重启、选模型、发请求」链路。真机验证时应重点看三处：应用私有目录下 `provider_config.json` 是否只含内核允许的字段，`coda-provider-meta.json` 是否记录了正确的分类，以及模型面板在重启内核后是否出现新供应商的全部模型。

推理档位目前对所有个人模型只声明中性的一档，不再假设模型支持开关推理。上下文窗口不再写死，按供应商单独设置；默认仍是 200000，短上下文模型需要用户按实际能力调低。

第二轮审查又确认了三处与格式强相关的问题。界面在添加 Anthropic 兼容供应商时写入的 `api.type` 是 `anthropic`，而内核枚举只接受 `anthropic-messages`，非法值会让整份供应商配置解析失败，表现与字段污染相同，加载时已加入就地纠正。OpenAI 的 Responses 格式内核本来支持，界面却没有入口，现已补上 `openai-responses` 选项。ASR 供应商此前与聊天供应商写在同一份内核文件里，转写模型会被注册成聊天模型，现改为独立的 `coda-asr-providers.json`，并一并纳入备份与恢复。

多模态能力此前被统一写成只支持文本，视觉模型收到图片也会被当成纯文本处理。现在图片、视频、音频、PDF 四项按供应商单独声明，默认图片与 PDF 开启，视频与音频关闭；升级时会重写一次旧模型规则，让改动立即生效。

备份范围已扩展到应用的全部私有状态：files 目录除可重新释放的内核载荷与临时目录外全部打包，包含完整的 Linux 根文件系统、全部 SharedPreferences、应用数据库、no_backup 目录、Bot 与会话数据、搜索索引、工作区元数据以及网关凭据。恢复时按条目流式写回并校验相对路径；导入前先停内核与两个网关，恢复完成后自动重启应用，回到备份时的状态。OAuth 供应商的输入能力也可以单独编辑，不再只能使用默认值。
