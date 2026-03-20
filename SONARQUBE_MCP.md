# SonarQube MCP Server — Legacy Custom Tools Reference

> **Note:** This document describes the original `SonarqubeMcpDemo` approach where SonarQube was wrapped with custom `@Tool` methods. The current `SonarqubeGithubMcpDemo` project uses the **official SonarQube MCP server** (`mcp/sonarqube` Docker image) and the **official GitHub MCP server** (`ghcr.io/github/github-mcp-server`) instead. See `GITHUB_MCP_INTEGRATION.md` for the current setup guide.

---

A Spring Boot application that acts as a **Model Context Protocol (MCP) server**, exposing SonarQube code quality data as tools that AI assistants (like Claude) can call directly.

---

## What is MCP?

The **Model Context Protocol (MCP)** is an open protocol that lets AI assistants call external tools and data sources in a standardised way. Instead of building custom integrations for each AI, you implement one MCP server and any compatible AI client can use it.

```
AI Assistant (e.g. Claude Desktop)
        │
        │  MCP Protocol (STDIO or SSE)
        ▼
┌─────────────────────────┐
│  SonarQube MCP Server   │   ← this application
│  (Spring Boot + AI MCP) │
└─────────────────────────┘
        │
        │  SonarQube Web API (HTTP)
        ▼
┌─────────────────────────┐
│  SonarQube Instance     │
└─────────────────────────┘
```

---

## Project Structure

```
src/main/java/com/example/mcp/SonarqubeMcpDemo/
├── Application.java                     # Spring Boot entry point
├── client/
│   └── SonarQubeClient.java             # HTTP client for SonarQube Web API
├── config/
│   ├── SonarQubeProperties.java         # Reads sonarqube.url / sonarqube.token
│   ├── McpServerConfig.java             # Registers tools with the MCP server
│   └── SecurityConfig.java             # Minimal Spring Security config
└── tools/
    └── SonarQubeMcpTools.java           # The MCP tool definitions (@Tool methods)
```

---

## Available MCP Tools

| Tool | Description |
|------|-------------|
| `listProjects` | List all SonarQube projects accessible with the configured token |
| `getProjectMetrics` | Get bugs, vulnerabilities, code smells, coverage, duplications, and ratings for a project |
| `getQualityGateStatus` | Get Quality Gate PASSED/FAILED status with individual conditions |
| `searchIssues` | Search issues filtered by project, severity, and type |
| `getSecurityHotspots` | Get security hotspots that need manual review |
| `getServerInfo` | Get SonarQube server version |

---

## How the MCP Server Works

### 1. Tool Registration (`McpServerConfig`)

```java
@Bean
public ToolCallbackProvider sonarQubeToolCallbackProvider(SonarQubeMcpTools tools) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build();
}
```

Spring AI's MCP server auto-configuration picks up all `ToolCallbackProvider` beans and publishes the tools over the MCP protocol. No manual wiring is needed.

### 2. Tool Definition (`SonarQubeMcpTools`)

```java
@Tool(description = "Get the Quality Gate status for a SonarQube project.")
public String getQualityGateStatus(
        @ToolParam(description = "The SonarQube project key") String projectKey) {
    return client.get("/api/qualitygates/project_status?projectKey=" + projectKey);
}
```

Each `@Tool` method becomes a callable tool. The `description` fields are sent to the AI so it knows when and how to use each tool.

### 3. Transport

Two transport modes are supported by Spring AI MCP:

| Mode | Use case | Config |
|------|----------|--------|
| **STDIO** | Claude Desktop, local CLI | `spring.ai.mcp.server.stdio: true` |
| **SSE** | Web-based clients, remote access | `spring.ai.mcp.server.stdio: false` |

---

## Role of Each Component

This app **is** the MCP server — it does not connect to any other MCP server. The `spring-ai-starter-mcp-client` dependency is included in the project template but is not used here.

| Component | Role | Where it runs |
|-----------|------|---------------|
| This Spring Boot app | MCP server — exposes SonarQube as AI tools | Your machine |
| SonarQube | Source of code quality data, called over HTTP | Docker / local (see below) |
| Claude Desktop / AI client | MCP client — talks to this app | Your machine |
| Any external MCP server | **Not used** | N/A |

