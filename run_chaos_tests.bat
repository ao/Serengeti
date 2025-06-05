@echo off
REM Script to run chaos tests for the StorageScheduler component
REM This script provides options to focus on specific chaos scenarios
REM and to set different levels of chaos intensity.

REM Default values
set COMPONENT=storage-scheduler
set SCENARIO=all
set INTENSITY=medium
set SHOW_HELP=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto check_args
if /i "%~1"=="-c" set COMPONENT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--component" set COMPONENT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-s" set SCENARIO=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--scenario" set SCENARIO=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-i" set INTENSITY=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--intensity" set INTENSITY=%~2 & shift & shift & goto parse_args
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
echo Usage: run_chaos_tests.bat [OPTIONS]
echo.
echo Options:
echo   -c, --component COMPONENT  Component to test (default: storage-scheduler)
echo                              Available components: storage-scheduler, all
echo   -s, --scenario SCENARIO    Chaos scenario to test (default: all)
echo                              Available scenarios: disk-failure, network-outage,
echo                              resource-constraint, thread-interruption,
echo                              unexpected-exception, combined, all
echo   -i, --intensity INTENSITY  Chaos intensity level (default: medium)
echo                              Available levels: low, medium, high
echo   -h, --help                 Show this help message
echo.
echo Examples:
echo   run_chaos_tests.bat
echo   run_chaos_tests.bat --component storage-scheduler
echo   run_chaos_tests.bat --scenario disk-failure
echo   run_chaos_tests.bat --intensity high
echo   run_chaos_tests.bat --component storage-scheduler --scenario network-outage --intensity low
exit /b 0

:run_tests
REM Record start time
set start_time=%time%

REM Display header
call :display_header "Chaos Tests for %COMPONENT% (Scenario: %SCENARIO%, Intensity: %INTENSITY%)"

REM Set Maven system properties based on parameters
set MAVEN_PROPS=-Dchaos.component=%COMPONENT% -Dchaos.scenario=%SCENARIO% -Dchaos.intensity=%INTENSITY%

REM Run the appropriate tests based on component
if "%COMPONENT%"=="storage-scheduler" goto run_storage_scheduler_chaos
if "%COMPONENT%"=="all" goto run_storage_scheduler_chaos
echo Error: Unknown component '%COMPONENT%'
echo Available components: storage-scheduler, all
exit /b 1

:run_storage_scheduler_chaos
REM Run StorageScheduler chaos tests
call mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing %MAVEN_PROPS%
set status=%ERRORLEVEL%

REM Display summary
call :display_summary %status%

REM Exit with the appropriate status code
exit /b %status%

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