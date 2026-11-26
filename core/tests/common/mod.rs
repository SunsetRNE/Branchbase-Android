//! 集成测试共用的小工具：一个**一次性回环 HTTP 服务**。
//!
//! 为什么不用 mock 库：要钉的是「请求行 / 请求头 / 请求体到底长什么样」，
//! 而 reqwest 到线上服务之间只差一个 socket。回环服务收到的就是真实字节，
//! 比断言 `RequestBuilder` 的中间态可靠。
//!
//! `tests/common/mod.rs` 不会被当成独立测试目标（Cargo 约定：子目录里的模块要显式 `mod`）。

#![allow(dead_code)]

use branchbase_core::translate::Options;

use std::io::{Read, Write};
use std::net::TcpListener;

/// 起一次性回环服务：收一个请求、回一个固定响应，并把请求原文交回给测试。
pub fn spawn_server(
    status: &'static str,
    body: &'static str,
) -> (String, std::thread::JoinHandle<String>) {
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

/// 起一个**多请求**回环服务：按顺序回放 `responses`，把收到的请求原文按序交回。
///
/// 用来测「服务商不认字段 → 去掉字段重试一次」这类**两次请求**的行为。
pub fn spawn_sequence(
    responses: Vec<(&'static str, &'static str)>,
) -> (String, std::thread::JoinHandle<Vec<String>>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("绑定回环端口失败");
    let addr = listener.local_addr().unwrap();

    let handle = std::thread::spawn(move || {
        let mut requests = Vec::new();
        for (status, body) in responses {
            let (mut stream, _) = listener.accept().expect("accept 失败");
            let mut buf: Vec<u8> = Vec::new();
            let mut chunk = [0u8; 1024];
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
            requests.push(String::from_utf8_lossy(&buf).to_string());
        }
        requests
    });

    (format!("http://{addr}"), handle)
}

/// 一份「自带 Key 的大模型」选项：协议码 + 地址 + Key + 模型。
pub fn options(protocol: &str, base_url: String, key: &str, model: &str) -> Options {
    Options {
        provider: "deepseek".into(),
        protocol: protocol.into(),
        api_key: key.into(),
        model: model.into(),
        base_url,
        thinking: Default::default(),
    }
}
