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
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configures the SonarQube MCP client for local pre-PR scanning.
 *
 * <p>The SonarQube MCP server is launched as a Docker subprocess and communicates via stdio.
 * GitHub MCP is NOT needed for local scanning (no PR interaction required).
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code SONARQUBE_TOKEN} – SonarQube/SonarCloud user token</li>
 *   <li>{@code SONARQUBE_URL}   – SonarQube server URL (default: https://sonarcloud.io)</li>
 *   <li>{@code SONARQUBE_ORG}  – SonarCloud organization key (SonarCloud only)</li>
 * </ul>
 */
@Configuration
public class McpClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientsConfig.class);

    private McpSyncClient sonarqubeClient;

    @Bean
    public McpSyncClient sonarqubeMcpClient(SonarQubeProperties props) {
        String sonarUrl = props.getUrl().contains("sonarcloud.io")
                ? props.getUrl()
                : props.getUrl()
                        .replace("localhost", "host.docker.internal")
                        .replace("127.0.0.1", "host.docker.internal");

        var argsList = new ArrayList<String>();
        argsList.addAll(List.of("run", "--init", "--rm", "-i",
                "-e", "SONARQUBE_TOKEN=" + props.getToken(),
                "-e", "SONARQUBE_URL=" + sonarUrl));

        if (props.getOrg() != null && !props.getOrg().isBlank()) {
            argsList.add("-e");
            argsList.add("SONARQUBE_ORG=" + props.getOrg());
        }
        argsList.add("mcp/sonarqube");

        ServerParameters params = ServerParameters.builder("docker").args(argsList).build();
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

    @Bean
    public SyncMcpToolCallbackProvider allMcpToolCallbackProvider(McpSyncClient sonarqubeMcpClient) {
        // Single-client provider — no prefix generator needed (no duplicate tool names)
        return new SyncMcpToolCallbackProvider(sonarqubeMcpClient);
    }

    @PreDestroy
    public void cleanup() {
        if (sonarqubeClient != null) {
            try { sonarqubeClient.closeGracefully(); } catch (Exception e) {
                log.warn("SonarQube MCP close error", e);
            }
        }
    }
}
