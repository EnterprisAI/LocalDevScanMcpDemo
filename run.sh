#!/usr/bin/env bash
# Run LocalDevScanMcpDemo — all credentials are embedded in application.yaml
# Prerequisites: Java 17+, Docker Desktop running
set -e

cd "$(dirname "$0")"

echo "============================================"
echo "  LocalDevScanMcpDemo — Local Pre-PR Scanner"
echo "============================================"

# Check Java
if ! java -version 2>/dev/null; then
  echo "ERROR: Java not found. Install Java 17+."
  exit 1
fi

# Check Docker
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running. Start Docker Desktop first."
  exit 1
fi

echo ""
echo "Building..."
./gradlew bootJar -q

echo ""
echo "Starting server on http://localhost:8080 ..."
echo "(Ctrl+C to stop)"
echo ""
java -jar build/libs/LocalDevScanMcpDemo-0.0.1-SNAPSHOT.jar
