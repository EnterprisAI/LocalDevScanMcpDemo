package com.example.mcp.SonarqubeMcpDemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sonarqube")
public class SonarQubeProperties {

    private String url = "https://sonarcloud.io";
    private String token = "";
    private String org = "";  // SonarCloud organization key (e.g. "my-org")

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getOrg() {
        return org;
    }

    public void setOrg(String org) {
        this.org = org;
    }
}
