//! JNI 导出函数
//!
//! 对应 Kotlin 侧 `com.branchbase.core.RustBridge` 的 `external fun` 声明。
//! 函数命名遵循 JNI 规范：`Java_<包名>_<类名>_<方法名>`。
//!
//! 约定：返回 JSON 字符串；出错时返回空字符串（Kotlin 侧判空处理）。

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::future::Future;
use std::sync::OnceLock;

use crate::auth::oauth::{OAuthClient, PkcePair};
use crate::auth::twofactor::TwoFactorVerifier;
use crate::error::CoreError;
use crate::models::OAuthConfig;

/// 全局 tokio runtime（复用，避免反复创建）
fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| tokio::runtime::Runtime::new().expect("创建 tokio runtime 失败"))
}

/// 阻塞执行异步任务
fn block_on<F: Future>(f: F) -> F::Output {
    runtime().block_on(f)
}

/// 从 JString 提取 Rust String
fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// 将 Result<String> 转为 JString（失败返回 "ERROR:..." 前缀，供 Kotlin 区分）
fn into_jstring(env: &mut JNIEnv, r: crate::error::Result<String>) -> jstring {
    match r {
        Ok(s) => env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => env
            .new_string(format!("ERROR:{e}"))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

/// 返回核心库版本号
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeCoreVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    env.new_string(crate::CORE_VERSION)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// 生成 PKCE 参数（返回 JSON: {"verifier": "...", "challenge": "..."}）
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGeneratePkce<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let pair = PkcePair::generate();
    let json = serde_json::json!({
        "verifier": pair.verifier,
        "challenge": pair.challenge,
    });
    into_jstring(&mut env, serde_json::to_string(&json).map_err(CoreError::from))
}

/// 构建 OAuth 授权 URL
/// 参数：clientId, redirectUri, host, scopes(空格分隔)
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeBuildAuthorizeUrl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_id: JString<'local>,
    redirect_uri: JString<'local>,
    host: JString<'local>,
    scopes: JString<'local>,
    challenge: JString<'local>,
) -> jstring {
    let config = OAuthConfig {
        client_id: jstr(&mut env, &client_id),
        client_secret: String::new(),
        redirect_uri: jstr(&mut env, &redirect_uri),
        host: jstr(&mut env, &host),
        scopes: jstr(&mut env, &scopes)
            .split_whitespace()
            .map(|s| s.to_string())
            .collect(),
    };
    let challenge = jstr(&mut env, &challenge);
    let client = OAuthClient::new(config);
    into_jstring(&mut env, client.authorize_url(&challenge))
}

/// 用授权码交换 token，返回 Session JSON
/// 参数：clientId, redirectUri, host, code, verifier
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeExchangeCode<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_id: JString<'local>,
    client_secret: JString<'local>,
    redirect_uri: JString<'local>,
    host: JString<'local>,
    code: JString<'local>,
    verifier: JString<'local>,
) -> jstring {
    let config = OAuthConfig {
        client_id: jstr(&mut env, &client_id),
        client_secret: jstr(&mut env, &client_secret),
        redirect_uri: jstr(&mut env, &redirect_uri),
        host: jstr(&mut env, &host),
        scopes: vec![],
    };
    let client = OAuthClient::new(config);
    let code = jstr(&mut env, &code);
    let verifier = jstr(&mut env, &verifier);

    let result: crate::error::Result<String> = block_on(async move {
        let session = client.exchange_code(&code, &verifier).await?;
        serde_json::to_string(&session).map_err(CoreError::from)
    });

    into_jstring(&mut env, result)
}

/// 获取当前用户信息（返回 User JSON）
/// 参数：host, accessToken
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetCurrentUser<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        let user = crate::api::GitHubApi::new(client).me().await?;
        serde_json::to_string(&user).map_err(CoreError::from)
    });

    into_jstring(&mut env, result)
}

/// 获取当前用户仓库列表（返回 Repository 数组 JSON）
/// 参数：host, accessToken
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetMyRepos<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        let repos = crate::api::GitHubApi::new(client).my_repos().await?;
        serde_json::to_string(&repos).map_err(CoreError::from)
    });

    into_jstring(&mut env, result)
}

