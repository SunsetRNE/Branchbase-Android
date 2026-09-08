//! Git 工具包：基于 libgit2（`git2` crate），提供 clone / pull（fast-forward）。
//!
//! 对齐 `docs/code-editing-collaboration-thinking.md` §9：Git 引擎（libgit2）+ 稳定接口。
//! HTTPS 走 vendored OpenSSL（见 Cargo.toml）。

use crate::error::{CoreError, Result};

/// 浅 clone 仓库到本地目录。
///
/// - `url`：仓库 HTTPS URL（如 `https://github.com/o/r.git`）。
/// - `into`：本地目标目录（绝对路径）。
/// - `branch`：要检出的分支（`None` = 默认分支）。
/// - `token`：可选的 PAT（私有仓库用；`None` = 匿名）。
pub fn clone_repo(url: &str, into: &str, branch: Option<&str>, token: Option<&str>) -> Result<()> {
    use git2::build::RepoBuilder;
    use git2::{FetchOptions, RemoteCallbacks};

    let mut callbacks = RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    } else {
        callbacks.credentials(|_url, _username, _allowed| git2::Cred::default());
    }

    let mut fo = FetchOptions::new();
    fo.remote_callbacks(callbacks).depth(1); // 浅 clone，减体积

    let mut builder = RepoBuilder::new();
    builder.fetch_options(fo);
    if let Some(b) = branch {
        builder.branch(b);
    }

    builder
        .clone(url, std::path::Path::new(into))
        .map_err(|e| CoreError::Other(format!("clone 失败: {e}")))?;
    Ok(())
}

/// pull：fetch origin 并 fast-forward 当前分支到远端。
pub fn pull_repo(dir: &str, token: Option<&str>) -> Result<()> {
    use git2::{FetchOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

    let mut callbacks = RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut fo = FetchOptions::new();
    fo.remote_callbacks(callbacks);
    remote
        .fetch(&["refs/heads/*:refs/remotes/origin/*"], Some(&mut fo), None)
        .map_err(|e| CoreError::Other(format!("fetch 失败: {e}")))?;

    // 当前分支名
    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let branch_short = head
        .shorthand()
        .ok_or_else(|| CoreError::Other("无法获取分支名".into()))?
        .to_string();
    let head_name = head
        .name()
        .ok_or_else(|| CoreError::Other("无法获取 ref 名".into()))?
        .to_string();

    // 远端跟踪分支
    let remote_ref_name = format!("refs/remotes/origin/{branch_short}");
    let remote_ref = match repo.find_reference(&remote_ref_name) {
        Ok(r) => r,
        Err(_) => return Ok(()), // 无远端跟踪分支，跳过合并
    };
    let remote_commit = repo
        .reference_to_annotated_commit(&remote_ref)
        .map_err(|e| CoreError::Other(format!("解析远端提交失败: {e}")))?;

    let (analysis, _) = repo
        .merge_analysis(&[&remote_commit])
        .map_err(|e| CoreError::Other(format!("merge 分析失败: {e}")))?;

    if analysis.is_fast_forward() {
        let mut head_ref = repo
            .find_reference(&head_name)
            .map_err(|e| CoreError::Other(format!("读取分支 ref 失败: {e}")))?;
        head_ref
            .set_target(remote_commit.id(), "fast-forward")
            .map_err(|e| CoreError::Other(format!("fast-forward 失败: {e}")))?;
        repo.set_head(&head_name)
            .map_err(|e| CoreError::Other(format!("set_head 失败: {e}")))?;
        repo.checkout_head(Some(git2::build::CheckoutBuilder::default().force()))
            .map_err(|e| CoreError::Other(format!("checkout 失败: {e}")))?;
    } else if !analysis.is_up_to_date() {
        // 本地有未推送提交 + 远端领先 → 分叉（对齐 P0-1 分叉决策页触发条件）
        return Err(CoreError::Other("nff: 本地与远端分叉（non-fast-forward）".into()));
    }
    Ok(())
}

/// 本地 git commit：暂存所有改动 + 提交（返回 commit sha）
pub fn commit_repo(dir: &str, message: &str, author_name: &str, author_email: &str) -> Result<String> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut index = repo.index().map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    index
        .add_all(["*"].iter(), git2::IndexAddOption::DEFAULT, None)
        .map_err(|e| CoreError::Other(format!("暂存失败: {e}")))?;
    index.write().map_err(|e| CoreError::Other(format!("写索引失败: {e}")))?;

    let tree_id = index.write_tree().map_err(|e| CoreError::Other(format!("写树失败: {e}")))?;
    let tree = repo.find_tree(tree_id).map_err(|e| CoreError::Other(format!("找树失败: {e}")))?;
    let sig = Signature::now(author_name, author_email)
        .map_err(|e| CoreError::Other(format!("签名失败: {e}")))?;

    let parent = repo
        .head()
        .ok()
        .and_then(|h| h.target())
        .and_then(|t| repo.find_commit(t).ok());
    let parents: Vec<&git2::Commit> = parent.iter().collect();

    let id = repo
        .commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)
        .map_err(|e| CoreError::Other(format!("commit 失败: {e}")))?;
    Ok(id.to_string())
}

