#!/bin/bash
# =============================================================================
# Pre-push hook: Local Code Quality Gate
# =============================================================================
# Install: bash hooks/install-hooks.sh /path/to/your/repo
#
# This hook runs before every `git push`. It:
#   1. Calls the LocalDevScanMcpDemo server to scan this project
#   2. Displays LLM-generated fix suggestions
#   3. Lets you review each fix individually, apply all, or push anyway
#   4. Blocks the push so you can review applied changes before re-pushing
# =============================================================================

MCP_SERVER="${MCP_SERVER_URL:-http://localhost:8080}"
PROJECT_PATH="$(pwd)"

# Convert Git Bash Unix-style path (/c/temp/...) to Windows path (C:/temp/...)
# so that the Java server can open files with Paths.get(projectPath, ...)
if [[ "$PROJECT_PATH" =~ ^/([a-zA-Z])/(.*) ]]; then
    DRIVE="${BASH_REMATCH[1]}"
    REST="${BASH_REMATCH[2]}"
    PROJECT_PATH="${DRIVE^^}:/${REST}"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-$(basename "$PROJECT_PATH")}"
RESULTS_FILE="/tmp/scan-results-$$.json"
APPLY_FILE="/tmp/apply-request-$$.json"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

cleanup() { rm -f "$RESULTS_FILE" "$APPLY_FILE" /tmp/apply-result-$$.json; }
trap cleanup EXIT

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
if ! curl -s -o /dev/null --connect-timeout 3 "${MCP_SERVER}/scan-local" 2>/dev/null; then
    echo -e "${YELLOW}⚠  LocalDevScanMcpDemo server is not running at ${MCP_SERVER}${NC}"
    echo -e "   Start it with: ./run.sh"
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
        \"runSonarScan\": false,
        \"runSnykScan\": true
    }")

if [ "$HTTP_STATUS" != "200" ]; then
    echo -e "${YELLOW}⚠  Scan returned HTTP ${HTTP_STATUS}. Allowing push.${NC}"
    cat "$RESULTS_FILE"
    exit 0
fi

# ── Parse results ─────────────────────────────────────────────────────────────
TOTAL=$(grep -o '"totalIssues":[0-9]*' "$RESULTS_FILE" | cut -d: -f2)
TOTAL="${TOTAL:-0}"

if [ "$TOTAL" -eq "0" ]; then
    echo -e "${GREEN}✅ No issues found! Push proceeding.${NC}"
    exit 0
fi

# ── Display summary ───────────────────────────────────────────────────────────
echo -e "${RED}${BOLD}⚠  Found ${TOTAL} issue(s) in your code:${NC}"
echo ""

if command -v node &>/dev/null; then
    node -e "
const data = require('$RESULTS_FILE');
data.fixes.forEach((f, i) => {
    const icon = f.severity === 'CRITICAL' ? '🔴' :
                 f.severity === 'HIGH'     ? '🔴' :
                 f.severity === 'MAJOR'    ? '🟠' :
                 f.severity === 'MINOR'    ? '🟡' : '🔵';
    const src = f.source ? '[' + f.source.toUpperCase() + ']' : '';
    console.log('  ' + icon + ' #' + (i+1) + ' [' + f.severity + '] ' + src + ' ' + f.file + ':' + f.startLine);
    console.log('     ' + f.issue);
    console.log('');
});
" 2>/dev/null
else
    grep -o '"issue":"[^"]*"' "$RESULTS_FILE" | sed 's/"issue":"//;s/"//' | nl | head -20
fi

# ── Main menu ─────────────────────────────────────────────────────────────────
echo -e "${YELLOW}${BOLD}What would you like to do?${NC}"
echo "  [r] Review each fix one by one  (accept / skip / edit per fix)"
echo "  [a] Apply all fixes automatically"
echo "  [s] Show full JSON report"
echo "  [p] Push anyway (ignore issues)"
echo "  [x] Cancel push"
echo ""
printf "Choice [r/a/s/p/x]: "
read -r choice </dev/tty

# ── Helper: print a coloured diff for one fix ─────────────────────────────────
show_diff() {
    local original="$1"
    local suggested="$2"
    echo -e "${RED}  - ${original}${NC}"
    echo -e "${GREEN}  + ${suggested}${NC}"
}

