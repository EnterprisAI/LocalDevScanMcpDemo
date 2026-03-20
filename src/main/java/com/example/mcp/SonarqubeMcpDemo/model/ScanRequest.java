package com.example.mcp.SonarqubeMcpDemo.model;

public class ScanRequest {
    private String projectPath;      // absolute path to the project on disk
    private String sonarProjectKey;  // SonarCloud project key
    private String branch;           // git branch being scanned
    private boolean runSonarScan;    // true = run mvn sonar:sonar first; false = use existing results
    private boolean runSnykScan;     // true = run snyk test via Docker

    public ScanRequest() {
        this.runSonarScan = true;
        this.runSnykScan = true;
    }

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
}