/// 本地 git push：推送到 origin
pub fn push_repo(dir: &str, token: Option<&str>, branch: &str) -> Result<()> {
    use git2::{PushOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

    let mut callbacks = RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut opts = PushOptions::new();
    opts.remote_callbacks(callbacks);
    let refspec = format!("refs/heads/{branch}:refs/heads/{branch}");
    remote
        .push(&[&refspec], Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("push 失败: {e}")))?;
    Ok(())
}
// ── HTTPS 证书验证（Android 无 OpenSSL 兼容 CA 路径，自验证书链） ──

use std::sync::OnceLock;
use openssl::nid::Nid;
use openssl::x509::X509;

/// 内置 Mozilla CA bundle 解析（一次性，include_bytes 打包）
static CA_CERTS: OnceLock<Vec<X509>> = OnceLock::new();

fn ca_certs() -> &'static Vec<X509> {
    CA_CERTS.get_or_init(|| {
        let bundle = include_bytes!("../certs/cacert.pem");
        X509::stack_from_pem(bundle).unwrap_or_default()
    })
}

/// 通配符域名匹配（`*.github.com`）。
///
/// 要求 host 以 `.<suffix>` 结尾，且 `<suffix>` 之前恰好是一段不含点的主机名：
/// - `*.github.com` 匹配 `api.github.com`
/// - 不匹配 `a.b.github.com`（多级子域）
/// - 不匹配 `xgithub.com`（缺少点分隔，早期实现会误判为匹配）
/// - 不匹配裸 `github.com`
fn dns_matches(pattern: &str, host: &str) -> bool {
    if let Some(rest) = pattern.strip_prefix("*.") {
        let host_bytes = host.as_bytes();
        let rest_bytes = rest.as_bytes();
        if host_bytes.len() <= rest_bytes.len() + 1 || !host.ends_with(rest) {
            return false;
        }
        let dot_at = host_bytes.len() - rest_bytes.len() - 1;
        if host_bytes[dot_at] != b'.' {
            return false;
        }
        !host_bytes[..dot_at].contains(&b'.')
    } else {
        pattern == host
    }
}

/// 主机名校验：SAN（优先）→ CN 兜底
fn hostname_matches(leaf: &X509, host: &str) -> bool {
    let host = host.trim_end_matches('.').to_lowercase();
    if let Some(names) = leaf.subject_alt_names() {
        for n in names {
            if let Some(dns) = n.dnsname() {
                if dns_matches(&dns.to_lowercase(), &host) {
                    return true;
                }
            }
        }
    }
    for e in leaf.subject_name().entries_by_nid(Nid::COMMONNAME) {
        if let Ok(cn) = e.data().to_string() {
            if dns_matches(&cn.to_lowercase(), &host) {
                return true;
            }
        }
    }
    false
}