---

## Running SonarQube Locally

You need a running SonarQube instance for the tools to return real data. The quickest way is Docker:

```bash
docker run -d \
  --name sonarqube \
  -p 9000:9000 \
  sonarqube:community
```

Then:
1. Open `http://localhost:9000` in your browser (may take ~1 minute to start)
2. Log in with `admin` / `admin` and set a new password when prompted
3. Go to **My Account → Security → Generate Tokens**
4. Choose token type **User Token** — this grants read access to the Web API which is all this MCP server needs
   - **User Token** ✅ — for reading projects, metrics, issues, quality gates via the API
   - **Global Analysis Token** ❌ — only for submitting scan results to SonarQube, not for API reads
   - **Project Analysis Token** ❌ — same as above but scoped to one project
5. Copy the generated token — you'll use it as `SONARQUBE_TOKEN`

### Scanning a project into SonarQube

The tools need at least one analysed project to have data to return. Go to the project you want to scan (not this MCP demo project) and follow the steps below.

#### Step 1 — Add the SonarQube plugin to that project's `build.gradle`

The `gradle sonar` task does not exist until the plugin is declared. Open the target project's `build.gradle` and add:

```groovy
plugins {
    // ... your existing plugins ...
    id 'org.sonarqube' version '6.0.1.5171'
}
```

#### Step 2 — Run the scan

**macOS / Linux:**
```bash
./gradlew sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=squ_yourtoken \
  -Dsonar.projectKey=my-project
```

**Windows (Command Prompt) — single line:**
```cmd
.\gradlew sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=squ_yourtoken -Dsonar.projectKey=my-project
```

**Windows (PowerShell) — quote each `-D` argument to avoid `=` parsing issues:**
```powershell
.\gradlew sonar "-Dsonar.host.url=http://localhost:9000" "-Dsonar.token=squ_yourtoken" "-Dsonar.projectKey=my-project"
```

> Avoid multi-line `^` continuation for Gradle `-D` arguments on Windows — Gradle may interpret each line as a separate task name.

#### Alternative — standalone sonar-scanner CLI (no plugin needed)

If you don't want to modify the project's build file, install the [sonar-scanner CLI](https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/scanners/sonarscanner/) and run:

```bash
sonar-scanner \
  -Dsonar.projectKey=my-project \
  -Dsonar.sources=. \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=squ_yourtoken
```

---

## Setup & Configuration

### Prerequisites

- Java 17+
- Docker (to run SonarQube — see above)
- A SonarQube **User Token** (generated from *My Account → Security*)

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SONARQUBE_URL` | `http://localhost:9000` | Base URL of your SonarQube instance |
| `SONARQUBE_TOKEN` | *(empty)* | SonarQube user token for authentication |

### Running the Server

**macOS / Linux:**
```bash
# Build
./gradlew build

# Run
SONARQUBE_URL=http://localhost:9000 \
SONARQUBE_TOKEN=squ_yourtoken \
java -jar build/libs/SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

**Windows (Command Prompt):**
```cmd
.\gradlew build

set SONARQUBE_URL=http://localhost:9000
set SONARQUBE_TOKEN=squ_yourtoken
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

**Windows (PowerShell):**
```powershell
.\gradlew build

$env:SONARQUBE_URL = "http://localhost:9000"
$env:SONARQUBE_TOKEN = "squ_yourtoken"
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

---

## Integration with GitHub Models (Alternative to Claude Desktop)

If Claude Desktop is not available, you can use **GitHub Models** — a free AI model hosting service by GitHub that supports GPT-4o, Llama, Mistral and others via an OpenAI-compatible API.

This project exposes a `POST /chat` REST endpoint. You send it a plain-text question, the AI calls the SonarQube tools automatically, and you get a human-readable answer back.

```
You (HTTP client / curl / Postman)
        │  POST /chat  "What bugs does sonarqube-mcp-demo have?"
        ▼
┌─────────────────────────────────────┐
│  Spring Boot App (port 8080)        │
│  ChatController → ChatClient        │
│  + SonarQubeMcpTools (@Tool)        │
└─────────────────────────────────────┘
        │  tool calls (in-process)
        ▼                    ▲
