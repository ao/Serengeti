@echo off
setlocal enabledelayedexpansion

REM Script to run containerized tests for the StorageScheduler component
REM This script provides options to run different types of tests in Docker containers

REM Default values
set TEST_TYPE=all
set PRESERVE_RESULTS=false
set PRESERVE_LOGS=false
set PLATFORM=linux
set CLEAN_FIRST=false
set SHOW_HELP=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto :end_parse_args
if /i "%~1"=="-t" (
    set TEST_TYPE=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--test-type" (
    set TEST_TYPE=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="-p" (
    set PLATFORM=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--platform" (
    set PLATFORM=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="-r" (
    set PRESERVE_RESULTS=true
    shift
    goto :parse_args
)
if /i "%~1"=="--preserve-results" (
    set PRESERVE_RESULTS=true
    shift
    goto :parse_args
)
if /i "%~1"=="-l" (
    set PRESERVE_LOGS=true
    shift
    goto :parse_args
)
if /i "%~1"=="--preserve-logs" (
    set PRESERVE_LOGS=true
    shift
    goto :parse_args
)
if /i "%~1"=="-c" (
    set CLEAN_FIRST=true
    shift
    goto :parse_args
)
if /i "%~1"=="--clean" (
    set CLEAN_FIRST=true
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
    echo Usage: run_containerized_tests.bat [OPTIONS]
    echo.
    echo Options:
    echo   -t, --test-type TYPE    Type of tests to run (default: all^)
    echo                           Available types: all, unit, fast, integration,
    echo                           property, benchmark, chaos, mutation
    echo   -p, --platform PLATFORM Platform to run tests on (default: linux^)
    echo                           Available platforms: linux, windows
    echo   -r, --preserve-results  Preserve test results after completion
    echo   -l, --preserve-logs     Preserve container logs after completion
    echo   -c, --clean             Clean previous test results before running
    echo   -h, --help              Show this help message
    echo.
    echo Examples:
    echo   run_containerized_tests.bat
    echo   run_containerized_tests.bat --test-type fast
    echo   run_containerized_tests.bat --test-type unit --preserve-results
    echo   run_containerized_tests.bat --test-type all --platform windows
    echo   run_containerized_tests.bat --test-type benchmark --preserve-logs
    exit /b 0
)

REM Function to display test header
:display_header
echo ========================================================
echo   Running %TEST_TYPE% Tests in Container
echo ========================================================
echo Started at: %date% %time%
echo Platform: %PLATFORM%
echo --------------------------------------------------------
goto :eof

REM Clean previous test results if requested
if "%CLEAN_FIRST%"=="true" (
    echo Cleaning previous test results...
    if exist test-results rmdir /s /q test-results
    mkdir test-results
)

REM Create test results directory if it doesn't exist
if not exist test-results mkdir test-results

REM Record start time
set START_TIME=%time%

REM Determine the service name based on test type
if "%TEST_TYPE%"=="all" (
    set SERVICE_NAME=all-tests
) else if "%TEST_TYPE%"=="unit" (
    set SERVICE_NAME=unit-tests
) else if "%TEST_TYPE%"=="fast" (
    set SERVICE_NAME=fast-tests
) else if "%TEST_TYPE%"=="integration" (
    set SERVICE_NAME=integration-tests
) else if "%TEST_TYPE%"=="property" (
    set SERVICE_NAME=property-tests
) else if "%TEST_TYPE%"=="benchmark" (
    set SERVICE_NAME=benchmark-tests
) else if "%TEST_TYPE%"=="chaos" (
    set SERVICE_NAME=chaos-tests
) else if "%TEST_TYPE%"=="mutation" (
    set SERVICE_NAME=mutation-tests
) else (
    echo Unknown test type: %TEST_TYPE%
    echo Available types: all, unit, fast, integration, property, benchmark, chaos, mutation
    exit /b 1
)

REM Add platform-specific profile if needed
set PLATFORM_PROFILE=
if "%PLATFORM%"=="windows" (
    set PLATFORM_PROFILE=--profile windows
    if not "%SERVICE_NAME%"=="windows-tests" (
        set SERVICE_NAME=windows-tests
        echo Using windows-tests service for Windows platform
    )
)

REM Display header
call :display_header

REM Build and run the container
echo Building and running Docker container for %TEST_TYPE% tests...
docker-compose -f docker-compose.test.yml build %SERVICE_NAME%
docker-compose -f docker-compose.test.yml up %PLATFORM_PROFILE% %SERVICE_NAME%

REM Get the exit code of the container
for /f "tokens=*" %%i in ('docker-compose -f docker-compose.test.yml ps -q %SERVICE_NAME%') do (
    for /f "tokens=*" %%j in ('docker inspect -f "{{.State.ExitCode}}" %%i') do (
        set CONTAINER_EXIT_CODE=%%j
    )
)

REM Display summary
echo --------------------------------------------------------
echo Finished at: %date% %time%
echo.
if "%CONTAINER_EXIT_CODE%"=="0" (
    echo Status: SUCCESS ✅
) else (
    echo Status: FAILED ❌
)
echo ========================================================
echo.

REM Save logs if requested
if "%PRESERVE_LOGS%"=="true" (
    echo Saving container logs to ./test-results/%TEST_TYPE%-logs.txt
    docker-compose -f docker-compose.test.yml logs %SERVICE_NAME% > ./test-results/%TEST_TYPE%-logs.txt
)

REM Clean up containers if not preserving results
if "%PRESERVE_RESULTS%"=="false" (
    echo Cleaning up containers...
    docker-compose -f docker-compose.test.yml down
) else (
    echo Containers preserved. Use 'docker-compose -f docker-compose.test.yml down' to clean up.
)

REM Display results location
echo Test results are available in ./test-results/%TEST_TYPE%/

REM Exit with the container's exit code
exit /b %CONTAINER_EXIT_CODE%