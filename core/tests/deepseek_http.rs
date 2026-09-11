//! DeepSeek 后端的**真实 HTTP 形状**集成测试：起一个本机回环的小服务当端点，不联网。
//!
//! 为什么值得写：单元测试能覆盖提示词、响应解析、错误消息映射，但「请求到底长什么样」
//! （端点拼接、`Authorization` 头、请求体字段、是否漏传了 key）只有真的发一次才看得见。
//! 而真端点需要 API Key、还会花钱，所以这里用回环服务断言请求本身。
//!
//! 覆盖的是**契约**，不是网络库：
//! 1. `POST {base}/chat/completions`；
//! 2. `Authorization: Bearer <key>`；
//! 3. 请求体是 `{model, messages:[system,user], stream:false}`，system 里带目标语言与占位符约束；
//! 4. 401 / 402 的错误消息里带**能分类的关键词**（Kotlin 侧 `TranslateEngine.classify` 靠它决定熔断）。

use branchbase_core::translate::{translate, Options};
use std::io::{Read, Write};
use std::net::TcpListener;

/// 起一次性回环服务：收一个请求、回一个固定响应，并把请求原文交回给测试。
fn spawn_server(status: &'static str, body: &'static str) -> (String, std::thread::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("绑定回环端口失败");
    let addr = listener.local_addr().unwrap();

    let handle = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("accept 失败");
        let mut buf: Vec<u8> = Vec::new();
        let mut chunk = [0u8; 1024];
        // 读满「头部 + Content-Length 指定的正文」
        loop {
            let n = match stream.read(&mut chunk) {
                Ok(0) => break,
                Ok(n) => n,
                Err(_) => break,
            };
            buf.extend_from_slice(&chunk[..n]);
            let text = String::from_utf8_lossy(&buf);
            if let Some(header_end) = text.find("\r\n\r\n") {
                let content_len = text[..header_end]
                    .lines()
                    .find_map(|l| {
                        l.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|v| v.trim().parse::<usize>().unwrap_or(0))
                    })
                    .unwrap_or(0);
                if buf.len() >= header_end + 4 + content_len {
                    break;
                }
            }
        }
        let response = format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {len}\r\nConnection: close\r\n\r\n{body}",
            len = body.len(),
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.flush();
        String::from_utf8_lossy(&buf).to_string()
    });

    (format!("http://{addr}"), handle)
}

fn options(base_url: String, key: &str, model: &str) -> Options {
    Options {
        provider: "deepseek".into(),
        api_key: key.into(),
        model: model.into(),
        base_url,
    }
}

#[tokio::test]
async fn 请求形状与响应解析都符合_openai_兼容约定() {
    let (base, server) = spawn_server(
        "200 OK",
        r#"{"choices":[{"message":{"role":"assistant","content":"译文：你好，世界"}}]}"#,
    );

    let out = translate(
        "Hello world",
        "en",
        "zh-CN",
        &options(base, "sk-test-key", "my-model"),
    )
    .await
    .expect("应当翻译成功");
    // 模型爱加的「译文：」前缀会被清掉
    assert_eq!(out, "你好，世界");

    let request = server.join().unwrap();
    let lower = request.to_ascii_lowercase();
    assert!(request.starts_with("POST /chat/completions "), "请求行不对：{request}");
    assert!(
        lower.contains("authorization: bearer sk-test-key"),
        "缺少 Authorization 头：{request}"
    );
    assert!(request.contains("\"model\":\"my-model\""), "模型名没传对：{request}");
    assert!(request.contains("\"stream\":false"), "应当显式关掉流式：{request}");
    assert!(request.contains("\"role\":\"system\""), "缺少 system 消息：{request}");
    assert!(request.contains("\"role\":\"user\""), "缺少 user 消息：{request}");
    // 提示词里必须带上目标语言与占位符约束（Kotlin 侧的占位符保护依赖它）
    assert!(request.contains("简体中文"), "system 里没有目标语言：{request}");
    assert!(request.contains("⟦0⟧"), "system 里没有占位符约束：{request}");
    // 原文只在 user 里出现一次
    assert_eq!(request.matches("Hello world").count(), 1);
}

#[tokio::test]
async fn 自定义_base_url_会补上_chat_completions() {
    let (base, server) = spawn_server("200 OK", r#"{"choices":[{"message":{"content":"ok"}}]}"#);
    // 用户填的是中转的 /v1 地址
    let out = translate(
        "hi",
        "en",
        "zh-CN",
        &options(format!("{base}/v1"), "sk-x", ""),
    )
    .await
    .unwrap();
    assert_eq!(out, "ok");

    let request = server.join().unwrap();
    assert!(
        request.starts_with("POST /v1/chat/completions "),
        "端点没补对：{request}"
    );
    // 模型留空 → 用默认模型
    assert!(
        request.contains(&format!("\"model\":\"{}\"", branchbase_core::translate::deepseek::DEFAULT_MODEL)),
        "默认模型没生效：{request}"
    );
}

#[tokio::test]
async fn 认证失败与余额不足的消息要能被上层分类() {
    let (base, _) = spawn_server(
        "401 Unauthorized",
        r#"{"error":{"message":"Authentication Fails, Your api key is invalid","type":"authentication_error","code":"invalid_api_key"}}"#,
    );
    let err = translate("hi", "en", "zh-CN", &options(base, "bad-key", ""))
        .await
        .unwrap_err()
        .to_string();
    // 关键词是契约：Kotlin 的 classify 靠「认证失败 / 401」判 AUTH
    assert!(err.contains("认证失败"), "{err}");
    assert!(err.contains("invalid_api_key"), "{err}");

    let (base, _) = spawn_server(
        "402 Payment Required",
        r#"{"error":{"message":"Insufficient Balance","type":"insufficient_balance"}}"#,
    );
    let err = translate("hi", "en", "zh-CN", &options(base, "sk-x", ""))
        .await
        .unwrap_err()
        .to_string();
    assert!(err.contains("余额不足"), "{err}");
}

#[tokio::test]
async fn 没填_key_时直接报认证错误而不是发请求() {
    let err = translate(
        "hi",
        "en",
        "zh-CN",
        &options("http://127.0.0.1:1".into(), "   ", ""),
    )
    .await
    .unwrap_err()
    .to_string();
    assert!(err.contains("认证失败"), "{err}");
    assert!(err.contains("API Key"), "{err}");
}