┌───────────────────┐        │ HTTP REST
│  GitHub Models AI │        │
│  (GPT-4o-mini etc)│──────▶ SonarQube :9000
└───────────────────┘
```

### Step 1 — Create a GitHub Personal Access Token (PAT)

1. Go to **GitHub → Settings → Developer Settings → Personal Access Tokens → Fine-grained tokens**
2. Click **Generate new token**
3. Under **Permissions**, no special scopes are needed — the default read-only access is sufficient for GitHub Models
4. Copy the token (starts with `github_pat_...`)

### Step 2 — Run the App with GitHub Token

**Windows (Command Prompt):**
```cmd
set SONARQUBE_URL=http://localhost:9000
set SONARQUBE_TOKEN=squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b
set GITHUB_TOKEN=github_pat_yourtoken
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

**Windows (PowerShell):**
```powershell
$env:SONARQUBE_URL  = "http://localhost:9000"
$env:SONARQUBE_TOKEN = "squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b"
$env:GITHUB_TOKEN   = "github_pat_yourtoken"
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

### Step 3 — Ask Questions via curl or Postman

**curl:**
```cmd
curl -X POST http://localhost:8080/chat -H "Content-Type: text/plain" -d "What bugs does sonarqube-mcp-demo have?"
```

**PowerShell:**
```powershell
Invoke-RestMethod -Uri http://localhost:8080/chat -Method POST -ContentType "text/plain" -Body "What is the quality gate status of sonarqube-mcp-demo?"
```

**Postman:**
- Method: `POST`
- URL: `http://localhost:8080/chat`
- Body: `raw` → type `Text`
- Body content: your question

### Available GitHub Models

Change the model via the `GITHUB_MODEL` environment variable (default is `gpt-4o-mini`):

| Model | Env value |
|-------|-----------|
| GPT-4o mini (default, fast & free) | `gpt-4o-mini` |
| GPT-4o | `gpt-4o` |
| Meta Llama 3.3 70B | `Meta-Llama-3.3-70B-Instruct` |
| Mistral Large | `Mistral-Large-2411` |

```cmd
set GITHUB_MODEL=gpt-4o
```

### Example Questions to Try

```
What projects are in SonarQube?
What are the metrics for sonarqube-mcp-demo?
Is the quality gate passing for sonarqube-mcp-demo?
Show me all code smells in sonarqube-mcp-demo
Are there any security hotspots I should review?
Give me a full health summary of sonarqube-mcp-demo
```

---

## Integration with Claude Desktop

Add the following to your Claude Desktop config file:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "sonarqube": {
      "command": "java",
      "args": ["-jar", "/path/to/SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar"],
      "env": {
        "SONARQUBE_URL": "http://localhost:9000",
        "SONARQUBE_TOKEN": "squ_yourtoken"
      }
    }
  }
}
```

After restarting Claude Desktop, you can ask questions like:
- *"List all my SonarQube projects"*
- *"What is the quality gate status of my-service?"*
- *"Show me all BLOCKER bugs in the payment-api project"*
- *"What security hotspots need review in my-app?"*

---

## Key Dependencies

```groovy
// Spring AI MCP Server — provides @Tool, ToolCallbackProvider, STDIO/SSE transport
implementation 'org.springframework.ai:spring-ai-starter-mcp-server'

// Spring AI MCP Client — for acting as an MCP client to other servers
implementation 'org.springframework.ai:spring-ai-starter-mcp-client'

// MCP security extensions from the Spring AI Community
implementation 'org.springaicommunity:mcp-server-security:0.1.1'
implementation 'org.springaicommunity:mcp-client-security:0.1.1'
```

---

## Complete End-to-End Walkthrough (Verified Steps)

Everything below was executed and verified. Follow these steps in order to go from a fresh clone to a fully working SonarQube MCP server.

---

### Step 1 — Fix the Gradle Wrapper (offline / no network)

The project's wrapper pointed to Gradle 9.3.1 which requires a network download. Gradle 8.14.3 is already cached locally and works with the sonarqube plugin.

Edit `gradle/wrapper/gradle-wrapper.properties` and change the distribution URL:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
```

