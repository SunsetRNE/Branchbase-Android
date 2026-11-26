//! OpenAI Chat Completions 的**真实 HTTP 形状**集成测试。
//!
//! 这一档是覆盖面最广的一档（任何 OpenAI 兼容的厂商 / 中转 / 自建网关），
//! 所以这里钉的是**通用契约**，不是某一家：请求行、鉴权头、请求体字段、错误消息的可分类关键词。
//!
//! 端点用本机回环服务，不联网、不花钱。

mod common;

use branchbase_core::translate::{translate, Options};
use common::spawn_server;

fn options(base_url: String, key: &str, model: &str) -> Options {
    Options {
        provider: "deepseek".into(),
        protocol: "openai-chat".into(),
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
    assert!(
        request.starts_with("POST /v1/chat/completions "),
        "请求行不对：{request}"
    );
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
async fn 自定义_base_url_会补上_路径且模型原样透传() {
    let (base, server) = spawn_server("200 OK", r#"{"choices":[{"message":{"content":"ok"}}]}"#);
    // 用户填的是中转的 /v1 地址（DeepSeek 官方地址也长这样）
    let out = translate(
        "hi",
        "en",
        "zh-CN",
        &options(format!("{base}/v1"), "sk-x", "some-model"),
    )
    .await
    .unwrap();
    assert_eq!(out, "ok");

    let request = server.join().unwrap();
    assert!(
        request.starts_with("POST /v1/chat/completions "),
        "端点没补对：{request}"
    );
    // 1.4.54 起没有内置默认模型：填了什么就发什么
    assert!(
        request.contains("\"model\":\"some-model\""),
        "模型没原样透传：{request}"
    );
}

#[tokio::test]
async fn 模型为空时不发请求_直接给配置错误() {
    // 端口 1 一定连不上：真发了请求就会得到网络错误
    let err = translate(
        "hi",
        "en",
        "zh-CN",
        &options("http://127.0.0.1:1".into(), "sk-x", "   "),
    )
    .await
    .unwrap_err();
    assert_eq!(err.code(), Some("TRANSLATE_AUTH"), "{err}");
    assert!(err.to_string().contains("模型"), "{err}");
}

#[tokio::test]
async fn 认证失败与余额不足的消息要能被上层分类() {
    let (base, _) = spawn_server(
        "401 Unauthorized",
        r#"{"error":{"message":"Authentication Fails, Your api key is invalid","type":"authentication_error","code":"invalid_api_key"}}"#,
    );
    let err = translate("hi", "en", "zh-CN", &options(base, "bad-key", "m"))
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
    let err = translate("hi", "en", "zh-CN", &options(base, "sk-x", "m"))
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
        &options("http://127.0.0.1:1".into(), "   ", "m"),
    )
    .await
    .unwrap_err()
    .to_string();
    assert!(err.contains("认证失败"), "{err}");
    assert!(err.contains("API Key"), "{err}");
}
