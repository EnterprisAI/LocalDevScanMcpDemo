# SonarqubeGithubMcpDemo — Complete Usage Guide

A Spring Boot application that automatically analyzes GitHub Pull Requests using the **official GitHub MCP server** and **official SonarQube MCP server**, both running as Docker subprocesses. An LLM orchestrates the flow, matches SonarQube issues to changed files, and posts a code review directly to the PR.

> _"Developer writes code → scan → LLM fix suggestions → developer fixes → PR → pipeline passes ✅"_

---

## How It Works

```
Developer opens / updates a PR
           │
           │  GitHub webhook  POST /webhook/github
           ▼                  (or manual POST /analyze-pr)
┌──────────────────────────────────────────────────────┐
│         SonarqubeGithubMcpDemo  :8080                │
│                                                      │
│  PRAnalysisService  ────  ChatClient (Spring AI)     │
│                                  │                   │
│                    (5 tools injected per request)    │
│                    ┌─────────────┴─────────────┐    │
│                    │                           │    │
│             GitHub MCP tools          SonarQube MCP tools  │
└────────────────────│───────────────────────────│────┘
                     │ Docker stdio               │ Docker stdio
                     ▼                            ▼
          ghcr.io/github/github-mcp-server    mcp/sonarqube
                     │                            │
                     │ GitHub REST API             │ SonarQube/SonarCloud API
                     ▼                            ▼
               github.com                  sonarcloud.io (or self-hosted)
```

**End-to-end flow (recommended — GitHub Actions driven):**
1. A PR is opened/updated on GitHub
2. **GitHub Actions** runs the SonarCloud scan (`mvn sonar:sonar`) — this populates SonarCloud's database with issues from the PR branch
3. After the scan completes, the workflow calls `POST /analyze-pr` on the MCP server
4. `PRAnalysisService` calls the LLM with 5 relevant MCP tools
5. LLM calls **GitHub MCP tools** (`pull_request_read`) to get PR details and changed files
6. LLM calls **SonarQube MCP tools** (`search_sonar_issues_in_projects`) to get the freshly scanned issues
7. LLM matches issues to changed files and writes fix suggestions
8. LLM calls **GitHub MCP tools** (`pull_request_review_write`) to post a review on the PR
9. Developer sees the suggestions inline and fixes them before merge

> **Important:** `analyze-pr` reads issues that are **already in SonarCloud's database**. The SonarCloud scan must run before `analyze-pr` is called — otherwise it will find 0 issues. The GitHub Actions workflow handles this sequencing automatically.

---

## Prerequisites

- **Java 17+**
- **Docker Desktop** — must be running before starting the app (the MCP servers run as Docker containers spawned automatically by the app via stdio)
- **GitHub Personal Access Token (PAT)** with `repo` scope — for both GitHub MCP and LLM API (if using GitHub Models)
- **SonarQube/SonarCloud** — either a SonarCloud account or a self-hosted SonarQube instance with at least one scanned project

---

## Step 1 — Build the Project

```cmd
cd SonarqubeGithubMcpDemo
.\gradlew build
```

Expected: `BUILD SUCCESSFUL`. The JAR is at `build\libs\SonarqubeGithubMcpDemo-0.0.1-SNAPSHOT.jar`.

---

## Step 2 — Pull the MCP Docker Images (One-Time)

The app auto-pulls these images on first run, but pre-pulling avoids startup delays:

```cmd
docker pull ghcr.io/github/github-mcp-server:latest
docker pull mcp/sonarqube:latest
```

> **Note:** Docker Desktop must be running. These images are lightweight and communicate via stdin/stdout — they are not exposed on any port.

---

## Step 3 — Configure Environment Variables

Choose **one LLM option** and configure the SonarQube/GitHub credentials.

### Option A — Gemini via Google AI (Recommended — large context, handles 58 tools)

```cmd
:: LLM
set OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
set OPENAI_API_KEY=your_gemini_api_key
set OPENAI_MODEL=gemini-2.5-flash

:: GitHub
set GITHUB_TOKEN=ghp_your_github_token

:: SonarCloud
set SONARQUBE_URL=https://sonarcloud.io
set SONARQUBE_TOKEN=your_sonarcloud_token
set SONARQUBE_ORG=your_sonarcloud_org_key
```

