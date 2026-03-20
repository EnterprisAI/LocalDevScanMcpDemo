package com.example.mcp.SonarqubeMcpDemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds GitHub-related configuration from application.yaml / environment variables.
 * <p>
 * Properties:
 * <ul>
 *   <li>{@code github.token}        — GitHub PAT (same as GITHUB_TOKEN used for GitHub Models API)</li>
 *   <li>{@code github.webhook-secret} — optional shared secret to verify GitHub webhook payloads</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    private String token = "";
    private String webhookSecret = "";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }
}
