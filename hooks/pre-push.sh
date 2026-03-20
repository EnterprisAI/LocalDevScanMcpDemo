#!/bin/bash
# =============================================================================
# Pre-push hook: Local Code Quality Gate
# =============================================================================
# Install: cp hooks/pre-push.sh .git/hooks/pre-push && chmod +x .git/hooks/pre-push
#
# This hook runs before every `git push`. It:
#   1. Calls the LocalDevScanMcpDemo server to scan this project
#   2. Displays LLM-generated fix suggestions
#   3. Optionally applies all fixes automatically
#   4. Blocks the push if issues are found (unless --no-verify is used)
# =============================================================================

MCP_SERVER="${MCP_SERVER_URL:-http://localhost:8080}"
PROJECT_PATH="$(pwd)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-$(basename "$PROJECT_PATH")}"
RESULTS_FILE="/tmp/scan-results-$$.json"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'

echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║       Local Code Quality Gate (SonarQube + Snyk)        ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
echo -e "  Project : ${PROJECT_PATH}"
echo -e "  Branch  : ${BRANCH}"
echo -e "  Sonar   : ${SONAR_PROJECT_KEY}"
echo -e "  Server  : ${MCP_SERVER}"
echo ""

# ── Check server is running ───────────────────────────────────────────────────
if ! curl -s -o /dev/null --connect-timeout 3 "${MCP_SERVER}/webhook/github"; then
    echo -e "${YELLOW}⚠  LocalDevScanMcpDemo server is not running at ${MCP_SERVER}${NC}"
    echo -e "   Start it with: java -jar LocalDevScanMcpDemo-0.0.1-SNAPSHOT.jar"
    echo -e "   Skipping scan and allowing push."
    echo ""
    exit 0
fi

# ── Run scan ──────────────────────────────────────────────────────────────────
echo -e "🔍 Running SonarCloud + Snyk scan (this may take 2-4 minutes)..."
echo ""

HTTP_STATUS=$(curl -s -o "$RESULTS_FILE" -w "%{http_code}" \
    -X POST "${MCP_SERVER}/scan-local" \
    -H "Content-Type: application/json" \
    --max-time 300 \
    -d "{
        \"projectPath\": \"${PROJECT_PATH}\",
        \"sonarProjectKey\": \"${SONAR_PROJECT_KEY}\",
        \"branch\": \"${BRANCH}\",
        \"runSonarScan\": true,
        \"runSnykScan\": true
    }")

if [ "$HTTP_STATUS" != "200" ]; then
    echo -e "${YELLOW}⚠  Scan returned HTTP ${HTTP_STATUS}. Allowing push.${NC}"
    cat "$RESULTS_FILE"
    rm -f "$RESULTS_FILE"
    exit 0
fi

# ── Parse results ─────────────────────────────────────────────────────────────
TOTAL=$(cat "$RESULTS_FILE" | grep -o '"totalIssues":[0-9]*' | cut -d: -f2)
TOTAL="${TOTAL:-0}"

if [ "$TOTAL" -eq "0" ]; then
    echo -e "${GREEN}✅ No issues found! Push proceeding.${NC}"
    rm -f "$RESULTS_FILE"
    exit 0
fi

# ── Display issues ────────────────────────────────────────────────────────────
echo -e "${RED}⚠  Found ${TOTAL} issue(s) in your code:${NC}"
echo ""

# Pretty-print issues using node if available, otherwise raw JSON
if command -v node &>/dev/null; then
    node -e "
const data = require('$RESULTS_FILE');
data.fixes.forEach((f, i) => {
    const icon = f.severity === 'CRITICAL' || f.severity === 'HIGH' ? '🔴' :
                 f.severity === 'MAJOR' ? '🟠' : '🟡';
    console.log(\`  \${icon} [\${f.severity}] [\${f.source.toUpperCase()}] \${f.file}:\${f.startLine}\`);
    console.log(\`     Issue: \${f.issue}\`);
    console.log(\`     Fix  : \${f.explanation}\`);
    console.log('');
});
"
else
    cat "$RESULTS_FILE" | grep -o '"issue":"[^"]*"' | sed 's/"issue":"//;s/"//' | head -20
fi

# ── Prompt developer ──────────────────────────────────────────────────────────
echo ""
echo -e "${YELLOW}What would you like to do?${NC}"
echo "  [a] Apply all fixes automatically and re-push"
echo "  [s] Show full JSON report"
echo "  [p] Push anyway (ignore issues)"
echo "  [x] Cancel push (fix manually)"
echo ""
printf "Choice [a/s/p/x]: "
read -r choice </dev/tty

case "$choice" in
    a|A)
        echo ""
        echo -e "🔧 Applying fixes..."
        FIXES_JSON=$(cat "$RESULTS_FILE" | node -e "
const data = require('/dev/stdin');
const req = { projectPath: data.projectPath, fixes: data.fixes, rescanAfterApply: false };
process.stdout.write(JSON.stringify(req));
" 2>/dev/null || cat "$RESULTS_FILE")

        APPLY_STATUS=$(curl -s -o /tmp/apply-result-$$.json -w "%{http_code}" \
            -X POST "${MCP_SERVER}/apply-fixes" \
            -H "Content-Type: application/json" \
            --max-time 60 \
            -d "$FIXES_JSON")

        if [ "$APPLY_STATUS" = "200" ]; then
            APPLIED=$(cat /tmp/apply-result-$$.json | grep -o '"appliedCount":[0-9]*' | cut -d: -f2)
            FAILED=$(cat /tmp/apply-result-$$.json | grep -o '"failedCount":[0-9]*' | cut -d: -f2)
            echo -e "${GREEN}✅ Applied: ${APPLIED:-0} fix(es)${NC}"
            [ "${FAILED:-0}" -gt "0" ] && echo -e "${YELLOW}⚠  Could not apply: ${FAILED} fix(es) (manual fix required)${NC}"
            rm -f /tmp/apply-result-$$.json
        else
            echo -e "${RED}❌ Apply fixes failed (HTTP ${APPLY_STATUS})${NC}"
        fi
        rm -f "$RESULTS_FILE"
        echo ""
        echo -e "${YELLOW}Please review the applied changes, then run git push again.${NC}"
        exit 1  # Block this push — dev should review and re-push
        ;;
    s|S)
        cat "$RESULTS_FILE"
        echo ""
        printf "Push anyway? [y/N]: "
        read -r confirm </dev/tty
        [ "$confirm" = "y" ] || [ "$confirm" = "Y" ] && { rm -f "$RESULTS_FILE"; exit 0; }
        rm -f "$RESULTS_FILE"
        exit 1
        ;;
    p|P)
        echo -e "${YELLOW}⚠  Pushing with known issues.${NC}"
        rm -f "$RESULTS_FILE"
        exit 0
        ;;
    *)
        echo -e "${RED}Push cancelled. Fix issues and push again.${NC}"
        rm -f "$RESULTS_FILE"
        exit 1
        ;;
esac
