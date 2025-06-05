@echo off
REM Script to run mutation tests for the StorageScheduler component
REM This script provides options to run mutation tests with different configurations

REM Default values
set COMPONENT=storage-scheduler
set REPORT_FORMAT=HTML
set SHOW_HELP=false
set OPEN_REPORT=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto check_args
if /i "%~1"=="-c" set COMPONENT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--component" set COMPONENT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-f" set REPORT_FORMAT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--format" set REPORT_FORMAT=%~2 & shift & shift & goto parse_args
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
goto run_tests

:show_help
echo Usage: run_mutation_tests.bat [OPTIONS]
echo.
echo Options:
echo   -c, --component COMPONENT  Component to test (default: storage-scheduler)
echo                             Available components: storage-scheduler, all
echo   -f, --format FORMAT        Report format: HTML, XML (default: HTML)
echo   -o, --open                 Open the HTML report after generation
echo   -h, --help                 Show this help message
echo.
echo Examples:
echo   run_mutation_tests.bat
echo   run_mutation_tests.bat --component storage-scheduler
echo   run_mutation_tests.bat --format XML
echo   run_mutation_tests.bat --open
exit /b 0

:run_tests
REM Record start time
set start_time=%time%

REM Run mutation tests based on component
if "%COMPONENT%"=="storage-scheduler" goto run_storage_scheduler
if "%COMPONENT%"=="all" goto run_all
echo Unknown component: %COMPONENT%
echo Available components: storage-scheduler, all
exit /b 1

:run_storage_scheduler
call :display_header "StorageScheduler Mutation Tests"

REM Run mutation tests for StorageScheduler
call mvn test -Pstorage-scheduler-mutation

REM Store the exit code
set mutation_status=%ERRORLEVEL%

REM Display summary
call :display_summary %mutation_status%

REM Set overall status
set overall_status=%mutation_status%
goto report_location

:run_all
call :display_header "All Components Mutation Tests"

REM Run mutation tests for all components
call mvn test -Pmutation

REM Store the exit code
set mutation_status=%ERRORLEVEL%

REM Display summary
call :display_summary %mutation_status%

REM Set overall status
set overall_status=%mutation_status%
goto report_location

:report_location
REM Display report location
echo Mutation test report generated at: target\pit-reports\YYYYMMDDHHMI\index.html
echo You can open this file in a browser to view the detailed mutation test report.
echo.

REM Open the report if requested
if "%OPEN_REPORT%"=="true" if "%REPORT_FORMAT%"=="HTML" (
    echo Opening mutation test report...
    
    REM Find the latest report directory
    for /f "delims=" %%i in ('dir /b /od /a:d target\pit-reports\2*') do set LATEST_DIR=%%i
    
    if exist "target\pit-reports\%LATEST_DIR%\index.html" (
        start "" "target\pit-reports\%LATEST_DIR%\index.html"
    ) else (
        echo Could not find the mutation test report.
    )
)

exit /b %overall_status%

:display_header
echo ========================================================
echo   Running %~1
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