**PowerShell:**
```powershell
$env:OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
$env:OPENAI_API_KEY  = "your_gemini_api_key"
$env:OPENAI_MODEL    = "gemini-2.5-flash"
$env:GITHUB_TOKEN    = "ghp_your_github_token"
$env:SONARQUBE_URL   = "https://sonarcloud.io"
$env:SONARQUBE_TOKEN = "your_sonarcloud_token"
$env:SONARQUBE_ORG   = "your_sonarcloud_org_key"
```

### Option B — OpenAI (gpt-4o)

```cmd
set OPENAI_API_KEY=sk-your_openai_key
set OPENAI_MODEL=gpt-4o
set GITHUB_TOKEN=ghp_your_github_token
set SONARQUBE_URL=https://sonarcloud.io
set SONARQUBE_TOKEN=your_sonarcloud_token
set SONARQUBE_ORG=your_sonarcloud_org_key
```

### Option C — GitHub Models (free tier, gpt-4o-mini — limited to 8k tokens)

> Note: GitHub Models has an 8k input token limit. With 5 filtered tools + SonarQube results, this may be exceeded for large projects. Use Gemini or OpenAI for reliable results.

```cmd
set OPENAI_BASE_URL=https://models.inference.ai.azure.com
set OPENAI_COMPLETIONS_PATH=/chat/completions
set OPENAI_API_KEY=ghp_your_github_token
set OPENAI_MODEL=gpt-4o-mini
set GITHUB_TOKEN=ghp_your_github_token
set SONARQUBE_URL=https://sonarcloud.io
set SONARQUBE_TOKEN=your_sonarcloud_token
set SONARQUBE_ORG=your_sonarcloud_org_key
```

### Option D — Self-Hosted SonarQube (instead of SonarCloud)

Replace the SonarQube vars above with:
```cmd
set SONARQUBE_URL=http://localhost:9000
set SONARQUBE_TOKEN=squ_yourtoken
:: No SONARQUBE_ORG needed for self-hosted
```

> The app automatically remaps `localhost` → `host.docker.internal` so the SonarQube MCP Docker container can reach your local SonarQube instance.

---

## Step 4 — Start the Application

```cmd
java -jar build\libs\SonarqubeGithubMcpDemo-0.0.1-SNAPSHOT.jar
```

**Expected startup logs (takes ~15–30 seconds for Docker containers to initialize):**
```
GitHub MCP client ready — 41 tool(s) available
SonarQube MCP client ready — 17 tool(s) available
PRAnalysisService ready — 58 total tool(s) available
Started Application in 13s (process running for 13.7s)
```

The app is ready when you see `Started Application`. The MCP Docker containers run in the background as subprocesses; they are automatically stopped when the app shuts down.

---

## Step 5 — Test the Endpoints

### A. Manual PR Analysis

Trigger analysis for a specific PR without a webhook. Useful for testing:

**curl (CMD):**
```cmd
curl -X POST "http://localhost:8080/analyze-pr?owner=myorg&repo=myrepo&prNumber=1&sonarProjectKey=myrepo"
```

**PowerShell:**
```powershell
Invoke-RestMethod `
  -Uri "http://localhost:8080/analyze-pr?owner=myorg&repo=myrepo&prNumber=1&sonarProjectKey=myrepo" `
  -Method POST
```

**Parameters:**

| Parameter | Description | Example |
|-----------|-------------|---------|
| `owner` | GitHub org or username | `EnterprisAI` |
| `repo` | GitHub repository name | `springboot-sonar-snyk-demo` |
| `prNumber` | Pull request number | `1` |
| `sonarProjectKey` | SonarQube/SonarCloud project key | `springboot-sonar-snyk-demo` |

**Expected response:**
```
Analysis for pull request #1 in myorg/myrepo:

- Files changed in PR: 4
- Total SonarQube issues in project: 19
- Issues matched to changed files: 19
- Review posted: yes

A review with identified SonarQube issues and fix suggestions has been posted to pull request #1.
```

Analysis takes 30–120 seconds depending on the number of issues and LLM response time.

---

### B. General Chat (SonarQube + GitHub questions)

Ask free-form questions using all 58 MCP tools:

```cmd
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: text/plain" ^
  -d "What SonarQube issues exist in project springboot-sonar-snyk-demo?"
```

```cmd
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: text/plain" ^
  -d "What files were changed in PR #1 of EnterprisAI/springboot-sonar-snyk-demo?"
```

