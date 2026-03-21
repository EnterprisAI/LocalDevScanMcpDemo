# LocalDevScanMcpDemo

> **Local pre-PR quality gate** — catch and fix SonarQube issues, dependency CVEs, and code smells on your machine before you open a pull request, so your CI pipeline passes on the first run.

---

## Overview

`LocalDevScanMcpDemo` is a Spring Boot 4.0 MCP server that sits between your editor and your git remote. It combines three scanning tools and a 13-model LLM fallback chain to give you **line-accurate fix suggestions with one API call** — no dashboard visits, no waiting for CI feedback.

```
Developer edits code
        │
        ▼
POST /scan-local  ◄── SonarCloud (30+ issues via MCP)
                  ◄── Snyk via Docker (dependency CVEs)
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
- **Snyk scanning** — dependency CVEs (`snyk test`) and SAST issues (`snyk code test`) via Docker; no local CLI needed (see [SNYK_INTEGRATION.md](SNYK_INTEGRATION.md))
- **Accurate LLM fixes** — reads actual source code at each issue location before sending to LLM; eliminates hallucinated `originalCode`
- **13-model LLM fallback chain** — OpenRouter free → Gemini free → xAI Grok; never uses paid models without explicit opt-in (`paid: true` flag)
- **One-command apply** — finds `originalCode` in file, replaces with `suggestedCode`, creates `.bak` backup
- **Rescan after apply** — immediately shows remaining issue count after patches are written
- **Pre-push git hook** — install once per repo; prompts to apply fixes before every `git push`
- **Self-contained** — all credentials embedded in `application.yaml`; clone and run with no extra setup

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 17+, Spring Boot 4.0.3 |
| MCP Client | Spring AI MCP (`McpSyncClient`) |
| SonarQube MCP | `mcp/sonarqube` Docker image |
| Snyk | `snyk/snyk:maven` Docker image |
| LLM calls | RestTemplate fallback chain (OpenRouter, Gemini, xAI) |
| Build | Gradle 8 |

---

## Prerequisites

- **Java 17+** on PATH
- **Docker Desktop** running (`docker ps` works)
- A **SonarCloud** project that has been scanned at least once
- A **Snyk** account (free tier is enough) — see [SNYK_INTEGRATION.md](SNYK_INTEGRATION.md) for token setup

---

## Quick Start

### 1. Clone and build

```bash
git clone <repo-url>
cd LocalDevScanMcpDemo
./gradlew bootJar
```

### 2. Start the server

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

### 3. Scan your project

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

### 4. Apply fixes

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

### 5. Install the pre-push hook (optional)

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

| Tier | Provider | Models |
|------|----------|--------|
| 1 | OpenRouter (free) | nemotron-super-120b, llama-3.3-70b, qwen3-coder:free, mistral-small-24b, gemma-3-27b/12b, and more |
| 2 | Google Gemini (free) | `gemini-2.5-flash-lite` ✅ reliable fallback |
| 3 | xAI Grok (free tier) | `grok-3-mini` (activate at console.x.ai first) |
| — | Paid (skipped) | `qwen3-coder` — set `paid: false` to enable |

Add or remove providers in `application.yaml` under `llm.providers`.

---

## Configuration

All credentials are in `src/main/resources/application.yaml`. For a private repo this is the simplest approach — clone and run with zero extra setup.

Key config sections:

```yaml
sonarqube:
  url: https://sonarcloud.io
  token: <your-token>
  org: <your-org>

snyk:
  token: <your-token>
  docker-image: snyk/snyk:maven   # change to snyk/snyk:gradle for Gradle projects
  # See SNYK_INTEGRATION.md for full token setup guide and available Docker images

llm:
  providers:
    - name: gemini-flash-lite
      base-url: https://generativelanguage.googleapis.com/v1beta/openai
      api-key: <your-gemini-key>
      completions-path: /chat/completions
      model: models/gemini-2.5-flash-lite
      paid: false
```

---

## Snyk Integration

Snyk runs inside Docker — no local Snyk CLI installation required. It performs two scan types on every `POST /scan-local` call (when `runSnykScan: true`):

| Scan | Command | Finds |
|------|---------|-------|
| Dependency scan | `snyk test --json` | CVEs in `pom.xml` / `build.gradle` dependencies |
| SAST scan | `snyk code test --json` | Security issues in your own source code |

The raw JSON from both scans is sent to the LLM alongside SonarQube issues. The LLM generates `FixSuggestion` entries for each vulnerability, with `"source": "snyk"` and `"file": "pom.xml"` for dependency fixes.

**Docker images by project type:**

| Project type | Image |
|-------------|-------|
| Maven / Spring Boot | `snyk/snyk:maven` (default) |
| Gradle | `snyk/snyk:gradle` |
| Node.js | `snyk/snyk:node` |
| Python | `snyk/snyk:python` |

> See **[SNYK_INTEGRATION.md](SNYK_INTEGRATION.md)** for token setup, path conversion details, exit code handling, output format, and full troubleshooting guide.

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
│   │   ├── FallbackLlmService.java     # 13-provider fallback chain
│   │   ├── SnykScanService.java        # Docker-based Snyk runner
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
| [LOCAL_SCAN_GUIDE.md](LOCAL_SCAN_GUIDE.md) | Full usage guide — flow diagrams, API reference, architecture, LLM fallback chain |
| [SNYK_INTEGRATION.md](SNYK_INTEGRATION.md) | Snyk dependency & SAST scanning — Docker images, token setup, output format, troubleshooting |
| [SONARQUBE_MCP.md](SONARQUBE_MCP.md) | SonarQube MCP server setup and tool reference |
| [GITHUB_MCP_INTEGRATION.md](GITHUB_MCP_INTEGRATION.md) | GitHub MCP integration guide (for the PR-analysis flow) |
| [AutomaticWebhookInLocalhost.md](AutomaticWebhookInLocalhost.md) | Setting up GitHub webhooks on localhost via ngrok |

---

## How It Works

1. **Issue fetch** — `LocalScanService` calls `McpSyncClient.callTool("search_sonar_issues_in_projects")` directly, bypassing LLM tool-calling for reliability
2. **File enrichment** — for each issue, reads a ±2-line window around `textRange.startLine` from the actual file on disk
3. **Prompt construction** — each issue is sent to the LLM with the real code snippet attached, so `originalCode` in the response is character-accurate
4. **LLM fallback** — `FallbackLlmService` iterates providers; on HTTP 429/402 or any error, it logs a warning and tries the next one
5. **File patching** — `AutoFixService` searches for `originalCode` in the file (with CRLF normalization), backs up the original, and writes the patched version
6. **Rescan** — if `rescanAfterApply: true`, re-runs the full scan pipeline and returns the new `FixSuggestion[]`

---

## License

This project is for internal/private use. All credentials in `application.yaml` are personal API keys — keep the repository private.
