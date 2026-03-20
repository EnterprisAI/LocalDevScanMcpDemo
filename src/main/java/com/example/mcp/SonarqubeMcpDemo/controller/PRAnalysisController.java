package com.example.mcp.SonarqubeMcpDemo.controller;

import com.example.mcp.SonarqubeMcpDemo.service.PRAnalysisService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for manually triggering a PR analysis.
 * Useful for testing without setting up a GitHub webhook.
 *
 * <pre>
 * POST /analyze-pr?owner=myorg&repo=myrepo&prNumber=42&sonarProjectKey=my-project
 * </pre>
 *
 * The response is a plain-text summary from the LLM describing:
 * <ul>
 *   <li>Files changed in the PR</li>
 *   <li>SonarQube issues found for the project</li>
 *   <li>Issues matched to changed files</li>
 *   <li>Review comment posted to the PR (if GitHub tools are available)</li>
 * </ul>
 *
 * Example curl:
 * <pre>
 * curl -X POST "http://localhost:8080/analyze-pr?owner=deepak&repo=demo-app&prNumber=1&sonarProjectKey=demo-app"
 * </pre>
 */
@RestController
@RequestMapping("/analyze-pr")
public class PRAnalysisController {

    private final PRAnalysisService prAnalysisService;

    public PRAnalysisController(PRAnalysisService prAnalysisService) {
        this.prAnalysisService = prAnalysisService;
    }

    @PostMapping(produces = "text/plain")
    public String analyzePR(
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam int prNumber,
            @RequestParam(defaultValue = "") String sonarProjectKey) {

        // Default the SonarQube project key to the repo name if not provided
        String resolvedKey = sonarProjectKey.isBlank() ? repo.toLowerCase() : sonarProjectKey;
        return prAnalysisService.analyzePullRequest(owner, repo, prNumber, resolvedKey);
    }
}
