package com.example.mcp.SonarqubeMcpDemo.service;

import com.example.mcp.SonarqubeMcpDemo.config.SnykProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs Snyk scans via Docker subprocess and returns raw JSON output.
 *
 * <p>Runs three scan types:
 * <ul>
 *   <li>{@code snyk test} — open-source dependency vulnerabilities</li>
 *   <li>{@code snyk code test} — SAST code security issues</li>
 * </ul>
 *
 * <p>Docker must be running. The project directory is mounted into the container.
 */
@Service
public class SnykScanService {

    private static final Logger log = LoggerFactory.getLogger(SnykScanService.class);

    private final SnykProperties snykProperties;

    public SnykScanService(SnykProperties snykProperties) {
        this.snykProperties = snykProperties;
    }

    public record SnykResults(String dependencyJson, String codeJson, boolean success, String error) {}

    /**
     * Runs snyk test (dependencies) and snyk code test (SAST) on the given project path.
     */
    public SnykResults scan(String projectPath) {
        log.info("Starting Snyk scan on: {}", projectPath);

        // Normalize path for Docker volume mount (Windows → Docker-compatible)
        String dockerPath = toDockerPath(projectPath);

        // Note: "snyk" prefix is required because the Docker entrypoint passes args directly.
        // snyk code test is not available in all images; failures are tolerated.
        String dependencyJson = runSnykCommand(dockerPath, "snyk", "test", "--json");
        String codeJson = runSnykCommand(dockerPath, "snyk", "code", "test", "--json");

        boolean success = dependencyJson != null || codeJson != null;
        String error = (!success) ? "Both snyk test and snyk code test failed" : null;

        log.info("Snyk scan complete. Dependency scan: {}, Code scan: {}",
                dependencyJson != null ? "OK" : "FAILED",
                codeJson != null ? "OK" : "FAILED");

        return new SnykResults(
                dependencyJson != null ? dependencyJson : "{\"error\":\"snyk test failed\"}",
                codeJson != null ? codeJson : "{\"error\":\"snyk code test failed\"}",
                success, error);
    }

    private String runSnykCommand(String dockerProjectPath, String... snykArgs) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("docker");
            cmd.add("run");
            cmd.add("--rm");
            cmd.add("-e"); cmd.add("SNYK_TOKEN=" + snykProperties.getToken());
            cmd.add("-v"); cmd.add(dockerProjectPath + ":/project");
            cmd.add("-w"); cmd.add("/project");
            cmd.add(snykProperties.getDockerImage());
            for (String arg : snykArgs) {
                cmd.add(arg);
            }

            log.debug("Running: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String stdout = new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .lines().collect(Collectors.joining("\n"));
            String stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()))
                    .lines().collect(Collectors.joining("\n"));

            // Snyk exits with non-zero when vulnerabilities are found (exit code 1) — that's OK
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            int exitCode = finished ? process.exitValue() : -1;

            if (!stderr.isBlank()) {
                log.debug("Snyk stderr: {}", stderr);
            }

            // Exit code 0 = no issues, 1 = issues found (both are valid JSON), 2+ = error
            if (exitCode <= 1 && !stdout.isBlank()) {
                return stdout;
            } else if (exitCode > 1) {
                log.warn("Snyk command {} failed with exit code {}: {}", String.join(" ", snykArgs), exitCode, stderr);
                return null;
            }
            return stdout.isBlank() ? null : stdout;

        } catch (Exception e) {
            log.error("Failed to run snyk command {}: {}", String.join(" ", snykArgs), e.getMessage());
            return null;
        }
    }

    /**
     * Converts a Windows path like C:\Users\... or C:/Users/... to Docker-compatible format.
     * On Linux/Mac this is a no-op.
     */
    private String toDockerPath(String path) {
        // Windows backslash: C:\Users\foo → /c/Users/foo
        if (path.matches("[A-Za-z]:\\\\.*")) {
            String drive = path.substring(0, 1).toLowerCase();
            String rest = path.substring(2).replace("\\", "/");
            return "/" + drive + rest;
        }
        // Windows forward-slash: C:/Users/foo → /c/Users/foo
        if (path.matches("[A-Za-z]:/.*")) {
            String drive = path.substring(0, 1).toLowerCase();
            String rest = path.substring(2);
            return "/" + drive + rest;
        }
        return path.replace("\\", "/");
    }
}
