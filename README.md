# Branchbase

[![Release](https://img.shields.io/github/v/release/SunsetRNE/Branchbase-Android?include_prereleases&sort=semver&label=release)](https://github.com/SunsetRNE/Branchbase-Android/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/SunsetRNE/Branchbase-Android/ci.yml?branch=main&label=ci)](https://github.com/SunsetRNE/Branchbase-Android/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3ddc84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7f52ff?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Rust](https://img.shields.io/badge/core-Rust-dea584?logo=rust&logoColor=white)](https://www.rust-lang.org)
[![Stars](https://img.shields.io/github/stars/SunsetRNE/Branchbase-Android)](https://github.com/SunsetRNE/Branchbase-Android/stargazers)
[![Downloads](https://img.shields.io/github/downloads/SunsetRNE/Branchbase-Android/total)](https://github.com/SunsetRNE/Branchbase-Android/releases)

**A third-party GitHub client for Android.** Jetpack Compose UI on top of a Rust core
(OAuth, the GitHub API, libgit2, HTML parsing) over JNI.

> **Chinese is the authoritative language.** The full documentation —
> [`README.zh.md`](README.zh.md) and everything under [`docs/specs/`](docs/specs/) — is written in
> Chinese, maintained first, and wins whenever the two differ. This page is a compact
> showcase for external readers; it intentionally does not duplicate the specs.

## Download

| Channel | Package | Signing | Where |
|---|---|---|---|
| **Stable** | `com.branchbase` | release key | [Releases](https://github.com/SunsetRNE/Branchbase-Android/releases) |
| **Beta (debug)** | `com.branchbase` | shared beta key | Releases, entries marked **Pre-release** |
| **Dev** | `com.branchbase.dev` | shared beta key | same Pre-release (installs side by side) |
| **Test** | `com.branchbase.test` | shared beta key | same Pre-release (installs side by side) |

- Platform: Android 7.0+ (`minSdk 24`), phones and tablets only.
- Self-built APKs need **your own GitHub OAuth App** (`client_secret` is injected at build time);
  PAT sign-in needs no secret.

## Features

| | |
|---|---|
| **Sign-in** | OAuth (code + PKCE) or Personal Access Token; multi-account |
| **Browse** | Repositories, README rendering, languages, contributors, search, notifications |
| **Write** | Three commit modes, libgit2 clone/pull/commit/push, PR / issue / release flows |
| **Extras** | Built-in downloader (resumable), image viewer, code editor, immersive translation, job logs |
| **Polish** | Liquid-glass navigation, element/page motion budget, dark theme |

## Build

```bash
./setup_android_env.sh            # ARM64 one-shot environment setup
tools/build/build-core.sh         # Rust core → app/src/main/jniLibs/arm64-v8a/libbranchbase_core.so
./gradlew assembleDebug           # beta channel
./gradlew assembleDev assembleTest  # side-by-side dev / test channels
```

- `main` does **not** keep `libbranchbase_core.so`; packaging tasks fail fast when it is missing,
  and the error message points to `tools/build/fetch-core.sh` (fetch a published Beta's `.so`) or
  `tools/build/build-core.sh` (rebuild). Compile and unit tests are unaffected.
- Unit tests: `./gradlew :app:testDebugUnitTest :translate:testDebugUnitTest` · `cd core && cargo test`.

## Docs

| Audience | Start here |
|---|---|
| **Using it** | [`README.zh.md`](README.zh.md) · [`docs/specs/features-design.md`](docs/specs/features-design.md) |
| **Changing code** | [`docs/README.md`](docs/README.md) — the single index and filing rules (Chinese) |
| **Design / prototypes** | [`design/`](design/README.md) · [`docs/specs/prototypes/`](docs/specs/prototypes/) |
| **Audits** | [`review/`](review/README.md) |
| **What changed, and why** | [`docs/specs/VERSION-NOTES.md`](docs/specs/VERSION-NOTES.md) |

## License

MIT — see [`LICENSE`](LICENSE). Third-party components are declared in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md); two of them are **not** MIT:
Sora Editor (LGPL-2.1) and libgit2 (GPL-2.0 with linking exception). The “Branchbase” name and
icon are not covered by MIT — forks should use their own.
