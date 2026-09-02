//! 链接匹配器（网页匹配 / 项目匹配 / 链接匹配 的统一入口）
//!
//! 把任意 GitHub 链接（绝对 / 相对 / 锚点 / 站外）归一化为一个 `Destination`，
//! 供 Compose 层做「内部跳转导航」。
//!
//! 规则：有序、声明式（见 `docs/html-parser-design.md` §3），命中即停。

use serde::Serialize;

/// 归一化后的跳转目标（下发 Compose 层的 JSON）
#[derive(Debug, Clone, Default, Serialize)]
pub struct Destination {
    /// 网页类型：repo / blob / tree / raw / issue / pull / commit / user / anchor / external
    #[serde(rename = "type")]
    pub dest_type: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub owner: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub repo: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub branch: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub number: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sha: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub login: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lines: Option<String>,
    /// 最终绝对 URL（相对链接已展开）
    pub url: String,
    /// 是否为当前登录用户自己的项目（owner == current_user）
    pub is_own: bool,
    /// 是否站外（非 github 域名）
    pub is_external: bool,
}

/// 链接解析所需的上下文（base 仓库 + 当前用户）
#[derive(Debug, Clone)]
pub struct ResolveContext {
    /// 主机：`github.com` 或 GHE 域名
    pub host: String,
    /// 当前所在仓库 owner
    pub owner: String,
    /// 当前所在仓库名
    pub repo: String,
    /// 当前默认分支
    pub branch: String,
    /// 当前文件所在目录（"" = 仓库根，不带尾部 `/`）
    pub base_dir: String,
    /// 登录用户 login（用于判定 is_own）
    pub current_user: String,
}

/// 解析一个链接，返回归一化目标。
pub fn resolve_link(url: &str, ctx: &ResolveContext) -> Destination {
    let url = url.trim();

    // 空串 → 本页锚点
    if url.is_empty() {
        return Destination {
            dest_type: "anchor",
            url: url.to_string(),
            is_own: false,
            is_external: false,
            ..Default::default()
        };
    }

    // 纯锚点 `#xxx`
    if let Some(rest) = url.strip_prefix('#') {
        return Destination {
            dest_type: "anchor",
            url: format!("#{rest}"),
            is_own: false,
            is_external: false,
            ..Default::default()
        };
    }

    // 带协议
    if let Some((scheme, rest)) = url.split_once(':') {
        let scheme = scheme.to_ascii_lowercase();
        match scheme.as_str() {
            "http" | "https" => {
                let rest = rest.trim_start_matches('/');
                let (host, path, frag) = split_host_path(rest);
                if is_github_host(&host, &ctx.host) {
                    classify_github(&host, &path, frag.as_deref(), ctx)
                } else {
                    external(url)
                }
            }
            // mailto / tel / javascript 等
            _ => external(url),
        }
    } else if url.starts_with("//") {
        // 协议相对 `//github.com/...`
        let rest = &url[2..];
        let (host, path, frag) = split_host_path(rest);
        if is_github_host(&host, &ctx.host) {
            classify_github(&host, &path, frag.as_deref(), ctx)
        } else {
            external(url)
        }
    } else if url.starts_with('/') {
        // 根相对 `/owner/repo/...`，视为 github.com 内路径
        let (path, frag) = split_frag(url);
        classify_github(&ctx.host, &path, frag.as_deref(), ctx)
    } else {
        // 相对路径 → 基于 base_dir 归一化
        let (path, frag) = split_frag(url);
        resolve_relative(&path, frag.as_deref(), ctx)
    }
}

// ── 分类辅助 ──────────────────────────────────────────────

/// 判定 host 是否属于 GitHub（github.com / raw.*.github.com / GHE 域名）
fn is_github_host(host: &str, base_host: &str) -> bool {
    let host = host.to_ascii_lowercase();
    if host == base_host.to_ascii_lowercase() {
        return true;
    }
    host == "github.com" || host.ends_with(".github.com")
}

