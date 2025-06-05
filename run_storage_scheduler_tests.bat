@echo off
REM Script to run the StorageScheduler tests for Serengeti
REM This script provides options to run all StorageScheduler tests, only fast tests,
REM or only comprehensive tests.

REM Default values
set RUN_ALL=false
set RUN_FAST=false
set RUN_COMPREHENSIVE=false
set RUN_INTEGRATION=false
set RUN_PROPERTY=false
set RUN_BENCHMARK=false
set RUN_CHAOS=false
set WITH_COVERAGE=false
set WITH_MUTATION=false
set USE_CONTAINER=false
set PLATFORM=linux
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
if /i "%~1"=="-i" set RUN_INTEGRATION=true & shift & goto parse_args
if /i "%~1"=="--integration" set RUN_INTEGRATION=true & shift & goto parse_args
if /i "%~1"=="-p" set RUN_PROPERTY=true & shift & goto parse_args
if /i "%~1"=="--property" set RUN_PROPERTY=true & shift & goto parse_args
if /i "%~1"=="-b" set RUN_BENCHMARK=true & shift & goto parse_args
if /i "%~1"=="--benchmark" set RUN_BENCHMARK=true & shift & goto parse_args
if /i "%~1"=="-x" set RUN_CHAOS=true & shift & goto parse_args
if /i "%~1"=="--chaos" set RUN_CHAOS=true & shift & goto parse_args
if /i "%~1"=="--coverage" set WITH_COVERAGE=true & shift & goto parse_args
if /i "%~1"=="--mutation" set WITH_MUTATION=true & shift & goto parse_args
if /i "%~1"=="--container" set USE_CONTAINER=true & shift & goto parse_args
if /i "%~1"=="--platform" set PLATFORM=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-h" set SHOW_HELP=true & shift & goto parse_args
if /i "%~1"=="--help" set SHOW_HELP=true & shift & goto parse_args
echo Unknown option: %~1
set SHOW_HELP=true
shift
goto parse_args

:check_args
REM Show help if requested or no options provided
if "%SHOW_HELP%"=="true" goto show_help
if "%RUN_ALL%"=="false" if "%RUN_FAST%"=="false" if "%RUN_COMPREHENSIVE%"=="false" if "%RUN_INTEGRATION%"=="false" if "%RUN_PROPERTY%"=="false" if "%RUN_BENCHMARK%"=="false" if "%RUN_CHAOS%"=="false" goto show_help
goto run_tests

:show_help
echo Usage: run_storage_scheduler_tests.bat [OPTIONS]
echo.
echo Options:
echo   -a, --all            Run all StorageScheduler tests
echo   -f, --fast           Run only fast StorageScheduler tests
echo   -c, --comprehensive  Run only comprehensive StorageScheduler tests
echo   -i, --integration    Run only integration StorageScheduler tests
echo   -p, --property       Run only property-based StorageScheduler tests
echo   -b, --benchmark      Run only benchmark tests for StorageScheduler
echo   -x, --chaos          Run only chaos tests for StorageScheduler
echo   --coverage           Run tests with code coverage analysis
echo   --mutation           Run tests with mutation testing
echo   --container          Run tests in Docker container
echo   --platform PLATFORM  Platform to run tests on (linux, windows)
echo                        Only applicable with --container
echo   -h, --help           Show this help message
echo.
echo Examples:
echo   run_storage_scheduler_tests.bat --all
echo   run_storage_scheduler_tests.bat --fast
echo   run_storage_scheduler_tests.bat --comprehensive
echo   run_storage_scheduler_tests.bat --all --coverage
echo   run_storage_scheduler_tests.bat --fast --coverage
echo   run_storage_scheduler_tests.bat --all --mutation
echo   run_storage_scheduler_tests.bat --fast --mutation
echo   run_storage_scheduler_tests.bat --all --coverage --mutation
echo   run_storage_scheduler_tests.bat --all --container
echo   run_storage_scheduler_tests.bat --fast --container
echo   run_storage_scheduler_tests.bat --all --container --platform windows
exit /b 0

