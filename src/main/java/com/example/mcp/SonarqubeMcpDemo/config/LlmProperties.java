package com.example.mcp.SonarqubeMcpDemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered list of LLM providers tried in sequence — free models first.
 * Falls through to next on 429 / rate-limit / error.
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private List<Provider> providers = new ArrayList<>();

    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) { this.providers = providers; }

    public static class Provider {
        private String name;
        private String baseUrl;
        private String apiKey;
        private String completionsPath = "/v1/chat/completions";
        private String model;
        private boolean paid = false;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getCompletionsPath() { return completionsPath; }
        public void setCompletionsPath(String completionsPath) { this.completionsPath = completionsPath; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public boolean isPaid() { return paid; }
        public void setPaid(boolean paid) { this.paid = paid; }
    }
}