/// 用内置 bundle 验证证书链：逐级找 issuer、验签名，走到自签名根即通过
fn verify_cert_chain(leaf: &X509) -> bool {
    let certs = ca_certs();
    let mut current = leaf.clone();
    for _ in 0..8 {
        let issuer_name = current.issuer_name();
        let Some(ca) = certs.iter().find(|c| c.subject_name().try_cmp(&issuer_name).ok() == Some(std::cmp::Ordering::Equal)) else {
            return false;
        };
        let Ok(pubkey) = ca.public_key() else {
            return false;
        };
        if current.verify(&pubkey).is_err() {
            return false;
        }
        if ca.subject_name().try_cmp(ca.issuer_name()).ok() == Some(std::cmp::Ordering::Equal) {
            return true; // 自签名根，链走通
        }
        current = ca.clone();
    }
    false
}

/// libgit2 certificate_check 回调：验证通过 → Ok；否则交还 libgit2（其 openssl 后端会失败，
/// 不会静默放行不安全的连接）
fn check_cert(
    cert: &git2::cert::Cert<'_>,
    host: &str,
) -> std::result::Result<git2::CertificateCheckStatus, git2::Error> {
    let Some(x509_cert) = cert.as_x509() else {
        return Ok(git2::CertificateCheckStatus::CertificatePassthrough);
    };
    let Ok(leaf) = X509::from_der(x509_cert.data()) else {
        return Ok(git2::CertificateCheckStatus::CertificatePassthrough);
    };
    if hostname_matches(&leaf, host) && verify_cert_chain(&leaf) {
        Ok(git2::CertificateCheckStatus::CertificateOk)
    } else {
        Ok(git2::CertificateCheckStatus::CertificatePassthrough)
    }
}

// ── 决策页面支持 API（对齐 docs/decision-pages-gap.md §6） ──

use serde_json::json;

/// 仓库状态（JSON）：branch / ahead / behind / has_upstream / remote_url / dirty / unpushed。
/// 供分叉决策、Git 化回退、删除升级警告、撤销误提交等决策页面读取事实区。
pub fn repo_status(dir: &str) -> Result<String> {
    use git2::{Repository, StatusOptions};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;

    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let branch = head.shorthand().unwrap_or("").to_string();
    let local_commit = head
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 提交失败: {e}")))?;

    // 上游与领先/落后计数
    let mut ahead: usize = 0;
    let mut behind: usize = 0;
    let mut has_upstream = false;
    let mut remote_oid: Option<git2::Oid> = None;
    if let Ok(local_branch) = repo.find_branch(&branch, git2::BranchType::Local) {
        if let Ok(upstream) = local_branch.upstream() {
            has_upstream = true;
            if let (Some(local_oid), Some(up_oid)) = (local_commit.id().into(), upstream.get().target()) {
                let (a, b) = repo
                    .graph_ahead_behind(local_oid, up_oid)
                    .map_err(|e| CoreError::Other(format!("计算领先落后失败: {e}")))?;
                ahead = a;
                behind = b;
                remote_oid = Some(up_oid);
            }
        }
    }

    // 远端 URL
    let remote_url = repo
        .find_remote("origin")
        .ok()
        .and_then(|r| r.url().map(|s| s.to_string()))
        .unwrap_or_default();

    // dirty 文件清单
    let mut dirty = Vec::new();
    let mut opts = StatusOptions::new();
    opts.include_untracked(true);
    let statuses = repo
        .statuses(Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("读取工作区状态失败: {e}")))?;
    for entry in statuses.iter() {
        let s = entry.status();
        let st = if s.is_index_new() || s.is_wt_new() {
            "A"
        } else if s.is_index_deleted() || s.is_wt_deleted() {
            "D"
        } else {
            "M"
        };
        if let Some(p) = entry.path() {
            dirty.push(json!({ "path": p, "status": st }));
        }
    }

    // 未推送提交清单（HEAD..上游，最多 50 条）
    let mut unpushed = Vec::new();
    if let Some(up_oid) = remote_oid {
        let mut revwalk = repo
            .revwalk()
            .map_err(|e| CoreError::Other(format!("创建 revwalk 失败: {e}")))?;
        revwalk.push_head().map_err(|e| CoreError::Other(format!("revwalk push_head 失败: {e}")))?;
        revwalk.hide(up_oid).map_err(|e| CoreError::Other(format!("revwalk hide 失败: {e}")))?;
        for oid in revwalk.take(50) {
            let oid = oid.map_err(|e| CoreError::Other(format!("revwalk 迭代失败: {e}")))?;
            if let Ok(c) = repo.find_commit(oid) {
                unpushed.push(json!({
                    "sha": &c.id().to_string()[..7],
                    "message": c.summary().unwrap_or("")
                }));
            }
        }
    }

    Ok(json!({
        "branch": branch,
        "ahead": ahead,
        "behind": behind,
        "has_upstream": has_upstream,
        "remote_url": remote_url,
        "dirty": dirty,
        "unpushed": unpushed
    })
    .to_string())
}