/// 获取当前用户星标的仓库（返回 Repository 数组 JSON）
/// 参数：host, accessToken
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetStarredRepos<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        let repos = crate::api::GitHubApi::new(client).starred_repos().await?;
        serde_json::to_string(&repos).map_err(CoreError::from)
    });

    into_jstring(&mut env, result)
}

/// 获取当前用户软件包（返回 Package 数组 JSON）
/// 参数：host, accessToken
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetMyPackages<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        let packages = crate::api::GitHubApi::new(client).my_packages().await?;
        serde_json::to_string(&packages).map_err(CoreError::from)
    });

    into_jstring(&mut env, result)
}

/// 获取当前用户项目（返回 Project 数组 JSON）
/// 参数：host, accessToken
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetMyProjects<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        let projects = crate::api::GitHubApi::new(client).my_projects().await?;
        serde_json::to_string(&projects).map_err(CoreError::from)
    });

    into_jstring(&mut env, result)
}

/// 获取用户收到的事件（返回原始 JSON 数组）
/// 参数：host, accessToken, login
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetReceivedEvents<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    login: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let login = jstr(&mut env, &login);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).received_events(&login).await
    });

    into_jstring(&mut env, result)
}

/// 搜索仓库（返回原始 JSON 含 total_count + items）
/// 参数：host, accessToken, query, sort(可空)
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeSearchRepositories<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    query: JString<'local>,
    sort: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let query = jstr(&mut env, &query);
    let sort = jstr(&mut env, &sort);
    let sort_opt = if sort.is_empty() { None } else { Some(sort.as_str()) };

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).search_repositories(&query, sort_opt).await
    });

    into_jstring(&mut env, result)
}

/// 搜索用户（返回原始 JSON）
/// 参数：host, accessToken, query
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeSearchUsers<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    query: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let query = jstr(&mut env, &query);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).search_users(&query).await
    });

    into_jstring(&mut env, result)
}

/// 搜索 issues/PR（返回原始 JSON）
/// 参数：host, accessToken, query
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeSearchIssues<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    query: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let query = jstr(&mut env, &query);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).search_issues(&query).await
    });

    into_jstring(&mut env, result)
}

/// 搜索代码（返回原始 JSON，含 text_matches）
/// 参数：host, accessToken, query
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeSearchCode<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    query: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let query = jstr(&mut env, &query);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).search_code(&query).await
    });

    into_jstring(&mut env, result)
}

/// 搜索提交（返回原始 JSON 含 total_count + items）
/// 参数：host, accessToken, query
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeSearchCommits<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    query: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let query = jstr(&mut env, &query);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).search_commits(&query).await
    });

    into_jstring(&mut env, result)
}

/// 搜索主题（返回原始 JSON 含 total_count + items）
/// 参数：host, accessToken, query
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeSearchTopics<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    query: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let query = jstr(&mut env, &query);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).search_topics(&query).await
    });

    into_jstring(&mut env, result)
}

/// 校验 2FA 验证码格式（返回 "1" 表示有效，"0" 表示无效）
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeValidateTwoFactor<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    code: JString<'local>,
) -> jstring {
    let code = jstr(&mut env, &code);
    let valid = TwoFactorVerifier::validate_format(&code).is_ok();
    let out = if valid { "1" } else { "0" };
    env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// 用 refresh token 刷新 access token（返回新 Token JSON）
/// 参数：clientId, clientSecret, host, refreshToken
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeRefreshToken<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_id: JString<'local>,
    client_secret: JString<'local>,
    host: JString<'local>,
    refresh_token: JString<'local>,
) -> jstring {
    let config = OAuthConfig {
        client_id: jstr(&mut env, &client_id),
        client_secret: jstr(&mut env, &client_secret),
        redirect_uri: String::new(),
        host: jstr(&mut env, &host),
        scopes: vec![],
    };
    let client = OAuthClient::new(config);
    let refresh_token = jstr(&mut env, &refresh_token);

    let result: crate::error::Result<String> = block_on(async move {
        let token = client.refresh_token(&refresh_token).await?;
        serde_json::to_string(&token).map_err(CoreError::from)
    });

    into_jstring(&mut env, result)
}

// ── HTML 解析与跳转导航 ────────────────────────────────────

use crate::html::{resolve_link, ResolveContext};

