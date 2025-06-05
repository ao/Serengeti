# StorageScheduler Testing Strategy

## Overview

### Purpose of the StorageScheduler Component

The StorageScheduler is a critical component in the Serengeti database system responsible for periodically persisting database state to disk. It ensures data durability by:

- Running as a background thread that executes at regular intervals (every 60 seconds)
- Saving all database metadata to disk
- Persisting table storage objects and table replica objects
- Managing concurrent access to prevent data corruption
- Handling error conditions gracefully

The StorageScheduler plays a vital role in maintaining data integrity and preventing data loss in case of system failures.

### Testing Objectives and Goals

The primary objectives of the StorageScheduler testing strategy are:

1. **Functionality Verification**: Ensure the component correctly persists database state to disk under various conditions
2. **Reliability Testing**: Verify the component handles error conditions gracefully and maintains system stability
3. **Concurrency Testing**: Validate thread safety and proper handling of concurrent operations
4. **Performance Evaluation**: Measure execution time and resource usage to ensure acceptable performance
5. **Edge Case Handling**: Test behavior with special characters, large data volumes, and unusual configurations
6. **Resource Management**: Verify proper cleanup of resources after operations
7. **Mutation Testing**: Ensure tests are effective at catching bugs by verifying they fail when code is mutated

### Test Coverage Targets

The testing strategy aims to achieve:

- **Line Coverage**: >90% of code lines executed during tests
- **Branch Coverage**: >85% of conditional branches exercised
- **Method Coverage**: 100% of public methods tested
- **Scenario Coverage**: All identified use cases and edge cases tested
- **Error Handling**: All error paths exercised
- **Mutation Coverage**: >85% of code mutations detected by tests

These coverage targets are enforced through JaCoCo and PIT configuration in the Maven build. The build will fail if the StorageScheduler class does not meet these coverage thresholds. JaCoCo is used for line, branch, and method coverage, while PIT is used for mutation coverage.

## Test Structure

The StorageScheduler testing is organized into the following categories:

### Comprehensive Tests (StorageSchedulerTest.java)

Located in `src/test/java/com/ataiva/serengeti/unit/storage/StorageSchedulerTest.java`, these tests provide thorough validation of all aspects of the StorageScheduler component. They include:

- Detailed test cases for all functionality
- Performance measurements
- Stress testing with high loads
- Resource usage monitoring
- Edge case handling
- Comprehensive error condition testing

These tests are more time-consuming but provide the highest level of confidence in the component's correctness.

### Fast Tests (StorageSchedulerFastTest.java)

Located in `src/test/java/com/ataiva/serengeti/storage/StorageSchedulerFastTest.java`, these tests focus on:

- Core functionality verification
- Basic error handling
- Thread management
- Essential persistence operations

Fast tests are designed to run quickly and provide rapid feedback during development. They use minimal setup with mocked dependencies and small test data sets to avoid long-running operations.

### Test Organization and Categories

Both test classes use JUnit 5's nested test classes to organize tests into logical categories:

#### Comprehensive Tests Categories:
- Initialization Tests
- Basic Persistence Tests
- Advanced Persistence Tests
- Error Handling Tests
- Concurrency Tests
- Logging Tests
- Edge Case Tests
- Performance Tests
- Stress Tests
- Resource Cleanup Tests
- CI/CD Tests

#### Fast Tests Categories:
- Thread Management Tests
- Basic Persistence Tests
- Error Handling Tests

This organization makes it easy to locate specific test types and understand the test coverage for each aspect of the component.

### Chaos Tests (StorageSchedulerChaosTest.java)

Located in `src/test/java/com/ataiva/serengeti/chaos/StorageSchedulerChaosTest.java`, these tests verify the resilience of the StorageScheduler component under adverse conditions:

- Disk failures during persistence operations
- Network outages during distributed operations
- Resource constraints (memory, CPU, disk space)
- Thread interruptions and deadlocks
- Unexpected exceptions from dependencies
- Combined chaos scenarios

Chaos tests use fault injection techniques to simulate various failure modes and verify that the StorageScheduler can recover gracefully from unexpected failures. These tests help ensure the component is robust and can maintain data integrity even under chaotic conditions.

### Property Tests (StorageSchedulerPropertyTest.java)

Located in `src/test/java/com/ataiva/serengeti/property/StorageSchedulerPropertyTest.java`, these tests use property-based testing to:

- Verify invariants that should hold true regardless of input
- Generate a wide variety of test inputs automatically
- Uncover edge cases and unexpected behaviors
- Test the component's behavior under various conditions

Property tests use the jqwik framework to generate test inputs and verify that certain properties hold true for all generated inputs. This approach complements traditional unit tests by exploring a much wider range of possible inputs and scenarios.

### Benchmark Tests (StorageSchedulerBenchmark.java)

Located in `src/test/java/com/ataiva/serengeti/benchmark/StorageSchedulerBenchmark.java`, these tests measure the performance of the StorageScheduler component under different conditions:

- Different database sizes (small, medium, large)
- Different numbers of tables
- Different data sizes
- Different concurrency levels

Benchmark tests use the Java Microbenchmark Harness (JMH) to provide accurate and reliable performance measurements. They focus on the following metrics:

- Throughput: Operations per second
- Average time: Average time per operation
- Sample time: Distribution of operation times

These benchmarks help identify performance bottlenecks, track performance changes over time, and ensure the StorageScheduler meets performance requirements.

## Containerized Testing

### Purpose and Benefits

Containerized testing provides a consistent, isolated environment for running StorageScheduler tests, ensuring that tests behave the same way regardless of the host system. Benefits include:

- **Consistency**: Tests run in the same environment every time, eliminating "works on my machine" issues
- **Isolation**: Tests run in isolated containers, preventing interference between different test types
- **Reproducibility**: Test environments can be easily recreated across different systems
- **Parallelization**: Different test types can run in parallel in separate containers
- **Resource Control**: Container resource limits prevent tests from consuming excessive resources
- **Multi-platform Testing**: Tests can be run on different platforms (Linux, Windows) using the same configuration
- **CI/CD Integration**: Containerized tests can be easily integrated into CI/CD pipelines

### Docker Configuration

The containerized testing setup uses Docker and Docker Compose to create and manage test containers:

#### Dockerfile.test

The `Dockerfile.test` file defines the base container image for running tests:

```dockerfile
FROM maven:3.9-eclipse-temurin-11 AS test-env

# Install additional tools needed for testing
RUN apt-get update && apt-get install -y \
    curl \
    jq \
    bc \
    procps \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the project files
COPY . .

# Create directories for test results and reports
RUN mkdir -p /app/test-results/unit \
    /app/test-results/integration \
    /app/test-results/property \
    /app/test-results/benchmark \
    /app/test-results/chaos \
    /app/test-results/coverage \
    /app/test-results/mutation

# Set environment variables
ENV JAVA_OPTS="-Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=100"
ENV MAVEN_OPTS="-Xmx1024m"

# Default command (can be overridden)
CMD ["mvn", "test"]
```

#### docker-compose.test.yml

The `docker-compose.test.yml` file defines services for different test types:

- **test-base**: Base service with shared configuration
- **unit-tests**: Service for running unit tests
- **fast-tests**: Service for running fast tests
- **integration-tests**: Service for running integration tests
- **property-tests**: Service for running property-based tests
- **benchmark-tests**: Service for running benchmark tests
- **chaos-tests**: Service for running chaos tests
- **mutation-tests**: Service for running mutation tests
- **all-tests**: Service for running all test types
- **windows-tests**: Service for running tests on Windows platform

Each service is configured with appropriate resource limits, volume mounts for test results, and specific test commands.

### Running Containerized Tests

The project provides dedicated scripts for running containerized tests:

- **Linux/macOS**: `run_containerized_tests.sh`
- **Windows**: `run_containerized_tests.bat`

These scripts offer several options:

```
Options:
  -t, --test-type TYPE    Type of tests to run (default: all)
                          Available types: all, unit, fast, integration,
                          property, benchmark, chaos, mutation
  -p, --platform PLATFORM Platform to run tests on (default: linux)
                          Available platforms: linux, windows
  -r, --preserve-results  Preserve test results after completion
  -l, --preserve-logs     Preserve container logs after completion
  -c, --clean             Clean previous test results before running
  -h, --help              Show this help message
```

Examples:
```
./run_containerized_tests.sh
./run_containerized_tests.sh --test-type fast
./run_containerized_tests.sh --test-type unit --preserve-results
./run_containerized_tests.sh --test-type all --platform windows
./run_containerized_tests.sh --test-type benchmark --preserve-logs
```