/// 撤销最近一次提交但保留改动（reset --soft HEAD~1）。
pub fn reset_soft(dir: &str) -> Result<()> {
    use git2::{Repository, ResetType};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let parent_oid = repo
        .head()
        .map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 失败: {e}")))?
        .parent_id(0)
        .map_err(|e| CoreError::Other(format!("没有父提交可撤销: {e}")))?;
    let obj = repo
        .find_object(parent_oid, None)
        .map_err(|e| CoreError::Other(format!("找对象失败: {e}")))?;
    repo.reset(&obj, ResetType::Soft, None)
        .map_err(|e| CoreError::Other(format!("reset --soft 失败: {e}")))?;
    Ok(())
}

/// 放弃本地提交：reset --hard origin/{branch}（危险，需 UI 二次确认）。
pub fn reset_hard_to_remote(dir: &str, branch: &str) -> Result<()> {
    use git2::{Repository, ResetType};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let remote_ref_name = format!("refs/remotes/origin/{branch}");
    let remote_ref = repo
        .find_reference(&remote_ref_name)
        .map_err(|e| CoreError::Other(format!("找不到 {remote_ref_name}: {e}")))?;
    let oid = remote_ref
        .target()
        .ok_or_else(|| CoreError::Other("远端引用无目标".into()))?;
    let obj = repo
        .find_object(oid, None)
        .map_err(|e| CoreError::Other(format!("找对象失败: {e}")))?;
    repo.reset(&obj, ResetType::Hard, None)
        .map_err(|e| CoreError::Other(format!("reset --hard 失败: {e}")))?;
    Ok(())
}

/// 修改最近一次提交信息（amend，未推送提交专用）。
pub fn amend_message(dir: &str, new_message: &str) -> Result<()> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let commit = head
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 失败: {e}")))?;
    let tree = commit.tree().map_err(|e| CoreError::Other(format!("读取树失败: {e}")))?;
    let parents: Vec<git2::Commit> = commit.parents().collect();
    let parent_refs: Vec<&git2::Commit> = parents.iter().collect();

    let author = commit.author();
    let committer = commit.committer();
    let sig_author = Signature::new(
        author.name().unwrap_or(""),
        author.email().unwrap_or(""),
        &author.when(),
    )
    .map_err(|e| CoreError::Other(format!("构造作者签名失败: {e}")))?;
    let sig_committer = Signature::new(
        committer.name().unwrap_or(""),
        committer.email().unwrap_or(""),
        &committer.when(),
    )
    .map_err(|e| CoreError::Other(format!("构造提交者签名失败: {e}")))?;

    let head_name = head
        .name()
        .ok_or_else(|| CoreError::Other("无法获取 ref 名".into()))?
        .to_string();
    repo.commit(
        Some(&head_name),
        &sig_author,
        &sig_committer,
        new_message,
        &tree,
        &parent_refs,
    )
    .map_err(|e| CoreError::Other(format!("amend 失败: {e}")))?;
    Ok(())
}