/// 从 JString 参数构建链接解析上下文。
fn resolve_ctx(
    env: &mut JNIEnv,
    host: &JString,
    owner: &JString,
    repo: &JString,
    branch: &JString,
    base_dir: &JString,
    current_user: &JString,
) -> ResolveContext {
    ResolveContext {
        host: jstr(env, host),
        owner: jstr(env, owner),
        repo: jstr(env, repo),
        branch: jstr(env, branch),
        base_dir: jstr(env, base_dir),
        current_user: jstr(env, current_user),
    }
}

/// 解析单个链接为内部跳转目标（返回 Destination JSON）
/// 参数：url, host, owner, repo, branch, baseDir, currentUser
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeResolveLink<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    host: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
    branch: JString<'local>,
    base_dir: JString<'local>,
    current_user: JString<'local>,
) -> jstring {
    let url = jstr(&mut env, &url);
    let ctx = resolve_ctx(&mut env, &host, &owner, &repo, &branch, &base_dir, &current_user);
    let dest = resolve_link(&url, &ctx);
    into_jstring(
        &mut env,
        serde_json::to_string(&dest).map_err(CoreError::from),
    )
}

/// 解析 README 渲染 HTML 为块级树（返回 `{"blocks":[...]}` JSON，每个链接已解析 dest）
/// 参数：html, host, owner, repo, branch, baseDir, currentUser
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeParseHtml<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    html: JString<'local>,
    host: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
    branch: JString<'local>,
    base_dir: JString<'local>,
    current_user: JString<'local>,
) -> jstring {
    let html = jstr(&mut env, &html);
    let ctx = resolve_ctx(&mut env, &host, &owner, &repo, &branch, &base_dir, &current_user);
    into_jstring(&mut env, crate::html::parse_html_json(&html, &ctx).map_err(CoreError::from))
}

/// 获取仓库 README 渲染 HTML（返回 HTML 字符串）
/// 参数：host, accessToken, owner, repo
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeReadmeHtml<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let owner = jstr(&mut env, &owner);
    let repo = jstr(&mut env, &repo);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).readme_html(&owner, &repo).await
    });

    into_jstring(&mut env, result)
}

/// 获取单个仓库信息（返回原始 JSON）
/// 参数：host, accessToken, owner, repo
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetRepoInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let owner = jstr(&mut env, &owner);
    let repo = jstr(&mut env, &repo);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).repo_info(&owner, &repo).await
    });

    into_jstring(&mut env, result)
}

/// 获取仓库语言统计（返回 {语言:字节数} JSON）
/// 参数：host, accessToken, owner, repo
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetRepoLanguages<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let owner = jstr(&mut env, &owner);
    let repo = jstr(&mut env, &repo);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).repo_languages(&owner, &repo).await
    });

    into_jstring(&mut env, result)
}

/// 获取仓库贡献者（返回 JSON 数组）
/// 参数：host, accessToken, owner, repo
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetRepoContributors<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let owner = jstr(&mut env, &owner);
    let repo = jstr(&mut env, &repo);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).repo_contributors(&owner, &repo).await
    });

    into_jstring(&mut env, result)
}

/// 通用 GET（列表等任意路径，返回原始 JSON）
/// 参数：host, accessToken, path（如 "/repos/{o}/{r}/issues?state=open"）
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGetJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    path: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let path = jstr(&mut env, &path);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        client.get_json(&path).await
    });

    into_jstring(&mut env, result)
}

/// 将 markdown 渲染为 HTML（返回 HTML 字符串）
/// 参数：host, accessToken, text
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeRenderMarkdown<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    text: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let text = jstr(&mut env, &text);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client).render_markdown(&text).await
    });

    into_jstring(&mut env, result)
}

// ── Git 工具包（libgit2） ──

/// clone 仓库到本地（返回空串=成功，ERROR:=失败）
/// 参数：url, into, branch(可空), token(可空)
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGitClone<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    into: JString<'local>,
    branch: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let url = jstr(&mut env, &url);
    let into = jstr(&mut env, &into);
    let branch = jstr(&mut env, &branch);
    let token = jstr(&mut env, &token);
    let branch_opt = if branch.is_empty() { None } else { Some(branch.as_str()) };
    let token_opt = if token.is_empty() { None } else { Some(token.as_str()) };

    let result: crate::error::Result<String> =
        crate::git::clone_repo(&url, &into, branch_opt, token_opt).map(|_| String::new());
    into_jstring(&mut env, result)
}

