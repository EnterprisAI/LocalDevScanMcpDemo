package com.example.mcp.SonarqubeMcpDemo.service;

import com.example.mcp.SonarqubeMcpDemo.config.SonarQubeProperties;
import com.example.mcp.SonarqubeMcpDemo.model.FixSuggestion;
import com.example.mcp.SonarqubeMcpDemo.model.ScanRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Orchestrates a full local scan:
 * 1. Optionally runs mvn sonar:sonar to push results to SonarCloud
 * 2. Reads SonarCloud issues directly via the SonarQube MCP client
 * 3. Reads actual file content for each issue location
 * 4. Runs Snyk dependency + SAST scan via Docker
 * 5. Uses LLM to generate FixSuggestion[] with accurate originalCode
 */
@Service
public class LocalScanService {

    private static final Logger log = LoggerFactory.getLogger(LocalScanService.class);

    private static final String SYSTEM_PROMPT = """
            You are a code quality and security expert generating precise code fixes.

            For each issue provided, you will receive:
            - File path and line numbers
            - Issue description and rule ID
            - The EXACT code snippet from the file at those lines

            Your job: return a JSON array where each element has the EXACT originalCode from the snippet
            and a suggestedCode that is a drop-in replacement.

            Return a JSON array in this EXACT format (no markdown, no explanation outside the JSON):
            [
              {
                "file": "src/main/java/com/example/Foo.java",
                "startLine": 42,
                "endLine": 44,
                "issue": "clear description of the issue",
                "severity": "CRITICAL|MAJOR|MINOR|INFO",
                "source": "sonarqube|snyk",
                "ruleId": "rule or CVE identifier",
                "originalCode": "exact original code from the provided snippet",
                "suggestedCode": "exact replacement code with same indentation",
                "explanation": "why this fix works and what the risk was"
              }
            ]

            Rules:
            - originalCode MUST be copied EXACTLY from the provided code snippet (character-for-character)
            - suggestedCode must be a drop-in replacement with identical surrounding context
            - For dependency vulnerabilities (Snyk), set file to "pom.xml" or "build.gradle"
            - Return ONLY the JSON array, nothing else
            """;

    private final ChatClient chatClient;
    private final SnykScanService snykScanService;
    private final SonarQubeProperties sonarQubeProperties;
    private final McpSyncClient sonarqubeClient;
    private final ObjectMapper objectMapper;

    public LocalScanService(ChatClient.Builder builder,
                            SnykScanService snykScanService,
                            SonarQubeProperties sonarQubeProperties,
                            McpSyncClient sonarqubeMcpClient,
                            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper) {
        this.snykScanService = snykScanService;
        this.sonarQubeProperties = sonarQubeProperties;
        this.sonarqubeClient = sonarqubeMcpClient;
        this.objectMapper = objectMapper;
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
        log.info("LocalScanService ready");
    }

    public List<FixSuggestion> scan(ScanRequest request) throws Exception {
        log.info("Local scan started — project: {}, sonarKey: {}, branch: {}",
                request.getProjectPath(), request.getSonarProjectKey(), request.getBranch());

        // Step 1: Run SonarCloud scan if requested
        if (request.isRunSonarScan()) {
            runSonarScan(request);
        }

        // Step 2: Fetch SonarQube issues directly via MCP (no LLM needed for this)
        List<SonarIssueInfo> sonarIssues = fetchSonarIssues(request.getSonarProjectKey());
        log.info("Fetched {} SonarQube issues", sonarIssues.size());

        // Step 3: Run Snyk scan if requested
        String snykDependencyJson = "{}";
        String snykCodeJson = "{}";
        if (request.isRunSnykScan()) {
            var snykResults = snykScanService.scan(request.getProjectPath());
            snykDependencyJson = snykResults.dependencyJson();
            snykCodeJson = snykResults.codeJson();
        }

        // Step 4: Enrich each SonarQube issue with actual file content
        List<EnrichedIssue> enriched = enrichWithFileContent(request.getProjectPath(), sonarIssues);

        // Step 5: Build prompt with actual code snippets and call LLM
        String prompt = buildPrompt(request, enriched, snykDependencyJson, snykCodeJson);
        log.info("Calling LLM to generate fix suggestions for {} issues (prompt: {} chars)...",
                enriched.size(), prompt.length());
        String llmResponse = chatClient.prompt(prompt).call().content();
        log.info("LLM response received ({} chars)", llmResponse != null ? llmResponse.length() : 0);

        return parseFixSuggestions(llmResponse);
    }

