@echo off
REM Script to run the StorageScheduler tests for Serengeti
REM This script provides options to run all StorageScheduler tests, only fast tests,
REM or only comprehensive tests.

REM Default values
set RUN_ALL=false
set RUN_FAST=false
set RUN_COMPREHENSIVE=false
set SHOW_HELP=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto check_args
if /i "%~1"=="-a" set RUN_ALL=true & shift & goto parse_args
if /i "%~1"=="--all" set RUN_ALL=true & shift & goto parse_args
if /i "%~1"=="-f" set RUN_FAST=true & shift & goto parse_args
if /i "%~1"=="--fast" set RUN_FAST=true & shift & goto parse_args
if /i "%~1"=="-c" set RUN_COMPREHENSIVE=true & shift & goto parse_args
if /i "%~1"=="--comprehensive" set RUN_COMPREHENSIVE=true & shift & goto parse_args
if /i "%~1"=="-h" set SHOW_HELP=true & shift & goto parse_args
if /i "%~1"=="--help" set SHOW_HELP=true & shift & goto parse_args
echo Unknown option: %~1
set SHOW_HELP=true
shift
goto parse_args

:check_args
REM Show help if requested or no options provided
if "%SHOW_HELP%"=="true" goto show_help
if "%RUN_ALL%"=="false" if "%RUN_FAST%"=="false" if "%RUN_COMPREHENSIVE%"=="false" goto show_help
goto run_tests

:show_help
echo Usage: run_storage_scheduler_tests.bat [OPTIONS]
echo.
echo Options:
echo   -a, --all            Run all StorageScheduler tests
echo   -f, --fast           Run only fast StorageScheduler tests
echo   -c, --comprehensive  Run only comprehensive StorageScheduler tests
echo   -h, --help           Show this help message
echo.
echo Examples:
echo   run_storage_scheduler_tests.bat --all
echo   run_storage_scheduler_tests.bat --fast
echo   run_storage_scheduler_tests.bat --comprehensive
exit /b 0

:run_tests
REM Record start time
set start_time=%time%

REM Run tests based on options
if "%RUN_ALL%"=="true" goto run_all
if "%RUN_COMPREHENSIVE%"=="true" goto run_comprehensive
if "%RUN_FAST%"=="true" goto run_fast
goto end

:run_all
call :display_header "All StorageScheduler Tests"
set overall_status=0

REM Run comprehensive tests
call :display_header "StorageScheduler Comprehensive Tests"
call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest
set comprehensive_status=%ERRORLEVEL%
call :display_summary %comprehensive_status%

REM Update overall status
if %comprehensive_status% NEQ 0 set overall_status=%comprehensive_status%

REM Run fast tests
call :display_header "StorageScheduler Fast Tests"
call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
set fast_status=%ERRORLEVEL%
call :display_summary %fast_status%

REM Update overall status
if %fast_status% NEQ 0 set overall_status=%fast_status%

REM Display overall summary
echo ========================================================
echo   Overall Test Summary
echo ========================================================
call :calculate_execution_time "%start_time%" "%time%"
echo Total execution time: %execution_time% seconds
if %overall_status% EQU 0 (
    echo Overall Status: SUCCESS ✓
) else (
    echo Overall Status: FAILED ✗
)
echo ========================================================

exit /b %overall_status%

:run_comprehensive
call :display_header "StorageScheduler Comprehensive Tests"
call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest
set comprehensive_status=%ERRORLEVEL%
call :display_summary %comprehensive_status%
exit /b %comprehensive_status%

:run_fast
call :display_header "StorageScheduler Fast Tests"
call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
set fast_status=%ERRORLEVEL%
call :display_summary %fast_status%
exit /b %fast_status%

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

:end
exit /b %ERRORLEVEL%