If there is an incomplete Gradle 9.3.1 download cached at `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.3.1-bin`, delete that folder — otherwise IntelliJ will keep retrying the failed download.

---

### Step 2 — Add Spring Milestones Repository to `build.gradle`

Spring AI `2.0.0-M2` is a milestone release and is not published to Maven Central. Add the milestone repo:

```groovy
repositories {
    mavenCentral()
    maven { url 'https://repo.spring.io/milestone' }
    maven { url 'https://repo.spring.io/snapshot' }
}
```

---

### Step 3 — Add the SonarQube Gradle Plugin to `build.gradle`

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.3'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'org.sonarqube' version '6.0.1.5171'
}
```

> The `sonar` task does not exist at all until this plugin is declared. Run `.\gradlew sonar` only after adding it.

---

### Step 4 — Build the Project

```cmd
.\gradlew build
```

Expected output: `BUILD SUCCESSFUL`

---

### Step 5 — Start SonarQube via Docker

```cmd
docker pull sonarqube:community
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
```

Wait about 60 seconds, then open `http://localhost:9000` in a browser. When the page loads, SonarQube is ready.

---

### Step 6 — Change the Default Admin Password

Default credentials are `admin` / `admin`. SonarQube forces a password change on first login.

Either change it through the browser, or via the API (password must be at least 12 characters):

```bash
curl -X POST "http://localhost:9000/api/users/change_password" \
  -u admin:admin \
  -d "login=admin&previousPassword=admin&password=Admin@123456"
```

**Credentials used in this project:**
- Username: `admin`
- Password: `Admin@123456`

---

### Step 7 — Generate a User Token

```bash
curl -X POST "http://localhost:9000/api/user_tokens/generate" \
  -u admin:Admin@123456 \
  -d "name=mcp-demo-token&type=USER_TOKEN"
```

This returns a token in the response JSON — copy the `token` field value.

**Token generated for this project:** `squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b`

> This is the token to use as `SONARQUBE_TOKEN` when running the MCP server.

Token type choice explained:
- **User Token** ✅ — grants Web API read access (projects, metrics, issues, quality gates)
- **Global Analysis Token** ❌ — write-only, for submitting scan results
- **Project Analysis Token** ❌ — same as above but scoped to one project

---

### Step 8 — Scan the Project into SonarQube

Run the scan from the project root. Use `.\gradlew` (not `gradle`) so the cached Gradle 8.14.3 wrapper is used — system Gradle 9.x is incompatible with the sonarqube plugin.

**Windows (Command Prompt) — single line:**
```cmd
.\gradlew sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b -Dsonar.projectKey=sonarqube-mcp-demo "-Dsonar.projectName=SonarQube MCP Demo"
```

**Windows (PowerShell):**
```powershell
.\gradlew sonar "-Dsonar.host.url=http://localhost:9000" "-Dsonar.token=squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b" "-Dsonar.projectKey=sonarqube-mcp-demo" "-Dsonar.projectName=SonarQube MCP Demo"
```

> Avoid multi-line `^` continuation for `-D` arguments on Windows — Gradle treats each line as a separate task name and fails.

Expected output: `BUILD SUCCESSFUL`

Open `http://localhost:9000/projects` to confirm the project `SonarQube MCP Demo` appears.

---

### Step 9 — Run the MCP Server

**Windows (Command Prompt):**
```cmd
set SONARQUBE_URL=http://localhost:9000
set SONARQUBE_TOKEN=squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

**Windows (PowerShell):**
```powershell
$env:SONARQUBE_URL = "http://localhost:9000"
$env:SONARQUBE_TOKEN = "squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b"
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

The server starts in STDIO mode — it waits for MCP protocol messages on stdin. Logs are written to `sonarqube-mcp.log` in the project root.

---

### Step 10 — Connect Claude Desktop

Edit `%APPDATA%\Claude\claude_desktop_config.json` and add:

```json
{
  "mcpServers": {
    "sonarqube": {
      "command": "java",
      "args": ["-jar", "C:\\path\\to\\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar"],
      "env": {
        "SONARQUBE_URL": "http://localhost:9000",
        "SONARQUBE_TOKEN": "squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b"
      }
    }
  }
}
```

