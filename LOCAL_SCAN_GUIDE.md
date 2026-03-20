# LocalDevScanMcpDemo — Local Pre-PR Quality Gate

Fix bugs, vulnerabilities, and code smells **locally before creating a PR**, so the CI pipeline passes on the first run.

## What It Does

```
Developer edits code
        ↓
POST /scan-local   ←— SonarQube MCP (30+ issues)
                   ←— Snyk (dependency CVEs)
                   ←— Gemini LLM (reads actual code → generates precise fixes)
        ↓
Developer reviews FixSuggestion[] — file, line, issue, originalCode, suggestedCode
        ↓
POST /apply-fixes  ←— patches files on disk (.bak backup created)
                   ←— optional rescan to verify
        ↓
git push (pre-push hook runs scan automatically)
```

## Quick Start

### 1. Prerequisites

- Docker Desktop running (`docker ps` works)
- SonarQube project already scanned in SonarCloud
- Java 17+ on PATH

### 2. Build

```bash
./gradlew bootJar
```

### 3. Start the Server

```bash
MSYS_NO_PATHCONV=1 \
OPENAI_BASE_URL="https://generativelanguage.googleapis.com/v1beta/openai" \
OPENAI_COMPLETIONS_PATH="/chat/completions" \
OPENAI_MODEL="models/gemini-2.5-flash-lite" \
OPENAI_API_KEY="<your-gemini-api-key>" \
SONARQUBE_TOKEN="<your-sonarcloud-token>" \
SONARQUBE_URL="https://sonarcloud.io" \
SONARQUBE_ORG="<your-sonarcloud-org>" \
SNYK_TOKEN="<your-snyk-token>" \
java -jar build/libs/LocalDevScanMcpDemo-0.0.1-SNAPSHOT.jar
```

> **Windows note:** `MSYS_NO_PATHCONV=1` prevents Git Bash from converting `/chat/completions` to a Windows path.

## API Endpoints

### POST /scan-local

Scans a local project and returns LLM-generated fix suggestions.

```bash
curl -X POST http://localhost:8080/scan-local \
  -H 'Content-Type: application/json' \
  -d '{
    "projectPath": "C:/path/to/your/project",
    "sonarProjectKey": "your-sonar-project-key",
    "branch": "main",
    "runSonarScan": false,
    "runSnykScan": true
  }'
```

**Request fields:**
| Field | Type | Description |
|-------|------|-------------|
| `projectPath` | string | Absolute path to the project root |
| `sonarProjectKey` | string | SonarCloud project key |
| `branch` | string | Branch name (for display only, SonarCloud data is pre-existing) |
| `runSonarScan` | boolean | If true, runs `mvn sonar:sonar` first (slow, ~2 min) |
| `runSnykScan` | boolean | If true, runs Snyk via Docker (requires Docker) |

**Response:**
```json
{
  "projectPath": "C:/path/to/project",
  "sonarProjectKey": "your-key",
  "branch": "main",
  "totalIssues": 31,
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
      "explanation": "The == operator checks reference equality; use .equals() for content comparison.",
      "applied": false
    }
  ]
}
```

### POST /apply-fixes

Applies selected fix suggestions to files on disk.

```bash
curl -X POST http://localhost:8080/apply-fixes \
  -H 'Content-Type: application/json' \
  -d '{
    "projectPath": "C:/path/to/your/project",
    "rescanAfterApply": false,
    "fixes": [
      {
        "file": "src/main/java/com/example/HelloController.java",
        "startLine": 12,
        "originalCode": "if (password == \"hardcoded_password\") {",
        "suggestedCode": "if (password.equals(\"hardcoded_password\")) {",
        "...": "...other fields from /scan-local response..."
      }
    ]
  }'
```

**Response:**
```json
{
  "appliedCount": 1,
  "failedCount": 0,
  "applied": [...],
  "failed": [],
  "rescanNote": "No rescan requested"
}
```

**How it works:**
- Finds `originalCode` in the file (normalizes CRLF)
- Backs up the file as `filename.java.bak`
- Replaces `originalCode` with `suggestedCode`
- Returns which fixes succeeded and which failed

## Pre-Push Git Hook

Install a pre-push hook that automatically scans before every `git push`:

```bash
# Install into any git repo
bash /path/to/LocalDevScanMcpDemo/hooks/install-hooks.sh /path/to/your/repo
```

The hook:
1. Calls `POST /scan-local` with the repo's project path
2. Displays issues with severity and file locations
3. Prompts: **[a]pply fixes / [s]show details / [p]push anyway / [x]cancel**
4. If you choose apply, calls `POST /apply-fixes` automatically

## Architecture

```
HTTP Request
    ↓
LocalScanController
    ↓
LocalScanService
  ├── McpSyncClient.callTool("search_sonar_issues_in_projects")
  │       → SonarCloud issues with file paths + line numbers
  ├── Read actual file content (±2 lines around each issue)
  ├── SnykScanService.scan(projectPath)
  │       → docker run snyk/snyk:maven snyk test --json
  │       → 56 dependency vulnerability CVEs
  └── ChatClient (Gemini 2.5 Flash Lite)
          prompt: issues + actual code snippets
          → FixSuggestion[] with accurate originalCode

AutoFixService
  ├── Finds originalCode in file
  ├── Backs up as .bak
  └── Writes patched file
```

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `OPENAI_API_KEY` | ✅ | Gemini API key (or OpenAI key) |
| `OPENAI_BASE_URL` | ✅ | `https://generativelanguage.googleapis.com/v1beta/openai` for Gemini |
| `OPENAI_COMPLETIONS_PATH` | ✅ | `/chat/completions` (set MSYS_NO_PATHCONV=1 on Windows) |
| `OPENAI_MODEL` | ✅ | `models/gemini-2.5-flash-lite` (recommended free tier model) |
| `SONARQUBE_TOKEN` | ✅ | SonarCloud user token |
| `SONARQUBE_URL` | ✅ | `https://sonarcloud.io` |
| `SONARQUBE_ORG` | ✅ (SonarCloud) | SonarCloud organization key |
| `SNYK_TOKEN` | for Snyk | Snyk API token |
| `SNYK_DOCKER_IMAGE` | no | Default: `snyk/snyk:maven` |

## Supported LLMs

Any OpenAI-compatible API works:

| Provider | Base URL | Model |
|----------|----------|-------|
| **Gemini** (recommended) | `https://generativelanguage.googleapis.com/v1beta/openai` | `models/gemini-2.5-flash-lite` |
| OpenAI | `https://api.openai.com` | `gpt-4o-mini` |
| Azure OpenAI | your Azure endpoint | deployment name |

## Snyk Docker Images

| Project Type | Image |
|-------------|-------|
| Maven (Java) | `snyk/snyk:maven` |
| Gradle (Java) | `snyk/snyk:gradle` |
| Node.js | `snyk/snyk:node` |
| Python | `snyk/snyk:python` |

Set `SNYK_DOCKER_IMAGE=snyk/snyk:gradle` for Gradle projects.
