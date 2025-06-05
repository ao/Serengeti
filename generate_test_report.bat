@echo off
setlocal enabledelayedexpansion

REM Script to generate comprehensive test reports for the StorageScheduler component
REM This script aggregates results from different test types and generates a unified report

REM Default values
set FORMAT=html
set OUTPUT_DIR=target\reports\storage-scheduler
set PUBLISH_DIR=
set DETAIL_LEVEL=standard
set OPEN_REPORT=false
set SHOW_HELP=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto :end_parse_args
if /i "%~1"=="-f" (
    set FORMAT=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--format" (
    set FORMAT=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="-o" (
    set OUTPUT_DIR=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--output-dir" (
    set OUTPUT_DIR=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="-p" (
    set PUBLISH_DIR=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--publish" (
    set PUBLISH_DIR=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="-d" (
    set DETAIL_LEVEL=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--detail" (
    set DETAIL_LEVEL=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--open" (
    set OPEN_REPORT=true
    shift
    goto :parse_args
)
if /i "%~1"=="-h" (
    set SHOW_HELP=true
    shift
    goto :parse_args
)
if /i "%~1"=="--help" (
    set SHOW_HELP=true
    shift
    goto :parse_args
)
echo Unknown option: %~1
set SHOW_HELP=true
shift
goto :parse_args
:end_parse_args

REM Show help if requested
if "%SHOW_HELP%"=="true" (
    echo Usage: generate_test_report.bat [OPTIONS]
    echo.
    echo Options:
    echo   -f, --format FORMAT      Report format: html, xml, json (default: html)
    echo   -o, --output-dir DIR     Directory to store the report (default: target\reports\storage-scheduler)
    echo   -p, --publish DIR        Directory to publish the report to (optional)
    echo   -d, --detail LEVEL       Detail level: minimal, standard, full (default: standard)
    echo       --open               Open the report after generation (HTML format only)
    echo   -h, --help               Show this help message
    echo.
    echo Examples:
    echo   generate_test_report.bat
    echo   generate_test_report.bat --format xml
    echo   generate_test_report.bat --detail full --open
    echo   generate_test_report.bat --publish C:\inetpub\wwwroot\reports
    exit /b 0
)

REM Function to display header
:display_header
echo ========================================================
echo   StorageScheduler Test Report Generator
echo ========================================================
echo Started at: %date% %time%
echo Format: %FORMAT%
echo Detail Level: %DETAIL_LEVEL%
echo Output Directory: %OUTPUT_DIR%
if not "%PUBLISH_DIR%"=="" (
    echo Publish Directory: %PUBLISH_DIR%
)
echo --------------------------------------------------------
goto :eof

REM Function to display summary
:display_summary
echo --------------------------------------------------------
echo Finished at: %date% %time%
echo Execution time: %1 seconds
if "%2"=="0" (
    echo Status: SUCCESS ✅
) else (
    echo Status: FAILED ❌
)
echo ========================================================
echo.
goto :eof

REM Record start time
set START_TIME=%time%

REM Display header
call :display_header

REM Create output directory if it doesn't exist
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

REM Set report file path based on format
for /f "tokens=2-4 delims=/ " %%a in ('date /t') do (set DATE=%%c%%a%%b)
for /f "tokens=1-2 delims=: " %%a in ('time /t') do (set TIME=%%a%%b)
set TIMESTAMP=%DATE%%TIME%
set REPORT_FILE=%OUTPUT_DIR%\storage-scheduler-report-%TIMESTAMP%.%FORMAT%

echo Collecting test results...

REM Run the report generator
call mvn exec:java -Dexec.mainClass="com.ataiva.serengeti.report.StorageSchedulerReportGenerator" ^
    -Dexec.args="--format %FORMAT% --output %REPORT_FILE% --detail %DETAIL_LEVEL%"

REM Store the exit code
set STATUS=%ERRORLEVEL%

REM Calculate execution time
for /f "tokens=1-4 delims=:.," %%a in ("%START_TIME%") do (
    set /a "START_SECONDS=(((%%a*60)+1%%b %% 100)*60+1%%c %% 100)*100+1%%d %% 100"
)
for /f "tokens=1-4 delims=:.," %%a in ("%time%") do (
    set /a "END_SECONDS=(((%%a*60)+1%%b %% 100)*60+1%%c %% 100)*100+1%%d %% 100"
)
set /a ELAPSED_SECONDS=(END_SECONDS-START_SECONDS)/100

REM Display summary
call :display_summary %ELAPSED_SECONDS% %STATUS%

REM Publish the report if requested
if %STATUS%==0 (
    if not "%PUBLISH_DIR%"=="" (
        echo Publishing report to %PUBLISH_DIR%...
        if not exist "%PUBLISH_DIR%" mkdir "%PUBLISH_DIR%"
        
        REM Copy the report file
        copy "%REPORT_FILE%" "%PUBLISH_DIR%\"
        
        REM Copy additional resources for HTML reports
        if "%FORMAT%"=="html" (
            if exist "%OUTPUT_DIR%\resources" (
                xcopy /E /I /Y "%OUTPUT_DIR%\resources" "%PUBLISH_DIR%\resources" > nul
            )
        )
        
        echo Report published successfully.
    )
)

REM Open the report if requested
if %STATUS%==0 (
    if "%OPEN_REPORT%"=="true" (
        if "%FORMAT%"=="html" (
            echo Opening report...
            start "" "%REPORT_FILE%"
        )
    )
)

echo Report generated at: %REPORT_FILE%

REM Exit with the appropriate status code
exit /b %STATUS%