**PowerShell:**
```powershell
Invoke-RestMethod -Uri http://localhost:8080/chat `
  -Method POST -ContentType "text/plain" `
  -Body "List all open issues in project springboot-sonar-snyk-demo"
```

---

### C. GitHub Webhook (Automatic Trigger on PR Events)

Register your server as a GitHub webhook so analysis runs automatically whenever a PR is opened or updated.

#### Step C1 — Expose your local server with a tunnel

GitHub cannot reach `localhost:8080` directly. Use a tunnel to create a public URL.

**localtunnel (recommended — free, no account needed):**
```cmd
npm install -g localtunnel
lt --port 8080 --subdomain my-mcp-server
:: URL: https://my-mcp-server.loca.lt
```

**ngrok (alternative):**
```cmd
:: Install from https://ngrok.com/download, then:
ngrok http 8080
:: URL: https://abc123.ngrok-free.app
```

Note the public URL from either tool.

#### Step C2 — Set the webhook secret (optional but recommended)

Choose any random string as the secret:

```cmd
set GITHUB_WEBHOOK_SECRET=mysecretvalue123
```

Restart the app with this env var set alongside the others.

#### Step C3 — Register the webhook on GitHub

1. Go to **your repository → Settings → Webhooks → Add webhook**
2. **Payload URL**: `https://abc123.ngrok-free.app/webhook/github`
3. **Content type**: `application/json`
4. **Secret**: `mysecretvalue123` (same as `GITHUB_WEBHOOK_SECRET`)
5. **Which events**: select **"Let me select individual events"** → check **Pull requests** only
6. Click **Add webhook**

#### Step C4 — Test it

Open or push a commit to a PR in your repo. GitHub sends a POST to your webhook URL → ngrok forwards it to your app → analysis runs asynchronously → review appears on the PR within ~60 seconds.

The webhook returns `HTTP 202 Accepted` immediately (analysis runs in the background). Check app logs to see progress.

**SonarQube project key override:**

By default, the webhook derives the SonarQube project key from the repository name (lowercase). If your project key is different, add a custom header:

```
X-Sonar-Project-Key: my-custom-project-key
```

---

## Environment Variables Reference

| Variable | Default | Required | Description |
|----------|---------|:--------:|-------------|
| `GITHUB_TOKEN` | — | ✅ | GitHub PAT with `repo` scope — used by the GitHub MCP Docker container |
| `SONARQUBE_TOKEN` | — | ✅ | SonarQube User Token or SonarCloud token |
| `SONARQUBE_URL` | `https://sonarcloud.io` | | SonarQube/SonarCloud base URL |
| `SONARQUBE_ORG` | _(empty)_ | SonarCloud only | SonarCloud organization key |
| `OPENAI_API_KEY` | `$GITHUB_TOKEN` | ✅ | API key for the LLM (Gemini, OpenAI, or GitHub PAT for GitHub Models) |
| `OPENAI_MODEL` | `gpt-4o` | | LLM model name |
| `OPENAI_BASE_URL` | `https://api.openai.com` | | Override for Gemini or GitHub Models endpoints |
| `OPENAI_COMPLETIONS_PATH` | `/v1/chat/completions` | | Override for GitHub Models (use `/chat/completions`) |
| `GITHUB_WEBHOOK_SECRET` | _(empty)_ | | HMAC-SHA256 secret for verifying webhook authenticity |

---

## Available Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/analyze-pr` | Manual PR analysis (query params: `owner`, `repo`, `prNumber`, `sonarProjectKey`) |
| `POST` | `/chat` | Free-form question using all 58 SonarQube + GitHub tools |
| `POST` | `/webhook/github` | GitHub webhook receiver — handles `pull_request` opened/synchronize events |
| `GET` | `/sse` | MCP server SSE endpoint (for Claude Desktop) |
| `POST` | `/mcp/message` | MCP server message endpoint (for Claude Desktop) |

---

## Available MCP Tools

The app loads **58 tools total** at startup (41 GitHub + 17 SonarQube). For PR analysis, only 5 are sent to the LLM per request to stay within token limits.

### GitHub MCP Tools (41 total, via `ghcr.io/github/github-mcp-server`)

Key tools used for PR analysis:

| Tool | Description |
|------|-------------|
| `pull_request_read` | Get PR details and list all files changed in the PR |
| `pull_request_review_write` | Post a review on the PR (use `method="create_review"`, `event="COMMENT"`) |
| `add_comment_to_pending_review` | Add a comment to a pending review |