/// 对已推送提交创建 revert 提交（不改写历史，对齐 D11 边界）。
/// 返回新提交 sha。
pub fn revert_commit(
    dir: &str,
    sha: &str,
    message: &str,
    author_name: &str,
    author_email: &str,
) -> Result<String> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let obj = repo
        .revparse_single(sha)
        .map_err(|e| CoreError::Other(format!("解析提交 {sha} 失败: {e}")))?;
    let commit = obj
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("目标不是提交: {e}")))?;

    // 工作区必须干净才能 revert
    let mut st_opts = git2::StatusOptions::new();
    st_opts.include_untracked(false);
    let statuses = repo
        .statuses(Some(&mut st_opts))
        .map_err(|e| CoreError::Other(format!("读取工作区状态失败: {e}")))?;
    if !statuses.is_empty() {
        return Err(CoreError::Other("工作区有未提交改动，无法 revert".into()));
    }

    // git2 0.18：revert 直接应用到工作区与索引，返回 ()；随后从索引取树
    repo.revert(&commit, None)
        .map_err(|e| CoreError::Other(format!("revert 失败: {e}")))?;
    let mut idx = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    let tree_id = idx
        .write_tree()
        .map_err(|e| CoreError::Other(format!("写树失败: {e}")))?;
    let tree = repo.find_tree(tree_id).map_err(|e| CoreError::Other(format!("找树失败: {e}")))?;
    let sig = Signature::now(author_name, author_email)
        .map_err(|e| CoreError::Other(format!("签名失败: {e}")))?;
    let parent = repo
        .head()
        .ok()
        .and_then(|h| h.target())
        .and_then(|t| repo.find_commit(t).ok());
    let parents: Vec<&git2::Commit> = parent.iter().collect();
    let id = repo
        .commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)
        .map_err(|e| CoreError::Other(format!("revert 提交失败: {e}")))?;
    Ok(id.to_string())
}