:run_tests
REM If using container, delegate to the containerized test script
if "%USE_CONTAINER%"=="true" (
    echo Running tests in Docker container...
    
    REM Determine test type
    set TEST_TYPE=all
    if "%RUN_FAST%"=="true" set TEST_TYPE=fast
    if "%RUN_COMPREHENSIVE%"=="true" set TEST_TYPE=unit
    if "%RUN_INTEGRATION%"=="true" set TEST_TYPE=integration
    if "%RUN_PROPERTY%"=="true" set TEST_TYPE=property
    if "%RUN_BENCHMARK%"=="true" set TEST_TYPE=benchmark
    if "%RUN_CHAOS%"=="true" set TEST_TYPE=chaos
    
    REM Build additional options
    set CONTAINER_OPTS=
    if "%WITH_COVERAGE%"=="true" set CONTAINER_OPTS=%CONTAINER_OPTS% --preserve-results
    if "%WITH_MUTATION%"=="true" set TEST_TYPE=mutation
    
    REM Run containerized tests
    call run_containerized_tests.bat --test-type %TEST_TYPE% --platform %PLATFORM% %CONTAINER_OPTS%
    
    REM Exit with the exit code from the containerized test script
    exit /b %ERRORLEVEL%
)

REM Record start time
set START_TIME=%time%
echo Usage: run_storage_scheduler_tests.bat [OPTIONS]
echo.
echo Options:
echo   -a, --all            Run all StorageScheduler tests
echo   -f, --fast           Run only fast StorageScheduler tests
echo   -c, --comprehensive  Run only comprehensive StorageScheduler tests
echo   -i, --integration    Run only integration StorageScheduler tests
echo   -p, --property       Run only property-based StorageScheduler tests
echo   -b, --benchmark      Run only benchmark tests for StorageScheduler
echo   -x, --chaos          Run only chaos tests for StorageScheduler
echo   --coverage           Run tests with code coverage analysis
echo   --mutation           Run tests with mutation testing
echo   -h, --help           Show this help message
echo.
echo Examples:
echo   run_storage_scheduler_tests.bat --all
echo   run_storage_scheduler_tests.bat --fast
echo   run_storage_scheduler_tests.bat --comprehensive
echo   run_storage_scheduler_tests.bat --integration
echo   run_storage_scheduler_tests.bat --property
echo   run_storage_scheduler_tests.bat --benchmark
echo   run_storage_scheduler_tests.bat --chaos
echo   run_storage_scheduler_tests.bat --all --coverage
echo   run_storage_scheduler_tests.bat --fast --coverage
echo   run_storage_scheduler_tests.bat --all --mutation
echo   run_storage_scheduler_tests.bat --fast --mutation
echo   run_storage_scheduler_tests.bat --all --coverage --mutation
exit /b 0

:run_tests
REM Record start time
set start_time=%time%

REM Run tests based on options
if "%RUN_ALL%"=="true" goto run_all
if "%RUN_COMPREHENSIVE%"=="true" goto run_comprehensive
if "%RUN_FAST%"=="true" goto run_fast
if "%RUN_INTEGRATION%"=="true" goto run_integration
if "%RUN_PROPERTY%"=="true" goto run_property
if "%RUN_BENCHMARK%"=="true" goto run_benchmark
if "%RUN_CHAOS%"=="true" goto run_chaos
goto end

:run_all
call :display_header "All StorageScheduler Tests"
set overall_status=0

REM Run comprehensive tests
call :display_header "StorageScheduler Comprehensive Tests"
if "%WITH_COVERAGE%"=="true" if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest -Pjacoco,storage-scheduler-mutation
) else if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest -Pjacoco
) else if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest -Pstorage-scheduler-mutation
) else (
    call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest
)
set comprehensive_status=%ERRORLEVEL%
call :display_summary %comprehensive_status%

REM Update overall status
if %comprehensive_status% NEQ 0 set overall_status=%comprehensive_status%

REM Run fast tests
call :display_header "StorageScheduler Fast Tests"
if "%WITH_COVERAGE%"=="true" if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest -Pjacoco,storage-scheduler-mutation
) else if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest -Pjacoco
) else if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest -Pstorage-scheduler-mutation
) else (
    call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
)
set fast_status=%ERRORLEVEL%
call :display_summary %fast_status%

REM Update overall status
if %fast_status% NEQ 0 set overall_status=%fast_status%

