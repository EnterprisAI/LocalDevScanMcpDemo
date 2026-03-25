package com.example.mcp.SonarqubeMcpDemo.service;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs Snyk scans via the Snyk MCP server (npx snyk mcp -t stdio).
 *
 * <p>Two scan types are executed:
 * <ul>
 *   <li>{@code snyk_sca_scan} — open-source dependency vulnerabilities (replaces snyk test)</li>
 *   <li>{@code snyk_code_scan} — SAST code security issues (replaces snyk code test)</li>
 * </ul>
 *
 * <p>No Docker volume mounting required. The Snyk MCP server reads the local project path
 * directly and reports findings to Snyk Cloud using the configured SNYK_TOKEN.
 */
@Service
public class SnykScanService {

    private static final Logger log = LoggerFactory.getLogger(SnykScanService.class);

    private final McpSyncClient snykMcpClient;

    public SnykScanService(@Qualifier("snykMcpClient") McpSyncClient snykMcpClient) {
        this.snykMcpClient = snykMcpClient;
    }

    public record SnykResults(String dependencyJson, String codeJson, boolean success, String error) {}

    /**
     * Runs snyk_sca_scan (dependencies) and snyk_code_scan (SAST) on the given project path
     * via the Snyk MCP server.
     *
     * <p>The Snyk MCP server is started with {@code --disable-trust} so no folder trust prompt
     * is shown — scans run fully headlessly.
     */
    public SnykResults scan(String projectPath) {
        log.info("Starting Snyk MCP scan on: {}", projectPath);

        String scaResult = callSnykTool("snyk_sca_scan", projectPath, "SCA (dependency)");
        String codeResult = callSnykTool("snyk_code_scan", projectPath, "Code (SAST)");

        boolean success = scaResult != null || codeResult != null;
        String error = (!success) ? "Both snyk_sca_scan and snyk_code_scan failed" : null;

        log.info("Snyk MCP scan complete. SCA: {}, Code: {}",
                scaResult != null ? "OK" : "FAILED",
                codeResult != null ? "OK" : "FAILED");

        return new SnykResults(
                scaResult  != null ? scaResult  : "{\"error\":\"snyk_sca_scan failed\"}",
                codeResult != null ? codeResult : "{\"error\":\"snyk_code_scan failed\"}",
                success, error);
    }

    private String callSnykTool(String toolName, String projectPath, String label) {
        try {
            log.info("Calling Snyk MCP tool: {} on {}", toolName, projectPath);
            Map<String, Object> args = Map.of("path", projectPath);
            var request = new McpSchema.CallToolRequest(toolName, args);
            var result = snykMcpClient.callTool(request);

            String text = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .collect(Collectors.joining("\n"));

            if (text.isBlank()) {
                log.warn("Snyk MCP tool {} returned empty response", toolName);
                return null;
            }
            log.info("Snyk {} scan returned {} chars", label, text.length());
            return text;
        } catch (Exception e) {
            log.warn("Snyk MCP tool {} failed: {}", toolName, e.getMessage());
            return null;
        }
    }
}
