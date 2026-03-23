package com.example.mcp.SonarqubeMcpDemo.controller;

import com.example.mcp.SonarqubeMcpDemo.config.ScanProperties;
import com.example.mcp.SonarqubeMcpDemo.model.ApplyFixesRequest;
import com.example.mcp.SonarqubeMcpDemo.model.FixSuggestion;
import com.example.mcp.SonarqubeMcpDemo.model.ScanRequest;
import com.example.mcp.SonarqubeMcpDemo.service.AutoFixService;
import com.example.mcp.SonarqubeMcpDemo.service.LocalScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for local pre-PR scanning and auto-fix.
 *
 * <p>All request fields are optional — if omitted, defaults from {@code application.yaml}
 * under the {@code scan.*} key are used. This means you can call {@code POST /scan-local}
 * with an empty body {@code {}} and it will scan the project configured in YAML.
 *
 * <pre>
 * POST /scan-local    — run SonarQube + Snyk scan, get FixSuggestion[]
 * POST /apply-fixes   — apply selected fixes to files on disk
 * GET  /scan-config   — show current effective scan defaults from YAML
 * </pre>
 */
@RestController
public class LocalScanController {

    private static final Logger log = LoggerFactory.getLogger(LocalScanController.class);

    private final LocalScanService localScanService;
    private final AutoFixService autoFixService;
    private final ScanProperties scanProperties;

    public LocalScanController(LocalScanService localScanService,
                               AutoFixService autoFixService,
                               ScanProperties scanProperties) {
        this.localScanService = localScanService;
        this.autoFixService = autoFixService;
        this.scanProperties = scanProperties;
    }

    /**
     * Returns the current scan defaults loaded from application.yaml.
     * Useful to verify config before running a scan.
     */
    @GetMapping("/scan-config")
    public ResponseEntity<?> getScanConfig() {
        var config = new LinkedHashMap<String, Object>();
        config.put("projectPath", scanProperties.getProjectPath());
        config.put("sonarProjectKey", scanProperties.getSonarProjectKey());
        config.put("branch", scanProperties.getBranch());
        config.put("runSonarScan", scanProperties.isRunSonarScan());
        config.put("runSnykScan", scanProperties.isRunSnykScan());
        config.put("mavenExecutable", scanProperties.getMavenExecutable());
        config.put("mavenGoals", scanProperties.getMavenGoals());
        config.put("sonarScanTimeoutMinutes", scanProperties.getSonarScanTimeoutMinutes());
        return ResponseEntity.ok(config);
    }

    /**
     * Run a full local scan (SonarQube + Snyk) and return LLM-generated fix suggestions.
     *
     * <p>All fields are optional — omitted fields fall back to {@code scan.*} in application.yaml.
     * Send an empty body {@code {}} to scan using all YAML defaults.
     *
     * <pre>
     * {
     *   "projectPath":     "C:/path/to/project",   // optional if scan.project-path set in YAML
     *   "sonarProjectKey": "org_key",               // optional if scan.sonar-project-key set in YAML
     *   "branch":          "main",                  // optional
     *   "runSonarScan":    false,                   // optional, default from YAML
     *   "runSnykScan":     true                     // optional, default from YAML
     * }
     * </pre>
     */
    @PostMapping("/scan-local")
    public ResponseEntity<?> scanLocal(@RequestBody(required = false) ScanRequest request) {
        if (request == null) request = new ScanRequest();
        applyDefaults(request);

        log.info("POST /scan-local — project: {}, sonarKey: {}, branch: {}",
                request.getProjectPath(), request.getSonarProjectKey(), request.getBranch());

        if (request.getProjectPath() == null || request.getProjectPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "projectPath is required — set it in the request body or via scan.project-path in application.yaml"));
        }
        if (request.getSonarProjectKey() == null || request.getSonarProjectKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sonarProjectKey is required — set it in the request body or via scan.sonar-project-key in application.yaml"));
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
     * <p>projectPath is optional if {@code scan.project-path} is set in application.yaml.
     *
     * <pre>
     * {
     *   "projectPath":      "C:/path/to/project",  // optional if set in YAML
     *   "sonarProjectKey":  "org_key",              // optional, used for rescan
     *   "branch":           "main",                 // optional, used for rescan
     *   "rescanAfterApply": false,
     *   "fixes": [ ...FixSuggestion objects from /scan-local... ]
     * }
     * </pre>
     */
    @PostMapping("/apply-fixes")
    public ResponseEntity<?> applyFixes(@RequestBody ApplyFixesRequest request) {
        applyDefaults(request);

        log.info("POST /apply-fixes — project: {}, fixes: {}",
                request.getProjectPath(),
                request.getFixes() != null ? request.getFixes().size() : 0);

        if (request.getProjectPath() == null || request.getProjectPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "projectPath is required — set it in the request body or via scan.project-path in application.yaml"));
        }
        if (request.getFixes() == null || request.getFixes().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fixes list is required and must not be empty"));
        }

        try {
            AutoFixService.ApplyResult result = autoFixService.applyFixes(request);
            var response = new LinkedHashMap<String, Object>();
            response.put("appliedCount", result.applied().size());
            response.put("failedCount", result.failed().size());
            response.put("applied", result.applied());
            response.put("failed", result.failed());
            response.put("rescanNote", result.rescanSummary() != null ? result.rescanSummary() : "No rescan requested");
            if (result.rescanFixes() != null) {
                response.put("remainingIssues", result.rescanFixes().size());
                response.put("rescanFixes", result.rescanFixes());
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Apply fixes failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Merge YAML defaults into request (request field wins if provided) ─────

    private void applyDefaults(ScanRequest req) {
        if (isBlank(req.getProjectPath()))     req.setProjectPath(scanProperties.getProjectPath());
        if (isBlank(req.getSonarProjectKey())) req.setSonarProjectKey(scanProperties.getSonarProjectKey());
        if (isBlank(req.getBranch()))          req.setBranch(scanProperties.getBranch());
        // Boolean flags: only apply YAML default if request was constructed with its own defaults
        // ScanRequest constructor sets runSonarScan=true, runSnykScan=true as its own defaults.
        // We let YAML override those constructor defaults using a sentinel approach via the request.
        if (!req.isRunSonarScanExplicitlySet()) req.setRunSonarScan(scanProperties.isRunSonarScan());
        if (!req.isRunSnykScanExplicitlySet())  req.setRunSnykScan(scanProperties.isRunSnykScan());
    }

    private void applyDefaults(ApplyFixesRequest req) {
        if (isBlank(req.getProjectPath()))     req.setProjectPath(scanProperties.getProjectPath());
        if (isBlank(req.getSonarProjectKey())) req.setSonarProjectKey(scanProperties.getSonarProjectKey());
        if (isBlank(req.getBranch()))          req.setBranch(scanProperties.getBranch());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
