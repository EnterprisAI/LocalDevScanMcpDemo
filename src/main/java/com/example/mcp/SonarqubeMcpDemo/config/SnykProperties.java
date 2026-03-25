package com.example.mcp.SonarqubeMcpDemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "snyk")
public class SnykProperties {
    private String token = "";
    private String orgId = "";

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
}