Restart Claude Desktop. You should see the SonarQube tools available in the tools panel.

---

### Step 11 — Generate a GitHub Personal Access Token (PAT)

If Claude Desktop is not available, GitHub Models is a free alternative that works with GPT-4o, Llama, Mistral and others.

1. Go to **GitHub → Settings → Developer Settings → Personal Access Tokens → Fine-grained tokens**
2. Click **Generate new token**
3. No special scopes needed — default read-only access is sufficient for GitHub Models
4. Copy the token (starts with `github_pat_...`)

---

### Step 12 — Run the App with GitHub Models

Build the JAR if not already done:

```cmd
.\gradlew build
```

**Windows (Command Prompt):**
```cmd
set SONARQUBE_URL=http://localhost:9000
set SONARQUBE_TOKEN=squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b
set GITHUB_TOKEN=github_pat_yourtoken
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

**Windows (PowerShell):**
```powershell
$env:SONARQUBE_URL   = "http://localhost:9000"
$env:SONARQUBE_TOKEN = "squ_16c95e0325227fb5a46369b1f5f5c2d1afc0752b"
$env:GITHUB_TOKEN    = "github_pat_yourtoken"
java -jar build\libs\SonarqubeMcpDemo-0.0.1-SNAPSHOT.jar
```

The app starts on `http://localhost:8080`. Logs appear in the console (SSE mode, not STDIO).

---

### Step 13 — Ask Questions via curl or Postman

The `POST /chat` endpoint accepts a plain-text question. The AI automatically calls the SonarQube tools and returns a human-readable answer.

**curl (Command Prompt):**
```cmd
curl -X POST http://localhost:8080/chat -H "Content-Type: text/plain" -d "What bugs does sonarqube-mcp-demo have?"
```

**PowerShell:**
```powershell
Invoke-RestMethod -Uri http://localhost:8080/chat -Method POST -ContentType "text/plain" -Body "What is the quality gate status of sonarqube-mcp-demo?"
```

**Postman:**
- Method: `POST`
- URL: `http://localhost:8080/chat`
- Body: select `raw`, type `Text`, enter your question

**Example questions to try:**
```
What projects are in SonarQube?
What are the metrics for sonarqube-mcp-demo?
Is the quality gate passing for sonarqube-mcp-demo?
Show me all code smells in sonarqube-mcp-demo
Are there any security hotspots I should review?
Give me a full health summary of sonarqube-mcp-demo
```

**Changing the model** (default is `gpt-4o-mini`):

| Model | Set `GITHUB_MODEL` to |
|-------|-----------------------|
| GPT-4o mini (default) | `gpt-4o-mini` |
| GPT-4o | `gpt-4o` |
| Meta Llama 3.3 70B | `Meta-Llama-3.3-70B-Instruct` |
| Mistral Large | `Mistral-Large-2411` |

```cmd
set GITHUB_MODEL=gpt-4o
```

---

### Verified Results After All Steps

| MCP Tool | Status | Result |
|----------|--------|--------|
| `listProjects` | ✅ | Returns `sonarqube-mcp-demo` |
| `getProjectMetrics` | ✅ | Bugs: 0, Vulnerabilities: 0, Code Smells: 8, LOC: 161 |
| `getQualityGateStatus` | ✅ | **PASSED** |
| `searchIssues` | ✅ | Returns 8 open issues |
| `getSecurityHotspots` | ✅ | No hotspots |
| `getServerInfo` | ✅ | SonarQube 26.3.0 |
| `POST /chat` (GitHub Models) | ✅ | AI answers using live SonarQube data |

---

## Example AI Conversation

```
User: What's the quality gate status of the checkout-service?

Claude: [calls getQualityGateStatus("checkout-service")]
        → SonarQube returns: FAILED
          Conditions:
          - Coverage < 80%  (actual: 61.3%)  ❌
          - Blocker Issues = 0  (actual: 0)   ✓

        The quality gate for checkout-service is currently FAILED.
        The main issue is test coverage at 61.3%, which is below the
        required 80% threshold.

User: Show me the blocker bugs.

Claude: [calls searchIssues("checkout-service", "BLOCKER", "BUG")]
        → Returns list of blocker-level bugs with file locations and messages
```
