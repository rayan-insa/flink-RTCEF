@echo off
REM RTCEF Observer Service - Simulation Script for Windows

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR:~0,-1%

echo ======================================================================
echo RTCEF Observer Service - Simulation Setup
echo ======================================================================

if "%1"=="" (
    goto :show_usage
)

if /i "%1"=="setup" (
    echo Checking Kafka availability...
    where kafka-topics.bat >nul 2>&1
    if errorlevel 1 (
        echo Kafka tools not found in PATH
        echo Please ensure Kafka is installed and KAFKA_HOME is in PATH
        exit /b 1
    )
    
    echo Creating Kafka topics...
    kafka-topics.bat --create ^
        --topic reports ^
        --partitions 1 ^
        --replication-factor 1 ^
        --bootstrap-server localhost:9092 ^
        --if-not-exists 2>nul
    
    kafka-topics.bat --create ^
        --topic instructions ^
        --partitions 1 ^
        --replication-factor 1 ^
        --bootstrap-server localhost:9092 ^
        --if-not-exists 2>nul
    
    echo Topics created
    goto :end
)

if /i "%1"=="build" (
    echo Building Observer service...
    cd /d "%PROJECT_ROOT%"
    call sbt observer/assembly
    echo Build completed
    goto :end
)

if /i "%1"=="example" (
    echo Running component examples...
    cd /d "%PROJECT_ROOT%"
    call sbt "observer/runMain observer.examples.ObserverExample"
    goto :end
)

if /i "%1"=="help" (
    goto :show_usage
)

echo Unknown command: %1
:show_usage
echo.
echo Usage: %~nx0 [COMMAND]
echo.
echo Commands:
echo   setup       - Check Kafka and create topics
echo   build       - Build the observer service
echo   example     - Run component examples
echo   help        - Show this help message
echo.
echo Examples:
echo   %~nx0 setup          # Verify Kafka and create topics
echo   %~nx0 build          # Build observer
echo   %~nx0 example        # Run component examples
echo.
echo To run the full Observer service:
echo   java -jar %PROJECT_ROOT%\observer\target\observer-0.6.0-SNAPSHOT.jar
echo.
echo To run with custom config:
echo   java -jar %PROJECT_ROOT%\observer\target\observer-0.6.0-SNAPSHOT.jar C:\path\to\observer.properties
echo.

:end
endlocal