    // ── MCP tool call ──────────────────────────────────────────────────────────

    private List<SonarIssueInfo> fetchSonarIssues(String projectKey) {
        try {
            Map<String, Object> toolArgs = new HashMap<>();
            toolArgs.put("projectKey", projectKey);
            toolArgs.put("pageSize", 500);
            var callRequest = new McpSchema.CallToolRequest("search_sonar_issues_in_projects", toolArgs);
            var result = sonarqubeClient.callTool(callRequest);

            String responseText = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .collect(Collectors.joining("\n"));

            return parseSonarIssues(responseText, projectKey);
        } catch (Exception e) {
            log.error("Failed to fetch SonarQube issues: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SonarIssueInfo> parseSonarIssues(String json, String projectKey) {
        List<SonarIssueInfo> issues = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isMissingNode()) {
                // The tool might return the content as text with embedded JSON
                int start = json.indexOf('{');
                if (start >= 0) {
                    root = objectMapper.readTree(json.substring(start));
                    issuesNode = root.path("issues");
                }
            }
            if (issuesNode.isArray()) {
                for (JsonNode issue : issuesNode) {
                    String component = issue.path("component").asText("");
                    // component format: "projectKey:src/main/java/..."
                    String filePath = component.contains(":") ? component.substring(component.indexOf(':') + 1) : component;
                    // line may be at top-level or inside textRange
                    int line = issue.path("line").asInt(0);
                    if (line == 0) line = issue.path("textRange").path("startLine").asInt(1);
                    int endLine = issue.path("textRange").path("endLine").asInt(line);
                    String rule = issue.path("rule").asText("");
                    String severity = issue.path("severity").asText("MINOR");
                    String message = issue.path("message").asText("");
                    String type = issue.path("type").asText("CODE_SMELL");

                    issues.add(new SonarIssueInfo(filePath, line, endLine, rule, severity, message, type));
                }
            } else {
                log.warn("Could not parse SonarQube issues JSON: {}", json.substring(0, Math.min(500, json.length())));
            }
        } catch (Exception e) {
            log.error("Error parsing SonarQube issues: {}", e.getMessage());
        }
        return issues;
    }

    // ── File content enrichment ────────────────────────────────────────────────