REM Run integration tests
call :display_header "StorageScheduler Integration Tests"
if "%WITH_COVERAGE%"=="true" if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest -Pjacoco,storage-scheduler-mutation
) else if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest -Pjacoco
) else if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest -Pstorage-scheduler-mutation
) else (
    call mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest
)
set integration_status=%ERRORLEVEL%
call :display_summary %integration_status%

REM Update overall status
if %integration_status% NEQ 0 set overall_status=%integration_status%

REM Run property tests
call :display_header "StorageScheduler Property Tests"
if "%WITH_COVERAGE%"=="true" if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.property.StorageSchedulerPropertyTest -Pjacoco,storage-scheduler-mutation
) else if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.property.StorageSchedulerPropertyTest -Pjacoco
) else if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.property.StorageSchedulerPropertyTest -Pstorage-scheduler-mutation
) else (
    call mvn test -Dtest=com.ataiva.serengeti.property.StorageSchedulerPropertyTest
)
set property_status=%ERRORLEVEL%
call :display_summary %property_status%

REM Update overall status
if %property_status% NEQ 0 set overall_status=%property_status%

REM Run chaos tests
call :display_header "StorageScheduler Chaos Tests"
if "%WITH_COVERAGE%"=="true" if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing,jacoco,storage-scheduler-mutation
) else if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing,jacoco
) else if "%WITH_MUTATION%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing,storage-scheduler-mutation
) else (
    call mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing
)
set chaos_status=%ERRORLEVEL%
call :display_summary %chaos_status%

REM Update overall status
if %chaos_status% NEQ 0 set overall_status=%chaos_status%

REM Run benchmark tests
call :display_header "StorageScheduler Benchmark Tests"
call run_benchmarks.bat --component storage-scheduler
set benchmark_status=%ERRORLEVEL%
call :display_summary %benchmark_status%

REM Update overall status
if %benchmark_status% NEQ 0 set overall_status=%benchmark_status%

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

REM Display coverage report location if coverage was enabled
if "%WITH_COVERAGE%"=="true" (
    echo.
    echo Coverage report generated at: target\site\jacoco\index.html
    echo You can open this file in a browser to view the detailed coverage report.
    echo.
)

REM Display mutation report location if mutation testing was enabled
if "%WITH_MUTATION%"=="true" (
    echo.
    echo Mutation test report generated at: target\pit-reports\YYYYMMDDHHMI\index.html
    echo You can open this file in a browser to view the detailed mutation test report.
    echo.
)

exit /b %overall_status%

:run_comprehensive
call :display_header "StorageScheduler Comprehensive Tests"
if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest -Pjacoco
) else (
    call mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest
)
set comprehensive_status=%ERRORLEVEL%
call :display_summary %comprehensive_status%
exit /b %comprehensive_status%

:run_fast
call :display_header "StorageScheduler Fast Tests"
if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest -Pjacoco
) else (
    call mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
)
set fast_status=%ERRORLEVEL%
call :display_summary %fast_status%
exit /b %fast_status%

:run_integration
call :display_header "StorageScheduler Integration Tests"
if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest -Pjacoco
) else (
    call mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest
)
set integration_status=%ERRORLEVEL%
call :display_summary %integration_status%
exit /b %integration_status%

:run_property
call :display_header "StorageScheduler Property Tests"
if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.property.StorageSchedulerPropertyTest -Pjacoco
) else (
    call mvn test -Dtest=com.ataiva.serengeti.property.StorageSchedulerPropertyTest
)
set property_status=%ERRORLEVEL%
call :display_summary %property_status%
exit /b %property_status%

:run_benchmark
call :display_header "StorageScheduler Benchmark Tests"
call run_benchmarks.bat --component storage-scheduler
set benchmark_status=%ERRORLEVEL%
call :display_summary %benchmark_status%
exit /b %benchmark_status%

:run_chaos
call :display_header "StorageScheduler Chaos Tests"
if "%WITH_COVERAGE%"=="true" (
    call mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing,jacoco
) else (
    call mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing
)
set chaos_status=%ERRORLEVEL%
call :display_summary %chaos_status%
exit /b %chaos_status%

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