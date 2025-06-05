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

### Test Coverage Targets

The testing strategy aims to achieve:

- **Line Coverage**: >90% of code lines executed during tests
- **Branch Coverage**: >85% of conditional branches exercised
- **Method Coverage**: 100% of public methods tested
- **Scenario Coverage**: All identified use cases and edge cases tested
- **Error Handling**: All error paths exercised

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
  -h, --help           Show this help message
```

Examples:
```
./run_storage_scheduler_tests.sh --all
./run_storage_scheduler_tests.sh --fast
./run_storage_scheduler_tests.sh --comprehensive
```

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