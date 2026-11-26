//! 思维深度的**请求形状与兜底**集成测试。
//!
//! 覆盖三件事：
//! 1. 档位确实落进请求体（OpenAI 系是 `reasoning_effort`，Anthropic 是 `thinking.budget_tokens`）；
//! 2. 服务商不认这个字段（400）时，**去掉字段重试一次**，翻译仍然成功；
//! 3. 探测模式（`strict`）**不兜底**：失败如实报错，不会把「不支持」伪装成成功。

mod common;

use branchbase_core::translate::{translate, Options, Thinking};
use common::{spawn_sequence, spawn_server};

fn options(protocol: &str, base: String, model: &str, thinking: Thinking) -> Options {
    Options {
        provider: "deepseek".into(),
        protocol: protocol.into(),
        api_key: "sk-test".into(),
        model: model.into(),
        base_url: base,
        thinking,
    }
}

#[tokio::test]
async fn 服务商不认思维字段时去掉字段重试一次() {
    let (base, server) = spawn_sequence(vec![
        // 第一枪：服务商说「不认识 reasoning_effort」
        (
            "400 Bad Request",
            r#"{"error":{"message":"unknown field reasoning_effort","type":"invalid_request_error"}}"#,
        ),
        // 第二枪（去掉字段）：正常译文
        (
            "200 OK",
            r#"{"choices":[{"message":{"content":"你好"}}]}"#,
        ),
    ]);

    let out = translate(
        "Hello",
        "en",
        "zh-CN",
        &options(
            "openai-chat",
            base,
            "m",
            Thinking {
                effort: "low".into(),
                budget_tokens: 0,
                strict: false,
            },
        ),
    )
    .await
    .expect("去掉字段后应当翻译成功");
    assert_eq!(out, "你好");

    let requests = server.join().unwrap();
    assert_eq!(requests.len(), 2, "应当恰好重试一次");
    assert!(requests[0].contains("\"reasoning_effort\":\"low\""), "第一枪应带字段");
    assert!(
        !requests[1].contains("reasoning_effort"),
        "重试必须去掉字段：{}",
        requests[1]
    );
}

#[tokio::test]
async fn 探测模式不兜底_不支持的字段如实报错() {
    let (base, _) = spawn_server(
        "400 Bad Request",
        r#"{"error":{"message":"unknown field reasoning_effort","type":"invalid_request_error"}}"#,
    );
    let err = translate(
        "Hello",
        "en",
        "zh-CN",
        &options(
            "openai-chat",
            base,
            "m",
            Thinking {
                effort: "low".into(),
                budget_tokens: 0,
                strict: true,
            },
        ),
    )
    .await
    .unwrap_err()
    .to_string();
    // 探测必须看见真实失败，否则「服务商不认这个字段」会被兜底掩盖
    assert!(err.contains("unknown field"), "{err}");
}

#[tokio::test]
async fn anthropic_的预算落进_thinking_字段() {
    let (base, server) = spawn_server(
        "200 OK",
        r#"{"content":[{"type":"text","text":"你好"}]}"#,
    );
    let out = translate(
        "Hello",
        "en",
        "zh-CN",
        &options(
            "anthropic-messages",
            base,
            "claude-x",
            Thinking {
                effort: String::new(),
                budget_tokens: 4096,
                strict: true,
            },
        ),
    )
    .await
    .expect("应当翻译成功");
    assert_eq!(out, "你好");

    let request = server.join().unwrap();
    assert!(request.contains("\"thinking\":"), "缺少 thinking：{request}");
    assert!(request.contains("\"budget_tokens\":4096"), "缺少预算：{request}");
    // budget_tokens 必须 < max_tokens
    let max_tokens: u64 = request
        .split("\"max_tokens\":")
        .nth(1)
        .and_then(|rest| rest.split(|c: char| !c.is_ascii_digit()).next())
        .and_then(|v| v.parse().ok())
        .expect("请求体里应有 max_tokens");
    assert!(max_tokens > 4096, "max_tokens={max_tokens} 必须大于预算");
}

#[tokio::test]
async fn 关掉思维深度时不发任何相关字段() {
    let (base, server) = spawn_server("200 OK", r#"{"choices":[{"message":{"content":"ok"}}]}"#);
    translate(
        "Hello",
        "en",
        "zh-CN",
        &options("openai-chat", base, "m", Thinking::default()),
    )
    .await
    .unwrap();

    let request = server.join().unwrap();
    assert!(!request.contains("reasoning_effort"), "{request}");
    assert!(!request.contains("\"thinking\""), "{request}");
}
