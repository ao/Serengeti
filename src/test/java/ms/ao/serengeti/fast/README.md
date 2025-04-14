# Serengeti Fast Test Suite

This directory contains a fast test suite for the Serengeti autonomous distributed database system. The fast test suite is designed to run quickly (under 2 minutes) and provide rapid feedback during development.

## Overview

The fast test suite focuses on unit tests with minimal setup and dependencies. It uses lightweight test bases that initialize only the components needed for each test, rather than setting up the entire system.

## Test Structure

The fast test suite is organized as follows:

- **Base Classes**: Lightweight test base classes that provide minimal setup
  - `LightweightTestBase`: Base class for all fast tests
  - `StorageFastTestBase`: Base class for Storage tests
  - `LSMFastTestBase`: Base class for LSM storage engine tests
  - `NetworkFastTestBase`: Base class for Network tests
  - `QueryFastTestBase`: Base class for Query tests
  - `ServerFastTestBase`: Base class for Server tests

- **Test Classes**: Fast tests for each component
  - `StorageFastTest`: Fast tests for the Storage component
  - `LSMStorageEngineFastTest`: Fast tests for the LSM storage engine
  - `MemTableFastTest`: Fast tests for the MemTable component
  - `SSTableFastTest`: Fast tests for the SSTable component
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

Or use the provided scripts to run specific component tests:

```bash
# Run all fast tests
./run_fast_tests.sh

# Run only LSM storage engine fast tests
mvn test -Dtest=ms.ao.serengeti.storage.lsm.*FastTest
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

## CI/CD Integration

The fast test suite is integrated with the CI/CD pipeline using GitHub Actions. The workflow is defined in `.github/workflows/fast-tests.yml` and includes:

1. **Parallel Test Execution**: Tests are run in parallel using GitHub Actions matrix strategy
2. **Code Coverage**: JaCoCo is used to generate code coverage reports
3. **Test Results Publishing**: Test results are published for easy viewing
4. **Coverage Check**: The workflow checks that code coverage is at least 80%

The workflow runs on every push to main, master, and develop branches, as well as on pull requests to these branches.

### Automated Release Process

The fast test suite is part of a comprehensive CI/CD pipeline that includes automatic releases to DockerHub. The release process is defined in `.github/workflows/release.yml` and includes:

1. **Automatic Triggering**: The release workflow is triggered automatically after a successful Maven CI workflow run on the main or master branch.
2. **Docker Image Building**: The workflow builds a Docker image from the Dockerfile in the repository.
3. **DockerHub Publishing**: The Docker image is pushed to DockerHub with appropriate tags based on the version and commit SHA.
4. **GitHub Release Creation**: A GitHub release is created with the JAR file attached.

This ensures that once all tests pass (including the fast tests), a new release is automatically created and pushed to DockerHub, making it available for deployment.

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
6. **Temporary Directories**: Use temporary directories for tests that require file system operations
7. **Small Data Sets**: Use small data sets for testing

## LSM Storage Engine Fast Tests

The LSM storage engine fast tests focus on testing the core functionality of the LSM storage engine components:

1. **LSMStorageEngine**: Tests basic operations like put, get, and delete
2. **MemTable**: Tests in-memory operations and size tracking
3. **SSTable**: Tests on-disk operations with temporary directories

These tests use the `LSMFastTestBase` class, which provides a lightweight setup for LSM storage engine tests.