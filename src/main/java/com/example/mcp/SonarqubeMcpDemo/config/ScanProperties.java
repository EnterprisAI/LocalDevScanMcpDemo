package com.example.mcp.SonarqubeMcpDemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Default scan settings loaded from application.yaml under the {@code scan} key.
 *
 * <p>These act as defaults for every POST /scan-local and POST /apply-fixes request.
 * Any field supplied in the request body overrides the corresponding default here.
 *
 * <pre>
 * scan:
 *   project-path: C:/path/to/project
 *   sonar-project-key: org_project-key
 *   branch: main
 *   run-sonar-scan: false
 *   run-snyk-scan: true
 *   maven-executable: mvn          # or ./mvnw for wrapper
 *   maven-goals: verify sonar:sonar
 *   sonar-scan-timeout-minutes: 10
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "scan")
public class ScanProperties {

    /** Absolute path to the project to scan. */
    private String projectPath = "";

    /** SonarCloud project key (e.g. my-org_my-project). */
    private String sonarProjectKey = "";

    /** Git branch being scanned. */
    private String branch = "main";

    /** Whether to run mvn sonar:sonar before fetching issues. */
    private boolean runSonarScan = false;

    /** Whether to run Snyk via Docker. */
    private boolean runSnykScan = true;

    /** Maven executable — use mvn, ./mvnw, or full path. */
    private String mavenExecutable = "mvn";

    /** Maven goals passed to the sonar scan command. */
    private String mavenGoals = "verify sonar:sonar";

    /** Timeout in minutes for the mvn sonar:sonar process. */
    private int sonarScanTimeoutMinutes = 10;

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public String getSonarProjectKey() { return sonarProjectKey; }
    public void setSonarProjectKey(String sonarProjectKey) { this.sonarProjectKey = sonarProjectKey; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public boolean isRunSonarScan() { return runSonarScan; }
    public void setRunSonarScan(boolean runSonarScan) { this.runSonarScan = runSonarScan; }

    public boolean isRunSnykScan() { return runSnykScan; }
    public void setRunSnykScan(boolean runSnykScan) { this.runSnykScan = runSnykScan; }

    public String getMavenExecutable() { return mavenExecutable; }
    public void setMavenExecutable(String mavenExecutable) { this.mavenExecutable = mavenExecutable; }

    public String getMavenGoals() { return mavenGoals; }
    public void setMavenGoals(String mavenGoals) { this.mavenGoals = mavenGoals; }

    public int getSonarScanTimeoutMinutes() { return sonarScanTimeoutMinutes; }
    public void setSonarScanTimeoutMinutes(int sonarScanTimeoutMinutes) { this.sonarScanTimeoutMinutes = sonarScanTimeoutMinutes; }
}
