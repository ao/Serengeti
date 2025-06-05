@echo off
REM Script to generate code coverage reports for the StorageScheduler tests
REM This script provides options to generate reports in different formats (HTML, XML, CSV)
REM and to focus on specific test types (all, fast, comprehensive)

REM Default values
set FORMAT=html
set TEST_TYPE=all
set OPEN_REPORT=false
set SHOW_HELP=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto check_args
if /i "%~1"=="-f" set FORMAT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--format" set FORMAT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-t" set TEST_TYPE=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--test-type" set TEST_TYPE=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-o" set OPEN_REPORT=true & shift & goto parse_args
if /i "%~1"=="--open" set OPEN_REPORT=true & shift & goto parse_args
if /i "%~1"=="-h" set SHOW_HELP=true & shift & goto parse_args
if /i "%~1"=="--help" set SHOW_HELP=true & shift & goto parse_args
echo Unknown option: %~1
set SHOW_HELP=true
shift
goto parse_args

:check_args
REM Show help if requested
if "%SHOW_HELP%"=="true" goto show_help
goto run_report

:show_help
echo Usage: generate_coverage_report.bat [OPTIONS]
echo.
echo Options:
echo   -f, --format FORMAT    Report format: html, xml, csv (default: html)
echo   -t, --test-type TYPE   Test type: all, fast, comprehensive (default: all)
echo   -o, --open             Open the report after generation
echo   -h, --help             Show this help message
echo.
echo Examples:
echo   generate_coverage_report.bat
echo   generate_coverage_report.bat --format xml
echo   generate_coverage_report.bat --test-type fast --open
echo   generate_coverage_report.bat --format csv --test-type comprehensive
exit /b 0

:run_report
REM Record start time
set start_time=%time%

REM Display header
call :display_header "Generating StorageScheduler Coverage Report"

REM Set Maven goals based on format
if /i "%FORMAT%"=="html" (
    set REPORT_GOAL=jacoco:report
    set REPORT_PATH=target\site\jacoco\index.html
) else if /i "%FORMAT%"=="xml" (
    set REPORT_GOAL=jacoco:report
    set REPORT_PATH=target\site\jacoco\jacoco.xml
) else if /i "%FORMAT%"=="csv" (
    set REPORT_GOAL=jacoco:report
    set REPORT_PATH=target\site\jacoco\jacoco.csv
) else (
    echo Invalid format: %FORMAT%. Using HTML format.
    set REPORT_GOAL=jacoco:report
    set REPORT_PATH=target\site\jacoco\index.html
)

REM Set test class based on test type
if /i "%TEST_TYPE%"=="all" (
    set TEST_CLASSES=com.ataiva.serengeti.unit.storage.StorageSchedulerTest,com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
) else if /i "%TEST_TYPE%"=="fast" (
    set TEST_CLASSES=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
) else if /i "%TEST_TYPE%"=="comprehensive" (
    set TEST_CLASSES=com.ataiva.serengeti.unit.storage.StorageSchedulerTest
) else (
    echo Invalid test type: %TEST_TYPE%. Using all tests.
    set TEST_CLASSES=com.ataiva.serengeti.unit.storage.StorageSchedulerTest,com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
)

REM Run tests with coverage
echo Running StorageScheduler tests with coverage analysis...
call mvn clean test -Dtest=%TEST_CLASSES% jacoco:prepare-agent %REPORT_GOAL%

REM Store the exit code
set status=%ERRORLEVEL%

REM Display summary
call :display_summary %status%

REM Display report location
if %status% EQU 0 (
    echo Coverage report generated at: %REPORT_PATH%
    
    REM Display coverage summary
    echo.
    echo Coverage Summary:
    echo ----------------
    
    REM Open the report if requested
    if "%OPEN_REPORT%"=="true" if /i "%FORMAT%"=="html" (
        echo.
        echo Opening coverage report...
        start "" "%REPORT_PATH%"
    )
    
    echo.
    echo To view the detailed coverage report, open the following file in a browser:
    echo %REPORT_PATH%
)

REM Exit with the appropriate status code
exit /b %status%

:display_header
echo ========================================================
echo   %~1
echo ========================================================
echo Started at: %date% %time%
echo --------------------------------------------------------
exit /b 0

:display_summary
set status=%~1
call :calculate_execution_time "%start_time%" "%time%"
echo --------------------------------------------------------
echo Finished at: %date% %time%
echo Execution time: %execution_time% seconds
if %status% EQU 0 (
    echo Status: SUCCESS ✓
) else (
    echo Status: FAILED ✗
)
echo ========================================================
echo.
exit /b 0

:calculate_execution_time
REM Convert start time to seconds
for /f "tokens=1-4 delims=:,. " %%a in ("%~1") do (
    set /a start_h=%%a
    set /a start_m=%%b
    set /a start_s=%%c
    set /a start_ms=%%d
)
set /a start_time_s=(start_h*3600 + start_m*60 + start_s)

REM Convert end time to seconds
for /f "tokens=1-4 delims=:,. " %%a in ("%~2") do (
    set /a end_h=%%a
    set /a end_m=%%b
    set /a end_s=%%c
    set /a end_ms=%%d
)
set /a end_time_s=(end_h*3600 + end_m*60 + end_s)

REM Calculate time difference
set /a execution_time=end_time_s-start_time_s
if %execution_time% LSS 0 set /a execution_time+=86400
exit /b 0