### Test Results and Artifacts

Containerized tests store their results in the `./test-results` directory, with subdirectories for each test type:

- `./test-results/unit`: Unit test results
- `./test-results/fast`: Fast test results
- `./test-results/integration`: Integration test results
- `./test-results/property`: Property test results
- `./test-results/benchmark`: Benchmark test results
- `./test-results/chaos`: Chaos test results
- `./test-results/mutation`: Mutation test results
- `./test-results/coverage`: Coverage reports

These results can be preserved after test execution using the `--preserve-results` option.

### Multi-platform Testing

The containerized testing setup supports running tests on different platforms:

- **Linux**: Default platform using the standard Docker images
- **Windows**: Uses Windows-specific Docker images with the `--platform windows` option

This allows testing the StorageScheduler component in different operating system environments to ensure cross-platform compatibility.

### CI/CD Integration

The containerized tests can be integrated into CI/CD pipelines by using the Docker Compose configuration and test scripts. The GitHub Actions workflow can be updated to use containerized tests instead of running tests directly on the runner.

Example GitHub Actions workflow step:
```yaml
- name: Run Containerized Tests
  run: ./run_containerized_tests.sh --test-type all
```

This ensures that tests run in the same environment in CI/CD as they do locally, providing consistent results across different systems.

## Test Categories

### Initialization Tests

These tests verify that the StorageScheduler correctly initializes and starts its background thread:

- Test that the scheduler starts a background thread when initialized
- Verify that the thread executes the persistence operation at the expected intervals
- Ensure proper handling of thread interruptions

Example test: `testInit()` in the `InitializationTests` nested class.

### Basic Persistence Tests

These tests validate the core functionality of persisting database state to disk:

- Test successful persistence when the network is online and the scheduler is not already running
- Verify that persistence is skipped when the network is offline
- Ensure persistence is skipped when the scheduler is already running
- Test handling of empty databases

Example test: `testPerformPersistToDiskSuccess()` in the `BasicPersistenceTests` nested class.

### Test Data Management

The StorageScheduler testing framework includes comprehensive test data management utilities to ensure tests have access to consistent, well-defined test data. This makes tests more reliable, maintainable, and easier to understand.

#### Test Data Generation Utilities

Located in `src/test/java/com/ataiva/serengeti/testdata/StorageSchedulerTestData.java`, these utilities provide methods for generating various types of test data:

- **Database Generation**: Create test databases with configurable properties
- **Table Generation**: Create test tables with different characteristics
- **Row Generation**: Generate test data rows with various patterns and sizes
- **Complete Environment Setup**: Set up a complete test environment with databases, tables, and rows

Example usage:
```java
// Create a test database with 3 tables
DatabaseObject db = StorageSchedulerTestData.createTestDatabaseWithTables("test_db", 3);

// Create and populate a table storage object
TableStorageObject tableStorage = StorageSchedulerTestData.createTestTableStorage("test_db", "test_table");
StorageSchedulerTestData.populateTableStorage(tableStorage, 100); // Add 100 rows

// Set up Storage with test data
Map<String, Object> originalState = StorageSchedulerTestData.setupStorageWithTestData(2, 3, 50);
// ... run test ...
StorageSchedulerTestData.restoreStorageState(originalState); // Clean up
```

#### Test Fixtures

Located in `src/test/resources/fixtures/storage-scheduler/`, these JSON fixtures provide predefined test scenarios:

- **basic_database.json**: A simple database with a few tables and rows
- **large_database.json**: A database with many tables and rows for testing scalability
- **special_characters.json**: A database with special characters in names for testing edge cases
- **empty_database.json**: An empty database for testing boundary conditions
- **error_scenarios.json**: Scenarios for testing error handling
- **performance_test.json**: Configurations for performance testing

#### Test Data Loaders

Located in `src/test/java/com/ataiva/serengeti/testdata/StorageSchedulerTestDataLoader.java`, these utilities load test data from fixtures:

- **Fixture Loading**: Load JSON fixtures from files
- **Database Creation**: Create database objects from fixture data
- **Storage Setup**: Set up Storage static fields with fixture data
- **Scenario Loading**: Load specific test scenarios from fixtures

