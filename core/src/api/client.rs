//! 底层 HTTP 客户端（封装 reqwest，统一注入 token 与请求头）

use crate::error::{CoreError, Result};
use std::path::Path;
use std::sync::RwLock;
use std::time::Duration;

/// 单个附件的上限。
///
/// GitHub 自己的上限是 2GiB，但本实现是把文件读进内存再发（原因见 [ApiClient::post_binary]），
/// 移动端一次分配 2GB 必被 OOM 杀掉。超过这个值宁可明确报错，让用户去网页端传。
const MAX_UPLOAD_BYTES: u64 = 256 * 1024 * 1024;

/// 上传专用的 HTTP 客户端。
///
/// **为什么不复用 [shared_http]**：那个客户端带 15s **总**超时（对 JSON 请求是必要的兜底），
/// 而一个几十 MB 的 APK 上传经常要跑十几秒到几分钟 —— 用同一个客户端会把正常上传掐断在
/// 15 秒处，且报错长得像网络故障。这里只保留连接超时。
fn upload_http() -> reqwest::Client {
    get_or_build(&UPLOAD_HTTP, || {
        reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(20))
            .tcp_nodelay(true)
            .build()
            .expect("构建上传客户端失败")
    })
}

/// 进程内共享的 HTTP 客户端。
///
/// **为什么必须共享**：每个 JNI 调用各自 `reqwest::Client::builder().build()` 会拿到
/// 全新的连接池与 TLS 会话 —— 实测一次冷连接比复用连接多约 **390ms**
/// （DNS 48ms + TCP 159ms + TLS 296ms，本机到 api.github.com）。仓库页一次要发 4 个请求，
/// 串行 + 每次重建连接 ≈ 3.4s，共享连接池后握手只付一次。
///
/// 同时启用 HTTP/2（Cargo 的 `http2` feature）—— 多个并行请求可复用一个连接多路复用。
///
/// **为什么是可丢弃的**（不是 `OnceLock<Client>`）：池里的连接是在**建池那一刻的网络**上
/// 握手完成的。手机切换网络（最典型的是「App 先开着、之后才接入 VPN」）后，这些连接既不可用
/// 也不会自己消失 —— 后续请求继续复用它们只会一路超时，「换了网络还是打不开远端」的
/// 一半成因就在这里。所以池子放在 [RwLock] 里，网络跃迁时由 [reset_http_client] 整个丢掉，
/// 下一次请求重新建连（见 Kotlin 侧 `NetworkWatch`）。
///
/// 返回值是 `Client` 而非引用：`reqwest::Client` 内部是 `Arc`，clone 只加引用计数、不复制连接池。
/// 在途请求各自持有引用，重置不会把它们掐断 —— 丢掉的只是「以后新建请求要用的那一份」。
pub(crate) fn shared_http() -> reqwest::Client {
    get_or_build(&SHARED_HTTP, build_shared_http)
}

/// 丢弃进程内共享的 HTTP 客户端（含上传专用那一份）：连接池与 TLS 会话随之释放，
/// 下一次取用时重建。
///
/// 只在网络路径发生变化时调用（VPN 接入 / 断开、换网）。调用本身不做 IO，
/// 在途请求各自持有引用计数、不受影响。
pub fn reset_http_client() {
    *lock_mut(&SHARED_HTTP) = None;
    *lock_mut(&UPLOAD_HTTP) = None;
}

fn build_shared_http() -> reqwest::Client {
    #[cfg(test)]
    {
        BUILDS.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    }
    reqwest::Client::builder()
        // 超时兜底：避免请求永久挂起（README/列表加载卡死根因）
        .timeout(Duration::from_secs(15))
        // 连接池：并行加载最多同时用到 4~6 个连接，留出余量
        .pool_max_idle_per_host(8)
        // 空闲连接保留 90s：列表→详情→切 tab 的连续操作都能命中同一连接
        .pool_idle_timeout(Duration::from_secs(90))
        .tcp_nodelay(true)
        .tcp_keepalive(Duration::from_secs(60))
        .build()
        .expect("构建 reqwest 客户端失败")
}

