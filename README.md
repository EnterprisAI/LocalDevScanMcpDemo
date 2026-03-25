# LocalDevScanMcpDemo

> **Local pre-PR quality gate** — catch and fix SonarQube issues, dependency CVEs, and code smells on your machine before you open a pull request, so your CI pipeline passes on the first run.

---

## Overview

`LocalDevScanMcpDemo` is a Spring Boot 4.0 MCP server that sits between your editor and your git remote. It combines three scanning tools and a 10-model LLM fallback chain to give you **line-accurate fix suggestions with one API call** — no dashboard visits, no waiting for CI feedback.

```
Developer edits code
        │
        ▼
POST /scan-local  ◄── SonarCloud (30+ issues via SonarQube MCP)
                  ◄── Snyk via MCP (snyk_sca_scan + snyk_code_scan → Snyk Cloud)
                  ◄── LLM reads actual file content → precise fixes
        │
        ▼
Review FixSuggestion[]  ──  file, line, originalCode, suggestedCode
        │
        ▼
POST /apply-fixes  ◄── patches files on disk (.bak backup created)
                   ◄── optional rescan to confirm remaining issues
        │
        ▼
git push  ◄── pre-push hook runs the whole flow automatically
```

---

## Features

- **SonarCloud integration** — fetches issues directly via the official SonarQube MCP server; optionally triggers a fresh `mvn sonar:sonar` scan
- **Snyk MCP scanning** — dependency CVEs (`snyk_sca_scan`) and SAST issues (`snyk_code_scan`) via the Snyk MCP server; communicates with Snyk Cloud, no Docker volume mounting (see [SNYK_INTEGRATION.md](SNYK_INTEGRATION.md))
- **Accurate LLM fixes** — reads actual source code at each issue location before sending to LLM; eliminates hallucinated `originalCode`
- **10-model LLM fallback chain** — all OpenRouter free models; 90-second per-provider timeout; never uses paid models without explicit opt-in (`paid: true` flag)
- **One-command apply** — finds `originalCode` in file, replaces with `suggestedCode`, creates `.bak` backup
- **Rescan after apply** — immediately shows remaining issue count after patches are written
- **Pre-push git hook** — install once per repo; prompts to apply fixes before every `git push`
- **Self-contained** — all credentials embedded in `application.yaml`; set `OPENROUTER_API_KEY` env var and run

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 17+, Spring Boot 4.0.3 |
| MCP Client | Spring AI MCP (`McpSyncClient`) |
| SonarQube MCP | `mcp/sonarqube` Docker image (stdio) |
| Snyk MCP | `snyk mcp -t stdio --disable-trust` (Snyk CLI, stdio) |
| LLM calls | `java.net.http.HttpClient` fallback chain — 10 OpenRouter free models |
| Build | Gradle 8 |

---

## Prerequisites

