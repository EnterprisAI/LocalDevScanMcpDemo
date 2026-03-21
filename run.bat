@echo off
REM Run LocalDevScanMcpDemo on Windows — all credentials in application.yaml
REM Prerequisites: Java 17+, Docker Desktop running

echo ============================================
echo   LocalDevScanMcpDemo — Local Pre-PR Scanner
echo ============================================

java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java not found. Install Java 17+.
    exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not running. Start Docker Desktop first.
    exit /b 1
)

echo.
echo Building...
call gradlew.bat bootJar -q

echo.
echo Starting server on http://localhost:8080 ...
echo (Ctrl+C to stop)
echo.
java -jar build\libs\LocalDevScanMcpDemo-0.0.1-SNAPSHOT.jar
