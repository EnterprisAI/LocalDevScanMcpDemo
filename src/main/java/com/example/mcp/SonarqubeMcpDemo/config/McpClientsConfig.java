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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures MCP clients for local pre-PR scanning.
 *
 * <p>Two MCP servers are launched as subprocesses communicating via stdio:
 * <ul>
 *   <li><b>SonarQube MCP</b> — Docker image {@code mcp/sonarqube}, reads issues from SonarCloud</li>
 *   <li><b>Snyk MCP</b>      — {@code npx snyk mcp -t stdio}, runs {@code snyk_sca_scan} and
 *       {@code snyk_code_scan} tools against the local project path, reporting to Snyk Cloud</li>
 * </ul>
 */
@Configuration
public class McpClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientsConfig.class);

    private McpSyncClient sonarqubeClient;
    private McpSyncClient snykClient;

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
    public McpSyncClient snykMcpClient(SnykProperties props) {
        // Inherit full system environment (keeps PATH, USERPROFILE, etc.)
        // then add/override Snyk-specific vars
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("SNYK_TOKEN", props.getToken());
        if (props.getOrgId() != null && !props.getOrgId().isBlank()) {
            env.put("SNYK_CFG_ORG", props.getOrgId());
        }

        // On Windows, snyk is a .cmd script — must invoke via cmd /c
        // Snyk CLI must be installed globally: npm install -g snyk
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ServerParameters params;
        if (isWindows) {
            params = ServerParameters.builder("cmd")
                    .args(List.of("/c", "snyk", "mcp", "-t", "stdio", "--disable-trust"))
                    .env(env)
                    .build();
        } else {
            params = ServerParameters.builder("snyk")
                    .args(List.of("mcp", "-t", "stdio", "--disable-trust"))
                    .env(env)
                    .build();
        }

        var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));
        snykClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(120))
                .build();

        log.info("Initializing Snyk MCP client (npx snyk mcp -t stdio, org: {})...", props.getOrgId());
        snykClient.initialize();
        log.info("Snyk MCP client ready — {} tool(s) available",
                snykClient.listTools().tools().size());
        return snykClient;
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
        if (snykClient != null) {
            try { snykClient.closeGracefully(); } catch (Exception e) {
                log.warn("Snyk MCP close error", e);
            }
        }
    }
}
