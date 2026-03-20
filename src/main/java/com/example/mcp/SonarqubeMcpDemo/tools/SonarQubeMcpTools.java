package com.example.mcp.SonarqubeMcpDemo.tools;

import com.example.mcp.SonarqubeMcpDemo.client.SonarQubeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tools that expose SonarQube functionality to AI assistants.
 * Each method annotated with {@code @Tool} becomes a callable tool in the MCP protocol.
 *
 * <p>Responses are trimmed to only the fields the LLM needs, keeping token usage low.
 */
@Service
public class SonarQubeMcpTools {

    private final SonarQubeClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public SonarQubeMcpTools(SonarQubeClient client) {
        this.client = client;
    }

    @Tool(description = "List all SonarQube projects accessible with the configured token. Returns project keys, names, and last analysis dates.")
    public String listProjects() {
        return client.get("/api/projects/search?ps=50&additionalFields=analysisDate");
    }

    @Tool(description = "Get key quality metrics for a SonarQube project: bugs, vulnerabilities, code smells, test coverage percentage, duplicated lines density, and lines of code.")
    public String getProjectMetrics(
            @ToolParam(description = "The SonarQube project key, e.g. 'my-project'") String projectKey) {

        String metricKeys = "bugs,vulnerabilities,code_smells,coverage,"
                + "duplicated_lines_density,ncloc,sqale_rating,"
                + "reliability_rating,security_rating,alert_status";

        return client.get("/api/measures/component?component=" + projectKey
                + "&metricKeys=" + metricKeys);
    }

    @Tool(description = "Get the Quality Gate status for a SonarQube project. Returns PASSED or FAILED with the individual conditions that were evaluated.")
    public String getQualityGateStatus(
            @ToolParam(description = "The SonarQube project key, e.g. 'my-project'") String projectKey) {

        return client.get("/api/qualitygates/project_status?projectKey=" + projectKey);
    }

    @Tool(description = "Search for code issues in a SonarQube project. Returns a compact list: severity, type, message, file path, and line number. Filter by severity (INFO, MINOR, MAJOR, CRITICAL, BLOCKER) and/or type (BUG, VULNERABILITY, CODE_SMELL).")
    public String searchIssues(
            @ToolParam(description = "The SonarQube project key, e.g. 'my-project'") String projectKey,
            @ToolParam(description = "Issue severity filter: INFO, MINOR, MAJOR, CRITICAL, or BLOCKER. Leave empty for all severities.") String severity,
            @ToolParam(description = "Issue type filter: BUG, VULNERABILITY, or CODE_SMELL. Leave empty for all types.") String type) {

        StringBuilder path = new StringBuilder("/api/issues/search?componentKeys=")
                .append(projectKey)
                .append("&ps=20");

        if (severity != null && !severity.isBlank()) {
            path.append("&severities=").append(severity.toUpperCase());
        }
        if (type != null && !type.isBlank()) {
            path.append("&types=").append(type.toUpperCase());
        }

        String raw = client.get(path.toString());
        return slimIssues(raw);
    }

    @Tool(description = "Get security hotspots that require manual review for a SonarQube project. Hotspots highlight security-sensitive code that may or may not be vulnerabilities.")
    public String getSecurityHotspots(
            @ToolParam(description = "The SonarQube project key, e.g. 'my-project'") String projectKey) {

        return client.get("/api/hotspots/search?projectKey=" + projectKey + "&ps=20");
    }

    @Tool(description = "Get a summary of the SonarQube server version, plugins, and edition information.")
    public String getServerInfo() {
        return client.get("/api/server/version");
    }

    /**
     * Strips verbose SonarQube issue JSON down to the fields the LLM needs:
     * severity, type, message, component (file path), and line.
     * This keeps the token footprint small enough for 8k-token model limits.
     */
    private String slimIssues(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (root.has("error")) return raw; // pass errors through unchanged

            int total = root.path("paging").path("total").asInt(0);
            JsonNode issues = root.path("issues");

            ArrayNode slim = mapper.createArrayNode();
            for (JsonNode issue : issues) {
                ObjectNode s = mapper.createObjectNode();
                s.put("severity", issue.path("severity").asText());
                s.put("type",     issue.path("type").asText());
                s.put("message",  issue.path("message").asText());
                // component looks like "project:src/main/java/Foo.java" — strip the project prefix
                String component = issue.path("component").asText();
                int colon = component.indexOf(':');
                s.put("file", colon >= 0 ? component.substring(colon + 1) : component);
                s.put("line", issue.path("line").asInt(0));
                slim.add(s);
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("total", total);
            result.set("issues", slim);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return raw; // if parsing fails, return raw JSON
        }
    }
}
