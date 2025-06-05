@echo off
REM Script to run benchmark tests for Serengeti
REM This script provides options to run benchmarks for specific components
REM and with different output formats.

REM Default values
set COMPONENT=all
set FORMAT=json
set ITERATIONS=3
set WARMUP=2
set THREADS=1
set SHOW_HELP=false
set OPEN_REPORT=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto check_args
if /i "%~1"=="-c" set COMPONENT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--component" set COMPONENT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-f" set FORMAT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--format" set FORMAT=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-i" set ITERATIONS=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--iterations" set ITERATIONS=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-w" set WARMUP=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--warmup" set WARMUP=%~2 & shift & shift & goto parse_args
if /i "%~1"=="-t" set THREADS=%~2 & shift & shift & goto parse_args
if /i "%~1"=="--threads" set THREADS=%~2 & shift & shift & goto parse_args
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
goto run_benchmarks

:show_help
echo Usage: run_benchmarks.bat [OPTIONS]
echo.
echo Options:
echo   -c, --component COMPONENT  Component to benchmark (default: all)
echo                              Available components: storage-scheduler, all
echo   -f, --format FORMAT        Output format: json, csv, scsv, text, latex (default: json)
echo   -i, --iterations N         Number of measurement iterations (default: 3)
echo   -w, --warmup N             Number of warmup iterations (default: 2)
echo   -t, --threads N            Number of threads to use (default: 1)
echo   -o, --open                 Open the HTML report after generation
echo   -h, --help                 Show this help message
echo.
echo Examples:
echo   run_benchmarks.bat
echo   run_benchmarks.bat --component storage-scheduler
echo   run_benchmarks.bat --format csv
echo   run_benchmarks.bat --iterations 5 --warmup 3
echo   run_benchmarks.bat --component storage-scheduler --format json --open
exit /b 0

:run_benchmarks
REM Record start time
set start_time=%time%

REM Create results directory if it doesn't exist
if not exist target\benchmarks mkdir target\benchmarks

REM Run benchmarks based on component
if "%COMPONENT%"=="all" goto run_all_benchmarks
if "%COMPONENT%"=="storage-scheduler" goto run_storage_scheduler_benchmarks
echo Unknown component: %COMPONENT%
exit /b 1

:run_all_benchmarks
call :run_storage_scheduler_benchmarks
goto end

:run_storage_scheduler_benchmarks
call :display_header "StorageScheduler"

REM Build JMH benchmark options
set JMH_OPTS=-wi %WARMUP% -i %ITERATIONS% -t %THREADS% -f 1 -rf %FORMAT%

REM Run StorageScheduler benchmarks
call mvn clean test -Pbenchmark -Djmh.includes=StorageSchedulerBenchmark -Djmh.args="%JMH_OPTS% -rff target/benchmarks/storage-scheduler-results.%FORMAT%"

REM Store the exit code
set benchmark_status=%ERRORLEVEL%

REM Display summary
call :display_summary %benchmark_status%

