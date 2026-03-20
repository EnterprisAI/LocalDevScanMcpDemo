package com.example.mcp.SonarqubeMcpDemo.config;

import org.springframework.context.annotation.Configuration;

/**
 * MCP server configuration.
 *
 * <p>Tool registration is automatic: Spring AI MCP server auto-config picks up
 * all {@code ToolCallbackProvider} beans in the context. The
 * {@code allMcpToolCallbackProvider} bean defined in {@link McpClientsConfig}
 * exposes tools from both the official GitHub MCP server and the official
 * SonarQube MCP server to any connected MCP client (e.g. Claude Desktop).
 *
 * <p>No manual bean registration is needed here.
 */
@Configuration
public class McpServerConfig {
}