/// 对 github 域内的 path 做「网页匹配 + 项目匹配」
fn classify_github(host: &str, path: &str, frag: Option<&str>, ctx: &ResolveContext) -> Destination {
    let segs: Vec<&str> = path
        .trim_start_matches('/')
        .split('/')
        .filter(|s| !s.is_empty())
        .collect();

    // raw.githubusercontent.com/{owner}/{repo}/{branch}/{path...}
    if host.eq_ignore_ascii_case("raw.githubusercontent.com") || host.starts_with("raw.") {
        if segs.len() >= 3 {
            let owner = segs[0].to_string();
            let repo = segs[1].to_string();
            let branch = segs[2].to_string();
            let file = segs[3..].join("/");
            let url = build_url(host, path, frag);
            return Destination {
                dest_type: "raw",
                owner: Some(owner.clone()),
                repo: Some(repo),
                branch: Some(branch),
                path: Some(file),
                url,
                is_own: owner.eq_ignore_ascii_case(&ctx.current_user),
                is_external: false,
                ..Default::default()
            };
        }
        return external(&build_url(host, path, frag));
    }

    match segs.as_slice() {
        [] => external(&build_url(host, path, frag)),
        // 单段 = 用户/组织主页
        [login] => Destination {
            dest_type: "user",
            login: Some(login.to_string()),
            url: build_url(host, path, frag),
            is_own: login.eq_ignore_ascii_case(&ctx.current_user),
            is_external: false,
            ..Default::default()
        },
        // 两段 = 仓库根
        [owner, repo] => repo_dest(host, path, frag, owner, repo, None, ctx),
        // blob / tree / raw
        [owner, repo, kind, branch, rest @ ..] if *kind == "blob" || *kind == "tree" || *kind == "raw" => {
            let file = rest.join("/");
            let dt = match *kind {
                "blob" => "blob",
                "tree" => "tree",
                _ => "raw",
            };
            Destination {
                dest_type: dt,
                owner: Some(owner.to_string()),
                repo: Some(repo.to_string()),
                branch: Some(branch.to_string()),
                path: Some(file),
                lines: frag_lines(frag),
                url: build_url(host, path, frag),
                is_own: owner.eq_ignore_ascii_case(&ctx.current_user),
                is_external: false,
                ..Default::default()
            }
        }
        // commit
        [owner, repo, "commit", sha, ..] => Destination {
            dest_type: "commit",
            owner: Some(owner.to_string()),
            repo: Some(repo.to_string()),
            sha: Some(sha.to_string()),
            url: build_url(host, path, frag),
            is_own: owner.eq_ignore_ascii_case(&ctx.current_user),
            is_external: false,
            ..Default::default()
        },
        // issue 单个（数字）
        [owner, repo, "issues", num, ..] if num.parse::<u64>().is_ok() => {
            let n = num.parse::<u64>().unwrap_or(0);
            Destination {
                dest_type: "issue",
                owner: Some(owner.to_string()),
                repo: Some(repo.to_string()),
                number: Some(n),
                url: build_url(host, path, frag),
                is_own: owner.eq_ignore_ascii_case(&ctx.current_user),
                is_external: false,
                ..Default::default()
            }
        }
        // pull 单个（数字）
        [owner, repo, "pull", num, ..] if num.parse::<u64>().is_ok() => Destination {
            dest_type: "pull",
            owner: Some(owner.to_string()),
            repo: Some(repo.to_string()),
            number: Some(num.parse::<u64>().unwrap_or(0)),
            url: build_url(host, path, frag),
            is_own: owner.eq_ignore_ascii_case(&ctx.current_user),
            is_external: false,
            ..Default::default()
        },
        // 其余子页（issues/pulls/commits/releases/wiki/discussions/…）折叠为 repo 级
        [owner, repo, ..] => repo_dest(host, path, frag, owner, repo, Some(segs[2..].join("/")), ctx),
    }
}

fn repo_dest(
    host: &str,
    path: &str,
    frag: Option<&str>,
    owner: &str,
    repo: &str,
    _sub: Option<String>,
    ctx: &ResolveContext,
) -> Destination {
    Destination {
        dest_type: "repo",
        owner: Some(owner.to_string()),
        repo: Some(repo.to_string()),
        url: build_url(host, path, frag),
        is_own: owner.eq_ignore_ascii_case(&ctx.current_user),
        is_external: false,
        ..Default::default()
    }
}