Other available GitHub tools include repository management, issue tracking, file reading, branch/commit operations, and more.

### SonarQube MCP Tools (17 total, via `mcp/sonarqube`)

Key tools used for PR analysis:

| Tool | Description |
|------|-------------|
| `search_sonar_issues_in_projects` | Get all open issues for a project (filtered by project key) |
| `get_project_quality_gate_status` | Check if the project passes the quality gate |

Other available tools include listing projects, getting metrics, and more.

---

## GitHub Actions Setup (Recommended Automated Flow)

The workflow file lives in the **target repo** (`springboot-sonar-snyk-demo`), not in this MCP server project. It runs the SonarCloud scan and then calls `analyze-pr` automatically on every PR.

### Workflow file

The workflow is already committed at `.github/workflows/sonar-pr-review.yml` in `EnterprisAI/springboot-sonar-snyk-demo`. It:
1. Triggers on `pull_request` (opened, synchronize, reopened)
2. Checks out with full history (`fetch-depth: 0`) — required by SonarCloud
3. Runs `mvn verify sonar:sonar` with PR decoration flags
4. Waits 15 seconds for SonarCloud to process the results
5. Calls `POST /analyze-pr` on the MCP server (skipped if `MCP_SERVER_URL` variable is not set)

### Required GitHub Actions configuration

Go to **Repository → Settings → Secrets and variables → Actions** in `EnterprisAI/springboot-sonar-snyk-demo`:

| Type | Name | Value |
|------|------|-------|
| **Secret** | `SONAR_TOKEN` | SonarCloud user token (`a3bef20d...`) ✅ already set |
| **Variable** | `SONAR_PROJECT_KEY` | `springboot-sonar-snyk-demo` ✅ already set |
| **Variable** | `SONAR_ORG` | `vivid-vortex-devops` ✅ already set |
| **Variable** | `MCP_SERVER_URL` | Public URL of your MCP server (see below) |

### Setting MCP_SERVER_URL

