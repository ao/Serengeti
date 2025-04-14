@echo off
REM Script to run the fast tests for Serengeti components

echo Running Serengeti Fast Tests...
echo -----------------------------

REM Run the fast tests using Maven
call mvn test -Dtest=ms.ao.serengeti.storage.lsm.*FastTest,ms.ao.serengeti.network.NetworkFastTest,ms.ao.serengeti.unit.query.QueryFastTest,ms.ao.serengeti.unit.server.ServerFastTest

REM Check the exit code
if %ERRORLEVEL% EQU 0 (
    echo -----------------------------
    echo Fast Tests completed successfully!
) else (
    echo -----------------------------
    echo Fast Tests failed!
    exit /b 1
)