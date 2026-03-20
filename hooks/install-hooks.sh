#!/bin/bash
# Installs the pre-push hook into the current git repository.
# Run from the root of the project you want to protect (e.g. springboot-sonar-snyk-demo).
#
# Usage:
#   bash /path/to/LocalDevScanMcpDemo/hooks/install-hooks.sh
#
# Optional env vars:
#   MCP_SERVER_URL    — URL of the LocalDevScanMcpDemo server (default: http://localhost:8080)
#   SONAR_PROJECT_KEY — SonarCloud project key (default: basename of current dir)

HOOKS_DIR="$(git rev-parse --git-dir)/hooks"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ ! -d "$HOOKS_DIR" ]; then
    echo "❌ Not inside a git repository. Run this from your project root."
    exit 1
fi

# Write the pre-push hook with env vars baked in
cat > "$HOOKS_DIR/pre-push" << EOF
#!/bin/bash
export MCP_SERVER_URL="${MCP_SERVER_URL:-http://localhost:8080}"
export SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-}"
bash "${SCRIPT_DIR}/pre-push.sh" "\$@"
EOF

chmod +x "$HOOKS_DIR/pre-push"
echo "✅ pre-push hook installed at: $HOOKS_DIR/pre-push"
echo ""
echo "Configuration:"
echo "  MCP_SERVER_URL    = ${MCP_SERVER_URL:-http://localhost:8080}"
echo "  SONAR_PROJECT_KEY = ${SONAR_PROJECT_KEY:-(will use directory name)}"
echo ""
echo "To test: git push (or git push --no-verify to bypass)"
