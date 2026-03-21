# LocalDevScanMcpDemo — Local Pre-PR Quality Gate

Fix bugs, vulnerabilities, and code smells **locally before creating a PR**, so the CI pipeline passes on the first run.

---

## Table of Contents

1. [What It Does](#what-it-does)
2. [High-Level Flow](#high-level-flow)
3. [Detailed Component Flow](#detailed-component-flow)
4. [LLM Fallback Chain](#llm-fallback-chain)
5. [Apply-Fixes Flow](#apply-fixes-flow)
6. [Quick Start](#quick-start)
7. [API Reference](#api-reference)
8. [Pre-Push Git Hook](#pre-push-git-hook)
9. [Architecture](#architecture)
10. [Supported LLMs](#supported-llms)
11. [Snyk Docker Images](#snyk-docker-images)
12. [Environment Variables](#environment-variables)

---

## What It Does

The app acts as a **local quality gate** that runs before you push code. It:

- Fetches existing SonarCloud issues for your project (or runs a fresh scan)
- Runs Snyk to find dependency CVEs
- Reads the **actual source code** at each issue location
- Asks an LLM to generate precise fix suggestions with exact `originalCode` → `suggestedCode`
- Lets you apply the fixes directly to files on disk (with `.bak` backups)
- Optionally rescans after applying to confirm the remaining issue count

---

## High-Level Flow

```mermaid
flowchart TD
    A([Developer edits code]) --> B[POST /scan-local]
    B --> C{runSonarScan?}
    C -- yes --> D[mvn sonar:sonar\nPushes analysis to SonarCloud]
    C -- no --> E[Use existing SonarCloud results]
    D --> E
    E --> F[SonarQube MCP\nsearch_sonar_issues_in_projects]
    F --> G[Read actual file content\nfor each issue ±2 lines]
    B --> H{runSnykScan?}
    H -- yes --> I[Docker: snyk/snyk:maven\nsnyk test --json\nsnyk code test --json]
    H -- no --> J[Skip Snyk]
    G --> K[Build prompt\nissues + real code snippets]
    I --> K
    K --> L[FallbackLlmService\ntries 13 free models in order]
    L --> M[FixSuggestion array\nfile, line, originalCode, suggestedCode]
    M --> N([Developer reviews fixes])
    N --> O[POST /apply-fixes]
    O --> P[Find originalCode in file\nReplace with suggestedCode\nCreate .bak backup]
    P --> Q{rescanAfterApply?}
    Q -- yes --> R[POST /scan-local internally\nreturns remaining issues]
    Q -- no --> S([Done — push to git])
    R --> S
```

---

## Detailed Component Flow

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant API as LocalScanController
    participant LSS as LocalScanService
    participant MCP as SonarQube MCP Client
    participant SC as SonarCloud API
    participant FS as File System
    participant SNK as SnykScanService
    participant Docker as Docker (snyk/snyk:maven)
    participant LLM as FallbackLlmService
    participant AFS as AutoFixService

    Dev->>API: POST /scan-local\n{projectPath, sonarProjectKey, runSonarScan, runSnykScan}
    API->>LSS: scan(request)

    alt runSonarScan = true
        LSS->>LSS: mvn verify sonar:sonar
        LSS-->>SC: Push analysis results
    end

    LSS->>MCP: callTool("search_sonar_issues_in_projects", {projectKey, pageSize:500})
    MCP->>SC: REST API call
    SC-->>MCP: JSON {issues: [...]}
    MCP-->>LSS: issues with file paths + textRange.startLine

    loop For each issue
        LSS->>FS: Read file lines [startLine-3 .. endLine+2]
        FS-->>LSS: Code snippet with line numbers
    end

    alt runSnykScan = true
        LSS->>SNK: scan(projectPath)
        SNK->>Docker: docker run snyk/snyk:maven snyk test --json
        Docker-->>SNK: dependency CVEs JSON
        SNK->>Docker: docker run snyk/snyk:maven snyk code test --json
        Docker-->>SNK: SAST issues JSON
        SNK-->>LSS: {dependencyJson, codeJson}
    end

    LSS->>LLM: chat(SYSTEM_PROMPT, prompt with issues+code)
    LLM-->>LSS: JSON array of FixSuggestion

    LSS-->>API: List<FixSuggestion>
    API-->>Dev: {totalIssues, fixes: [...]}

    Dev->>API: POST /apply-fixes\n{projectPath, sonarProjectKey, rescanAfterApply, fixes:[...]}
    API->>AFS: applyFixes(request)

    loop For each fix
        AFS->>FS: Read file
        AFS->>FS: Write file.bak (backup)
        AFS->>FS: Replace originalCode with suggestedCode
    end

    alt rescanAfterApply = true
        AFS->>LSS: scan(rescanRequest)
        LSS-->>AFS: remaining FixSuggestion[]
    end

    AFS-->>API: {applied, failed, rescanSummary, remainingIssues}
    API-->>Dev: Response with results
```

---

## LLM Fallback Chain

The app never uses paid models without explicit opt-in (`paid: false` in config). It tries providers in order until one responds:

```mermaid
flowchart LR
    Start([chat request]) --> T1

    subgraph T1 [Tier 1 — OpenRouter Free]
        A1[nemotron-super-120b] --> A2[arcee-trinity-large]
        A2 --> A3[nemotron-nano-30b]
        A3 --> A4[qwen3-coder:free]
        A4 --> A5[llama-3.3-70b]
        A5 --> A6[mistral-small-24b]
        A6 --> A7[gemma-3-27b]
        A7 --> A8[gemma-3-12b]
        A8 --> A9[qwen3-next-80b]
    end

    T1 -- all rate-limited --> T2

    subgraph T2 [Tier 2 — Gemini Free]
        B1[gemini-2.5-flash-lite ✅] --> B2[gemma-3-27b]
        B2 --> B3[gemma-3-12b]
    end

    T2 -- exhausted --> T3

    subgraph T3 [Tier 3 — xAI Grok]
        C1[grok-3-mini]
    end

    T3 -- exhausted --> Err([RuntimeException\nAll free providers exhausted])

    T1 -- success --> Resp([LLM response])
    T2 -- success --> Resp
    T3 -- success --> Resp

    style B1 fill:#d4edda,stroke:#28a745
    style Err fill:#f8d7da,stroke:#dc3545
```

**On each failure:**
- HTTP 429 / rate-limited → log warning, try next
- HTTP 402 / payment required → log warning, try next
- Any other error → log warning, try next
- `paid: true` entries → skipped silently (never called)

---

## Apply-Fixes Flow

```mermaid
flowchart TD
    A([POST /apply-fixes]) --> B[For each FixSuggestion]
    B --> C{originalCode\nprovided?}
    C -- no --> FAIL1[Add to failed list]
    C -- yes --> D{suggestedCode\nprovided?}
    D -- no --> FAIL1
    D -- yes --> E[Read target file\nprojectPath + fix.file]
    E --> F{File exists?}
    F -- no --> FAIL2[Add to failed list\nfile not found]
    F -- yes --> G{originalCode\nfound in file?}
    G -- no --> H[Try CRLF normalization\nreplace \\r\\n with \\n]
    H --> I{found after\nnormalization?}
    I -- no --> FAIL2
    I -- yes --> J
    G -- yes --> J[Create .bak backup\nfile.java → file.java.bak]
    J --> K[Replace originalCode\nwith suggestedCode]
    K --> L[Write patched file]
    L --> OK[Add to applied list]
    OK --> B
    FAIL1 --> B
    FAIL2 --> B
    B --> M{rescanAfterApply\nAND applied.size > 0?}
    M -- yes --> N[LocalScanService.scan\nrunSonarScan=false\nrunSnykScan=false]
    N --> O[Return remainingIssues count\n+ rescanFixes array]
    M -- no --> P
    O --> P([Return response\nappliedCount, failedCount,\napplied, failed, rescanNote])
```

---

## Quick Start

### Prerequisites

- Java 17+ on PATH
- Docker Desktop running (`docker ps` should work)
- Your project already analysed in SonarCloud at least once

### 1. Clone and Build

```bash
git clone <repo-url>
cd LocalDevScanMcpDemo
./gradlew bootJar
```

### 2. Start the Server

```bash
MSYS_NO_PATHCONV=1 java -jar build/libs/LocalDevScanMcpDemo-0.0.1-SNAPSHOT.jar
```

> **Windows Git Bash:** `MSYS_NO_PATHCONV=1` prevents Git Bash from converting `/chat/completions` to a Windows path.
>
> **Port conflict:** If port 8080 is busy: `java -Dserver.port=8081 -jar ...`

Or use the provided scripts:

```bash
# Linux / Mac / Git Bash
./run.sh

# Windows CMD
run.bat
```

### 3. Scan Your Project

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

### 4. Apply Fixes

```bash
curl -X POST http://localhost:8080/apply-fixes \
  -H 'Content-Type: application/json' \
  -d '{
    "projectPath": "C:/path/to/your/project",
    "sonarProjectKey": "your-org_your-project",
    "branch": "main",
    "rescanAfterApply": true,
    "fixes": [ ... paste fixes from scan response ... ]
  }'
```

---

## API Reference

### `POST /scan-local`

Scans a local project and returns LLM-generated fix suggestions.

**Request body:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `projectPath` | string | required | Absolute path to project root |
| `sonarProjectKey` | string | required | SonarCloud project key |
| `branch` | string | `null` | Branch name (informational only) |
| `runSonarScan` | boolean | `true` | Run `mvn sonar:sonar` first (~2 min) |
| `runSnykScan` | boolean | `true` | Run Snyk via Docker |

**Response:**

```json
{
  "projectPath": "C:/path/to/project",
  "sonarProjectKey": "org_project",
  "branch": "main",
  "totalIssues": 30,
  "fixes": [
    {
      "file": "src/main/java/com/example/HelloController.java",
      "startLine": 12,
      "endLine": 12,
      "issue": "Strings and Boxed types should be compared using \"equals()\".",
      "severity": "MAJOR",
      "source": "sonarqube",
      "ruleId": "java:S4973",
      "originalCode": "if (password == \"hardcoded_password\") { // Bug: string comparison",
      "suggestedCode": "if (password.equals(\"hardcoded_password\")) { // Bug: string comparison",
      "explanation": "The == operator checks reference equality; .equals() checks content.",
      "applied": false
    }
  ]
}
```

---

### `POST /apply-fixes`

Applies selected fix suggestions to files on disk.

**Request body:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `projectPath` | string | required | Absolute path to project root |
| `sonarProjectKey` | string | optional | Needed for `rescanAfterApply` |
| `branch` | string | optional | Needed for `rescanAfterApply` |
| `rescanAfterApply` | boolean | `false` | Re-run scan after applying fixes |
| `fixes` | array | required | FixSuggestion objects from `/scan-local` |

**Response:**

```json
{
  "appliedCount": 1,
  "failedCount": 0,
  "applied": [ { "...": "fix that was applied" } ],
  "failed": [],
  "rescanNote": "Rescan complete: 29 remaining issue(s) from SonarCloud (applied 1 local fix(es) — run mvn sonar:sonar then rescan to see updated count)",
  "remainingIssues": 29,
  "rescanFixes": [ { "...": "remaining fix suggestions" } ]
}
```

> **Note on rescan:** `remainingIssues` reflects SonarCloud's last analysis. Local file patches are not visible in SonarCloud until you run `mvn sonar:sonar` again.

**How apply works:**
1. Reads the target file
2. Searches for `originalCode` (tries exact match, then CRLF-normalized match)
3. Creates a `.bak` backup (`HelloController.java` → `HelloController.java.bak`)
4. Replaces `originalCode` with `suggestedCode` and writes the file

---

## Pre-Push Git Hook

Install a hook that automatically runs the scan before every `git push`:

```bash
bash /path/to/LocalDevScanMcpDemo/hooks/install-hooks.sh /path/to/your/repo
```

**Hook behaviour:**

```mermaid
flowchart TD
    A([git push]) --> B[pre-push hook triggers]
    B --> C[POST /scan-local\nrunSonarScan=false\nrunSnykScan=true]
    C --> D{Issues found?}
    D -- no issues --> E([Push proceeds ✅])
    D -- issues found --> F[Display issue summary\nfile · line · severity · description]
    F --> G{User choice}

    G -- r: review per fix --> R[Show each fix as diff\noriginalCode → suggestedCode]
    R --> RC{Per-fix choice}
    RC -- y: accept --> SEL[Add to selected list]
    RC -- n: skip --> NEXT[Next fix]
    RC -- e: edit --> EDIT[Open suggestedCode\nin editor, then accept]
    RC -- a: apply all remaining --> SEL
    RC -- x: stop reviewing --> APPLY
    SEL --> NEXT
    NEXT --> RC
    NEXT --> APPLY[POST /apply-fixes\nselected fixes only]
    EDIT --> SEL

    G -- a: apply all --> H[POST /apply-fixes\nall suggestions]
    G -- s: show JSON --> J[Print full JSON report]
    J --> JP{Push anyway?}
    JP -- yes --> E
    JP -- no --> K

    H --> I[Files patched on disk\n.bak backup created]
    APPLY --> I
    I --> REV([Review changes\nthen git push again])

    G -- p: push anyway --> E
    G -- x: cancel --> K([Push cancelled ❌])
```

```bash
# Install into any git repo
bash hooks/install-hooks.sh /path/to/your/repo

# Uninstall
rm /path/to/your/repo/.git/hooks/pre-push
```

Override the server URL if running on a different port:

```bash
MCP_SERVER_URL=http://localhost:8081 git push
```

---

## Architecture

```mermaid
flowchart TB
    subgraph Client
        Dev[Developer]
        Hook[pre-push hook]
    end

    subgraph LocalDevScanMcpDemo [LocalDevScanMcpDemo — Spring Boot 4.0]
        LSC[LocalScanController\nREST endpoints]
        LSS[LocalScanService\norchestration]
        FLS[FallbackLlmService\n13-provider chain]
        SNK[SnykScanService\nDocker runner]
        AFS[AutoFixService\nfile patcher]
        MCP[McpSyncClient\nSonarQube MCP]
        LLP[LlmProperties\nllm.providers config]
    end

    subgraph External
        SC[SonarCloud API]
        OR[OpenRouter\nfree models]
        GEM[Google Gemini API\ngemini-2.5-flash-lite]
        XAI[xAI Grok API\ngrok-3-mini]
        Docker[Docker\nsnyk/snyk:maven]
    end

    Dev --> LSC
    Hook --> LSC
    LSC --> LSS
    LSC --> AFS
    LSS --> MCP
    LSS --> SNK
    LSS --> FLS
    MCP --> SC
    SNK --> Docker
    FLS --> LLP
    FLS --> OR
    FLS --> GEM
    FLS --> XAI
    AFS --> LSS
```

### Key Design Decisions

| Decision | Reason |
|----------|--------|
| `McpSyncClient.callTool()` directly (no LLM tool-calling) | Reliable, no hallucination of tool arguments |
| Read actual file content ±2 lines per issue | LLM sees real code → accurate `originalCode` (no hallucination) |
| RestTemplate fallback chain (not Spring AI `ChatClient`) | Fine-grained control over provider switching on 429/402 |
| `paid: true` flag in config | Prevents accidental paid API usage; explicit opt-in per provider |
| `.bak` backup before patching | Safe rollback without git |
| `textRange.startLine` over top-level `line` | SonarCloud MCP returns line in `textRange`, not root field |

---

## Supported LLMs

Any OpenAI-compatible API works. Configure in `application.yaml` under `llm.providers`:

| Provider | Base URL | Example Models |
|----------|----------|----------------|
| **OpenRouter** (free tier) | `https://openrouter.ai/api/v1` | `nvidia/nemotron-3-super-120b-a12b:free`, `meta-llama/llama-3.3-70b-instruct:free` |
| **Gemini** (recommended fallback) | `https://generativelanguage.googleapis.com/v1beta/openai` | `models/gemini-2.5-flash-lite` |
| **xAI Grok** | `https://api.x.ai/v1` | `grok-3-mini` (activate at console.x.ai first) |
| **OpenAI** | `https://api.openai.com/v1` | `gpt-4o-mini` |
| **Azure OpenAI** | your Azure endpoint | deployment name |

**Adding a new provider:**

```yaml
llm:
  providers:
    - name: my-provider
      base-url: https://api.example.com/v1
      api-key: your-api-key
      completions-path: /chat/completions
      model: my-model-name
      paid: false   # set true to exclude from automatic use
```

---

## Snyk Docker Images

| Project Type | Docker Image | Set in config |
|-------------|-------------|---------------|
| **Maven (Java)** | `snyk/snyk:maven` | default |
| Gradle (Java) | `snyk/snyk:gradle` | `snyk.docker-image: snyk/snyk:gradle` |
| Node.js | `snyk/snyk:node` | `snyk.docker-image: snyk/snyk:node` |
| Python | `snyk/snyk:python` | `snyk.docker-image: snyk/snyk:python` |

> **Important:** Do NOT use `snyk/snyk:node` for Java projects — it has no Maven and will fail with `spawn mvn ENOENT`.

---

## Environment Variables

All credentials are embedded in `application.yaml` for easy cloning. These environment variables override YAML values if set:

| Variable | YAML key | Description |
|----------|----------|-------------|
| `SONARQUBE_TOKEN` | `sonarqube.token` | SonarCloud user token |
| `SONARQUBE_URL` | `sonarqube.url` | SonarCloud URL |
| `SONARQUBE_ORG` | `sonarqube.org` | SonarCloud organization key |
| `SNYK_TOKEN` | `snyk.token` | Snyk API token |
| `SNYK_DOCKER_IMAGE` | `snyk.docker-image` | Default: `snyk/snyk:maven` |
| `OPENAI_API_KEY` | `spring.ai.openai.api-key` | For Spring AI ChatController endpoints |
| `OPENAI_BASE_URL` | `spring.ai.openai.base-url` | For Spring AI ChatController endpoints |
| `MSYS_NO_PATHCONV` | — | Set to `1` in Git Bash to prevent path conversion |
