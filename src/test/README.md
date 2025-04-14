# Serengeti Test Suite

This directory contains a comprehensive test suite for the Serengeti autonomous distributed database system. The test suite is designed to achieve 100% line coverage and ensure that all functionality is thoroughly tested to prevent regression issues during the implementation of Serengeti 2.0.

## Test Structure

The test suite is organized into the following categories:

### Unit Tests

Unit tests test individual classes and methods in isolation. They are located in the `com.ataiva.serengeti.unit` package.

- **Storage Tests**: Test the Storage component, which is responsible for managing databases, tables, and data.
- **Network Tests**: Test the Network component, which is responsible for node discovery and communication.
- **Query Tests**: Test the QueryEngine component, which is responsible for parsing and executing queries.
- **Server Tests**: Test the Server component, which is responsible for handling HTTP requests.

### Integration Tests

Integration tests test the interaction between multiple components. They are located in the `com.ataiva.serengeti.integration` package.

- **Storage-Network Integration Tests**: Test the interaction between the Storage and Network components, particularly for data replication.

### System Tests

System tests test the entire system as a whole. They are located in the `com.ataiva.serengeti.system` package.

- **Serengeti System Tests**: Test the entire Serengeti system by starting a complete instance and testing it as a black box.

### Performance Tests

Performance tests measure the performance of the system under load. They are located in the `com.ataiva.serengeti.performance` package.

- **Storage Performance Tests**: Measure the performance of the Storage component for various operations.

## Test Utilities

The test suite includes several utility classes to help with testing:

- **TestBase**: A base class for all tests that provides common setup and teardown functionality.
- **NetworkTestUtils**: Utilities for testing network functionality.
- **StorageTestUtils**: Utilities for testing storage functionality.
- **QueryTestUtils**: Utilities for testing query functionality.

## Mock Classes

The test suite includes mock implementations of key components to facilitate testing:

- **MockNetwork**: A mock implementation of the Network class.
- **MockStorage**: A mock implementation of the Storage class.
- **MockServer**: A mock implementation of the Server class.

## Running the Tests

### Running All Tests

To run all tests, use the following Maven command:

```bash
mvn test
```

### Running Specific Test Categories

To run only unit tests:

```bash
mvn test -Dtest=com.ataiva.serengeti.unit.**.*Test
```

To run only integration tests:

```bash
mvn test -Dtest=com.ataiva.serengeti.integration.**.*Test
```

To run only system tests:

```bash
mvn test -Dtest=com.ataiva.serengeti.system.**.*Test
```

To run only performance tests:

```bash
mvn test -Dtest=com.ataiva.serengeti.performance.**.*Test
```

### Running Specific Tests

To run a specific test class:

```bash
mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageComprehensiveTest
```

To run a specific test method:

```bash
mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageComprehensiveTest#testCreateDatabaseWithValidName
```

## Code Coverage

The test suite is designed to achieve 100% line coverage. To generate a code coverage report, use the following Maven command:

```bash
mvn test jacoco:report
```

The coverage report will be generated in the `target/site/jacoco` directory. Open the `index.html` file in a web browser to view the report.

## Continuous Integration

The test suite is integrated with the CI/CD pipeline. The following tests are run at different stages:

- **Unit tests**: Run on every commit.
- **Integration tests**: Run on every pull request.
- **System and performance tests**: Run nightly.

## Adding New Tests

When adding new functionality to Serengeti, make sure to add corresponding tests to maintain 100% line coverage. Follow these guidelines:

1. **Unit Tests**: Add unit tests for new classes and methods.
2. **Integration Tests**: Add integration tests for interactions between components.
3. **System Tests**: Update system tests to cover new functionality.
4. **Performance Tests**: Add performance tests for performance-critical functionality.

## Test-Driven Development

For new features in Serengeti 2.0, we adopt a test-driven development (TDD) approach:

1. Write tests for the new feature before implementation.
2. Implement the feature to pass the tests.
3. Refactor the code while maintaining test coverage.

This approach ensures that new features are well-tested from the beginning and helps maintain the 100% coverage goal.