/// 相对链接 → 基于 base_dir 归一化为 blob / tree
fn resolve_relative(path: &str, frag: Option<&str>, ctx: &ResolveContext) -> Destination {
    let is_dir = path.ends_with('/');

    let mut segs: Vec<&str> = ctx
        .base_dir
        .split('/')
        .filter(|s| !s.is_empty())
        .collect();
    for seg in path.split('/') {
        match seg {
            "" | "." => {}
            ".." => {
                segs.pop();
            }
            other => segs.push(other),
        }
    }

    let mut joined = segs.join("/");
    if is_dir && !joined.is_empty() {
        joined.push('/');
    }

    let dest_type = if is_dir { "tree" } else { "blob" };
    let file_path = joined.trim_end_matches('/').to_string();

    Destination {
        dest_type,
        owner: Some(ctx.owner.clone()),
        repo: Some(ctx.repo.clone()),
        branch: Some(ctx.branch.clone()),
        path: Some(file_path.clone()),
        lines: frag_lines(frag),
        url: format!(
            "https://{}/{}/{}/{}/{}{}",
            ctx.host,
            ctx.owner,
            ctx.repo,
            ctx.branch,
            file_path,
            frag.map(|f| format!("#{f}")).unwrap_or_default()
        ),
        is_own: ctx.owner.eq_ignore_ascii_case(&ctx.current_user),
        is_external: false,
        ..Default::default()
    }
}

fn external(url: &str) -> Destination {
    Destination {
        dest_type: "external",
        url: url.to_string(),
        is_own: false,
        is_external: true,
        ..Default::default()
    }
}

// ── URL 拆分辅助 ───────────────────────────────────────────

/// 从 `//host/path?query#frag` 拆分 host / path / fragment
fn split_host_path(rest: &str) -> (String, String, Option<String>) {
    let (rest, frag) = split_frag(rest);
    match rest.find('/') {
        Some(i) => {
            let host = rest[..i].to_string();
            let path = rest[i..].to_string();
            (host, path, frag)
        }
        None => (rest.to_string(), String::new(), frag),
    }
}

/// 拆分路径与 `#fragment`
fn split_frag(s: &str) -> (String, Option<String>) {
    match s.find('#') {
        Some(i) => (s[..i].to_string(), Some(s[i + 1..].to_string())),
        None => (s.to_string(), None),
    }
}

/// 从 fragment 提取行号锚点 `L12` / `L12-L34`
fn frag_lines(frag: Option<&str>) -> Option<String> {
    frag.filter(|f| f.starts_with('L')).map(|f| f.to_string())
}

/// 重建完整绝对 URL
fn build_url(host: &str, path: &str, frag: Option<&str>) -> String {
    let mut url = format!("https://{host}{path}");
    if let Some(f) = frag {
        url.push('#');
        url.push_str(f);
    }
    url
}

/// 解析 README 中的图片 `src`：
/// - 绝对地址（`http(s)://`）、协议相对（`//`）、内联 `data:` 等保持原样；
/// - 相对路径（`./images/x.png`、`docs/x.png`）基于 `base_dir` 归一化后，改写为
///   `raw` 绝对地址，供 Compose 层直接加载。
pub fn resolve_image_src(src: &str, ctx: &ResolveContext) -> String {
    let src = src.trim();
    if src.is_empty()
        || src.starts_with("http://")
        || src.starts_with("https://")
        || src.starts_with("//")
        || src.starts_with("data:")
    {
        return src.to_string();
    }

    // 根相对路径 `/owner/repo/...` → 视为 github host 根（补全为绝对地址）
    if src.starts_with('/') {
        return format!("https://{}{}", ctx.host, src);
    }

    // 相对路径 → 基于 base_dir 归一化（同 resolve_relative 的 `.`/`..` 处理）
    let (path, _frag) = split_frag(src);
    let mut segs: Vec<&str> = ctx
        .base_dir
        .split('/')
        .filter(|s| !s.is_empty())
        .collect();
    for seg in path.split('/') {
        match seg {
            "" | "." => {}
            ".." => {
                segs.pop();
            }
            other => segs.push(other),
        }
    }
    let file_path = segs.join("/");
    build_raw_url(&ctx.host, &ctx.owner, &ctx.repo, &ctx.branch, &file_path)
}