    private List<EnrichedIssue> enrichWithFileContent(String projectPath, List<SonarIssueInfo> issues) {
        List<EnrichedIssue> enriched = new ArrayList<>();
        for (SonarIssueInfo issue : issues) {
            if (issue.filePath().isBlank() || issue.filePath().equals("null")) {
                enriched.add(new EnrichedIssue(issue, null));
                continue;
            }
            try {
                Path filePath = Paths.get(projectPath).resolve(issue.filePath());
                if (!Files.exists(filePath)) {
                    enriched.add(new EnrichedIssue(issue, null));
                    continue;
                }
                List<String> allLines = Files.readAllLines(filePath);
                // Read a context window: 2 lines before, issue line, 2 lines after
                int from = Math.max(0, issue.startLine() - 3);  // 0-based
                int to = Math.min(allLines.size(), issue.endLine() + 2);  // exclusive
                List<String> window = allLines.subList(from, to);
                // Number the lines for clarity
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < window.size(); i++) {
                    sb.append("  ").append(from + i + 1).append(": ").append(window.get(i)).append("\n");
                }
                enriched.add(new EnrichedIssue(issue, sb.toString().stripTrailing()));
            } catch (Exception e) {
                log.warn("Could not read file {}: {}", issue.filePath(), e.getMessage());
                enriched.add(new EnrichedIssue(issue, null));
            }
        }
        return enriched;
    }

    // ── Prompt building ────────────────────────────────────────────────────────

    private String buildPrompt(ScanRequest request, List<EnrichedIssue> sonarIssues,
                               String snykDependencyJson, String snykCodeJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(request.getProjectPath()).append("\n\n");

        sb.append("## SonarQube Issues (").append(sonarIssues.size()).append(" total)\n\n");
        for (int i = 0; i < sonarIssues.size(); i++) {
            EnrichedIssue ei = sonarIssues.get(i);
            SonarIssueInfo issue = ei.issue();
            sb.append("### Issue ").append(i + 1).append("\n");
            sb.append("File: ").append(issue.filePath()).append("\n");
            sb.append("Lines: ").append(issue.startLine()).append("-").append(issue.endLine()).append("\n");
            sb.append("Rule: ").append(issue.ruleId()).append("\n");
            sb.append("Severity: ").append(issue.severity()).append("\n");
            sb.append("Issue: ").append(issue.message()).append("\n");
            if (ei.codeSnippet() != null) {
                sb.append("Code (line numbers shown):\n").append(ei.codeSnippet()).append("\n");
                sb.append("(Use the exact code from line ").append(issue.startLine())
                        .append(" as originalCode)\n");
            }
            sb.append("\n");
        }

        // Include Snyk results (truncated)
        String snykDep = truncate(snykDependencyJson, 5000);
        String snykCode = truncate(snykCodeJson, 5000);
        if (!snykDep.equals("{}")) {
            sb.append("## Snyk Dependency Vulnerabilities\n```json\n").append(snykDep).append("\n```\n\n");
        }
        if (!snykCode.equals("{}")) {
            sb.append("## Snyk Code Issues\n```json\n").append(snykCode).append("\n```\n\n");
        }

        sb.append("Return the JSON array of FixSuggestion objects for ALL issues above.");
        return sb.toString();
    }

    // ── SonarCloud Maven scan ──────────────────────────────────────────────────

    private void runSonarScan(ScanRequest request) throws Exception {
        log.info("Running SonarCloud scan via Maven on: {}", request.getProjectPath());
        List<String> cmd = new ArrayList<>(List.of(
                "cmd", "/c", "mvn", "-B", "verify", "sonar:sonar",
                "-Dsonar.projectKey=" + request.getSonarProjectKey(),
                "-Dsonar.organization=" + sonarQubeProperties.getOrg(),
                "-Dsonar.host.url=" + sonarQubeProperties.getUrl(),
                "-Dsonar.token=" + sonarQubeProperties.getToken()
        ));
        if (request.getBranch() != null && !request.getBranch().isBlank()) {
            cmd.add("-Dsonar.branch.name=" + request.getBranch());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Paths.get(request.getProjectPath()).toFile());
        pb.inheritIO();
        Process process = pb.start();
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) { process.destroyForcibly(); throw new RuntimeException("Maven sonar:sonar timed out"); }
        if (process.exitValue() != 0) throw new RuntimeException("Maven sonar:sonar failed: " + process.exitValue());
        log.info("SonarCloud scan completed");
    }

    // ── JSON parsing ───────────────────────────────────────────────────────────

    private List<FixSuggestion> parseFixSuggestions(String llmResponse) {
        try {
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, FixSuggestion.class));
        } catch (Exception e) {
            log.error("Failed to parse LLM response as JSON: {}", e.getMessage());
            log.debug("LLM response was: {}", llmResponse);
            return List.of();
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "\n... (truncated)" : s;
    }

    // ── Inner records ──────────────────────────────────────────────────────────

    record SonarIssueInfo(String filePath, int startLine, int endLine,
                          String ruleId, String severity, String message, String type) {}

    record EnrichedIssue(SonarIssueInfo issue, String codeSnippet) {}
}