/// 客户端存放槽。`RwLock<Option<..>>` 而不是 `OnceLock`：值必须能被换掉（见 [shared_http]）。
type ClientSlot = RwLock<Option<reqwest::Client>>;

static SHARED_HTTP: ClientSlot = RwLock::new(None);
static UPLOAD_HTTP: ClientSlot = RwLock::new(None);

/// 取槽里的客户端（没有则返回 None）。
fn current(slot: &'static ClientSlot) -> Option<reqwest::Client> {
    slot.read().unwrap_or_else(|e| e.into_inner()).as_ref().cloned()
}

/// 双重检查地取用：并发首次取用时 [build] 只跑一次。
fn get_or_build(slot: &'static ClientSlot, build: fn() -> reqwest::Client) -> reqwest::Client {
    if let Some(client) = current(slot) {
        return client;
    }
    let mut guard = lock_mut(slot);
    if let Some(client) = guard.as_ref() {
        return client.clone();
    }
    let built = build();
    *guard = Some(built.clone());
    built
}

fn lock_mut(slot: &'static ClientSlot) -> std::sync::RwLockWriteGuard<'static, Option<reqwest::Client>> {
    // 毒化（持锁线程 panic）时照常取用：这里没有会被写坏的不变量，
    // 宁可继续用也不能让一次 panic 永久废掉整个客户端的重建能力。
    slot.write().unwrap_or_else(|e| e.into_inner())
}

/// 客户端被真实构建的次数。只给单测用：证明 [reset_http_client] 之后确实重建了。
#[cfg(test)]
static BUILDS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[cfg(test)]
fn build_count() -> u64 {
    BUILDS.load(std::sync::atomic::Ordering::SeqCst)
}

/// API 客户端：持有 base host 与 access token（HTTP 连接由 [shared_http] 复用）
#[derive(Debug, Clone)]
pub struct ApiClient {
    pub host: String,
    pub token: String,
    http: reqwest::Client,
}

impl ApiClient {
    pub fn new(host: impl Into<String>, token: impl Into<String>) -> Self {
        Self {
            host: host.into(),
            token: token.into(),
            // reqwest::Client 内部是 Arc，clone 只增加引用计数，不会新建连接池
            http: shared_http(),
        }
    }

    /// 构造 API 基础 URL（数据请求走 api.github.com，OAuth 端点才走 github.com）
    pub fn base_url(&self) -> String {
        if self.host == "github.com" {
            "https://api.github.com/".to_string()
        } else {
            format!("https://{}/api/v3/", self.host)
        }
    }

    /// 带鉴权的 GET 请求，返回 JSON 字符串
    pub async fn get_json(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .get(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 GET 请求（自定义 Accept），返回 JSON 字符串
    pub async fn get_json_with_accept(&self, path: &str, accept: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .get(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", accept)
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 POST 请求，返回 JSON 字符串
    pub async fn post_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .post(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 把本地文件作为请求体 POST 到一个**绝对 URL**，返回响应体。
    ///
    /// 与 [post_json] 的三点不同：
    /// 1. **不同源**：release 附件走 `uploads.github.com`，`base_url()` 拼不出来，所以收绝对 URL；
    /// 2. **请求体是二进制**，`Content-Type` 由调用方按扩展名给；
    /// 3. 走 [upload_http]（没有总超时），见那个函数的注释。
    ///
    /// ## 关于「一次性读进内存」
    ///
    /// 本仓库的 reqwest 只开了 `json / rustls-tls / http2`，流式 body（`Body::wrap_stream`）
    /// 挂在 `stream` feature 下，而该 feature 会连带 `wasm-streams`（离线环境取不到，交叉编译也不需要）。
    /// 所以这里在 `spawn_blocking` 里读成 `Vec<u8>` 再发，并用 [MAX_UPLOAD_BYTES] 兜住上限。
    /// 要升级成真正的流式：给 reqwest 开 `stream`，把 body 换成
    /// `Body::wrap_stream(tokio_util::io::ReaderStream::new(tokio::fs::File::open(path).await?))`。
    pub async fn post_binary(&self, url: &str, file: &Path, content_type: &str) -> Result<String> {
        let path = file.to_path_buf();
        // 文件 IO 是阻塞的：放到阻塞线程池，别把 runtime 的工作线程占住
        let bytes = tokio::task::spawn_blocking(move || -> Result<Vec<u8>> {
            let len = std::fs::metadata(&path)
                .map_err(|e| CoreError::Other(format!("读取附件失败: {e}")))?
                .len();
            if len == 0 {
                return Err(CoreError::Other("附件是空文件".to_string()));
            }
            if len > MAX_UPLOAD_BYTES {
                return Err(CoreError::Other(format!(
                    "附件超过 {} MB 上限，请改用网页端上传",
                    MAX_UPLOAD_BYTES / 1024 / 1024
                )));
            }
            std::fs::read(&path).map_err(|e| CoreError::Other(format!("读取附件失败: {e}")))
        })
        .await
        .map_err(|e| CoreError::Other(format!("上传任务异常: {e}")))??;

        let len = bytes.len();
        let resp = upload_http()
            .post(url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", content_type)
            .header("Content-Length", len.to_string())
            .header("User-Agent", "Branchbase/0.1")
            .body(bytes)
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 POST（`Authorization: bearer`，用于 GraphQL v4 端点）
    ///
    /// 与 [post_json] 只差鉴权前缀：REST 用 `token`，GraphQL 文档要求 `bearer`。
    pub async fn post_bearer_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .post(&url)
            .header("Authorization", format!("bearer {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PUT 请求，返回 JSON 字符串（用于更新/新建文件）
    pub async fn put_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .put(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PATCH 请求，返回 JSON 字符串（用于标记通知已读等）
    pub async fn patch_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .patch(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PUT 请求（无 body，用于标记全部通知已读等），返回 JSON 字符串
    pub async fn put_empty(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .put(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PATCH 请求（无 body，用于标记单条通知已读等），返回 JSON 字符串
    pub async fn patch_empty(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .patch(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 下载任意 URL 的文本内容（公开资源，不带鉴权，如 release 附件）
    /// 带鉴权的 DELETE 请求（删除分支/仓库等），返回 JSON 字符串或空串
    pub async fn delete_json(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .delete(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    pub async fn get_raw_text(&self, url: &str) -> Result<String> {
        let resp = self
            .http
            .get(url)
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }
}
#[cfg(test)]
mod tests {
    use super::{build_count, reset_http_client, shared_http};
    use std::io::{Read, Write};
    use std::net::TcpListener;

    /// 起一个只回 `200 {}` 的回环服务，接受 [times] 次连接后退出。
    fn spawn_server(times: usize) -> (String, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("绑定回环端口失败");
        let addr = listener.local_addr().unwrap();
        let handle = std::thread::spawn(move || {
            for _ in 0..times {
                let Ok((mut stream, _)) = listener.accept() else { return };
                // GET 没有正文，读一次请求头就够（回环上一次 read 就能拿全）
                let mut buf = [0u8; 1024];
                let _ = stream.read(&mut buf);
                let body = "{}";
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.flush();
            }
        });
        (format!("http://{addr}"), handle)
    }

    /// 网络跃迁后调 [reset_http_client]，下一次必须**真的重建**客户端 ——
    /// 不重建就等于池子里还留着切换前那条网络上的连接，症状原样存在。
    #[test]
    fn 重置之后共享客户端会重建() {
        let _ = shared_http(); // 先确保已经建过一份
        let before = build_count();
        reset_http_client();
        let _ = shared_http();
        assert!(
            build_count() > before,
            "reset_http_client 之后应重新构建客户端（before={before}, after={}）",
            build_count()
        );
    }

    /// 重置不是「把客户端废掉」：重建出来的那一份仍要能正常发请求。
    /// 两次请求各自建连（服务端接两次），正好覆盖「丢池 → 重建 → 再发」这条路径。
    #[tokio::test]
    async fn 重置之后重建的客户端仍能发请求() {
        let (base, server) = spawn_server(2);
        let first = shared_http().get(&base).send().await.expect("第一次请求失败");
        assert!(first.status().is_success());

        reset_http_client();

        let second = shared_http().get(&base).send().await.expect("重建后请求失败");
        assert!(second.status().is_success());
        let _ = server.join();
    }
}
