# Automatic Webhook Flow on Localhost

Right now you can trigger analysis manually via `POST /analyze-pr?...`. The webhook makes it automatic — GitHub calls your server whenever a PR is opened or updated.

---

## The 3 Pieces Involved

### 1. Your server's webhook endpoint

Your app already has `/webhook/github` listening for `POST` requests. When GitHub sends a `pull_request` event, the controller reads the payload, extracts `owner`, `repo`, `prNumber`, and calls `PRAnalysisService.analyzePullRequest(...)` automatically. The webhook returns `HTTP 202 Accepted` immediately; the analysis runs in the background.

### 2. A public URL for your server

GitHub cannot reach `localhost:8080` from the internet. Use a tunnel tool to create a public URL.

**localtunnel (recommended — free, open-source, no account needed):**
```bash
npm install -g localtunnel
lt --port 8080 --subdomain my-mcp-server
# URL: https://my-mcp-server.loca.lt  (stable as long as the process runs)
```

**ngrok (alternative):**
```bash
# Install from https://ngrok.com/download, then:
ngrok http 8080
# URL: https://abc123.ngrok-free.app
```

Both tools give you a public URL that forwards to `localhost:8080`.

> **localtunnel note:** The first browser visit may show a "tunnel password" page. Enter the IP address shown on that page to proceed. Programmatic clients (curl, GitHub webhooks) are not affected.

### 3. The webhook secret (`GITHUB_WEBHOOK_SECRET`)

This is a shared secret you choose (any random string, e.g. `mysecret123`). GitHub signs every webhook payload with it using HMAC-SHA256 and sends the signature in the `X-Hub-Signature-256` header. Your app verifies this signature to confirm the request is genuinely from GitHub. The secret is optional — if `GITHUB_WEBHOOK_SECRET` is not set, signature verification is skipped.

---

## Step-by-Step Setup

### Step 1 — Start the tunnel

**localtunnel:**
```bash
lt --port 8080 --subdomain my-mcp-server
# URL: https://my-mcp-server.loca.lt
```

**ngrok:**
```bash
ngrok http 8080
# URL: https://abc123.ngrok-free.app
```

Keep the terminal open — closing it stops the tunnel.

### Step 2 — Start the app with the webhook secret

Add `GITHUB_WEBHOOK_SECRET` alongside your other env vars:

**Windows CMD:**
```cmd
set GITHUB_WEBHOOK_SECRET=mysecret123
set GITHUB_TOKEN=ghp_your_token
set SONARQUBE_TOKEN=your_sonar_token
set SONARQUBE_URL=https://sonarcloud.io
set SONARQUBE_ORG=your_org_key
set OPENAI_API_KEY=your_gemini_or_openai_key
set OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
set OPENAI_MODEL=gemini-2.5-flash
java -jar build\libs\SonarqubeGithubMcpDemo-0.0.1-SNAPSHOT.jar
```

**Windows PowerShell:**
```powershell
$env:GITHUB_WEBHOOK_SECRET = "mysecret123"
$env:GITHUB_TOKEN    = "ghp_your_token"
$env:SONARQUBE_TOKEN = "your_sonar_token"
$env:SONARQUBE_URL   = "https://sonarcloud.io"
$env:SONARQUBE_ORG   = "your_org_key"
$env:OPENAI_API_KEY  = "your_gemini_or_openai_key"
$env:OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
$env:OPENAI_MODEL    = "gemini-2.5-flash"
java -jar build\libs\SonarqubeGithubMcpDemo-0.0.1-SNAPSHOT.jar
```

### Step 3 — Register the webhook on GitHub

1. Go to **Repository → Settings → Webhooks → Add webhook**
2. **Payload URL**: `https://abc123.ngrok-free.app/webhook/github`
3. **Content type**: `application/json`
4. **Secret**: `mysecret123` (same value as `GITHUB_WEBHOOK_SECRET`)
5. **Which events**: select **"Let me select individual events"** → check **Pull requests** only
6. Click **Add webhook**

GitHub will send a `ping` event immediately to verify the endpoint is reachable — you should see a green checkmark.

### Step 4 — Test it

Open a new PR (or push a commit to an existing PR) in your repo. GitHub sends a `POST pull_request` event to your webhook URL → ngrok forwards it → your app returns `202 Accepted` → analysis runs in the background → review appears on the PR within ~60 seconds.

**Check the app logs** to see the analysis progress:
```
PR #2 opened in myorg/myrepo — starting async analysis (sonar key: myrepo)
Analysis complete for PR #2: 4 files changed, 12 issues matched, review posted
```

---

## SonarQube Project Key

By default, the webhook derives the SonarQube project key from the **repository name** (lowercase). For example, a PR in `myorg/springboot-sonar-snyk-demo` uses project key `springboot-sonar-snyk-demo`.

If your SonarQube/SonarCloud project key is different, add the `X-Sonar-Project-Key` header to the webhook or call `/analyze-pr` with the `sonarProjectKey` query parameter.

You cannot set custom headers from GitHub's webhook UI. Instead, use the `sonarProjectKey` query parameter via manual `/analyze-pr` calls for projects where the key differs from the repo name.

---

## For Production (Beyond Dev)

localtunnel subdomains are stable within a session but not persistent across restarts. For a stable, always-on setup:

1. Deploy the Spring Boot app to a cloud VM or container service (AWS EC2, Azure App Service, Railway, Render, etc.)
2. Use its fixed public URL directly in the GitHub webhook config — no ngrok needed
3. Set all env vars as secrets in your cloud platform's environment configuration