/// 首次 push：确保 origin 远端 + 推送 + 设置上游分支（对齐 P2-2）。
pub fn push_set_upstream(
    dir: &str,
    remote_url: &str,
    branch: &str,
    token: Option<&str>,
) -> Result<()> {
    use git2::{BranchType, PushOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = match repo.find_remote("origin") {
        Ok(r) => r,
        Err(_) => repo
            .remote("origin", remote_url)
            .map_err(|e| CoreError::Other(format!("添加 origin 失败: {e}")))?,
    };
    if remote.url() != Some(remote_url) {
        repo.remote_set_url("origin", remote_url)
            .map_err(|e| CoreError::Other(format!("更新 origin URL 失败: {e}")))?;
    }

    let mut callbacks = RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut opts = PushOptions::new();
    opts.remote_callbacks(callbacks);
    let refspec = format!("refs/heads/{branch}:refs/heads/{branch}");
    remote
        .push(&[&refspec], Some(&mut opts))
        .map_err(|e| map_push_error(&e.to_string()))?;

    let mut local_branch = repo
        .find_branch(branch, BranchType::Local)
        .map_err(|e| CoreError::Other(format!("找不到本地分支 {branch}: {e}")))?;
    local_branch
        .set_upstream(Some(&format!("origin/{branch}")))
        .map_err(|e| CoreError::Other(format!("设置上游失败: {e}")))?;
    Ok(())
}

/// 敏感信息本地扫描（提交前警告，对齐 P0-4）。
/// 返回 JSON 数组：[{line, kind, mask}]；完整值仅取前 4 字符打码，绝不上传。
pub fn scan_sensitive(text: &str) -> Result<String> {
    let mut hits = Vec::new();

    // 逐行扫描（行号从 1 开始）
    for (idx, line) in text.lines().enumerate() {
        let line_no = idx + 1;
        let mut detect = |prefix: &str, kind: &str, min_len: usize| {
            if let Some(pos) = line.find(prefix) {
                let rest = &line[pos + prefix.len()..];
                let token: String = rest
                    .chars()
                    .take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
                    .collect();
                if token.len() >= min_len {
                    let mask = format!("{}****", &token.chars().take(4).collect::<String>());
                    hits.push(json!({ "line": line_no, "kind": kind, "mask": mask }));
                }
            }
        };
        detect("ghp_", "GitHub PAT", 20);
        detect("gho_", "GitHub OAuth", 20);
        detect("ghu_", "GitHub User Token", 20);
        detect("ghs_", "GitHub Server Token", 20);
        detect("ghr_", "GitHub Refresh Token", 20);
        detect("AKIA", "AWS Access Key", 16);
        detect("xoxb-", "Slack Bot Token", 10);
        detect("xoxp-", "Slack User Token", 10);
        detect("sk-", "API Key", 20);

        // 私钥块起始
        if line.starts_with("-----BEGIN") && line.contains("PRIVATE KEY") {
            hits.push(json!({ "line": line_no, "kind": "私钥", "mask": "-----BEGIN****" }));
        }

        // key=value 通用模式（api_key / secret / password / token = ...）
        let lower = line.to_lowercase();
        for kw in ["api_key", "apikey", "secret", "password", "passwd", "token"] {
            if lower.contains(kw) {
                if let Some(eq) = line.find('=') {
                    let val = line[eq + 1..].trim().trim_matches('"').trim_matches('\'');
                    if val.len() >= 8 && !val.contains(' ') {
                        let mask = format!("{}****", &val.chars().take(4).collect::<String>());
                        hits.push(json!({ "line": line_no, "kind": format!("疑似{kw}"), "mask": mask }));
                    }
                }
            }
        }
    }

    Ok(serde_json::to_string(&hits).map_err(|e| CoreError::Json(e))?)
}

/// 初始化 TLS 证书信任：把内置 Mozilla CA bundle 写入 {dir}/branchbase-cacert.pem，
/// 并注入 libgit2（openssl 后端）。Android 系统无 OpenSSL 兼容的 CA 路径，必须自备。
/// 全局生效一次，供 clone/pull/push 所有 HTTPS 连接使用。
pub fn init_ssl_certs(dir: &str) -> Result<()> {
    use std::io::Write;

    let bundle: &[u8] = include_bytes!("../certs/cacert.pem");
    let path = std::path::Path::new(dir).join("branchbase-cacert.pem");
    // 内容一致则跳过写入（幂等）
    let need_write = match std::fs::read(&path) {
        Ok(existing) => existing != bundle,
        Err(_) => true,
    };
    if need_write {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| CoreError::Other(format!("创建证书目录失败: {e}")))?;
        }
        let mut f = std::fs::File::create(&path)
            .map_err(|e| CoreError::Other(format!("写证书文件失败: {e}")))?;
        f.write_all(bundle)
            .map_err(|e| CoreError::Other(format!("写证书失败: {e}")))?;
    }

    // 不直接调用 libgit2 API（冷启动期 libgit2/OpenSSL 未初始化，git_libgit2_opts
    // 会空指针崩溃）。改用 libgit2 官方配置键 http.sslCAInfo：
    //   1. 写全局 gitconfig 文件（[http] sslCAInfo = bundle 路径）
    //   2. 设 GIT_CONFIG_GLOBAL 环境变量指向它（libgit2 读 global config 时遵循）
    // 证书在首次 clone/pull/push 建立 HTTPS 连接时由已初始化的 OpenSSL 加载。
    let cfg_path = std::path::Path::new(dir).join("branchbase-gitconfig");
    // 保留用户已设置的代理行（App 每次启动都会重写本文件）
    let mut extra = String::new();
    if let Ok(existing) = std::fs::read_to_string(&cfg_path) {
        for line in existing.lines() {
            if line.trim_start().starts_with("proxy") {
                extra.push_str(line);
                extra.push('\n');
            }
        }
    }
    let cfg_content = format!("[http]\n\tsslCAInfo = {}\n{}", path.display(), extra);
    let need_write_cfg = match std::fs::read_to_string(&cfg_path) {
        Ok(existing) => existing != cfg_content,
        Err(_) => true,
    };
    if need_write_cfg {
        std::fs::write(&cfg_path, cfg_content)
            .map_err(|e| CoreError::Other(format!("写 gitconfig 失败: {e}")))?;
    }
    std::env::set_var("GIT_CONFIG_GLOBAL", &cfg_path);
    // 双保险：OpenSSL 默认验证路径也会读 SSL_CERT_FILE（若 libgit2 走 openssl 默认路径）
    std::env::set_var("SSL_CERT_FILE", &path);
    Ok(())
}

