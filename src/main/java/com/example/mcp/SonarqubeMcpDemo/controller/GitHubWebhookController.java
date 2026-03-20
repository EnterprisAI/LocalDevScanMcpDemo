package com.example.mcp.SonarqubeMcpDemo.controller;

import com.example.mcp.SonarqubeMcpDemo.config.GitHubProperties;
import com.example.mcp.SonarqubeMcpDemo.service.PRAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * Handles incoming GitHub webhook events.
 *
 * <p>Register this endpoint in your GitHub repository:
 * <pre>
 *   Repository → Settings → Webhooks → Add webhook
 *   Payload URL : http://your-server:8080/webhook/github
 *   Content type: application/json
 *   Secret      : value of GITHUB_WEBHOOK_SECRET (optional but recommended)
 *   Events      : Pull requests
 * </pre>
 *
 * <p>When a PR is opened or updated the webhook triggers an async LLM analysis that:
 * <ol>
 *   <li>Gets PR details and changed files via GitHub API tools</li>
 *   <li>Fetches SonarQube issues for the repository's project</li>
 *   <li>Posts matched issues as a PR review comment</li>
 * </ol>
 *
 * <p>The SonarQube project key defaults to the repository name (lowercase).
 * Override with the {@code X-Sonar-Project-Key} request header if your project key differs.
 */
@RestController
@RequestMapping("/webhook")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    private final PRAnalysisService prAnalysisService;
    private final GitHubProperties gitHubProperties;
    private final ObjectMapper objectMapper;

    public GitHubWebhookController(
            PRAnalysisService prAnalysisService,
            GitHubProperties gitHubProperties,
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper) {
        this.prAnalysisService = prAnalysisService;
        this.gitHubProperties = gitHubProperties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-Sonar-Project-Key", required = false) String sonarKeyOverride,
            @RequestBody String payload) {

        // Verify HMAC signature if a webhook secret is configured
        if (!gitHubProperties.getWebhookSecret().isBlank()) {
            if (!verifySignature(payload, signature)) {
                log.warn("Webhook rejected — invalid or missing X-Hub-Signature-256");
                return ResponseEntity.status(401).body("Invalid webhook signature");
            }
        }

        // Only handle pull_request events
        if (!"pull_request".equals(event)) {
            log.debug("Ignoring event '{}' — only 'pull_request' events are handled", event);
            return ResponseEntity.ok("Event '" + event + "' ignored — only pull_request events trigger analysis");
        }

        try {
            JsonNode json = objectMapper.readTree(payload);
            String action = json.path("action").asText();

            // Only react to PR opened or new commits pushed
            if (!"opened".equals(action) && !"synchronize".equals(action)) {
                log.debug("Ignoring PR action '{}' — only 'opened' and 'synchronize' trigger analysis", action);
                return ResponseEntity.ok("PR action '" + action + "' ignored");
            }

            String owner = json.path("repository").path("owner").path("login").asText();
            String repo = json.path("repository").path("name").asText();
            int prNumber = json.path("pull_request").path("number").asInt();

            // Derive the SonarQube project key from the repo name unless overridden
            String sonarProjectKey = (sonarKeyOverride != null && !sonarKeyOverride.isBlank())
                    ? sonarKeyOverride
                    : repo.toLowerCase();

            log.info("PR #{} {} in {}/{} — starting async analysis (sonar key: {})",
                    prNumber, action, owner, repo, sonarProjectKey);

            // Run asynchronously so the webhook returns HTTP 202 immediately
            // GitHub requires a response within 10 seconds; LLM analysis may take longer
            CompletableFuture.runAsync(() -> {
                try {
                    String result = prAnalysisService.analyzePullRequest(owner, repo, prNumber, sonarProjectKey);
                    log.info("Analysis complete for PR #{} in {}/{}: {}", prNumber, owner, repo, result);
                } catch (Exception e) {
                    log.error("Analysis failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
                }
            });

            return ResponseEntity.accepted()
                    .body("Analysis started for PR #" + prNumber + " in " + owner + "/" + repo
                            + " (SonarQube project: " + sonarProjectKey + ")");

        } catch (Exception e) {
            log.error("Failed to parse webhook payload", e);
            return ResponseEntity.badRequest().body("Failed to parse payload: " + e.getMessage());
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature sent by GitHub.
     * GitHub computes: HMAC-SHA256(webhookSecret, requestBody) and sends it as "sha256=&lt;hex&gt;".
     */
    private boolean verifySignature(String payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    gitHubProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hmac);
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("Signature verification failed", e);
            return false;
        }
    }
}
