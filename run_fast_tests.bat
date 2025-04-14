@echo off
REM Script to run the Serengeti fast test suite

echo Running Serengeti Fast Test Suite...
echo -----------------------------------

REM Run the fast tests using Maven
call mvn test -Pfast-tests

REM Check the exit code
if %ERRORLEVEL% EQU 0 (
    echo -----------------------------------
    echo Fast Test Suite completed successfully!
) else (
    echo -----------------------------------
    echo Fast Test Suite failed!
    exit /b 1
)