package com.example.mcp.SonarqubeMcpDemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures MCP clients for the official GitHub and SonarQube MCP servers.
 *
 * <p>Both servers are launched as Docker subprocesses and communicate via stdio.
 * The resulting {@link SyncMcpToolCallbackProvider} is auto-detected by the
 * Spring AI MCP server auto-configuration and by {@code PRAnalysisService}.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code GITHUB_TOKEN} – GitHub personal access token</li>
 *   <li>{@code SONARQUBE_TOKEN} – SonarQube user token</li>
 *   <li>{@code SONARQUBE_URL} – SonarQube server URL (default: http://localhost:9000)</li>
 * </ul>
 */
@Configuration
public class McpClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientsConfig.class);

    // Kept for @PreDestroy cleanup
    private McpSyncClient githubClient;
    private McpSyncClient sonarqubeClient;

    @Bean
    public McpSyncClient githubMcpClient(GitHubProperties props) {
        // Token must be passed via Docker -e flag so the container receives it
        ServerParameters params = ServerParameters.builder("docker")
                .args("run", "--init", "--rm", "-i",
                      "-e", "GITHUB_PERSONAL_ACCESS_TOKEN=" + props.getToken(),
                      "ghcr.io/github/github-mcp-server")
                .build();

        var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));
        githubClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        log.info("Initializing GitHub MCP client (docker: ghcr.io/github/github-mcp-server)...");
        githubClient.initialize();
        log.info("GitHub MCP client ready — {} tool(s) available",
                githubClient.listTools().tools().size());
        return githubClient;
    }

    @Bean
    public McpSyncClient sonarqubeMcpClient(SonarQubeProperties props) {
        // For self-hosted SonarQube on localhost, remap to host.docker.internal
        // so the Docker container can reach the host machine.
        // For SonarCloud (sonarcloud.io) no remapping is needed.
        String sonarUrl = props.getUrl().contains("sonarcloud.io")
                ? props.getUrl()
                : props.getUrl()
                        .replace("localhost", "host.docker.internal")
                        .replace("127.0.0.1", "host.docker.internal");

        // Tokens must be passed via Docker -e flags so the container receives them
        var argsList = new java.util.ArrayList<String>();
        argsList.addAll(java.util.List.of("run", "--init", "--rm", "-i",
                "-e", "SONARQUBE_TOKEN=" + props.getToken(),
                "-e", "SONARQUBE_URL=" + sonarUrl));

        // SonarCloud requires an organization key
        if (props.getOrg() != null && !props.getOrg().isBlank()) {
            argsList.add("-e");
            argsList.add("SONARQUBE_ORG=" + props.getOrg());
        }
        argsList.add("mcp/sonarqube");

        ServerParameters params = ServerParameters.builder("docker")
                .args(argsList)
                .build();

        var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));
        sonarqubeClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .build();

        log.info("Initializing SonarQube MCP client (docker: mcp/sonarqube, url: {})...", sonarUrl);
        sonarqubeClient.initialize();
        log.info("SonarQube MCP client ready — {} tool(s) available",
                sonarqubeClient.listTools().tools().size());
        return sonarqubeClient;
    }

    /**
     * Combined tool-callback provider exposing tools from both MCP servers.
     * Uses {@link DefaultMcpToolNamePrefixGenerator} to prefix duplicate tool names
     * (e.g. both servers expose list_pull_requests → they get unique prefixed names).
     * Auto-detected by Spring AI MCP server auto-config and by PRAnalysisService.
     */
    @Bean
    public SyncMcpToolCallbackProvider allMcpToolCallbackProvider(
            @Qualifier("githubMcpClient") McpSyncClient githubMcpClient,
            @Qualifier("sonarqubeMcpClient") McpSyncClient sonarqubeMcpClient) {
        return new SyncMcpToolCallbackProvider(
                (info, tool) -> true,                  // allow all tools from both servers
                new DefaultMcpToolNamePrefixGenerator(), // prefix duplicates automatically
                githubMcpClient, sonarqubeMcpClient);
    }

    @PreDestroy
    public void cleanup() {
        log.info("Closing MCP clients...");
        if (githubClient != null) {
            try { githubClient.closeGracefully(); } catch (Exception e) { log.warn("GitHub MCP close error", e); }
        }
        if (sonarqubeClient != null) {
            try { sonarqubeClient.closeGracefully(); } catch (Exception e) { log.warn("SonarQube MCP close error", e); }
        }
    }
}
