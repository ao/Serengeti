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

The StorageScheduler testing is organized into two main categories:

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

### Advanced Persistence Tests

These tests examine more complex persistence scenarios:

- Test handling of multiple databases and tables
- Verify correct file path generation for database persistence
- Test databases with special characters in names
- Handle very large database objects

Example test: `testPerformPersistToDiskMultipleDatabases()` in the `AdvancedPersistenceTests` nested class.

### Error Handling Tests

These tests ensure the StorageScheduler gracefully handles error conditions:

- Test handling of file write exceptions
- Verify proper handling of table save exceptions
- Test recovery from null database objects or tables
- Ensure the running flag is properly reset after exceptions

Example test: `testPerformPersistToDiskFileWriteException()` in the `ErrorHandlingTests` nested class.

### Concurrency Tests

These tests validate thread safety and proper handling of concurrent operations:

- Test that the running flag is properly managed during execution
- Verify that concurrent persistence operations are prevented
- Test behavior with multiple threads trying to persist simultaneously
- Ensure persistence works correctly during system shutdown

Example test: `testMultipleThreadsPersisting()` in the `ConcurrencyTests` nested class.

### Performance Tests

These tests measure the execution time and resource usage of the StorageScheduler:

- Test that persistence completes within acceptable time limits
- Verify linear scaling with increasing database size
- Measure memory usage during persistence operations

Example test: `testPersistenceScalability()` in the `PerformanceTests` nested class.

### Stress Tests

These tests verify system stability under high load:

- Test handling of a high volume of concurrent persistence requests
- Verify behavior with rapid sequential persistence requests
- Test persistence with a large number of databases

Example test: `testHighConcurrentLoad()` in the `StressTests` nested class.

## Running Tests

### Using the Dedicated Test Scripts

The project provides dedicated scripts for running StorageScheduler tests:

- **Linux/macOS**: `run_storage_scheduler_tests.sh`
- **Windows**: `run_storage_scheduler_tests.bat`

These scripts offer several options:

```
Options:
  -a, --all            Run all StorageScheduler tests
  -f, --fast           Run only fast StorageScheduler tests
  -c, --comprehensive  Run only comprehensive StorageScheduler tests
  --coverage           Run tests with code coverage analysis
  --mutation           Run tests with mutation testing
  -h, --help           Show this help message
```

Examples:
```
./run_storage_scheduler_tests.sh --all
./run_storage_scheduler_tests.sh --fast
./run_storage_scheduler_tests.sh --comprehensive
./run_storage_scheduler_tests.sh --all --coverage
./run_storage_scheduler_tests.sh --fast --coverage
./run_storage_scheduler_tests.sh --all --mutation
./run_storage_scheduler_tests.sh --fast --mutation
./run_storage_scheduler_tests.sh --all --coverage --mutation
```

### Using the Coverage Report Scripts

For detailed code coverage analysis, dedicated coverage report scripts are provided:

- **Linux/macOS**: `generate_coverage_report.sh`
- **Windows**: `generate_coverage_report.bat`

These scripts offer several options:

```
Options:
  -f, --format FORMAT    Report format: html, xml, csv (default: html)
  -t, --test-type TYPE   Test type: all, fast, comprehensive (default: all)
  -o, --open             Open the report after generation
  -h, --help             Show this help message
```

Examples:
```
./generate_coverage_report.sh
./generate_coverage_report.sh --format xml
./generate_coverage_report.sh --test-type fast --open
./generate_coverage_report.sh --format csv --test-type comprehensive
```

The coverage report scripts will:
1. Run the specified tests with JaCoCo coverage instrumentation
2. Generate coverage reports in the requested format
3. Display a summary of coverage metrics
4. Indicate whether coverage meets the defined targets
5. Optionally open the HTML report in a browser

### Available Options and Parameters

The test scripts provide detailed output including:

- Test execution start and end times
- Total execution time
- Success or failure status for each test category
- Overall test summary when running all tests

The scripts use Maven to execute the tests with specific test class filters:

- Comprehensive tests: `mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest`
- Fast tests: `mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest`

### Interpreting Test Results

Test results include:

- **Success/Failure Status**: Indicated by ✅/❌ symbols
- **Execution Time**: Total time taken for test execution
- **Console Output**: Detailed logs of test operations
- **Error Messages**: Clear descriptions of any test failures

