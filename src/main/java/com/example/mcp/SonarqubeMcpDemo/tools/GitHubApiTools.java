package com.example.mcp.SonarqubeMcpDemo.tools;

import com.example.mcp.SonarqubeMcpDemo.config.GitHubProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * MCP tools that expose GitHub Pull Request operations to the AI assistant.
 *
 * <p>These tools mirror the key tools provided by the official GitHub MCP server
 * but call the GitHub REST API directly, avoiding OAuth transport complications.
 *
 * <p>Tools available:
 * <ul>
 *   <li>{@link #getPullRequest} — get PR details</li>
 *   <li>{@link #listPullRequestFiles} — list files changed in a PR</li>
 *   <li>{@link #createPullRequestReview} — post a review comment to a PR</li>
 *   <li>{@link #getFileContents} — read a file from the repository</li>
 * </ul>
 */
@Service
public class GitHubApiTools {

    private static final String GITHUB_API = "https://api.github.com";

    private final GitHubProperties props;
    private final HttpClient httpClient;

    public GitHubApiTools(GitHubProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Tool(description = "Get details of a GitHub pull request including title, description, state, author, source branch, target branch, and number of changed files.")
    public String getPullRequest(
            @ToolParam(description = "GitHub repository owner (user or organisation), e.g. 'myorg'") String owner,
            @ToolParam(description = "GitHub repository name, e.g. 'my-service'") String repo,
            @ToolParam(description = "Pull request number, e.g. 42") int pullNumber) {

        return get("/repos/" + owner + "/" + repo + "/pulls/" + pullNumber);
    }

    @Tool(description = "List all files changed in a GitHub pull request. Returns filename, status (added/modified/removed), additions, deletions, and patch diff for each file.")
    public String listPullRequestFiles(
            @ToolParam(description = "GitHub repository owner") String owner,
            @ToolParam(description = "GitHub repository name") String repo,
            @ToolParam(description = "Pull request number") int pullNumber) {

        return get("/repos/" + owner + "/" + repo + "/pulls/" + pullNumber + "/files?per_page=100");
    }

    @Tool(description = "Post a review on a GitHub pull request. Use event=COMMENT to add comments without approving or requesting changes. The body should be a markdown summary of findings and fix suggestions.")
    public String createPullRequestReview(
            @ToolParam(description = "GitHub repository owner") String owner,
            @ToolParam(description = "GitHub repository name") String repo,
            @ToolParam(description = "Pull request number") int pullNumber,
            @ToolParam(description = "Review event: COMMENT, APPROVE, or REQUEST_CHANGES. Use COMMENT for neutral analysis.") String event,
            @ToolParam(description = "Markdown body of the review — include all findings and fix suggestions here") String body) {

        String json = "{\"event\":\"" + event.toUpperCase() + "\","
                + "\"body\":" + escapeJson(body) + "}";

        return post("/repos/" + owner + "/" + repo + "/pulls/" + pullNumber + "/reviews", json);
    }

    @Tool(description = "Get the raw contents of a file from a GitHub repository. Useful for reading source code to provide more specific fix suggestions.")
    public String getFileContents(
            @ToolParam(description = "GitHub repository owner") String owner,
            @ToolParam(description = "GitHub repository name") String repo,
            @ToolParam(description = "File path relative to repository root, e.g. 'src/main/java/HelloController.java'") String path,
            @ToolParam(description = "Branch name or commit SHA. Leave empty to use the default branch.") String ref) {

        String url = "/repos/" + owner + "/" + repo + "/contents/" + path;
        if (ref != null && !ref.isBlank()) {
            url += "?ref=" + ref;
        }
        // Request raw content directly
        return getRaw(url);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private String get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API + path))
                    .header("Authorization", "Bearer " + props.getToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return handleResponse(response, path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Request interrupted\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String getRaw(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API + path))
                    .header("Authorization", "Bearer " + props.getToken())
                    .header("Accept", "application/vnd.github.raw+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return handleResponse(response, path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Request interrupted\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String post(String path, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API + path))
                    .header("Authorization", "Bearer " + props.getToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return handleResponse(response, path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Request interrupted\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String handleResponse(HttpResponse<String> response, String path) {
        if (response.statusCode() == 401) {
            return "{\"error\": \"GitHub authentication failed. Check GITHUB_TOKEN.\"}";
        }
        if (response.statusCode() == 403) {
            return "{\"error\": \"GitHub API rate limited or insufficient permissions.\"}";
        }
        if (response.statusCode() == 404) {
            return "{\"error\": \"Not found: " + path + "\"}";
        }
        if (response.statusCode() == 422) {
            return "{\"error\": \"Unprocessable Entity — review may already exist or PR is merged.\", \"body\": " + response.body() + "}";
        }
        if (response.statusCode() >= 400) {
            return "{\"error\": \"GitHub API HTTP " + response.statusCode() + "\", \"body\": " + response.body() + "}";
        }
        return response.body();
    }

    /** Wraps a string value in a JSON string literal, escaping special characters. */
    private String escapeJson(String value) {
        if (value == null) return "\"\"";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
