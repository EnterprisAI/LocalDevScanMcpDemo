package com.example.mcp.SonarqubeMcpDemo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;

/**
 * Core service that orchestrates PR analysis using an LLM with access to the
 * official GitHub MCP server tools and the official SonarQube MCP server tools.
 *
 * <p>Flow for PR analysis:
 * <ol>
 *   <li>LLM uses GitHub MCP tools to get PR details and changed files</li>
 *   <li>LLM uses SonarQube MCP tools to get issues for the project</li>
 *   <li>LLM matches issues to changed files and composes fix suggestions</li>
 *   <li>LLM uses GitHub MCP tools to post the review on the PR</li>
 * </ol>
 */
@Service
public class PRAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(PRAnalysisService.class);

    private static final String SYSTEM_PROMPT = """
            You are a code quality and security assistant with access to GitHub and SonarQube via MCP tools.

            GitHub tools available:
            - pull_request_read: get PR details and list files changed in the PR
            - pull_request_review_write: post a review on the PR — IMPORTANT: use method="create_review"
              with event="COMMENT" and body containing all findings in a single call. Do NOT use
              create/add_comment/submit_pending flow — use a single create_review call.
            - add_comment_to_pending_review: add a comment to a pending review (only if needed)

            SonarQube tools available:
            - search_sonar_issues_in_projects: get open code issues for a project
            - get_project_quality_gate_status: check if the project passes quality gate

            When analyzing a pull request:
            1. Use pull_request_read to get the list of changed files
            2. Use search_sonar_issues_in_projects to get issues for the sonarProjectKey
            3. Match issues to changed files by comparing file paths
            4. Build a markdown summary of all matched issues with severity, file, line, message, and fix suggestion
            5. Call pull_request_review_write ONCE with method="create_review", event="COMMENT",
               and the full markdown body — this MUST be called to post the review to GitHub
            6. Return a plain-text summary of what was done

            Always cite the project key or PR number in your response.
            """;

    // Tools needed for PR analysis — keeps request payload under GitHub Models 8k limit.
    // GitHub tools: read PR details, list files, post review.
    // SonarQube tools: search issues, check quality gate.
    private static final Set<String> PR_ANALYSIS_TOOLS = Set.of(
            // GitHub MCP tools
            "pull_request_read",
            "pull_request_review_write",
            "add_comment_to_pending_review",
            // SonarQube MCP tools
            "search_sonar_issues_in_projects",
            "get_project_quality_gate_status"
    );

    private final ChatClient chatClient;
    private final ToolCallback[] allToolCallbacks;

    public PRAnalysisService(ChatClient.Builder builder,
                             SyncMcpToolCallbackProvider allMcpToolCallbackProvider) {
        this.allToolCallbacks = allMcpToolCallbackProvider.getToolCallbacks();

        // Log all available tool names so we can tune PR_ANALYSIS_TOOLS
        log.info("Available MCP tools ({}):", allToolCallbacks.length);
        Arrays.stream(allToolCallbacks)
              .forEach(t -> log.info("  tool: {}", t.getToolDefinition().name()));

        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();  // No default tools — injected per-request to stay under token limit

        log.info("PRAnalysisService ready — {} total tool(s) available", allToolCallbacks.length);
    }

    /** Returns only the tools relevant to PR analysis to stay under the 8k token limit. */
    private ToolCallback[] prAnalysisTools() {
        return Arrays.stream(allToolCallbacks)
                .filter(t -> PR_ANALYSIS_TOOLS.contains(t.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    /**
     * Analyzes a GitHub PR using SonarQube data and posts review comments.
     *
     * @param owner           GitHub repository owner
     * @param repo            GitHub repository name
     * @param prNumber        Pull request number
     * @param sonarProjectKey SonarQube project key
     * @return LLM-generated summary of findings and actions taken
     */
    public String analyzePullRequest(String owner, String repo, int prNumber, String sonarProjectKey) {
        String prompt = String.format("""
                Analyze GitHub pull request #%d in the repository %s/%s.

                Steps to follow:
                1. Use the GitHub MCP tool to get details of pull request #%d in %s/%s
                   (title, description, branch info).
                2. Use the GitHub MCP tool to list all files changed in pull request #%d in %s/%s.
                3. Use the SonarQube MCP tool to get all open issues for project key "%s".
                4. For each SonarQube issue whose file path matches any of the changed files,
                   compose a specific fix suggestion with:
                   - Issue message and severity
                   - File path and line number
                   - Concrete fix recommendation
                5. Use the GitHub MCP tool to post a PR review on pull request #%d in %s/%s
                   with event="COMMENT" and a markdown body summarizing all matched findings.
                6. Return a plain-text summary:
                   - Files changed in PR
                   - Total SonarQube issues in project
                   - Issues matched to changed files
                   - Review posted: yes/no
                """,
                prNumber, owner, repo,
                prNumber, owner, repo,
                prNumber, owner, repo,
                sonarProjectKey,
                prNumber, owner, repo);

        ToolCallback[] tools = prAnalysisTools();
        log.info("analyzePullRequest: sending {} tool(s) to LLM", tools.length);
        return chatClient.prompt(prompt).toolCallbacks(tools).call().content();
    }

    /**
     * General-purpose chat using GitHub and SonarQube MCP tools.
     */
    public String chat(String question) {
        return chatClient.prompt(question).toolCallbacks(allToolCallbacks).call().content();
    }
}
