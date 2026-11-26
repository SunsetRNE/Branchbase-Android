# 翻译协议矩阵：请求头 / 响应头与端点方言（2026-11 · 第八轮）

**这份文档回答一件事**：同一句「把这段翻译成中文」，DeepSeek 官方 / OpenAI Chat Completions /
OpenAI Responses / Anthropic Messages / Gemini API 这五家到底各要什么头、什么路径、回什么样的体 ——
以及 App 里哪些是**自动补**的、哪些**只能用户填**、哪些**故意不补**。

真源：`translate/src/main/java/com/branchbase/translate/TranslateProtocol.kt`（方言表）、
`ModelProbe.kt`（HTTP 执行 + 响应头解读）。设置页只是这张表的渲染层
（`ui/profile/TranslateSettingsScreen.kt` 的协议行 + `ProtocolPickerDialog`）。

---

## 一、为什么「协议」要单独成一层

翻译后端（`TranslateProvider`）回答「用谁」，协议回答「怎么说」。两者的组合是**多对多**的：

| 组合 | 例子 | 说明 |
|---|---|---|
| 同服务、不同方言 | `api.deepseek.com` 的 `/chat/completions` 与 `/anthropic` | DeepSeek 官方同时提供 OpenAI 与 Anthropic 两种入口，同一个 Key 都能用 |
| 同方言、不同服务 | `api.openai.com` 与任意「OpenAI 兼容」中转 | 只差地址与路径前缀，鉴权头一模一样 |
| 不同方言、不同服务 | Gemini 原生格式 | 路径里带模型名、Key 走 `x-goog-api-key`、响应结构完全不同 |

所以协议不能塞进 `TranslateProvider`：那会让「换服务商」被迫绑定「换方言」。

## 二、五档方言对照表

| | DeepSeek 官方（默认） | OpenAI Chat | OpenAI Responses | Anthropic Messages | Gemini API |
|---|---|---|---|---|---|
| 官方 base | `https://api.deepseek.com` | `https://api.openai.com` | `https://api.openai.com` | `https://api.anthropic.com` | `https://generativelanguage.googleapis.com` |
| 翻译端点 | `POST /chat/completions` | `POST /v1/chat/completions` | `POST /v1/responses` | `POST /v1/messages` | `POST /v1beta/models/{model}:generateContent` |
| 鉴权头 | `Authorization: Bearer <key>` | 同左 | 同左 | `x-api-key: <key>`（也接受 Bearer） | `x-goog-api-key: <key>`（也接受 `?key=`） |
| 必填版本头 | — | — | — | `anthropic-version`（缺了直接 400） | — |
| 模型列表端点 | `GET /models` | `GET /v1/models` | `GET /v1/models`（同一个 Models API） | `GET /v1/models` | `GET /v1beta/models` |
| 列表响应结构 | `{"data":[{"id":…}]}` | 同左 | 同左 | `{"data":[{"id":…,"display_name":…}]}` | `{"models":[{"name":"models/…","supportedGenerationMethods":[…]}]}` |
| 请求体关键差异 | `messages[]` + `model` | 同左 | `input`（不是 `messages`），system 走 `instructions` | `max_tokens` **必填**，system 是**顶层字段** | `contents[].parts[]`，system 走 `systemInstruction` |
| 取译文的路径 | `choices[0].message.content` | 同左 | `output[].content[]` 里 `type=="output_text"` 的 `text` | `content[].text` | `candidates[0].content.parts[].text` |
| 错误体 | `{"error":{"message","type","code"}}` | 同左 | 同左 | `{"type":"error","error":{"type","message"}}` | `{"error":{"code","message","status"}}` |

> 官方文档：<https://api-docs.deepseek.com/zh-cn/>（含 Anthropic 兼容入口 `base_url = …/anthropic`）、
> <https://platform.openai.com/docs/api-reference/responses>、
> <https://platform.claude.com/docs/en/api/models/list>（`GET /v1/models` 与 `anthropic-version`）、
> <https://platform.claude.com/docs/en/api/versioning>（版本值清单）、
> Gemini 的端点与 `ListModelsResponse` 结构按官方 discovery 文档核对
> （`https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta`）。

## 三、请求头自动补全：补什么、为什么不补更多

`TranslateProtocol.requestHeaders(apiKey, json)` 是**唯一真源**，探测（本轮）与将来的 Rust
翻译请求都读它。补的只有四类，顺序固定（好比对、好写单测）：

| 头 | 值 | 何时补 | 缺了会怎样 |
|---|---|---|---|
| `Accept` | `application/json` | 总是 | 有的网关默认回 HTML，JSON 解析失败变「空列表」 |
| `Content-Type` | `application/json` | 只有 POST（`json = true`） | 400（服务端按表单解析） |
| 鉴权头 | 见上表三选一 | 有 Key 时 | 401 |
| `anthropic-version` | `2023-06-01` | 仅 Anthropic | 400 `missing anthropic-version` |

**故意不补**的：

- `User-Agent` / `Content-Length` / `Accept-Encoding` / `Connection`：`HttpURLConnection` 与
  reqwest 自己会算，手写容易出现「声明的长度和实际请求体不一致」这类难查的问题；