/// 设置/清除 libgit2 的 HTTP 代理（写全局 gitconfig 的 [http] proxy，重启后由
/// init_ssl_certs 保留）。`proxy` 为空表示清除。
/// 形如 `http://127.0.0.1:7890` / `socks5://127.0.0.1:1080`。
pub fn set_git_proxy(dir: &str, proxy: &str) -> Result<()> {
    let cfg_path = std::path::Path::new(dir).join("branchbase-gitconfig");
    let mut lines: Vec<String> = match std::fs::read_to_string(&cfg_path) {
        Ok(existing) => existing
            .lines()
            .filter(|l| !l.trim_start().starts_with("proxy"))
            .map(|l| l.to_string())
            .collect(),
        Err(_) => vec!["[http]".to_string()],
    };
    if lines.is_empty() {
        lines.push("[http]".to_string());
    }
    if !proxy.trim().is_empty() {
        lines.push(format!("\tproxy = {}", proxy.trim()));
    }
    std::fs::write(&cfg_path, lines.join("\n") + "\n")
        .map_err(|e| CoreError::Other(format!("写代理配置失败: {e}")))?;
    std::env::set_var("GIT_CONFIG_GLOBAL", &cfg_path);
    Ok(())
}

/// 推送错误归一：被拒（非快进/锁 ref/rejected）→ `nff:` 前缀，供上层触发分叉决策页；
/// 其余原样透出为 `push 失败:`。
fn map_push_error(msg: &str) -> CoreError {
    if msg.contains("non-fast-forward") || msg.contains("cannot lock ref") || msg.contains("rejected") {
        CoreError::Other(format!("nff: 推送被拒（远端领先）: {msg}"))
    } else {
        CoreError::Other(format!("push 失败: {msg}"))
    }
}

#[cfg(test)]
mod tests {
    // 注：CI（build-core.yml）会把失败关键行以 annotation 输出，便于无日志下载权限时定位。
    use super::*;

    // ───────────────────── dns_matches：证书主机名匹配 ─────────────────────

    #[test]
    fn dns_matches_requires_dot_separator() {
        assert!(dns_matches("*.github.com", "api.github.com"));
        // 缺少点分隔不应匹配（早期实现会把 xgithub.com 误判为匹配）
        assert!(!dns_matches("*.github.com", "xgithub.com"));
    }

    #[test]
    fn dns_matches_rejects_multi_level_subdomain() {
        assert!(!dns_matches("*.github.com", "a.b.github.com"));
    }

