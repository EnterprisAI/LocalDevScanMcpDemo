package com.example.mcp.SonarqubeMcpDemo.model;

import java.util.List;

public class ApplyFixesRequest {
    private String projectPath;
    private String sonarProjectKey;   // needed for rescan
    private String branch;            // needed for rescan
    private List<FixSuggestion> fixes;
    private boolean rescanAfterApply;  // re-run sonar+snyk after applying fixes

    public ApplyFixesRequest() {
        this.rescanAfterApply = false;
    }

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public String getSonarProjectKey() { return sonarProjectKey; }
    public void setSonarProjectKey(String sonarProjectKey) { this.sonarProjectKey = sonarProjectKey; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public List<FixSuggestion> getFixes() { return fixes; }
    public void setFixes(List<FixSuggestion> fixes) { this.fixes = fixes; }
    public boolean isRescanAfterApply() { return rescanAfterApply; }
    public void setRescanAfterApply(boolean rescanAfterApply) { this.rescanAfterApply = rescanAfterApply; }
}
