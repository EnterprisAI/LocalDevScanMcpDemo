package com.example.mcp.SonarqubeMcpDemo.controller;

import com.example.mcp.SonarqubeMcpDemo.service.PRAnalysisService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint that accepts a plain-text question and answers using both
 * SonarQube tools and GitHub tools (via the GitHub MCP server).
 *
 * <pre>
 * POST /chat
 * Content-Type: text/plain
 *
 * Example questions:
 *   "What projects are in SonarQube?"
 *   "What are the issues in PR #42 of myorg/myrepo?"
 *   "List changed files in PR #10 of deepak/demo-app"
 * </pre>
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final PRAnalysisService prAnalysisService;

    public ChatController(PRAnalysisService prAnalysisService) {
        this.prAnalysisService = prAnalysisService;
    }

    @PostMapping(consumes = "text/plain", produces = "text/plain")
    public String chat(@RequestBody String question) {
        return prAnalysisService.chat(question);
    }
}