REM Generate HTML report if requested
if "%OPEN_REPORT%"=="true" if "%FORMAT%"=="json" (
    echo Generating HTML report from JSON results...
    
    REM Create a simple HTML file that loads the results
    echo ^<!DOCTYPE html^> > target\benchmarks\report.html
    echo ^<html^> >> target\benchmarks\report.html
    echo ^<head^> >> target\benchmarks\report.html
    echo     ^<title^>StorageScheduler Benchmark Results^</title^> >> target\benchmarks\report.html
    echo     ^<script src="https://cdnjs.cloudflare.com/ajax/libs/react/15.4.1/react.min.js"^>^</script^> >> target\benchmarks\report.html
    echo     ^<script src="https://cdnjs.cloudflare.com/ajax/libs/react/15.4.1/react-dom.min.js"^>^</script^> >> target\benchmarks\report.html
    echo     ^<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.7/css/bootstrap.min.css"^> >> target\benchmarks\report.html
    echo     ^<script src="https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.7/js/bootstrap.min.js"^>^</script^> >> target\benchmarks\report.html
    echo     ^<script src="https://cdnjs.cloudflare.com/ajax/libs/jquery/3.1.1/jquery.min.js"^>^</script^> >> target\benchmarks\report.html
    echo     ^<script src="https://cdnjs.cloudflare.com/ajax/libs/lodash.js/4.17.2/lodash.min.js"^>^</script^> >> target\benchmarks\report.html
    echo     ^<script src="https://cdnjs.cloudflare.com/ajax/libs/d3/4.4.0/d3.min.js"^>^</script^> >> target\benchmarks\report.html
    echo     ^<script src="https://cdnjs.cloudflare.com/ajax/libs/metrics-graphics/2.11.0/metricsgraphics.min.js"^>^</script^> >> target\benchmarks\report.html
    echo     ^<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/metrics-graphics/2.11.0/metricsgraphics.min.css"^> >> target\benchmarks\report.html
    echo ^</head^> >> target\benchmarks\report.html
    echo ^<body^> >> target\benchmarks\report.html
    echo     ^<div id="app"^>^</div^> >> target\benchmarks\report.html
    echo     ^<script^> >> target\benchmarks\report.html
    echo         // Load the benchmark results >> target\benchmarks\report.html
    echo         fetch('storage-scheduler-results.json') >> target\benchmarks\report.html
    echo             .then(response =^> response.json^(^)) >> target\benchmarks\report.html
    echo             .then(data =^> { >> target\benchmarks\report.html
    echo                 // Display the results >> target\benchmarks\report.html
    echo                 const container = document.getElementById('app'); >> target\benchmarks\report.html
    echo                 const header = document.createElement('h1'); >> target\benchmarks\report.html
    echo                 header.textContent = 'StorageScheduler Benchmark Results'; >> target\benchmarks\report.html
    echo                 container.appendChild(header); >> target\benchmarks\report.html
    echo                 >> target\benchmarks\report.html
    echo                 // Create a table for the results >> target\benchmarks\report.html
    echo                 const table = document.createElement('table'); >> target\benchmarks\report.html
    echo                 table.className = 'table table-striped'; >> target\benchmarks\report.html
    echo                 container.appendChild(table); >> target\benchmarks\report.html
    echo                 >> target\benchmarks\report.html
    echo                 // Create table header >> target\benchmarks\report.html
    echo                 const thead = document.createElement('thead'); >> target\benchmarks\report.html
    echo                 table.appendChild(thead); >> target\benchmarks\report.html
    echo                 const headerRow = document.createElement('tr'); >> target\benchmarks\report.html
    echo                 thead.appendChild(headerRow); >> target\benchmarks\report.html
    echo                 >> target\benchmarks\report.html
    echo                 const headers = ['Benchmark', 'Mode', 'Threads', 'Samples', 'Score', 'Error', 'Units']; >> target\benchmarks\report.html
    echo                 headers.forEach(text =^> { >> target\benchmarks\report.html
    echo                     const th = document.createElement('th'); >> target\benchmarks\report.html
    echo                     th.textContent = text; >> target\benchmarks\report.html
    echo                     headerRow.appendChild(th); >> target\benchmarks\report.html
    echo                 }); >> target\benchmarks\report.html
    echo                 >> target\benchmarks\report.html
    echo                 // Create table body >> target\benchmarks\report.html
    echo                 const tbody = document.createElement('tbody'); >> target\benchmarks\report.html
    echo                 table.appendChild(tbody); >> target\benchmarks\report.html
    echo                 >> target\benchmarks\report.html
    echo                 // Add rows for each benchmark result >> target\benchmarks\report.html
    echo                 data.forEach(result =^> { >> target\benchmarks\report.html
    echo                     const row = document.createElement('tr'); >> target\benchmarks\report.html
    echo                     tbody.appendChild(row); >> target\benchmarks\report.html
    echo                     >> target\benchmarks\report.html
    echo                     const cells = [ >> target\benchmarks\report.html
    echo                         result.benchmark.split('.').pop(), >> target\benchmarks\report.html
    echo                         result.mode, >> target\benchmarks\report.html
    echo                         result.threads, >> target\benchmarks\report.html
    echo                         result.measurementIterations, >> target\benchmarks\report.html
    echo                         result.primaryMetric.score.toFixed(3), >> target\benchmarks\report.html
    echo                         result.primaryMetric.scoreError.toFixed(3), >> target\benchmarks\report.html
    echo                         result.primaryMetric.scoreUnit >> target\benchmarks\report.html
    echo                     ]; >> target\benchmarks\report.html
    echo                     >> target\benchmarks\report.html
    echo                     cells.forEach(text =^> { >> target\benchmarks\report.html
    echo                         const td = document.createElement('td'); >> target\benchmarks\report.html
    echo                         td.textContent = text; >> target\benchmarks\report.html
    echo                         row.appendChild(td); >> target\benchmarks\report.html
    echo                     }); >> target\benchmarks\report.html
    echo                 }); >> target\benchmarks\report.html
    echo             }) >> target\benchmarks\report.html
    echo             .catch(error =^> { >> target\benchmarks\report.html
    echo                 console.error('Error loading benchmark results:', error); >> target\benchmarks\report.html
    echo                 document.getElementById('app').textContent = 'Error loading benchmark results: ' + error.message; >> target\benchmarks\report.html
    echo             }); >> target\benchmarks\report.html
    echo     ^</script^> >> target\benchmarks\report.html
    echo ^</body^> >> target\benchmarks\report.html
    echo ^</html^> >> target\benchmarks\report.html
    
    REM Open the HTML report
    start "" "target\benchmarks\report.html"
)

REM Display results location
echo Benchmark results saved to: target\benchmarks\storage-scheduler-results.%FORMAT%
echo.

exit /b %benchmark_status%

:display_header
echo ========================================================
echo   Running %~1 Benchmarks
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