For performance tests, additional metrics are provided:
- Persistence operation completion time
- Memory usage statistics
- Scaling characteristics with different data sizes

## Extending Tests

### Guidelines for Adding New Tests

When adding new tests for the StorageScheduler:

1. **Determine Test Category**: Decide whether the test belongs in comprehensive or fast tests
2. **Choose Appropriate Nested Class**: Add the test to the relevant nested class based on its purpose
3. **Follow Naming Convention**: Use descriptive method names with the pattern `test[Functionality]`
4. **Add DisplayName Annotation**: Include a clear `@DisplayName` describing the test's purpose
5. **Add Appropriate Tags**: Use tags like `@Tag("performance")` to categorize the test
6. **Use Helper Methods**: Leverage existing helper methods for common setup tasks

Example of adding a new test:

```java
@Test
@DisplayName("Should handle database with encrypted content")
void testEncryptedDatabasePersistence() {
    // Test implementation
}
```

### Best Practices for Test Implementation

When implementing StorageScheduler tests:

1. **Isolate Tests**: Each test should be independent and not rely on state from other tests
2. **Use Mocks Appropriately**: Mock external dependencies but test real component behavior
3. **Clean Up Resources**: Ensure all resources are properly cleaned up after tests
4. **Verify State Changes**: Check both return values and side effects of operations
5. **Test Error Paths**: Include tests for error conditions and edge cases
6. **Use Assertions Effectively**: Make assertions specific and include meaningful messages
7. **Keep Fast Tests Fast**: Avoid long-running operations in tests tagged as "fast"

### Common Pitfalls to Avoid

When testing the StorageScheduler, be careful to avoid:

1. **Static State Leakage**: Always restore static fields to their original state after tests
2. **Timing Dependencies**: Avoid tests that depend on specific timing behavior
3. **Resource Leaks**: Ensure all file handles and other resources are properly closed
4. **Flaky Tests**: Avoid tests that might fail intermittently due to race conditions
5. **Over-mocking**: Don't mock the component under test, only its dependencies
6. **Hardcoded Paths**: Use the test data path from the test base class instead of hardcoded paths
7. **Insufficient Verification**: Verify both the result and side effects of operations

## Test Dependencies

### Required Mock Objects

The StorageScheduler tests use several mock objects:

- **DatabaseObject**: Mocked to simulate database metadata
- **TableStorageObject**: Mocked to simulate table storage
- **TableReplicaObject**: Mocked to simulate table replicas
- **Files and Paths**: Static mocking to avoid actual file system operations

Example of mock setup:

```java
@Mock
private DatabaseObject mockDatabaseObject;

@Mock
private TableStorageObject mockTableStorageObject;

@Mock
private TableReplicaObject mockTableReplicaObject;
```

### Test Utilities and Helpers

The tests leverage several utility classes and helper methods:

- **TestBase/StorageFastTestBase**: Base classes that provide common setup and teardown operations
- **StorageTestUtils**: Utilities specific to storage testing
- **setupSuccessfulPersistenceScenario()**: Helper method to set up a basic successful persistence scenario
- **setupMultipleDatabasesScenario()**: Helper method to set up a scenario with multiple databases
- **generateTestDatabase()**: Helper method to generate test databases with specified characteristics

### External Dependencies

The tests have the following external dependencies:

- **JUnit 5**: Testing framework
- **Mockito**: Mocking framework for creating test doubles
- **MockedStatic**: For mocking static methods in the Files and Paths classes

## Code Coverage Analysis

### Coverage Requirements

The StorageScheduler component has the following coverage requirements:

- **Line Coverage**: At least 90% of code lines must be executed during tests
- **Branch Coverage**: At least 85% of conditional branches must be exercised
- **Method Coverage**: 100% of public methods must be tested
- **Mutation Coverage**: At least 85% of code mutations must be detected by tests

The line, branch, and method coverage requirements are enforced through JaCoCo configuration in the Maven build. The mutation coverage requirement is enforced through PIT configuration. When running tests with the `--coverage` or `--mutation` options, the build will fail if these thresholds are not met.

### Interpreting Coverage Reports

The HTML coverage reports provide detailed information about code coverage:

- **Green lines**: Fully covered code
- **Yellow lines**: Partially covered branches
- **Red lines**: Uncovered code

The reports include:
- Overall coverage metrics
- Class-level coverage details
- Method-level coverage details
- Line-by-line coverage highlighting