    #[test]
    fn dns_matches_rejects_bare_apex() {
        assert!(!dns_matches("*.github.com", "github.com"));
    }

    #[test]
    fn dns_matches_exact_pattern() {
        assert!(dns_matches("github.com", "github.com"));
        assert!(!dns_matches("github.com", "api.github.com"));
        assert!(!dns_matches("github.com", "githubb.com"));
    }

    // ───────────────────── map_push_error：nff 归一 ─────────────────────

    #[test]
    fn push_error_maps_rejection_to_nff() {
        for msg in [
            "remote rejected (non-fast-forward)",
            "cannot lock ref 'refs/heads/main'",
            "! [rejected] main -> main",
        ] {
            let text = format!("{}", map_push_error(msg));
            assert!(text.starts_with("nff: "), "应归一为 nff 前缀，实际: {text}");
        }
    }

    #[test]
    fn push_error_keeps_other_failures() {
        let text = format!("{}", map_push_error("authentication failed"));
        assert!(text.starts_with("push 失败: "), "实际: {text}");
        assert!(!text.contains("nff:"), "非快进之外不应带 nff 前缀");
    }

    // ───────────────────── scan_sensitive：提交前敏感信息扫描 ─────────────────────

    fn hits(text: &str) -> Vec<serde_json::Value> {
        let json = scan_sensitive(text).expect("扫描应成功");
        serde_json::from_str(&json).expect("结果应是 JSON 数组")
    }

    #[test]
    fn scan_detects_github_pat_and_masks_value() {
        let out = hits("+ ghp_abcdefghijklmnopqrstuvwxyz01");
        assert_eq!(out.len(), 1, "应恰好命中 1 条: {out:?}");
        assert_eq!(out[0]["kind"], "GitHub PAT");
        assert_eq!(out[0]["line"], 1);
        // 只保留前 4 字符，其余打码
        assert_eq!(out[0]["mask"], "abcd****");
    }

    #[test]
    fn scan_detects_private_key_block() {
        let out = hits("-----BEGIN RSA PRIVATE KEY-----");
        assert_eq!(out.len(), 1);
        assert_eq!(out[0]["kind"], "私钥");
    }

    #[test]
    fn scan_detects_key_value_and_reports_line_number() {
        let out = hits("第一行\napi_key = \"abcdefgh1234\"\n");
        assert_eq!(out.len(), 1, "应命中 1 条: {out:?}");
        assert_eq!(out[0]["line"], 2);
        assert_eq!(out[0]["mask"], "abcd****");
    }

    #[test]
    fn scan_ignores_plain_and_short_values() {
        assert!(hits("").is_empty());
        assert!(hits("这是一个普通的提交信息").is_empty());
        // 长度不足 8 的疑似值不报警
        assert!(hits("password = ab").is_empty());
        // 含空格的值不像密钥
        assert!(hits("password = abc def ghi").is_empty());
    }

    // ───────────────────── verify_cert_chain：内置 CA bundle ─────────────────────

    #[test]
    fn builtin_bundle_contains_self_signed_root_that_verifies() {
        let certs = ca_certs();
        assert!(certs.len() > 10, "内置 bundle 应含多个 CA，实际 {}", certs.len());
        let root = certs
            .iter()
            .find(|c| c.subject_name().try_cmp(c.issuer_name()).ok() == Some(std::cmp::Ordering::Equal))
            .expect("bundle 里应存在自签名根证书");
        assert!(verify_cert_chain(root), "自签名根应通过链验证");
    }

    #[test]
    fn builtin_intermediate_chains_to_root() {
        let certs = ca_certs();
        let intermediate = certs.iter().find(|c| {
            c.subject_name().try_cmp(c.issuer_name()).ok() != Some(std::cmp::Ordering::Equal)
        });
        if let Some(ca) = intermediate {
            assert!(verify_cert_chain(ca), "中间证书应能链到内置根");
        }
    }
}