# ── Helper: call /apply-fixes with a list of fixes ───────────────────────────
do_apply() {
    local fixes_json="$1"
    local apply_body
    apply_body=$(printf '{"projectPath":"%s","sonarProjectKey":"%s","branch":"%s","rescanAfterApply":false,"fixes":%s}' \
        "$PROJECT_PATH" "$SONAR_PROJECT_KEY" "$BRANCH" "$fixes_json")

    local status
    status=$(curl -s -o /tmp/apply-result-$$.json -w "%{http_code}" \
        -X POST "${MCP_SERVER}/apply-fixes" \
        -H "Content-Type: application/json" \
        --max-time 60 \
        -d "$apply_body")

    if [ "$status" = "200" ]; then
        local applied failed
        applied=$(grep -o '"appliedCount":[0-9]*' /tmp/apply-result-$$.json | cut -d: -f2)
        failed=$(grep -o '"failedCount":[0-9]*' /tmp/apply-result-$$.json | cut -d: -f2)
        echo -e "${GREEN}✅ Applied: ${applied:-0} fix(es)${NC}"
        [ "${failed:-0}" -gt "0" ] && \
            echo -e "${YELLOW}⚠  Could not apply: ${failed} fix(es) — original code not found (may already be fixed)${NC}"
    else
        echo -e "${RED}❌ Apply fixes failed (HTTP ${status})${NC}"
    fi
}

