# Serengeti Fast Test Suite

This directory contains a fast test suite for the Serengeti autonomous distributed database system. The fast test suite is designed to run quickly (under 2 minutes) and provide rapid feedback during development.

## Overview

The fast test suite focuses on unit tests with minimal setup and dependencies. It uses lightweight test bases that initialize only the components needed for each test, rather than setting up the entire system.

## Test Structure

The fast test suite is organized as follows:

- **Base Classes**: Lightweight test base classes that provide minimal setup
  - `LightweightTestBase`: Base class for all fast tests
  - `StorageFastTestBase`: Base class for Storage tests
  - `NetworkFastTestBase`: Base class for Network tests
  - `QueryFastTestBase`: Base class for Query tests
  - `ServerFastTestBase`: Base class for Server tests

- **Test Classes**: Fast tests for each component
  - `StorageFastTest`: Fast tests for the Storage component
  - `NetworkFastTest`: Fast tests for the Network component
  - `QueryFastTest`: Fast tests for the Query component
  - `ServerFastTest`: Fast tests for the Server component

- **Mock Implementations**: Lightweight mock implementations
  - `InMemoryStorage`: In-memory implementation of the Storage class

## Running the Fast Test Suite

To run the fast test suite, use the following Maven command:

```bash
mvn test -Pfast-tests
```

This will run all tests tagged with `@Tag("fast")` in parallel with 4 threads.

## Running Specific Fast Tests

To run specific fast tests, use the following Maven command:

```bash
mvn test -Pfast-tests -Dtest=StorageFastTest
```

## Benefits

The fast test suite provides several benefits:

1. **Quick Feedback**: Tests run in under 2 minutes, providing rapid feedback during development
2. **Focused Testing**: Tests focus on core functionality without unnecessary setup
3. **Parallel Execution**: Tests run in parallel for faster completion
4. **Reduced Resource Usage**: Tests use minimal resources, making them suitable for development machines

## Relationship with Comprehensive Test Suite

The fast test suite complements the comprehensive test suite, which provides more thorough testing but takes longer to run. The comprehensive test suite should still be used for:

1. **Integration Testing**: Testing interactions between components
2. **System Testing**: Testing the entire system as a black box
3. **Performance Testing**: Measuring system performance under load
4. **Fault Tolerance Testing**: Testing system behavior under failure conditions

## Adding New Fast Tests

When adding new functionality to Serengeti, consider adding fast tests to provide rapid feedback during development. Follow these guidelines:

1. **Use Lightweight Test Bases**: Extend the appropriate lightweight test base
2. **Focus on Core Functionality**: Test only the most important aspects of the component
3. **Minimize Dependencies**: Use mocks and stubs to avoid dependencies on other components
4. **Tag Tests with `@Tag("fast")`**: Ensure tests are included in the fast test suite
5. **Keep Tests Fast**: Ensure tests run quickly and don't perform unnecessary operations

## Implementation Details

The fast test suite uses several techniques to improve performance:

1. **Lightweight Test Bases**: Minimal setup for each test
2. **In-Memory Storage**: Avoid file system operations
3. **Mocked Dependencies**: Avoid network and other external dependencies
4. **Parallel Execution**: Run tests concurrently
5. **Focused Test Scope**: Test only core functionality