Example usage:
```java
// Load test data from fixture
Map<String, Object> originalState = StorageSchedulerTestDataLoader.setupStorageFromFixture("basic_database");
// ... run test ...
StorageSchedulerTestDataCleaner.restoreStorageState(originalState); // Clean up

// Load error scenarios
List<ErrorScenario> errorScenarios = StorageSchedulerTestDataLoader.loadErrorScenarios();
```

#### Test Data Cleanup Utilities

Located in `src/test/java/com/ataiva/serengeti/testdata/StorageSchedulerTestDataCleaner.java`, these utilities ensure proper cleanup after tests:

- **State Restoration**: Restore original Storage state after tests
- **File Cleanup**: Clean up test data files from disk
- **Verification**: Verify that test data has been properly cleaned up

Example usage:
```java
// Create a temporary data directory
String tempDataPath = StorageSchedulerTestDataCleaner.createTempDataDirectory();
String originalDataPath = StorageSchedulerTestDataCleaner.setTempDataPath();
// ... run test ...
StorageSchedulerTestDataCleaner.cleanupDataFiles(tempDataPath);
StorageSchedulerTestDataCleaner.restoreDataPath(originalDataPath);
```

#### Benefits of the Test Data Management Framework

- **Consistency**: Tests use consistent, well-defined test data
- **Maintainability**: Test data is centralized and reusable
- **Readability**: Tests are more concise and focused on behavior, not setup
- **Reliability**: Tests are less likely to interfere with each other
- **Flexibility**: Easy to create different test scenarios
- **Efficiency**: Reduced duplication in test setup code

For examples of using these utilities, see `StorageSchedulerTestWithTestData.java`.

## Test Reporting

### Comprehensive Test Report Generator

The StorageScheduler component includes a comprehensive test report generator that aggregates results from all test types and provides a unified view of the test results. This helps stakeholders quickly understand the quality and reliability of the component.

#### Report Generator Components

The report generator consists of the following components:

1. **StorageSchedulerReportGenerator.java**: The main class that collects test results and generates reports
2. **HTML/XML/JSON Report Templates**: Templates for different report formats
3. **Report Generation Scripts**: Scripts for generating reports with various options

#### Report Types

The report generator can produce reports in the following formats:

- **HTML**: Interactive reports with charts and detailed test information
- **XML**: Structured reports for machine processing
- **JSON**: Data-oriented reports for integration with other tools

#### Report Content

The generated reports include:

- **Test Summary**: Overview of test results across all test types
- **Coverage Metrics**: Line, branch, method, and class coverage
- **Mutation Metrics**: Mutation score and details of killed/survived mutations
- **Benchmark Results**: Performance metrics from JMH benchmarks
- **Test Details**: Detailed information about individual test cases

#### Using the Report Generator

To generate a test report, use the provided scripts:

- **Linux/macOS**: `./generate_test_report.sh`
- **Windows**: `generate_test_report.bat`

These scripts offer several options:

```
Options:
  -f, --format FORMAT      Report format: html, xml, json (default: html)
  -o, --output-dir DIR     Directory to store the report (default: target/reports/storage-scheduler)
  -p, --publish DIR        Directory to publish the report to (optional)
  -d, --detail LEVEL       Detail level: minimal, standard, full (default: standard)
      --open               Open the report after generation (HTML format only)
  -h, --help               Show this help message
```

Examples:
```
./generate_test_report.sh
./generate_test_report.sh --format xml
./generate_test_report.sh --detail full --open
./generate_test_report.sh --publish /var/www/html/reports
```

#### Integration with CI/CD

The test report generator can be integrated into CI/CD pipelines to automatically generate and publish reports after test execution. This provides stakeholders with up-to-date information about the quality of the StorageScheduler component.

Example GitHub Actions workflow step:
```yaml
- name: Generate Test Report
  run: ./generate_test_report.sh --publish ${{ github.workspace }}/reports
  
- name: Upload Test Report
  uses: actions/upload-artifact@v2
  with:
    name: storage-scheduler-test-report
    path: reports/
```

#### Benefits of the Test Report Generator

- **Unified View**: Aggregates results from all test types in one place
- **Stakeholder Communication**: Provides clear, accessible information about test results
- **Trend Analysis**: Enables tracking of quality metrics over time
- **Issue Identification**: Helps identify problematic areas that need attention
- **Documentation**: Serves as documentation of the component's quality and reliability