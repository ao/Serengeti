@echo off
REM Script to run the LSM storage engine fast tests

echo Running LSM Storage Engine Fast Tests...
echo ---------------------------------------

REM Run the LSM fast tests using Maven
call mvn test -Dtest=*LSM*FastTest,*MemTable*FastTest,*SSTable*FastTest

REM Check the exit code
if %ERRORLEVEL% EQU 0 (
    echo ---------------------------------------
    echo LSM Fast Tests completed successfully!
) else (
    echo ---------------------------------------
    echo LSM Fast Tests failed!
    exit /b 1
)