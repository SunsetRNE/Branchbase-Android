# Branchbase

> A third-party GitHub client for Android, modelled on the information architecture and interaction
> quality of the official GitHub app.

The business core (OAuth, the GitHub API, Git operations, HTML parsing) is written in **Rust** and
exposed to the **Jetpack Compose** UI over JNI.

- **Repository**: <https://github.com/SunsetRNE/Branchbase-Android>
- **Platform scope**: **Android phones and tablets only** (`minSdk 24`, i.e. Android 7.0+).
  Desktop, web and iOS are out of scope.
- **Language policy**: the **Chinese** documentation under [`docs/specs/`](docs/specs/) is the
  authoritative source and is maintained first; this English README is provided for external
  readers. When the two differ, the Chinese version wins. The Chinese original of this page is
  [`README.zh.md`](README.zh.md).
- **Community**: QQ group “Branchbase开发交流”, group number **790735040**
  (maintained in Chinese; search by the group number if an invite link expires).

## 📥 Download and install

| Channel | Where | Notes |
|---|---|---|
| **Stable** | [Releases](https://github.com/SunsetRNE/Branchbase-Android/releases) | Built by the manually triggered release pipeline, with a signature file and a source tarball |
| **Beta** | Same page, entries marked **Pre-release** | Built automatically on every push to `main` |

- Artifacts are named `Branchbase-<version>-<yyyymmdd-hhmm>-<short-hash>.apk`
  (debug builds carry a `-debug` suffix).
- After installing, sign in on the welcome page with either **OAuth** (a one-time device
  verification may be required on a new device) or a **Personal Access Token (PAT)**.
- **Building it yourself**: the OAuth `client_secret` is injected at build time
  (`local.properties` → `BuildConfig`; CI uses repository secrets). The repository does not and
  should not contain one — a self-built APK needs **your own GitHub OAuth App**. PAT sign-in needs
  no secret.

## 🚀 Quick start

- **Sign in**: two entry points on the welcome page — OAuth (authorization code + PKCE) or PAT
  (`GET /user` validates before storing; a one-tap link opens the web page with the required scopes
  pre-filled).
- **Read code**: repository list → details → README / language breakdown / contributors. Links in
  the README are routed in-app or handed to the browser depending on their target.
- **Commit**: three modes (single file / multiple files / local repository). The Git floating
  button appears on the code and file pages **only in “local repository (Git)” mode** — the other
  two call the remote API directly and have no local work tree.
- **Sync a branch**: the `⋮` bubble at the bottom of the repository page → branch sync
  (server-side merge; shown only when you have write access).
- **Download attachments**: release assets are downloaded in-app (foreground service, notification
  progress, resumable); when finished you can install, open or share them.
- **Translation**: Settings → Immersive translation → enable the master switch, then a floating
  button appears on content pages (source text and translation side by side). The decision engine
  skips paragraphs that are already in the target language (including translations identical to the
  source) and translates only the fragments that need it (per-fragment pairs); you can switch to
  whole-paragraph or skip mixed-language rules in settings.
- **Export logs**: Settings → Logs → Export (zips into `Download/Branchbase`). The most useful thing
  to attach to an issue.
- **Back button**: on the top level, “press again to exit”; inside a page it closes that page layer
  (the same path as the top-left back arrow).

## ✨ Features

| Feature | In one line | Details |
|---|---|---|
| Sign-in and accounts | OAuth + PAT; multi-account management and status checks | [`features-design.md`](docs/specs/features-design.md) §1 |
| Profile | Overview / repositories / activity + stars · packages · projects · settings | §2 |
| Home dashboard | Pending / in progress / frequently used repositories (long-press the title to pin: own · collaborative · org and team repositories; local only, per account; falls back to the 5 most recent stars) | [`features-design.md`](docs/specs/features-design.md) §9 |
| Repository browsing | List / details / README rendering / languages / contributors / branch sync | §3 |
| Search | Seven search kinds; results open in-app; cached **per account** | §4 |
| Commits and local repositories | Three commit modes; libgit2 shallow clone / pull / commit / push | §5, [`local-git-engine-design.md`](docs/specs/local-git-engine-design.md) |
| Decision pages | Fork / revert / upstream / rollback / delete warnings / sensitive data … | [`decision-pages-design.md`](docs/specs/decision-pages-design.md) |
| Immersive translation (`:translate`) | Side-by-side translation on content pages; the decision engine skips already-matching paragraphs and translates only the fragments that need it | [`modules-design.md`](docs/specs/modules-design.md) §1 |
| Built-in downloader (`:downloader`) | Foreground service, notification progress, resumable downloads | §2 |
| Image viewer (`:imageviewer`) | Tap an image to zoom: pinch, pan, swipe down to close | §3 |
| Code editor (`:editor`) | Edit mode on the file page: monospace, line numbers, no border | §4 |
| Job logs (`:joblogs`) | Grouped log segments with a two-tier cache | §5 |

## 🧱 Stack and layout

| Layer | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose + Material 3 |
| Business core | Rust (`branchbase-core`, JNI bridge) |
| Git | libgit2 (clone / pull / commit / push) |
| Networking | reqwest (rustls-tls) |
| Async | tokio |
| Serialization | serde / serde_json |

| Directory | What it is |
|---|---|
| `app/` | Android application layer (Compose UI + `core/RustBridge`) |
| `core/` | Rust business core (cdylib → `libbranchbase_core.so`): `models` / `auth` / `api` / `bridge` / `git` / `translate` / `html` / `workflow` |
| `translate/` `downloader/` `imageviewer/` `editor/` `joblogs/` | Five **independent modules** (replaceable as a whole; dependencies always point `:app → :<module>`) |
| `docs/` | Design documentation (**conclusions**): rules + `specs/`. Chinese, authoritative |
| `design/` | Prototype sketches (HTML/CSS/JS). **Committed since 2026-11-24**, filled in incrementally — see [`design/README.md`](design/README.md) |
| `review/` | Audit and review material (contract audits, doc checks, investigation records). **Committed since 2026-11-24**, filled in incrementally — see [`review/README.md`](review/README.md) |
| `tools/` | Environment / build / frame-rate tooling (`tools/env`, `tools/build`, `tools/perf`) |
| `.github/workflows/` | CI/CD: Beta / stable / core compile checks |
| `version.properties` | Project version (maintained by hand; per-release notes in [`docs/specs/VERSION-NOTES.md`](docs/specs/VERSION-NOTES.md)) |

## 🔧 Building (developers)

Requirements: JDK 17+ · Android SDK (compileSdk 35) · a Rust toolchain.

```bash
./setup_android_env.sh            # ARM64 one-shot environment setup (detect → prepare → persist)
./gradlew assembleDebug           # APK (version gets a -Beta suffix)
cd core && cargo build --release  # produces libbranchbase_core.so
```

Unit tests: `./gradlew :app:testDebugUnitTest :translate:testDebugUnitTest` · `cd core && cargo test`.

> **The `main` branch does not keep `libbranchbase_core.so`.** Builds package the native core from
> `app/src/main/jniLibs/arm64-v8a/`; without it the APK installs but crashes on launch
> (`UnsatisfiedLinkError`). `app/build.gradle.kts` therefore **fails the packaging tasks** when the
> file is missing, with two recovery paths in the error message: `tools/build/fetch-core.sh`
> (fetches the matching `.so` from a published Beta) or `tools/build/build-core.sh` (rebuilds it).
> Compiling and unit tests are unaffected.
>
> In containers / proot, run `source tools/env/env.rc` first (it exports a UTF-8 locale — the JVM
> takes its filename encoding from the locale, and Chinese test-method names fail to compile
> without it; see [`BUILD-NOTES.md`](docs/specs/BUILD-NOTES.md)).

## 📚 Documentation

**Conclusions live in `docs/` (committed).** Prototypes and audit material now live in `design/`
and `review/` (also committed, filled in incrementally).

The single index and filing rules are in [`docs/README.md`](docs/README.md) — register new
documents there only. In short:

- **Using** the app → this file + [`features-design.md`](docs/specs/features-design.md);
- **Changing** an implementation → start at [`docs/README.md`](docs/README.md);
- **Changing a design** → prototypes in `design/<name>/`, their rationale and landing plan in
  [`docs/specs/prototypes/`](docs/specs/prototypes/);
- **What changed and why** → [`docs/specs/VERSION-NOTES.md`](docs/specs/VERSION-NOTES.md).

> The authoritative set is **Chinese**. English here is a reader-facing summary only.

## 💬 Feedback

- **Bugs / feature requests**: [open an issue](https://github.com/SunsetRNE/Branchbase-Android/issues)
  (include device model, OS version, reproduction steps and screenshots; the zip from
  Settings → Logs → Export is the most useful attachment).
- **Community**: QQ group **790735040** (Chinese).
- **Platform scope**: Android phones and tablets only; no desktop / web / iOS.

## 📄 License

Released under the **MIT** license — see [`LICENSE`](LICENSE)
(`Copyright (c) 2026 SunsetRNE`). You may use, modify and redistribute it, including commercially,
as long as the copyright and license notice are preserved.

**Third-party components are declared separately**: vendored files and build dependencies may be
distributed with this repository, but **must** be declared in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) with their upstream repository. Two of them are
**not MIT** — read those entries before touching the code or the license:

| Component | License | Upstream |
|---|---|---|
| **Sora Editor** (the code editor wrapped by `:editor`) | LGPL-2.1 | [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor) |
| **libgit2** (the C library behind clone / pull / push, vendored via the `git2` crate) | GPL-2.0 (with linking exception) | [libgit2/libgit2](https://github.com/libgit2/libgit2) |

Their current usage (unmodified + dynamically linked), the compliance basis, and what to re-check
when changing that usage are in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) §2.1. When adding
third-party content, follow the maintenance rules in §3 of the same document.

> The “Branchbase” name and icon are project identity and are **not covered by MIT** (MIT covers
> code and documentation distributed with the repository). Forks should use their own name and
> icon to avoid confusion with official builds — official Beta / stable builds carry a signature
> check (Settings → About).
