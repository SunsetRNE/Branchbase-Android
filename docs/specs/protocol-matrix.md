# 翻译协议矩阵：四档报文 + 地址列表（2026-11 · 第九轮 / 1.4.54）

**这份文档回答一件事**：同一句「把这段翻译成中文」，OpenAI Chat Completions / OpenAI Responses /
Anthropic Messages / Gemini API 四档协议各要什么头、什么路径、回什么样的体 ——
以及 App 里哪些是**自动补**的、哪些**只能用户填**、哪些**故意不补**、「用哪家厂商」又归谁管。

真源：`translate/src/main/java/com/branchbase/translate/TranslateProtocol.kt`（协议 + 地址表）、
`ModelProbe.kt`（HTTP 执行 + 响应头解读）。设置页只是这张表的渲染层。

---

## 一、协议与厂商是两件事（1.4.54 的拆层）

| 概念 | 回答什么 | 例子 |
|---|---|---|
| **协议**（4 档） | 「怎么说」：路径形状、鉴权头、请求体 / 响应体结构 | OpenAI Chat、OpenAI Responses、Anthropic Messages、Gemini |
| **接口地址**（地址列表） | 「对谁说」：厂商 / 中转 / 自建网关的 base URL | `api.openai.com/v1`、`api.deepseek.com/v1`、`api.deepseek.com/anthropic`、自填 |

1.4.53 及更早把 DeepSeek 当成「一种协议」，于是模型候选、默认地址、错误文案都被锁死在一家。
现在协议只剩四档（三家原生格式），**新增厂商 = 在地址表加一行**，不动协议、不动代码。

### DeepSeek 落在哪儿

DeepSeek **没有自己的私有报文**：官方文档把它列在「兼容协议」下，三条通道各与一档协议同形：

| DeepSeek 通道 | base_url | 对应协议 |
|---|---|---|
| OpenAI Chat 兼容 | `https://api.deepseek.com/v1`（`v1` 与模型版本无关，根地址亦可） | OpenAI Chat Completions |
| Anthropic 兼容 | `https://api.deepseek.com/anthropic` | Anthropic Messages（`anthropic-version` 会被忽略，多送无害） |
| Responses 兼容 | `https://api.deepseek.com`（官方示例走 OpenAI SDK） | OpenAI Responses（路径按官方示例是 `{base}/responses`，与我们的 `/v1/responses` **不同**，暂不列进地址表，需用户自测） |

DeepSeek 专属的只有**语义**（`thinking` / `reasoning_effort`、`safety_check`、`title`、模型名映射、
402 余额错误码）—— 这些是「服务商参数」，不是协议。

## 二、四档协议对照表

| | OpenAI Chat | OpenAI Responses | Anthropic Messages | Gemini API |
|---|---|---|---|---|
| 官方 base（地址表第一行） | `https://api.openai.com/v1` | `https://api.openai.com/v1` | `https://api.anthropic.com` | `https://generativelanguage.googleapis.com` |
| 翻译端点 | `POST {base}/v1/chat/completions` | `POST {base}/v1/responses` | `POST {base}/v1/messages` | `POST {base}/v1beta/models/{model}:generateContent` |
| 鉴权头 | `Authorization: Bearer <key>` | 同左 | `x-api-key: <key>`（也接受 Bearer） | `x-goog-api-key: <key>`（也接受 `?key=`） |
| 必填版本头 | — | — | `anthropic-version`（Anthropic 缺失直接 400；DeepSeek 忽略） | — |
| 模型列表端点 | `GET {base}/v1/models` | 同左（同一个 Models API） | `GET {base}/v1/models` | `GET {base}/v1beta/models` |
| 列表响应结构 | `{"data":[{"id":…}]}` | 同左 | 同左（多 `display_name`） | `{"models":[{"name":"models/…","supportedGenerationMethods":[…]}]}` |
| 请求体关键差异 | `messages[]` + `model` | `input`（不是 `messages`），system 走 `instructions` | `max_tokens` **必填**，system 打平到顶层 | `contents[].parts[]`，system 走 `systemInstruction` |
| 取译文的路径 | `choices[0].message.content` | `output[].content[]` 里 `type=="output_text"` 的 `text` | `content[].text`（跳过 `thinking` 块） | `candidates[0].content.parts[].text`（跳过 `thought:true`） |
| 错误体 | `{"error":{"message","type","code"}}` | 同左 | `{"type":"error","error":{"type","message"}}` | `{"error":{"code","message","status"}}` |

> 官方文档：<https://api-docs.deepseek.com/zh-cn/>、<https://platform.openai.com/docs/api-reference/responses>、
> <https://platform.claude.com/docs/en/api/models/list>、<https://platform.claude.com/docs/en/api/versioning>、
> Gemini 端点与 `ListModelsResponse` 按官方 discovery 文档核对
> （`https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta`）。

### 地址表里有什么

| 协议 | 已收录地址 | 说明 |
|---|---|---|
| OpenAI Chat | `openai`（官方）· `deepseek`（`/v1`，默认模型 `deepseek-flash`，候选两个） | 覆盖面最广的一档；任何 OpenAI 兼容中转都走「自定义地址」 |
| OpenAI Responses | `openai`（官方） | 模型列表与 Chat 共用 |
| Anthropic Messages | `anthropic`（官方）· `deepseek`（`/anthropic`） | DeepSeek 兼容通道 |
| Gemini | `google`（官方） | — |

