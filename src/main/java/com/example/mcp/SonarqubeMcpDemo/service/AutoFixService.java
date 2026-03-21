package com.example.mcp.SonarqubeMcpDemo.service;

import com.example.mcp.SonarqubeMcpDemo.model.ApplyFixesRequest;
import com.example.mcp.SonarqubeMcpDemo.model.FixSuggestion;
import com.example.mcp.SonarqubeMcpDemo.model.ScanRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies LLM-generated fix suggestions to files on disk.
 *
 * <p>For each FixSuggestion:
 * <ol>
 *   <li>Reads the target file</li>
 *   <li>Finds originalCode in the file content</li>
 *   <li>Replaces it with suggestedCode</li>
 *   <li>Writes the patched file (original backed up as .bak)</li>
 * </ol>
 */
@Service
public class AutoFixService {

    private static final Logger log = LoggerFactory.getLogger(AutoFixService.class);

    private final LocalScanService localScanService;

    public AutoFixService(LocalScanService localScanService) {
        this.localScanService = localScanService;
    }

    public record ApplyResult(List<FixSuggestion> applied, List<FixSuggestion> failed, String rescanSummary,
                              List<FixSuggestion> rescanFixes) {}

    public ApplyResult applyFixes(ApplyFixesRequest request) throws Exception {
        List<FixSuggestion> applied = new ArrayList<>();
        List<FixSuggestion> failed = new ArrayList<>();

        for (FixSuggestion fix : request.getFixes()) {
            if (fix.getOriginalCode() == null || fix.getOriginalCode().isBlank()) {
                log.warn("Skipping fix for {} — no originalCode provided", fix.getFile());
                fix.setApplied(false);
                failed.add(fix);
                continue;
            }
            if (fix.getSuggestedCode() == null) {
                log.warn("Skipping fix for {} — no suggestedCode provided", fix.getFile());
                fix.setApplied(false);
                failed.add(fix);
                continue;
            }

            try {
                boolean patched = applyFix(request.getProjectPath(), fix);
                fix.setApplied(patched);
                if (patched) {
                    applied.add(fix);
                    log.info("Applied fix: {} ({})", fix.getIssue(), fix.getFile());
                } else {
                    failed.add(fix);
                    log.warn("Could not apply fix: {} — original code not found in {}", fix.getIssue(), fix.getFile());
                }
            } catch (IOException e) {
                log.error("Error applying fix to {}: {}", fix.getFile(), e.getMessage());
                fix.setApplied(false);
                failed.add(fix);
            }
        }

        // Optionally re-scan
        String rescanSummary = null;
        List<FixSuggestion> rescanFixes = null;
        if (request.isRescanAfterApply() && !applied.isEmpty()) {
            log.info("Re-scanning after applying {} fix(es)...", applied.size());
            try {
                ScanRequest rescan = new ScanRequest();
                rescan.setProjectPath(request.getProjectPath());
                rescan.setSonarProjectKey(request.getSonarProjectKey());
                rescan.setBranch(request.getBranch());
                rescan.setRunSonarScan(false); // skip mvn sonar:sonar for speed — use cached SonarCloud results
                rescan.setRunSnykScan(request.getSonarProjectKey() == null); // snyk-only if no sonar key
                rescanFixes = localScanService.scan(rescan);
                rescanSummary = String.format(
                        "Rescan complete: %d remaining issue(s) from SonarCloud " +
                        "(applied %d local fix(es) — run mvn sonar:sonar then rescan to see updated count)",
                        rescanFixes.size(), applied.size());
                log.info(rescanSummary);
            } catch (Exception e) {
                rescanSummary = "Rescan failed: " + e.getMessage();
                log.error("Rescan failed: {}", e.getMessage());
            }
        }

        log.info("Apply complete: {} applied, {} failed", applied.size(), failed.size());
        return new ApplyResult(applied, failed, rescanSummary, rescanFixes);
    }

    private boolean applyFix(String projectPath, FixSuggestion fix) throws IOException {
        Path filePath = Paths.get(projectPath, fix.getFile());

        if (!Files.exists(filePath)) {
            log.warn("File not found: {}", filePath);
            return false;
        }

        String content = Files.readString(filePath);
        String original = fix.getOriginalCode();
        String suggested = fix.getSuggestedCode();

        if (!content.contains(original)) {
            // Try normalized whitespace match
            String normalizedContent = content.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
            String normalizedOriginal = original.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
            if (!normalizedContent.contains(normalizedOriginal)) {
                return false;
            }
            content = normalizedContent;
            original = normalizedOriginal;
        }

        // Backup original file
        Path backup = filePath.resolveSibling(filePath.getFileName() + ".bak");
        Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);

        // Apply fix
        String patched = content.replace(original, suggested);
        Files.writeString(filePath, patched);

        return true;
    }
}