/// pull 仓库（返回空串=成功，ERROR:=失败）
/// 参数：dir, token(可空)
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGitPull<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dir: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let dir = jstr(&mut env, &dir);
    let token = jstr(&mut env, &token);
    let token_opt = if token.is_empty() { None } else { Some(token.as_str()) };

    let result: crate::error::Result<String> =
        crate::git::pull_repo(&dir, token_opt).map(|_| String::new());
    into_jstring(&mut env, result)
}

/// 更新/新建单文件（`PUT /repos/{o}/{r}/contents/{path}`，返回原始 JSON）
/// 参数：host, token, owner, repo, path, message, content, sha, branch
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativePutContents<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
    path: JString<'local>,
    message: JString<'local>,
    content: JString<'local>,
    sha: JString<'local>,
    branch: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let owner = jstr(&mut env, &owner);
    let repo = jstr(&mut env, &repo);
    let path = jstr(&mut env, &path);
    let message = jstr(&mut env, &message);
    let content = jstr(&mut env, &content);
    let sha = jstr(&mut env, &sha);
    let branch = jstr(&mut env, &branch);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client)
            .put_contents(&owner, &repo, &path, &message, &content, &sha, &branch)
            .await
    });

    into_jstring(&mut env, result)
}

/// 本地 git commit（暂存 + 提交，返回 commit sha）
/// 参数：dir, message, authorName, authorEmail
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGitCommit<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dir: JString<'local>,
    message: JString<'local>,
    author_name: JString<'local>,
    author_email: JString<'local>,
) -> jstring {
    let dir = jstr(&mut env, &dir);
    let message = jstr(&mut env, &message);
    let author_name = jstr(&mut env, &author_name);
    let author_email = jstr(&mut env, &author_email);

    let result: crate::error::Result<String> =
        crate::git::commit_repo(&dir, &message, &author_name, &author_email);
    into_jstring(&mut env, result)
}

/// 本地 git push（推送到 origin，返回空串=成功）
/// 参数：dir, token(可空), branch
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeGitPush<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    dir: JString<'local>,
    token: JString<'local>,
    branch: JString<'local>,
) -> jstring {
    let dir = jstr(&mut env, &dir);
    let token = jstr(&mut env, &token);
    let branch = jstr(&mut env, &branch);
    let token_opt = if token.is_empty() { None } else { Some(token.as_str()) };

    let result: crate::error::Result<String> =
        crate::git::push_repo(&dir, token_opt, &branch).map(|_| String::new());
    into_jstring(&mut env, result)
}

/// 拉取 latest release 的 signature.txt 校验文件内容
/// 参数：host, token, owner, repo
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeLatestReleaseSignature<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let owner = jstr(&mut env, &owner);
    let repo = jstr(&mut env, &repo);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client)
            .latest_release_signature(&owner, &repo)
            .await
    });

    into_jstring(&mut env, result)
}

/// 拉取仓库 verify/signature.txt 校验文件内容
/// 参数：host, token, owner, repo
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeRepoSignature<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    owner: JString<'local>,
    repo: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let owner = jstr(&mut env, &owner);
    let repo = jstr(&mut env, &repo);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client)
            .repo_signature(&owner, &repo)
            .await
    });

    into_jstring(&mut env, result)
}

/// 标记单条通知已读（PATCH /notifications/threads/{id}）
/// 参数：host, token, threadId
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeMarkNotificationRead<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
    thread_id: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);
    let thread_id = jstr(&mut env, &thread_id);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client)
            .mark_notification_read(&thread_id)
            .await
    });

    into_jstring(&mut env, result)
}

/// 标记全部通知已读（PUT /notifications）
/// 参数：host, token
#[no_mangle]
pub extern "system" fn Java_com_branchbase_core_RustBridge_nativeMarkAllNotificationsRead<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    token: JString<'local>,
) -> jstring {
    let host = jstr(&mut env, &host);
    let token = jstr(&mut env, &token);

    let result: crate::error::Result<String> = block_on(async move {
        let client = crate::api::ApiClient::new(&host, &token);
        crate::api::GitHubApi::new(client)
            .mark_all_notifications_read()
            .await
    });

    into_jstring(&mut env, result)
}