case "$choice" in

    # ── Per-fix review ────────────────────────────────────────────────────────
    r|R)
        if ! command -v node &>/dev/null; then
            echo -e "${YELLOW}⚠  Per-fix review requires node. Falling back to apply-all prompt.${NC}"
            printf "Apply all fixes? [y/N]: "
            read -r confirm </dev/tty
            if [ "$confirm" = "y" ] || [ "$confirm" = "Y" ]; then
                ALL_FIXES=$(node -e "process.stdout.write(JSON.stringify(require('$RESULTS_FILE').fixes))" 2>/dev/null \
                    || grep -o '"fixes":\[.*\]' "$RESULTS_FILE" | sed 's/"fixes"://')
                do_apply "$ALL_FIXES"
            fi
            echo -e "${YELLOW}Review applied changes and run git push again.${NC}"
            exit 1
        fi

        echo ""
        echo -e "${CYAN}${BOLD}── Per-fix Review Mode ────────────────────────────────────${NC}"
        echo -e "  For each fix: ${GREEN}[y]${NC} apply  ${RED}[n]${NC} skip  ${YELLOW}[e]${NC} open in editor  ${BOLD}[a]${NC} apply all remaining  ${BOLD}[x]${NC} stop reviewing"
        echo ""

        # Extract fixes count
        FIX_COUNT=$(node -e "console.log(require('$RESULTS_FILE').fixes.length)" 2>/dev/null)
        SELECTED_FIXES="[]"
        APPLY_ALL_REMAINING=0

        for i in $(seq 0 $((FIX_COUNT - 1))); do
            FIX=$(node -e "
const d = require('$RESULTS_FILE');
const f = d.fixes[$i];
console.log(JSON.stringify(f));
" 2>/dev/null)

            FILE=$(echo "$FIX" | node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).file||'')" 2>/dev/null)
            LINE=$(echo "$FIX" | node -e "process.stdout.write(String(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).startLine||''))" 2>/dev/null)
            SEV=$(echo  "$FIX" | node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).severity||'')" 2>/dev/null)
            SRC=$(echo  "$FIX" | node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).source||'')" 2>/dev/null)
            ISSUE=$(echo "$FIX" | node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).issue||'')" 2>/dev/null)
            ORIG=$(echo  "$FIX" | node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).originalCode||'')" 2>/dev/null)
            SUGG=$(echo  "$FIX" | node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).suggestedCode||'')" 2>/dev/null)
            EXPL=$(echo  "$FIX" | node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('/dev/stdin','utf8')).explanation||'')" 2>/dev/null)

            echo -e "${BOLD}── Fix $((i+1)) / ${FIX_COUNT} ──────────────────────────────────────────${NC}"
            echo -e "  ${BOLD}File    :${NC} ${FILE}:${LINE}"
            echo -e "  ${BOLD}Severity:${NC} ${SEV}  [${SRC}]"
            echo -e "  ${BOLD}Issue   :${NC} ${ISSUE}"
            echo -e "  ${BOLD}Why     :${NC} ${EXPL}"
            echo ""
            echo -e "  ${BOLD}Change:${NC}"
            show_diff "$ORIG" "$SUGG"
            echo ""

            if [ "$APPLY_ALL_REMAINING" -eq 1 ]; then
                echo -e "  ${GREEN}→ Auto-applying (apply-all-remaining selected)${NC}"
                SELECTED_FIXES=$(node -e "
const sel = $SELECTED_FIXES;
const fix = $FIX;
sel.push(fix);
process.stdout.write(JSON.stringify(sel));
" 2>/dev/null)
                continue
            fi

            printf "  Apply this fix? [y/n/e/a/x]: "
            read -r fix_choice </dev/tty

            case "$fix_choice" in
                y|Y)
                    echo -e "  ${GREEN}✔ Accepted${NC}"
                    SELECTED_FIXES=$(node -e "
const sel = $SELECTED_FIXES;
const fix = $FIX;
sel.push(fix);
process.stdout.write(JSON.stringify(sel));
" 2>/dev/null)
                    ;;
                e|E)
                    # Write suggestedCode to a temp file for editing
                    TMP_EDIT="/tmp/fix-edit-$$.txt"
                    echo "$SUGG" > "$TMP_EDIT"
                    EDITOR_CMD="${VISUAL:-${EDITOR:-vi}}"
                    echo -e "  ${YELLOW}Opening in ${EDITOR_CMD}... (save and close to continue)${NC}"
                    "$EDITOR_CMD" "$TMP_EDIT" </dev/tty
                    EDITED=$(cat "$TMP_EDIT")
                    rm -f "$TMP_EDIT"
                    echo -e "  ${GREEN}✔ Accepted with your edits${NC}"
                    # Replace suggestedCode in the fix with the edited version
                    SELECTED_FIXES=$(node -e "
const sel = $SELECTED_FIXES;
const fix = $FIX;
fix.suggestedCode = $(node -e "process.stdout.write(JSON.stringify('$EDITED')" 2>/dev/null || echo "\"$EDITED\"");
sel.push(fix);
process.stdout.write(JSON.stringify(sel));
" 2>/dev/null)
                    ;;
                a|A)
                    echo -e "  ${GREEN}✔ Accepted — applying all remaining fixes too${NC}"
                    APPLY_ALL_REMAINING=1
                    SELECTED_FIXES=$(node -e "
const sel = $SELECTED_FIXES;
const fix = $FIX;
sel.push(fix);
process.stdout.write(JSON.stringify(sel));
" 2>/dev/null)
                    ;;
                x|X)
                    echo -e "  ${YELLOW}Stopped reviewing. Applying selected fixes so far.${NC}"
                    break
                    ;;
                *)
                    echo -e "  ${YELLOW}↷ Skipped${NC}"
                    ;;
            esac
            echo ""
        done

        # Apply selected fixes
        SEL_COUNT=$(node -e "console.log($SELECTED_FIXES.length)" 2>/dev/null || echo 0)
        if [ "${SEL_COUNT:-0}" -gt 0 ]; then
            echo -e "🔧 Applying ${SEL_COUNT} selected fix(es)..."
            do_apply "$SELECTED_FIXES"
            echo ""
            echo -e "${YELLOW}Review the applied changes, then run git push again.${NC}"
        else
            echo -e "${YELLOW}No fixes selected. Push cancelled — fix issues manually or use [p] to push anyway.${NC}"
        fi
        exit 1
        ;;

    # ── Apply all ─────────────────────────────────────────────────────────────
    a|A)
        echo ""
        echo -e "🔧 Applying all ${TOTAL} fix(es)..."
        ALL_FIXES=$(node -e "process.stdout.write(JSON.stringify(require('$RESULTS_FILE').fixes))" 2>/dev/null \
            || grep -o '"fixes":\[.*\]' "$RESULTS_FILE" | sed 's/"fixes"://')
        do_apply "$ALL_FIXES"
        echo ""
        echo -e "${YELLOW}Review the applied changes, then run git push again.${NC}"
        exit 1
        ;;

    # ── Show full JSON ────────────────────────────────────────────────────────
    s|S)
        cat "$RESULTS_FILE"
        echo ""
        printf "Push anyway? [y/N]: "
        read -r confirm </dev/tty
        [ "$confirm" = "y" ] || [ "$confirm" = "Y" ] && exit 0
        exit 1
        ;;

    # ── Push anyway ───────────────────────────────────────────────────────────
    p|P)
        echo -e "${YELLOW}⚠  Pushing with known issues.${NC}"
        exit 0
        ;;

    # ── Cancel ────────────────────────────────────────────────────────────────
    *)
        echo -e "${RED}Push cancelled. Fix issues and push again.${NC}"
        exit 1
        ;;
esac
