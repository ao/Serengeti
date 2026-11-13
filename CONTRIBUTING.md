# Contributing to Serengeti

Thank you for your interest in contributing to Serengeti! This document provides guidelines and instructions for contributing to the project.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Environment](#development-environment)
- [Contribution Workflow](#contribution-workflow)
- [Coding Standards](#coding-standards)
- [Testing Guidelines](#testing-guidelines)
- [Documentation Guidelines](#documentation-guidelines)
- [Issue Reporting](#issue-reporting)
- [Pull Requests](#pull-requests)
- [Review Process](#review-process)
- [Community](#community)

## Code of Conduct

By participating in this project, you agree to abide by our Code of Conduct. Please read and follow it to ensure a positive and respectful environment for everyone.

Key principles:
- Be respectful and inclusive
- Be collaborative
- Focus on what is best for the community
- Gracefully accept constructive criticism

## Getting Started

### Prerequisites

- JDK 17 or higher (CI runs Java 17 and 21)
- Maven 3.9 or higher
- Docker, for the container, quick start and example tests
- Git

### Setting Up the Development Environment

1. Fork the repository on GitHub
2. Clone your fork locally:
   ```
   git clone https://github.com/YOUR-USERNAME/serengeti.git
   ```
3. Add the upstream repository as a remote:
   ```
   git remote add upstream https://github.com/ao/serengeti.git
   ```
4. Create a branch for your work:
   ```
   git checkout -b feature/your-feature-name
   ```

## Development Environment

### Building the Project

```
mvn verify      # compiles, runs the unit and property tests
mvn package     # also builds target/Serengeti-<version>-jar-with-dependencies.jar
```

The version is the one in `pom.xml`, which stays at the next `-SNAPSHOT` version on main.

### Running the Project

```
java -jar target/Serengeti-2.0.0-SNAPSHOT-jar-with-dependencies.jar
```

A node listens on 127.0.0.1:1985 and keeps its data in `./data`. The first start needs an admin password;
the README's quick start shows how to set it. See docs/CONFIGURATION.md for the settings.

### IDE Setup

#### IntelliJ IDEA

1. Open IntelliJ IDEA
2. Select "Open" and navigate to the cloned repository
3. Import as a Maven project
4. Set up run configuration:
   - Main class: `com.ataiva.serengeti.Serengeti`
   - Classpath: `serengeti`

#### Eclipse

1. Open Eclipse
2. Select "Import" > "Existing Maven Projects"
3. Navigate to the cloned repository
4. Create a run configuration:
   - Main class: `com.ataiva.serengeti.Serengeti`

## Contribution Workflow

1. **Select an Issue**: Start by finding an issue to work on or creating a new one
2. **Create a Branch**: Create a branch for your work
3. **Implement Changes**: Make your changes following the coding standards
4. **Write Tests**: Add tests for your changes
5. **Update Documentation**: Update documentation as needed
6. **Run Tests**: Ensure all tests pass
7. **Submit Pull Request**: Create a pull request with your changes

### Branch Naming Convention

Use the following format for branch names:
- `feature/short-description` for new features
- `bugfix/short-description` for bug fixes
- `docs/short-description` for documentation changes
- `test/short-description` for test-related changes

### Commit Message Guidelines

Write clear, concise commit messages that explain the changes made:

```
[Component] Short description of the change

More detailed explanation if necessary. Keep lines wrapped at 72 characters.
Explain the problem that this commit is solving. Focus on why the
change is being made, rather than how.

Fixes #123
```

## Coding Standards

### Java Code Style

- Follow standard Java naming conventions
- Use 4 spaces for indentation (no tabs)
- Maximum line length of 100 characters
- Use meaningful variable and method names
- Add comments for complex logic
- Follow the principle of "clean code"

### Code Organization

- Keep classes focused on a single responsibility
- Organize related classes in the same package
- Use appropriate access modifiers
- Minimize dependencies between components

### Best Practices

- Write thread-safe code
- Handle exceptions appropriately
- Close resources properly
- Avoid premature optimization
- Write code that is easy to test

## Testing Guidelines

### Test Categories

Serengeti has four kinds of test:

1. Unit and property tests (`src/test/java`, JUnit 5 and jqwik): `mvn verify`, on Java 17 and 21.
2. Container tests (`tests/container/run.sh`): the image is started on a private Docker network and
   checked over HTTP, including setup, TLS, restarts and `kill -9` during writes. The script builds the
   image as `serengeti:it` unless `SKIP_BUILD=1` is set.
3. Quick start (`docs/quickstart.sh`): runs the commands in the README's Quick start section.
4. Examples (`examples/run.sh`): runs the curl, Python and JavaScript clients in their own containers.

Everything except the unit tests needs Docker. CI runs all four.

### Writing Tests

- Each feature should have corresponding tests
- Tests should be independent and repeatable
- Use meaningful test names that describe the scenario
- Follow the Arrange-Act-Assert pattern
- Mock external dependencies when appropriate

### Running Tests

```
# Run all unit and property tests
mvn verify

# Run one test class
mvn -Dtest=ParserTest test

# Run one test method
mvn -Dtest=ParserTest#createAndDropTable test
```

The container and example scripts run against the image; see the list above.

## Documentation Guidelines

### Code Documentation

- Add JavaDoc comments to all public classes and methods
- Explain parameters, return values, and exceptions
- Include examples for complex methods
- Document non-obvious behavior and edge cases

Example:
```java
/**
 * Retrieves data from the storage system.
 *
 * @param key The key to retrieve
 * @return The retrieved data, or null if not found
 * @throws StorageException If a storage error occurs
 */
public byte[] getData(String key) throws StorageException {
    // Implementation
}
```

### Project Documentation

- Keep README.md up to date
- Update architecture documentation when making significant changes
- Add user guides for new features
- Document API changes

### Documentation Format

- Use Markdown for all documentation
- Follow a consistent structure
- Include diagrams when helpful (using PlantUML or similar)
- Keep documentation concise and focused

## Issue Reporting

### Bug Reports

When reporting a bug, include:

1. **Title**: Clear, concise description of the issue
2. **Environment**: Serengeti version, JDK version, OS
3. **Steps to Reproduce**: Detailed steps to reproduce the issue
4. **Expected Behavior**: What you expected to happen
5. **Actual Behavior**: What actually happened
6. **Additional Context**: Logs, screenshots, etc.

### Feature Requests

When requesting a feature, include:

1. **Title**: Clear description of the feature
2. **Use Case**: Why the feature is needed
3. **Description**: Detailed description of the feature
4. **Alternatives**: Any alternatives you've considered
5. **Additional Context**: Any other relevant information

## Pull Requests

### Creating a Pull Request

1. Push your branch to your fork:
   ```
   git push origin feature/your-feature-name
   ```

2. Go to the original repository and create a pull request
3. Fill in the pull request template with:
   - Description of the changes
   - Related issue(s)
   - Testing performed
   - Documentation updates
   - Breaking changes (if any)

### Pull Request Checklist

Before submitting a pull request, ensure:

- [ ] Code follows the project's coding standards
- [ ] Tests have been added or updated
- [ ] All tests pass
- [ ] Documentation has been updated
- [ ] Code has been reviewed for security issues
- [ ] Commit messages follow guidelines
- [ ] Changes are focused on a single purpose

## Review Process

### Code Review

All contributions go through code review:

1. Maintainers will review your code
2. Automated checks will run (CI/CD)
3. Feedback may be provided requesting changes
4. Once approved, your code will be merged

### Review Criteria

Code is reviewed based on:

- Correctness: Does it work as intended?
- Quality: Is the code well-written and maintainable?
- Testing: Are there sufficient tests?
- Documentation: Is it well-documented?
- Performance: Are there any performance concerns?
- Security: Are there any security issues?

## Community

### Communication Channels

- **GitHub Issues**: For bug reports and feature requests
- **GitHub Discussions**: For general questions and discussions
- **Mailing List**: For broader announcements and discussions
- **Slack/Discord**: For real-time communication (if available)

### Recognition

Contributors are recognized in several ways:

- Listed in the CONTRIBUTORS.md file
- Mentioned in release notes for significant contributions
- Given maintainer status after consistent, quality contributions

## Thank You!

Your contributions help make Serengeti better for everyone. We appreciate your time and effort!