### Addressing Coverage Gaps

When coverage gaps are identified:

1. Analyze the uncovered code to understand what scenarios are not being tested
2. Create new test cases that specifically target the uncovered code paths
3. Focus on edge cases, error conditions, and boundary values
4. Re-run the coverage analysis to verify the gaps have been addressed

## Mutation Testing

### What is Mutation Testing?

Mutation testing is a technique to evaluate the quality of existing tests by introducing small changes (mutations) to the source code and verifying that the tests fail when these mutations are present. It helps identify weaknesses in the test suite and ensures that tests are effective at catching bugs.

### Mutation Operators

The StorageScheduler mutation testing uses the following mutation operators:

- **Default Operators**: Standard PIT mutation operators including:
  - Conditionals Boundary Mutator
  - Increments Mutator
  - Invert Negatives Mutator
  - Math Mutator
  - Negate Conditionals Mutator
  - Return Values Mutator
  - Void Method Calls Mutator
- **Additional Operators**:
  - Constructor Calls Mutator
  - Non-Void Method Calls Mutator
  - Remove Conditionals Mutator

### Running Mutation Tests

Mutation tests can be run using the dedicated scripts:

```
./run_mutation_tests.sh
./run_mutation_tests.bat
```

These scripts provide several options:

```
Options:
  -c, --component COMPONENT  Component to test (default: storage-scheduler)
                             Available components: storage-scheduler, all
  -f, --format FORMAT        Report format: HTML, XML (default: HTML)
  -o, --open                 Open the HTML report after generation
  -h, --help                 Show this help message
```

Mutation tests can also be run as part of the regular test execution by using the `--mutation` flag:

```
./run_storage_scheduler_tests.sh --all --mutation
```

### Interpreting Mutation Test Results

The mutation test report provides the following information:

- **Mutation Score**: The percentage of mutations that were detected (killed) by the tests
- **Survived Mutations**: Mutations that were not detected by any test
- **Killed Mutations**: Mutations that were detected by at least one test
- **Mutation Details**: Information about each mutation, including:
  - The mutated line of code
  - The type of mutation
  - Whether the mutation was killed or survived
  - Which test killed the mutation (if any)

### Improving Mutation Score

When surviving mutations are identified:

1. Analyze the surviving mutations to understand why they were not detected
2. Create new test cases that specifically target the surviving mutations
3. Improve existing tests to make them more sensitive to code changes
4. Re-run the mutation tests to verify the improvements

## Performance Considerations

### Expected Performance Characteristics

The StorageScheduler is expected to:

- Complete basic persistence operations in under 1000ms
- Scale linearly with database size
- Use memory proportional to the size of the data being persisted
- Handle concurrent requests efficiently

### Performance Test Thresholds

The performance tests enforce the following thresholds:

- Basic persistence operation: < 1000ms
- CI environment execution: < 500ms
- Linear scaling: execution time should grow linearly with database size
- Memory usage: monitored but without strict thresholds due to environment variability

Example threshold check:

```java
assertTrue(executionTimeMs < 1000,
    "Persistence operation should complete within 1000ms, took: " + executionTimeMs + "ms");
```

### Optimization Opportunities

Based on the test results, potential optimization areas include:

1. **Batch Processing**: Group database writes to reduce I/O operations
2. **Incremental Persistence**: Only persist changed data rather than all data
3. **Compression**: Compress data before writing to disk to reduce I/O volume
4. **Asynchronous I/O**: Use non-blocking I/O operations for better concurrency
5. **Resource Pooling**: Reuse file handles and buffers to reduce allocation overhead
6. **Prioritization**: Implement priority-based persistence for critical data

Performance tests can be extended to measure the impact of these optimizations.

## Integration Testing

### Purpose of Integration Tests

Integration tests for the StorageScheduler component verify that it works correctly in conjunction with other components it interacts with. While unit tests focus on isolated behavior, integration tests ensure that:

1. **Component Interactions**: StorageScheduler correctly interacts with Storage, Network, DatabaseObject, TableStorageObject, and TableReplicaObject components
2. **File System Integration**: Actual file system operations work as expected
3. **Error Propagation**: Errors are properly propagated between components
4. **End-to-End Workflows**: Complete persistence workflows function correctly

### Integration Test Structure