地址行的名字是 app 层字符串资源（模块只给稳定 `id`），**默认模型 / 已知候选挂在地址行上**：
`api.deepseek.com/v1` 预填 `deepseek-flash`，`api.openai.com/v1` 不预填（不臆造模型名）。

## 三、请求头自动补全：补什么、为什么不补更多

`TranslateProtocol.requestHeaders(apiKey, json)` 是**唯一真源**，探测与 Rust 翻译请求都读它：

| 头 | 值 | 何时补 | 缺了会怎样 |
|---|---|---|---|
| `Accept` | `application/json` | 总是 | 有的网关默认回 HTML，JSON 解析失败变「空列表」 |
| `Content-Type` | `application/json` | 只有 POST（`json = true`） | 400（服务端按表单解析） |
| 鉴权头 | 见上表三选一 | 有 Key 时 | 401 |
| `anthropic-version` | `2023-06-01` | 仅 Anthropic | Anthropic 400 `missing anthropic-version`（DeepSeek 忽略） |

**故意不补**：`User-Agent` / `Content-Length` / `Accept-Encoding`（客户端自算）、`OpenAI-Beta` 之类特性头
（翻译不用）、`Accept: text/event-stream`（全程非流式）、`?key=` 查询串（会进 URL / 日志 / 代理访问记录）。

## 四、响应头：只读「用户能照做」的那几个

| 头 | 谁发 | 读法 | 界面表现 |
|---|---|---|---|
| `Retry-After` | 通用（429 / 503） | 只认秒数 `0..3600`；HTTP-date 不解析 | `获取失败：HTTP 429，12 秒后重试` |
| `x-ratelimit-remaining` / `anthropic-ratelimit-requests-remaining` | OpenAI 系 / Anthropic | 值为 `0` | `获取失败：HTTP 429，额度已用尽` |

**HTTP 404 / 405 单独处理**：很多兼容网关（含 DeepSeek 的 `/anthropic`）没有 `GET /models`。
这时给 `NO_MODELS_ENDPOINT`，界面提示「该服务商没有模型列表接口，请手动填写模型名」，
而不是甩一个 404 让人以为地址填错了。

## 五、端点拼接：容忍用户「多写了一段」

`joinEndpoint(base, path, officialBase)` 的规则（`ModelProbeTest` 钉住）：

1. 空地址 → 该协议**自己的**官方地址；
2. 没写协议 → 补 `https://`；
3. 地址已以整条路径结尾（贴了完整端点）→ 原样；
4. 地址已含路径的第一段（`…/v1` + `/v1/chat/completions`、`…/v1beta` + `/v1beta/models`）→ 只补剩余段；
5. 其余 → `base + path`；
6. Gemini 的模型名在路径里，没填模型时保留 `{model}` 占位符（让「还没配置」在 URL 上看得见）。

## 六、两侧实现（必须逐字一致）

| 侧 | 位置 | 负责 |
|---|---|---|
| Kotlin | `TranslateProtocol.kt` / `ModelProbe.kt` | 探测端点 / 鉴权头 / 列表解析、地址表、默认模型（挂地址上） |
| Rust | `core/src/translate/mod.rs`（`Protocol`、`auth_headers`、`join_endpoint`） | 翻译端点 / 鉴权头 / 请求体与响应体分派 |
| Rust | `openai_chat.rs`（Chat Completions）、`responses.rs`、`anthropic.rs`、`gemini.rs` | 四套载荷与取译文路径 |
| Rust | `prompt.rs` | **与协议无关**的提示词、消息拼装、输出清理（1.4.54 从 deepseek 模块抽出） |
| Rust | `core/tests/openai_chat_http.rs` / `protocol_http.rs` | 回环服务的**真实请求形状**断言 |

**迁移与兼容**：`protocol = "deepseek"` 折成 `openai-chat`；`base_url` 为空的老配置由
`TranslateSettings.migrateLegacyBaseUrl` 补成 `https://api.deepseek.com/v1`
（否则会变成「DeepSeek 的 Key + OpenAI 的地址」，每次请求都 401）。
`.so` 是构建产物，Rust 改动必须随发版流水线重建（本机没有 NDK，交叉编译只在 CI 做）。

## 七、验证

| 类型 | 内容 |
|---|---|
| 单测（`:translate`） | `TranslateProtocolTest`（四档码 / 地址表 / 旧码迁移 / 三种鉴权头 / 版本头 / 路径占位 / 双份前缀 / 主机猜测 / 模型候选挂地址） |
| 单测（`:translate`） | `ModelProbeTest`（地址写法 / 两种响应体 / 坏 JSON / 空 Key / `Retry-After` / 额度用尽 / 头名大小写） |
| 单测（`:translate`） | `TranslateSettingsTest`（模型列表按协议 + 地址分桶、老配置地址迁移、options JSON、缓存变体） |
| 源码钉子（`:app`） | `SettingsSpecTest`：协议行 / 地址行 / 弹性弹窗 / 落盘 / 探测传协议 / 四档码 / 四个头名 / **Rust 表逐字一致** / 服务商行叫「外部模型接入」 |
| 集成（`core/tests`） | `openai_chat_http.rs` 5 例 + `protocol_http.rs` 5 例：真实请求行 / 鉴权头 / 必填头 / 请求体字段；空模型不发请求；没 Key 报认证错误 |
