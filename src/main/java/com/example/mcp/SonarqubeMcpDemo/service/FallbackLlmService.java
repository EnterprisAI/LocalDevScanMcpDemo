package com.example.mcp.SonarqubeMcpDemo.service;

import com.example.mcp.SonarqubeMcpDemo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Calls LLM providers in order, skipping any that are rate-limited or unavailable.
 * Never uses paid models (paid=true entries are skipped).
 * Throws if all free providers are exhausted.
 */
@Service
public class FallbackLlmService {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmService.class);

    private final LlmProperties llmProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FallbackLlmService(LlmProperties llmProperties,
                               @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
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
                if (result != null && !result.isBlank()) {
                    log.info("✅ Got response from provider: {}", provider.getName());
                    return result;
                }
                log.warn("Empty response from {}, trying next...", provider.getName());
            } catch (HttpClientErrorException e) {
                int status = e.getStatusCode().value();
                String body = e.getResponseBodyAsString();
                if (status == 429 || body.contains("rate") || body.contains("quota") || body.contains("temporarily")) {
                    log.warn("⚠️  Rate-limited on {} (HTTP {}), trying next...", provider.getName(), status);
                } else if (status == 402) {
                    log.warn("⚠️  Payment required for {} — skipping (free-only policy)", provider.getName());
                } else {
                    log.warn("⚠️  HTTP {} from {}: {}", status, provider.getName(), body.substring(0, Math.min(200, body.length())));
                }
            } catch (Exception e) {
                log.warn("⚠️  Error calling {}: {}", provider.getName(), e.getMessage());
            }
        }
        throw new RuntimeException(
            "All free LLM providers exhausted. Tried: " + tried +
            " — No paid models will be used without approval."
        );
    }

    private String callProvider(LlmProperties.Provider provider, String systemPrompt, String userPrompt) throws Exception {
        String url = provider.getBaseUrl().stripTrailing() + provider.getCompletionsPath();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(provider.getApiKey());
        // OpenRouter requires these headers for attribution
        headers.set("HTTP-Referer", "https://github.com/LocalDevScanMcpDemo");
        headers.set("X-Title", "LocalDevScanMcpDemo");

        Map<String, Object> body = Map.of(
            "model", provider.getModel(),
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "max_tokens", 8000,
            "temperature", 0.1
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? null : content.asText();
        }
        return null;
    }
}
