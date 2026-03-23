package com.example.mcp.SonarqubeMcpDemo.service;

import com.example.mcp.SonarqubeMcpDemo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls LLM providers in order, skipping any that are rate-limited or unavailable.
 * Never uses paid models (paid=true entries are skipped).
 * Throws if all free providers are exhausted.
 *
 * Uses java.net.http.HttpClient with a 90-second total request timeout per provider.
 * This prevents slow streaming models from blocking the fallback chain indefinitely.
 */
@Service
public class FallbackLlmService {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmService.class);
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(90);

    private final LlmProperties llmProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FallbackLlmService(LlmProperties llmProperties,
                               @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Sends a chat request with systemPrompt + userPrompt.
     * Iterates through free providers until one succeeds.
     */
    public String chat(String systemPrompt, String userPrompt) {
        List<LlmProperties.Provider> providers = llmProperties.getProviders();
        if (providers.isEmpty()) {
            throw new RuntimeException("No LLM providers configured in llm.providers");
        }

        StringBuilder tried = new StringBuilder();
        for (LlmProperties.Provider provider : providers) {
            if (provider.isPaid()) {
                log.info("Skipping paid provider: {} (paid=true, will not use without explicit approval)", provider.getName());
                continue;
            }
            tried.append(provider.getName()).append(", ");
            try {
                log.info("Trying LLM provider: {} ({})", provider.getName(), provider.getModel());
                String result = callProvider(provider, systemPrompt, userPrompt);
                if (result != null && !result.isBlank() && !result.trim().equals("null")) {
                    log.info("✅ Got response from provider: {}", provider.getName());
                    return result;
                }
                log.warn("Empty/null response from {}, trying next...", provider.getName());
            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("⏱️  Timeout ({}s) on {}, trying next...", PROVIDER_TIMEOUT.getSeconds(), provider.getName());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.contains("429") || msg.contains("rate") || msg.contains("quota")) {
                    log.warn("⚠️  Rate-limited on {}, trying next...", provider.getName());
                } else if (msg.contains("402")) {
                    log.warn("⚠️  Payment required for {} — skipping (free-only policy)", provider.getName());
                } else {
                    log.warn("⚠️  Error calling {}: {}", provider.getName(), msg.substring(0, Math.min(200, msg.length())));
                }
            }
        }
        throw new RuntimeException(
            "All free LLM providers exhausted. Tried: " + tried +
            " — No paid models will be used without approval."
        );
    }

    private String callProvider(LlmProperties.Provider provider, String systemPrompt, String userPrompt) throws Exception {
        String url = provider.getBaseUrl().stripTrailing() + provider.getCompletionsPath();

        Map<String, Object> bodyMap = Map.of(
            "model", provider.getModel(),
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "max_tokens", 8000,
            "temperature", 0.1
        );
        String bodyJson = objectMapper.writeValueAsString(bodyMap);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(PROVIDER_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + provider.getApiKey())
                .header("HTTP-Referer", "https://github.com/LocalDevScanMcpDemo")
                .header("X-Title", "LocalDevScanMcpDemo")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 429) throw new RuntimeException("429 rate-limited");
        if (status == 402) throw new RuntimeException("402 payment required");
        if (status < 200 || status >= 300) {
            String body = response.body();
            throw new RuntimeException("HTTP " + status + ": " + body.substring(0, Math.min(200, body.length())));
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isMissingNode() ? null : content.asText();
    }
}