- `OpenAI-Beta` / `anthropic-beta`：都是可选特性开关，翻译不用；
- `Accept: text/event-stream`：本 App 全是非流式（`stream: false`），流式留到以后；
- `?key=` 查询参数：Gemini 支持，但**查询串会进 URL、进日志、进代理访问记录**，
  与「Key 不写日志」的既有边界冲突 —— 只用头。

版本值选 `2023-06-01` 而不是官方当前推荐的 `2026-10-21`：后者更严（例如移除了 `temperature`），
而中转 / 兼容网关（含 DeepSeek 的 `/anthropic`）对老版本的接受度最高。这一项**不在界面上暴露**：
它不是用户配置，是协议的一部分。

## 四、响应头：只读「用户能照做」的那几个

探测失败时，光给 `HTTP 429` 用户没法决定是「等一会儿」还是「额度用完」。`ResponseHints` 只读两个：

| 头 | 谁发 | 读法 | 界面表现 |
|---|---|---|---|
| `Retry-After` | 通用（429 / 503） | 只认秒数，`0..3600`；HTTP-date 形态不解析 | `HTTP 429（Retry-After 12s）` |
| `x-ratelimit-remaining` | OpenAI 系 / 部分中转 | 值为 `0` | `HTTP 429（额度已用尽）` |
| `anthropic-ratelimit-requests-remaining` | Anthropic | 同上 | 同上（前缀不同，语义相同） |

**不读**：`Set-Cookie`、`request-id`、`cf-*`（Cloudflare 的挑战头）、整个响应头回显。
设置页不是抓包工具；多一行用户看不懂的头，只会让「一句话提示」变成噪音。
响应头名**大小写不敏感**（`HttpURLConnection` 返回的大小写不保证），统一按 `ignoreCase` 查。

## 五、端点拼接：容忍用户「多写了一段」

`joinEndpoint(base, path, officialBase)` 的规则（`ModelProbeTest` 钉住六种写法）：

1. 空地址 → 该协议的官方地址（**不是**统一回落到 DeepSeek 的地址）；
2. 没写协议 → 补 `https://`（用户常直接粘 `api.deepseek.com`）；
3. 地址已以整条路径结尾（贴了完整端点）→ 原样；
4. 地址已含路径的第一段（`…/v1` + `/v1/chat/completions`、`…/v1beta` + `/v1beta/models`）
   → 只补剩余段，**不写出 `/v1/v1/`**；
5. 其余 → `base + path`；
6. Gemini 的模型名在路径里，没填模型时保留字面量 `{model}` —— 让「还没配置」这件事
   在 URL 上看得见，而不是拼出一个 `…/models/:generateContent` 的怪路径。

## 六、两侧实现（1.4.51 起五档端到端可用）

协议是**同一张表的第二份实现**：Kotlin 要拼探测请求（拿模型列表），Rust 要发翻译请求。
两边共用不了同一份代码（跨 JNI 边界），所以各自维护一张表，**码值逐字一致**，并由钉子互相盯住
（`SettingsSpecTest` 会读 `core/src/translate/mod.rs`，少一档就红）。

| 侧 | 位置 | 负责 |
|---|---|---|
| Kotlin | `translate/.../TranslateProtocol.kt`、`ModelProbe.kt` | 探测端点 / 鉴权头 / 列表响应解析、设置页渲染、默认地址与模型 |
| Rust | `core/src/translate/mod.rs`（`Protocol`、`Options`、`auth_headers`、`join_endpoint`） | 翻译端点 / 鉴权头 / **请求体** / 响应体解析的分派 |
| Rust | `deepseek.rs`（DeepSeek + OpenAI Chat 共用）、`responses.rs`、`anthropic.rs`、`gemini.rs` | 四套请求体与取译文的路径 |
| Rust | `core/tests/protocol_http.rs` | 回环服务的**真实请求形状**断言（路径 / 头 / 字段） |

**务必记住**：`.so` 是构建产物，协议分派改的是 Rust —— **必须随发版流水线重建 `.so` 才生效**。
旧 `.so` 不认识 `protocol` 字段（serde 忽略未知字段），会继续按 `chat/completions` 跑，
表现就是「设置里选了协议、翻译却没变」。本机没有 NDK（`ANDROID_NDK_HOME` 空、
`/root/Android/ndk` 不存在），`aarch64-linux-android` 交叉编译只能在 CI 上做。

## 七、验证

| 类型 | 内容 |
|---|---|
| 单测（`:translate`） | `TranslateProtocolTest` 9 例（五档码 / 三种鉴权头 / 版本头 / 无 Key 不补头 / Gemini 路径占位 / 双份前缀 / 主机猜测 / 候选只有 DeepSeek 有） |
| 单测（`:translate`） | `ModelProbeTest` 13 例（六种地址写法 / 两种响应体 / 坏 JSON / 空 Key / `Retry-After` / 额度用尽 / 头名大小写） |
| 源码钉子（`:app`） | `SettingsSpecTest.翻译页协议单选_请求头自动补全只有一个真源`：协议行 / 弹性弹窗 / 落盘 / carryOver / 探测传协议 / 五档码 / 四个头名 / **Rust 表逐字一致 + 三个后端模块存在** |
| 集成（`core/tests`） | `protocol_http.rs` 5 例：四种方言的真实请求行 / 鉴权头 / 必填头 / 请求体字段；空模型不发请求；没 Key 报认证错误 |