/// 构建 raw 文件地址（github.com → raw.githubusercontent.com；GHE → {host}/raw/...）
fn build_raw_url(host: &str, owner: &str, repo: &str, branch: &str, path: &str) -> String {
    if host.eq_ignore_ascii_case("github.com") {
        format!("https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}")
    } else {
        format!("https://{host}/raw/{owner}/{repo}/{branch}/{path}")
    }
}

// ── 单元测试 ──────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ResolveContext {
        ResolveContext {
            host: "github.com".into(),
            owner: "SunsetRNE".into(),
            repo: "branchbase".into(),
            branch: "main".into(),
            base_dir: "".into(),
            current_user: "SunsetRNE".into(),
        }
    }

    #[test]
    fn test_own_repo_root() {
        let d = resolve_link("https://github.com/SunsetRNE/branchbase", &ctx());
        assert_eq!(d.dest_type, "repo");
        assert!(d.is_own);
    }

    #[test]
    fn test_third_party_repo() {
        let d = resolve_link("https://github.com/other/repo", &ctx());
        assert_eq!(d.dest_type, "repo");
        assert!(!d.is_own);
        assert_eq!(d.owner.as_deref(), Some("other"));
    }

    #[test]
    fn test_blob_with_lines() {
        let d = resolve_link(
            "https://github.com/SunsetRNE/branchbase/blob/main/src/lib.rs#L10-L20",
            &ctx(),
        );
        assert_eq!(d.dest_type, "blob");
        assert_eq!(d.path.as_deref(), Some("src/lib.rs"));
        assert_eq!(d.lines.as_deref(), Some("L10-L20"));
    }

    #[test]
    fn test_issue() {
        let d = resolve_link("https://github.com/SunsetRNE/branchbase/issues/42", &ctx());
        assert_eq!(d.dest_type, "issue");
        assert_eq!(d.number, Some(42));
    }

    #[test]
    fn test_relative_file() {
        let d = resolve_link("./docs/README.md", &ctx());
        assert_eq!(d.dest_type, "blob");
        assert_eq!(d.path.as_deref(), Some("docs/README.md"));
    }

    #[test]
    fn test_relative_parent() {
        let mut c = ctx();
        c.base_dir = "docs/sub".into();
        let d = resolve_link("../README.md", &c);
        assert_eq!(d.path.as_deref(), Some("docs/README.md"));
    }

    #[test]
    fn test_anchor() {
        let d = resolve_link("#usage", &ctx());
        assert_eq!(d.dest_type, "anchor");
    }

    #[test]
    fn test_external() {
        let d = resolve_link("https://example.com/x", &ctx());
        assert_eq!(d.dest_type, "external");
        assert!(d.is_external);
    }

    #[test]
    fn test_user() {
        let d = resolve_link("https://github.com/torvalds", &ctx());
        assert_eq!(d.dest_type, "user");
        assert_eq!(d.login.as_deref(), Some("torvalds"));
    }

    #[test]
    fn test_image_src_absolute() {
        let d = resolve_image_src("https://img.shields.io/badge/x-y", &ctx());
        assert_eq!(d, "https://img.shields.io/badge/x-y");
    }

    #[test]
    fn test_image_src_relative() {
        let d = resolve_image_src("./docs/images/a.png", &ctx());
        assert_eq!(
            d,
            "https://raw.githubusercontent.com/SunsetRNE/branchbase/main/docs/images/a.png"
        );
    }

    #[test]
    fn test_image_src_parent_relative() {
        let mut c = ctx();
        c.base_dir = "docs/sub".into();
        let d = resolve_image_src("../images/a.png", &c);
        assert_eq!(
            d,
            "https://raw.githubusercontent.com/SunsetRNE/branchbase/main/docs/images/a.png"
        );
    }

    #[test]
    fn test_image_src_root_relative() {
        // 根相对路径补全为 host 根绝对地址
        let d = resolve_image_src("/SunsetRNE/branchbase/raw/main/x.png", &ctx());
        assert_eq!(d, "https://github.com/SunsetRNE/branchbase/raw/main/x.png");
    }

    #[test]
    fn test_image_src_data_uri() {
        let d = resolve_image_src("data:image/svg+xml;base64,abc", &ctx());
        assert_eq!(d, "data:image/svg+xml;base64,abc");
    }
}