The MCP server must be publicly reachable for GitHub Actions (running on GitHub's cloud) to call it.

**Option A — localtunnel (free, no account needed):**

```bash
# Install once
npm install -g localtunnel

# Start tunnel (run while app is running)
lt --port 8080 --subdomain my-mcp-server
# URL: https://my-mcp-server.loca.lt
```

> localtunnel may show a "tunnel password" page on first visit. Use the IP shown on that page as the password, or add `--allow-invalid-cert` to bypass it.

**Option B — ngrok (free tier, account required for static domains):**

```bash
ngrok http 8080
# URL: https://abc123.ngrok-free.app
```

**Option C — Deployed server (production):** Use the server's fixed public URL.

Once you have the URL, set it as a GitHub Actions variable:

```bash
# Via GitHub UI: Repository → Settings → Secrets and variables → Actions → Variables → New
# Name: MCP_SERVER_URL
# Value: https://my-mcp-server.loca.lt
```

Or via API:
```bash
curl -X POST \
  -H "Authorization: Bearer ghp_yourtoken" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/EnterprisAI/springboot-sonar-snyk-demo/actions/variables" \
  -d '{"name":"MCP_SERVER_URL","value":"https://my-mcp-server.loca.lt"}'
```

### Triggering a test run

After setting `MCP_SERVER_URL`, push a commit to an open PR (or re-open PR #1):

```bash
# Re-trigger the workflow on PR #1 by pushing an empty commit to its branch
git checkout Vivid-Vortex-patch-1
git commit --allow-empty -m "trigger CI"
git push origin Vivid-Vortex-patch-1
```

Watch the Actions tab in the GitHub repo — the `SonarCloud Scan + AI PR Review` workflow will run.

---

## Setting Up SonarCloud (First Time)

If you don't have a SonarCloud account yet:

1. Go to [sonarcloud.io](https://sonarcloud.io) and sign in with your GitHub account
2. Click **+** → **Analyze new project** → select your GitHub repo
3. Note your **organization key** (shown in the URL: `sonarcloud.io/organizations/your-org-key`)
4. Go to **My Account → Security → Generate Tokens** → create a token
5. Use the token as `SONARQUBE_TOKEN` and the org key as `SONARQUBE_ORG`

### Scan your project into SonarCloud (Maven)

Run from the root of the project you want to analyze:

```cmd
mvn sonar:sonar ^
  -Dsonar.host.url=https://sonarcloud.io ^
  -Dsonar.token=your_sonarcloud_token ^
  -Dsonar.organization=your_org_key ^
  -Dsonar.projectKey=your_project_key
```

### Scan your project into SonarCloud (Gradle)

First add to `build.gradle`:
```groovy
plugins {
    id 'org.sonarqube' version '6.0.1.5171'
}
```

Then run:
```cmd
.\gradlew sonar "-Dsonar.host.url=https://sonarcloud.io" "-Dsonar.token=your_token" "-Dsonar.organization=your_org" "-Dsonar.projectKey=your_project_key"
```

---

## Setting Up Self-Hosted SonarQube (Alternative to SonarCloud)

If you prefer local SonarQube:

```cmd
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
```

Wait ~60 seconds, then open `http://localhost:9000`. Default credentials: `admin` / `admin` (you'll be forced to change the password on first login).

### Generate a token

```cmd
curl -X POST "http://localhost:9000/api/user_tokens/generate" ^
  -u admin:YourNewPassword ^
  -d "name=mcp-demo-token&type=USER_TOKEN"
```

Copy the `token` field from the response — use it as `SONARQUBE_TOKEN`. Set `SONARQUBE_URL=http://localhost:9000`. Do **not** set `SONARQUBE_ORG`.

The app automatically remaps `localhost` → `host.docker.internal` inside the Docker container so the SonarQube MCP server can reach your local SonarQube.

---

## Integration with Claude Desktop

This app also exposes itself as an MCP server for Claude Desktop. Add to `%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "sonarqube-github": {
      "command": "java",
      "args": ["-jar", "C:\\path\\to\\SonarqubeGithubMcpDemo-0.0.1-SNAPSHOT.jar"],
      "env": {
        "GITHUB_TOKEN": "ghp_your_github_token",
        "SONARQUBE_URL": "https://sonarcloud.io",
        "SONARQUBE_TOKEN": "your_sonarcloud_token",
        "SONARQUBE_ORG": "your_org_key",
        "OPENAI_API_KEY": "your_gemini_or_openai_key",
        "OPENAI_BASE_URL": "https://generativelanguage.googleapis.com/v1beta/openai",
        "OPENAI_MODEL": "gemini-2.5-flash"
      }
    }
  }
}
```

Restart Claude Desktop — you can then ask Claude to analyze PRs or query SonarQube directly.

---

## Mahesh's Vision — Coverage Status

| Capability | Status | Notes |
|------------|:------:|-------|
| Trigger on PR creation | ✅ | GitHub webhook → `/webhook/github` |
| SonarQube issue aggregation | ✅ | Official SonarQube MCP server |
| GitHub PR context (files changed) | ✅ | Official GitHub MCP server |
| LLM fix suggestions | ✅ | Gemini/GPT-4o matches issues to files |
| Post suggestions to PR as review | ✅ | `pull_request_review_write` MCP tool |
| Multi-tool orchestration | ✅ | 58 tools, 5 filtered per request |
| Snyk integration | 🔜 | Phase 2 — `SnykGithubMcpDemo` |
| IDE plugin | 🔜 | Future |
| Pre-commit hook | 🔜 | Future |

---

## Troubleshooting

### App fails to start — "Client failed to initialize by explicit API call"

Docker Desktop is not running. Start it and wait for it to fully initialize before running the app.

### App starts but analysis returns empty or wrong results

- Verify your `SONARQUBE_TOKEN` and `SONARQUBE_ORG` are correct
- Ensure the project has been scanned into SonarQube/SonarCloud (issues only appear after a scan)
- Check app logs for MCP tool call details

### GitHub Models returns HTTP 413 or truncated response

GitHub Models has an 8k token limit. Switch to Gemini (`gemini-2.5-flash`) or OpenAI (`gpt-4o`) which support much larger contexts.

### Review not posted to GitHub PR

- Confirm the `GITHUB_TOKEN` has `repo` scope (not just `read:user`)
- Check app logs for errors from `pull_request_review_write` tool calls
- Verify the PR is in a repo where your token has write access

### Webhook returns 401

The `X-Hub-Signature-256` header does not match. Ensure `GITHUB_WEBHOOK_SECRET` in the app matches the secret entered in GitHub webhook settings exactly.

### ngrok URL changed

ngrok free tier assigns a new URL on each restart. Update the GitHub webhook Payload URL each time, or use [ngrok static domains](https://ngrok.com/blog-post/free-static-domains-ngrok-users) (free, requires account).
