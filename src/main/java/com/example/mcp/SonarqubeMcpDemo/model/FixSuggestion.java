package com.example.mcp.SonarqubeMcpDemo.model;

public class FixSuggestion {
    private String file;           // relative path from project root
    private int startLine;
    private int endLine;
    private String issue;
    private String severity;       // CRITICAL, HIGH, MAJOR, MINOR, INFO
    private String source;         // "sonarqube" or "snyk"
    private String ruleId;
    private String originalCode;
    private String suggestedCode;
    private String explanation;
    private boolean applied;

    public FixSuggestion() {}

    // getters and setters
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }
    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }
    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }
    public String getIssue() { return issue; }
    public void setIssue(String issue) { this.issue = issue; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getOriginalCode() { return originalCode; }
    public void setOriginalCode(String originalCode) { this.originalCode = originalCode; }
    public String getSuggestedCode() { return suggestedCode; }
    public void setSuggestedCode(String suggestedCode) { this.suggestedCode = suggestedCode; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }
}
