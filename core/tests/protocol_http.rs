//! 四种「非 DeepSeek 官方」协议的**真实 HTTP 形状**集成测试。
//!
//! 单元测试覆盖了请求体 / 响应解析的纯函数，这里补的是「真的发出去时长什么样」：
//! 请求行（路径，Gemini 的模型在路径里）、鉴权头（`x-api-key` / `x-goog-api-key`）、
//! 必填头（`anthropic-version`）、请求体字段（`input` / `contents` / `system` 的位置）。
//!
//! 端点用本机回环服务，不联网、不消耗额度。

mod common;

use branchbase_core::translate::{translate, Options};
use common::{options, spawn_server};

#[tokio::test]
async fn anthropic_请求带_x_api_key_与版本头_并只取_text_块() {
    let (base, server) = spawn_server(
        "200 OK",
        r#"{"content":[{"type":"thinking","thinking":"推理过程"},{"type":"text","text":"译文：你好，世界"}]}"#,
    );

    let out = translate(
        "Hello world",
        "en",
        "zh-CN",
        &options("anthropic-messages", base, "sk-ant", "claude-sonnet-x"),
    )
    .await
    .expect("应当翻译成功");
    // 「译文：」外壳与思考过程都不该出现
    assert_eq!(out, "你好，世界");

    let request = server.join().unwrap();
    let lower = request.to_ascii_lowercase();
    assert!(request.starts_with("POST /v1/messages "), "请求行不对：{request}");
    assert!(lower.contains("x-api-key: sk-ant"), "缺少 x-api-key：{request}");
    assert!(
        lower.contains("anthropic-version: 2023-06-01"),
        "缺少 anthropic-version（缺了官方直接 400）：{request}"
    );
    assert!(
        !lower.contains("authorization:"),
        "Anthropic 走 x-api-key，不该再发 Authorization：{request}"
    );
    // max_tokens 必填、system 是顶层字段
    assert!(request.contains("\"max_tokens\":4096"), "缺少 max_tokens：{request}");
    assert!(request.contains("\"system\":"), "system 必须在顶层：{request}");
    assert!(
        !request.contains("\"role\":\"system\""),
        "system 不能写成 messages 里的一条：{request}"
    );
    assert_eq!(request.matches("Hello world").count(), 1);
}

#[tokio::test]
async fn gemini_模型名在路径里_key_走请求头() {
    let (base, server) = spawn_server(
        "200 OK",
        r#"{"candidates":[{"content":{"parts":[{"text":"（思考）","thought":true},{"text":"你好"}]}}]}"#,
    );

    let out = translate(
        "Hello",
        "en",
        "zh-CN",
        &options("gemini", base, "g-key", "gemini-2.5-pro"),
    )
    .await
    .expect("应当翻译成功");
    assert_eq!(out, "你好");

    let request = server.join().unwrap();
    let lower = request.to_ascii_lowercase();
    assert!(
        request.starts_with("POST /v1beta/models/gemini-2.5-pro:generateContent "),
        "模型名必须在路径里：{request}"
    );
    assert!(lower.contains("x-goog-api-key: g-key"), "缺少 x-goog-api-key：{request}");
    // Key 不能出现在 URL 里（查询串会进日志、进代理访问记录）
    assert!(!request.starts_with("POST /v1beta/models/gemini-2.5-pro:generateContent?key="));
    // 请求体：contents + systemInstruction，且没有顶层 model
    assert!(request.contains("\"contents\""), "缺少 contents：{request}");
    assert!(request.contains("\"systemInstruction\""), "缺少 systemInstruction：{request}");
    assert!(request.contains("简体中文"), "system 里没有目标语言：{request}");
}

#[tokio::test]
async fn responses_用_input_与_instructions() {
    let (base, server) = spawn_server(
        "200 OK",
        r#"{"output":[{"type":"message","content":[{"type":"output_text","text":"你好"}]}]}"#,
    );

    let out = translate(
        "Hello",
        "en",
        "zh-CN",
        &options("openai-responses", base, "sk-o", "gpt-x"),
    )
    .await
    .expect("应当翻译成功");
    assert_eq!(out, "你好");

    let request = server.join().unwrap();
    let lower = request.to_ascii_lowercase();
    assert!(request.starts_with("POST /v1/responses "), "请求行不对：{request}");
    assert!(lower.contains("authorization: bearer sk-o"), "缺少 Bearer：{request}");
    assert!(request.contains("\"input\":"), "Responses 用 input 而不是 messages：{request}");
    assert!(
        request.contains("\"type\":\"input_text\""),
        "内容块要带 input_text 类型：{request}"
    );
    assert!(request.contains("\"instructions\":"), "system 走 instructions：{request}");
    assert!(
        !request.contains("\"messages\":"),
        "Responses 不该出现 messages 字段：{request}"
    );
}

#[tokio::test]
async fn 模型为空时不发请求_给出可分类的配置错误() {
    for protocol in ["openai-chat", "anthropic-messages", "gemini", "openai-responses"] {
        // 端口 1 一定连不上：真发了请求就会得到网络错误，而不是这条配置错误
        let err = translate(
            "hi",
            "en",
            "zh-CN",
            &options(protocol, "http://127.0.0.1:1".into(), "sk-x", "   "),
        )
        .await
        .unwrap_err();
        // 码由 JNI 边界拼进 `ERROR:<code>:<message>`（Kotlin 侧据此按 AUTH 停发）
        assert_eq!(err.code(), Some("TRANSLATE_AUTH"), "{protocol}：{err}");
        assert!(err.to_string().contains("模型"), "{protocol}：{err}");
    }
}

#[tokio::test]
async fn 没填_key_时按协议报认证错误() {
    let err = translate(
        "hi",
        "en",
        "zh-CN",
        &Options {
            provider: "deepseek".into(),
            protocol: "gemini".into(),
            api_key: "   ".into(),
            model: "gemini-2.5-flash".into(),
            base_url: "http://127.0.0.1:1".into(),
            thinking: Default::default(),
        },
    )
    .await
    .unwrap_err()
    .to_string();
    assert!(err.contains("Gemini 认证失败"), "{err}");
    assert!(err.contains("API Key"), "{err}");
}
