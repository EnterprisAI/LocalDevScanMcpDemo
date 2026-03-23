package com.example.mcp.SonarqubeMcpDemo.model;

import com.fasterxml.jackson.annotation.JsonSetter;

public class ScanRequest {
    private String projectPath;
    private String sonarProjectKey;
    private String branch;
    private boolean runSonarScan;
    private boolean runSnykScan;

    // Sentinels — true when the field was explicitly set in the JSON body
    private boolean runSonarScanExplicitlySet = false;
    private boolean runSnykScanExplicitlySet  = false;

    public ScanRequest() {
        // No constructor defaults — YAML defaults are applied by LocalScanController
    }

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public String getSonarProjectKey() { return sonarProjectKey; }
    public void setSonarProjectKey(String sonarProjectKey) { this.sonarProjectKey = sonarProjectKey; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public boolean isRunSonarScan() { return runSonarScan; }

    @JsonSetter("runSonarScan")
    public void setRunSonarScan(boolean runSonarScan) {
        this.runSonarScan = runSonarScan;
        this.runSonarScanExplicitlySet = true;
    }

    public boolean isRunSnykScan() { return runSnykScan; }

    @JsonSetter("runSnykScan")
    public void setRunSnykScan(boolean runSnykScan) {
        this.runSnykScan = runSnykScan;
        this.runSnykScanExplicitlySet = true;
    }

    public boolean isRunSonarScanExplicitlySet() { return runSonarScanExplicitlySet; }
    public boolean isRunSnykScanExplicitlySet()  { return runSnykScanExplicitlySet; }
}