- **Java 17+** on PATH
- **Node.js + npm** on PATH (for `snyk` CLI — `node --version` should work)
- **Docker Desktop** running (`docker ps` works) — needed for SonarQube MCP only
- A **SonarCloud** project that has been scanned at least once
- A **Snyk** account (free tier) — see [SNYK_INTEGRATION.md](SNYK_INTEGRATION.md) for token setup
- **`OPENROUTER_API_KEY`** environment variable set (free key from [openrouter.ai](https://openrouter.ai))

---

## Quick Start

### 1. Install Snyk CLI globally

```bash
npm install -g snyk
snyk --version   # should print 1.1298.0 or later
```

### 2. Set OpenRouter API key

```bash
# Linux / Mac / Git Bash
export OPENROUTER_API_KEY=sk-or-v1-...

# Windows — persists across sessions
setx OPENROUTER_API_KEY "sk-or-v1-..."
```

Get a free key at [openrouter.ai/keys](https://openrouter.ai/keys).

### 3. Clone and build

```bash
git clone <repo-url>
cd LocalDevScanMcpDemo
./gradlew bootJar
```

### 4. Start the server

```bash
# Linux / Mac / Git Bash
MSYS_NO_PATHCONV=1 java -jar build/libs/LocalDevScanMcpDemo-0.0.1-SNAPSHOT.jar
```

```bat
:: Windows CMD
run.bat
```

> **Git Bash on Windows:** `MSYS_NO_PATHCONV=1` prevents Git Bash from converting `/chat/completions` to a Windows path.
> **Port conflict:** if 8080 is busy, add `-Dserver.port=8081`.
> **Startup time:** ~20 seconds — both SonarQube MCP (Docker) and Snyk MCP (CLI) initialize on boot.

### 5. Scan your project

First, verify the config loaded correctly:

```bash
curl http://localhost:8080/scan-config
```

Then run the scan (empty body uses all defaults from `application.yaml`):

```bash
curl -X POST http://localhost:8080/scan-local \
  -H "Content-Type: application/json" \
  -d "{}"
```

Or override specific fields:

```bash
curl -X POST http://localhost:8080/scan-local \
  -H 'Content-Type: application/json' \
  -d '{
    "projectPath": "C:/path/to/your/project",
    "sonarProjectKey": "your-org_your-project",
    "branch": "main",
    "runSonarScan": false,
    "runSnykScan": true
  }'
```

### 6. Apply fixes

```bash
curl -X POST http://localhost:8080/apply-fixes \
  -H 'Content-Type: application/json' \
  -d '{
    "projectPath": "C:/path/to/your/project",
    "sonarProjectKey": "your-org_your-project",
    "rescanAfterApply": true,
    "fixes": [ ... paste fixes array from scan response ... ]
  }'
```

### 7. Install the pre-push hook (optional)

```bash
bash hooks/install-hooks.sh /path/to/your/repo
```

From now on, every `git push` in that repo scans automatically and prompts:

- **[r] Review per fix** — steps through each fix one by one, shows a coloured diff (`-` original / `+` suggested), and lets you **accept**, **skip**, **edit in `$EDITOR`**, or **apply all remaining**
- **[a] Apply all** — applies every suggestion without review
- **[s] Show JSON** — prints the full report, then asks whether to push
- **[p] Push anyway** — proceeds with known issues
- **[x] Cancel** — aborts the push

After any fixes are applied the push is blocked so you can review the patched files before re-pushing.

---

## API Reference

### `POST /scan-local`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `projectPath` | string | required | Absolute path to the project root on disk |
| `sonarProjectKey` | string | required | SonarCloud project key |
| `branch` | string | — | Branch name (informational) |
| `runSonarScan` | boolean | `true` | Run `mvn sonar:sonar` before fetching issues |
| `runSnykScan` | boolean | `true` | Run Snyk via Docker |

**Response:** `{ totalIssues, fixes: [ { file, startLine, endLine, severity, ruleId, originalCode, suggestedCode, explanation } ] }`

---

### `POST /apply-fixes`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `projectPath` | string | required | Absolute path to the project root |
| `sonarProjectKey` | string | — | Required when `rescanAfterApply: true` |
| `rescanAfterApply` | boolean | `false` | Re-run scan after applying; returns remaining issues |
| `fixes` | array | required | `FixSuggestion` objects from `/scan-local` |

**Response:** `{ appliedCount, failedCount, applied, failed, rescanNote, remainingIssues?, rescanFixes? }`

---

## LLM Fallback Chain

The app never calls a paid model without your explicit opt-in. Providers are tried in order; on a 429 or error it moves to the next:

All providers are OpenRouter free-tier models, tried in this order:

| Order | Model | Notes |
|-------|-------|-------|
| 1 | `meta-llama/llama-3.3-70b-instruct:free` | Fast, good code quality |
| 2 | `qwen/qwen3-coder:free` | Code-specialist |
| 3 | `deepseek/deepseek-r1:free` | Strong reasoning |
| 4 | `mistralai/mistral-small-3.1-24b-instruct:free` | Reliable, fast |
| 5 | `meta-llama/llama-4-scout:free` | Latest Llama |
| 6 | `google/gemma-3-27b-it:free` | Large context |
| 7 | `google/gemma-3-12b-it:free` | Faster fallback |
| 8 | `nvidia/nemotron-3-super-120b-a12b:free` | Large model |
| 9 | `arcee-ai/trinity-large-preview:free` | Large context |
| 10 | `nvidia/nemotron-3-nano-30b-a3b:free` | Last resort |

Each provider has a **90-second total timeout**. On 429 / rate-limit / timeout, the next provider is tried automatically. All use a single `OPENROUTER_API_KEY` environment variable.

Add or remove providers in `application.yaml` under `llm.providers`.

---

## Configuration

All credentials are in `src/main/resources/application.yaml`. The only external dependency is the `OPENROUTER_API_KEY` environment variable (all 10 LLM providers share this key).

Key config sections:

```yaml
sonarqube:
  url: https://sonarcloud.io
  token: <your-sonarcloud-token>
  org: <your-sonarcloud-org>

snyk:
  token: <your-snyk-token>       # UAT or API token from app.snyk.io/account
  org-id: <your-snyk-org-slug>   # e.g. "vivid-vortex" — found in Snyk Settings → General

scan:
  project-path: C:/path/to/your/project   # ← only this section changes per project
  sonar-project-key: your-org_your-project
  branch: main
  run-sonar-scan: false
  run-snyk-scan: true

llm:
  providers:
    - name: llama-3.3-70b
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY}      # ← from environment variable, not hardcoded
      completions-path: /chat/completions
      model: meta-llama/llama-3.3-70b-instruct:free
      paid: false
    # ... 9 more providers, all using ${OPENROUTER_API_KEY}
```

---

## Snyk Integration

Snyk runs via the **Snyk MCP server** (`snyk mcp -t stdio`) — no Docker volume mounting required. It performs two scan types on every `POST /scan-local` call (when `runSnykScan: true`).

> **Why `npm install -g snyk` is required:** The locally installed `snyk` binary is the MCP server process — your app spawns it as a subprocess and talks to it over stdio. The binary itself is just the transport layer; the actual CVE database and SAST rules live in **Snyk Cloud**. The CLI authenticates to `snyk.io` using your `SNYK_TOKEN` and pulls live vulnerability data on every scan. No local token = no Snyk Cloud access = scan returns an auth error.

| MCP Tool | Equivalent CLI | Finds |
|----------|---------------|-------|
| `snyk_sca_scan` | `snyk test` | CVEs in `pom.xml` / `build.gradle` dependencies |
| `snyk_code_scan` | `snyk code test` | SAST — hardcoded secrets, injections, insecure crypto |

The Snyk MCP server starts once at application boot and communicates with **Snyk Cloud** to retrieve vulnerability data. Both scan results (up to 5KB each) are sent to the LLM alongside SonarQube issues. The LLM generates `FixSuggestion` entries with `"source": "snyk"` and `"file": "pom.xml"` for dependency fixes.

**Key differences from the old Docker approach:**

| | Old (Docker) | New (MCP) |
|-|-------------|-----------|
| Startup | Per-scan Docker pull (~500 MB, first time) | Single process at boot, reused across all scans |
| Scan time | 30–60s per scan (Docker overhead) | ~5s per scan (MCP already running) |
| Snyk data | Scans local files only | Reads local files + enriches from **Snyk Cloud** |
| Project mounting | Docker volume mount required | Not needed — CLI reads path directly |
| Windows path issues | Needed `C:\...` → `/c/...` conversion | No conversion needed |

> See **[SNYK_INTEGRATION.md](SNYK_INTEGRATION.md)** for full setup guide, token types, org-id, MCP tool reference, and troubleshooting.

---

## Project Structure

```
LocalDevScanMcpDemo/
├── src/main/java/.../
│   ├── config/
│   │   ├── LlmProperties.java          # binds llm.providers list from YAML
│   │   ├── McpClientsConfig.java        # SonarQube MCP client setup
│   │   ├── SonarQubeProperties.java
│   │   └── SnykProperties.java
│   ├── controller/
│   │   └── LocalScanController.java    # POST /scan-local, POST /apply-fixes
│   ├── service/
│   │   ├── LocalScanService.java       # orchestration: MCP → file read → LLM
│   │   ├── FallbackLlmService.java     # 10-provider OpenRouter fallback chain (90s timeout)
│   │   ├── SnykScanService.java        # Snyk MCP client (snyk_sca_scan + snyk_code_scan)
│   │   └── AutoFixService.java         # file patcher with .bak backup
│   └── model/
│       ├── FixSuggestion.java
│       ├── ScanRequest.java
│       └── ApplyFixesRequest.java
├── src/main/resources/
│   └── application.yaml               # all config + credentials
├── hooks/
│   ├── pre-push.sh                    # git pre-push hook script
│   └── install-hooks.sh               # installs hook into any repo
├── run.sh                             # one-command startup (Linux/Mac/Git Bash)
├── run.bat                            # one-command startup (Windows CMD)
└── docs/
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [LOCAL_SCAN_GUIDE.md](LOCAL_SCAN_GUIDE.md) | Full usage guide — new machine setup, scanning a different project, flow diagrams, API reference, architecture, LLM fallback chain, troubleshooting |
| [SNYK_INTEGRATION.md](SNYK_INTEGRATION.md) | Snyk dependency & SAST scanning — Docker images, token setup, output format, troubleshooting |
| [SONARQUBE_MCP.md](SONARQUBE_MCP.md) | SonarQube MCP server setup and tool reference |
| [GITHUB_MCP_INTEGRATION.md](GITHUB_MCP_INTEGRATION.md) | GitHub MCP integration guide (for the PR-analysis flow) |
| [AutomaticWebhookInLocalhost.md](AutomaticWebhookInLocalhost.md) | Setting up GitHub webhooks on localhost via ngrok |

---

## How It Works

1. **Issue fetch** — `LocalScanService` calls `McpSyncClient.callTool("search_sonar_issues_in_projects")` on the SonarQube MCP client directly, bypassing LLM tool-calling for reliability
2. **Snyk scan** — `SnykScanService` calls `snyk_sca_scan` and `snyk_code_scan` on the Snyk MCP client; both MCP servers are already running at this point (started at boot)
3. **File enrichment** — for each SonarQube issue, reads a ±2-line window around `textRange.startLine` from the actual file on disk
4. **Prompt construction** — issues + real code snippets + Snyk CVE data are assembled into one prompt; `originalCode` is from the real file, not guessed
5. **LLM fallback** — `FallbackLlmService` uses `java.net.http.HttpClient` with a 90-second total timeout per provider; on 429/402/timeout it moves to the next provider
6. **File patching** — `AutoFixService` searches for `originalCode` in the file (with CRLF normalization), backs up the original, and writes the patched version
7. **Rescan** — if `rescanAfterApply: true`, re-runs the full scan pipeline and returns the new `FixSuggestion[]`

---

## License

This project is for internal/private use. All credentials in `application.yaml` are personal API keys — keep the repository private.