Integration tests are located in `src/test/java/com/ataiva/serengeti/integration/StorageSchedulerIntegrationTest.java` and extend the `TestBase` class. They use real (non-mocked) components to test actual interactions.

#### Test Categories:

- **Storage Integration Tests**: Verify that StorageScheduler correctly interacts with the Storage component
- **Network Integration Tests**: Test that StorageScheduler respects the Network.online flag
- **Schema Objects Integration Tests**: Verify correct persistence of DatabaseObject, TableStorageObject, and TableReplicaObject
- **File System Integration Tests**: Test actual file system operations with different path configurations
- **Concurrency Tests**: Verify proper handling of concurrent operations
- **Error Handling Tests**: Test graceful handling of error conditions across component boundaries

### Integration Test Environment

Integration tests use a temporary directory for data storage to avoid interfering with the actual data directory. The test environment:

1. Creates a unique database and table for each test
2. Uses real Storage, Network, and other components
3. Verifies actual file creation and content
4. Cleans up all resources after each test

### Running Integration Tests

Integration tests can be run using Maven:

```
mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest
```

Or using the provided scripts with a new integration option:

```
./run_storage_scheduler_tests.sh --integration
```

### Integration Test Best Practices

When extending or modifying integration tests:

1. **Use Real Components**: Avoid mocking components in integration tests
2. **Test Complete Workflows**: Focus on end-to-end scenarios
3. **Verify File System State**: Check that files are created with correct content
4. **Test Error Conditions**: Verify proper error handling across component boundaries
5. **Clean Up Resources**: Ensure all resources are properly cleaned up after tests
6. **Isolate Tests**: Each test should be independent and not rely on state from other tests

## CI/CD Integration

### CI/CD Pipeline Configuration

The StorageScheduler tests are fully integrated into the project's CI/CD pipeline to ensure code quality and prevent regressions. The integration includes:

1. **Fast Tests in Fast Tests Workflow**:
   - StorageScheduler fast tests are run as part of the `fast-tests.yml` workflow
   - Tests run in parallel with other component tests
   - Results are included in the overall coverage report

2. **Dedicated StorageScheduler Tests Workflow**:
   - Located in `.github/workflows/storage-scheduler-tests.yml`
   - Triggered on changes to StorageScheduler code or tests
   - Runs comprehensive, integration, and mutation tests
   - Enforces coverage thresholds (90% line, 85% branch, 100% method)
   - Publishes detailed test reports

3. **Basic Tests in Main CI Workflow**:
   - Fast StorageScheduler tests are run as part of the main CI workflow
   - Ensures basic functionality is maintained with every commit

### CI/CD Quality Gates

The CI/CD pipeline enforces several quality gates for StorageScheduler tests:

1. **Coverage Thresholds**:
   - Line coverage: >90%
   - Branch coverage: >85%
   - Method coverage: 100%
   - Mutation coverage: >85%

2. **Test Success Rate**:
   - All tests must pass for the workflow to succeed
   - Test results are published for easy review

3. **Static Analysis**:
   - Code is checked with SpotBugs, PMD, and Checkstyle
   - Must pass all static analysis checks

### Interpreting CI/CD Test Results

When a CI/CD workflow runs StorageScheduler tests, the following artifacts are produced:

1. **Test Reports**:
   - Located in the "Artifacts" section of the workflow run
   - Contains detailed test execution results
   - Shows which tests passed or failed

2. **Coverage Reports**:
   - HTML reports showing line-by-line coverage
   - Summary metrics for line, branch, and method coverage
   - Highlights uncovered code sections

3. **Mutation Test Reports**:
   - Shows which mutations were killed or survived
   - Provides mutation score as a percentage
   - Helps identify weaknesses in the test suite

### Troubleshooting CI/CD Test Failures

If StorageScheduler tests fail in the CI/CD pipeline:

1. **Check Test Reports**:
   - Download and review the test reports artifact
   - Identify which specific tests failed
   - Look for error messages and stack traces

2. **Check Coverage Reports**:
   - If tests pass but the workflow fails, it may be a coverage issue
   - Review the coverage report to identify uncovered code
   - Add tests for uncovered code paths

3. **Check Mutation Reports**:
   - If mutation testing fails, review surviving mutations
   - Add or improve tests to kill surviving mutations

4. **Local Reproduction**:
   - Run the failing tests locally using the provided scripts
   - Use the same command that the CI/CD workflow uses
   - Debug and fix the issues locally before pushing changes