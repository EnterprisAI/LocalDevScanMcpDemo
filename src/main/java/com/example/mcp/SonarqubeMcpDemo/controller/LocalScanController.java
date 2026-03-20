package com.example.mcp.SonarqubeMcpDemo.controller;

import com.example.mcp.SonarqubeMcpDemo.model.ApplyFixesRequest;
import com.example.mcp.SonarqubeMcpDemo.model.FixSuggestion;
import com.example.mcp.SonarqubeMcpDemo.model.ScanRequest;
import com.example.mcp.SonarqubeMcpDemo.service.AutoFixService;
import com.example.mcp.SonarqubeMcpDemo.service.LocalScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for local pre-PR scanning and auto-fix.
 *
 * <pre>
 * POST /scan-local          — run full SonarQube + Snyk scan, get FixSuggestion[]
 * POST /apply-fixes         — apply selected fixes to files on disk
 * </pre>
 */
@RestController
public class LocalScanController {

    private static final Logger log = LoggerFactory.getLogger(LocalScanController.class);

    private final LocalScanService localScanService;
    private final AutoFixService autoFixService;

    public LocalScanController(LocalScanService localScanService, AutoFixService autoFixService) {
        this.localScanService = localScanService;
        this.autoFixService = autoFixService;
    }

    /**
     * Run a full local scan (SonarQube + Snyk) and return LLM-generated fix suggestions.
     *
     * <p>Request body (JSON):
     * <pre>
     * {
     *   "projectPath": "C:\\path\\to\\project",
     *   "sonarProjectKey": "my-project-key",
     *   "branch": "feature/my-branch",
     *   "runSonarScan": true,
     *   "runSnykScan": true
     * }
     * </pre>
     */
    @PostMapping("/scan-local")
    public ResponseEntity<?> scanLocal(@RequestBody ScanRequest request) {
        log.info("POST /scan-local — project: {}, sonarKey: {}",
                request.getProjectPath(), request.getSonarProjectKey());

        if (request.getProjectPath() == null || request.getProjectPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectPath is required"));
        }
        if (request.getSonarProjectKey() == null || request.getSonarProjectKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sonarProjectKey is required"));
        }

        try {
            List<FixSuggestion> fixes = localScanService.scan(request);
            log.info("Scan complete — {} fix suggestion(s) generated", fixes.size());
            return ResponseEntity.ok(Map.of(
                    "projectPath", request.getProjectPath(),
                    "sonarProjectKey", request.getSonarProjectKey(),
                    "branch", request.getBranch() != null ? request.getBranch() : "unknown",
                    "totalIssues", fixes.size(),
                    "fixes", fixes
            ));
        } catch (Exception e) {
            log.error("Scan failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Apply selected fix suggestions to files on disk.
     *
     * <p>Request body (JSON):
     * <pre>
     * {
     *   "projectPath": "C:\\path\\to\\project",
     *   "rescanAfterApply": false,
     *   "fixes": [ ...FixSuggestion objects from /scan-local... ]
     * }
     * </pre>
     */
    @PostMapping("/apply-fixes")
    public ResponseEntity<?> applyFixes(@RequestBody ApplyFixesRequest request) {
        log.info("POST /apply-fixes — project: {}, fixes: {}",
                request.getProjectPath(),
                request.getFixes() != null ? request.getFixes().size() : 0);

        if (request.getProjectPath() == null || request.getProjectPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectPath is required"));
        }
        if (request.getFixes() == null || request.getFixes().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fixes list is required and must not be empty"));
        }

        try {
            AutoFixService.ApplyResult result = autoFixService.applyFixes(request);
            return ResponseEntity.ok(Map.of(
                    "appliedCount", result.applied().size(),
                    "failedCount", result.failed().size(),
                    "applied", result.applied(),
                    "failed", result.failed(),
                    "rescanNote", result.rescanSummary() != null ? result.rescanSummary() : "No rescan requested"
            ));
        } catch (Exception e) {
            log.error("Apply fixes failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
