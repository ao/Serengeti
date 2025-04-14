# Serengeti CI/CD Workflows

This directory contains GitHub Actions workflows for Serengeti's continuous integration and continuous deployment.

## Workflows

### 1. Fast Tests Workflow

**File:** `fast-tests.yml`

This workflow runs the fast test suite on every push to main, master, and develop branches, as well as on pull requests to these branches.

#### Features:

1. **Parallel Test Execution**: Tests are run in parallel using GitHub Actions matrix strategy, with each component running in a separate job:
   - Storage LSM tests
   - Network tests
   - Query tests
   - Server tests

2. **Code Coverage**: JaCoCo is used to generate code coverage reports, which are uploaded as artifacts.

3. **Test Results Publishing**: Test results are published using the `EnricoMi/publish-unit-test-result-action` action.

4. **Coverage Check**: The workflow checks that code coverage is at least 80%.

#### How to Use:

The workflow runs automatically on push and pull request events. You can also run it manually from the GitHub Actions tab.

### 2. Main CI Workflow

**File:** `main.yml`

This workflow runs static code analysis tools on every push to main, master, and develop branches, as well as on pull requests to these branches.

#### Features:

1. **SpotBugs Integration**: Runs SpotBugs static code analysis to identify potential bugs.
2. **PMD Integration**: Runs PMD static code analysis to identify code quality issues.
3. **Checkstyle Integration**: Runs Checkstyle to ensure code style consistency.
4. **JDK 11 Setup**: Sets up JDK 11 for the build.

### 3. Maven CI Workflow

**File:** `maven.yml`

This workflow builds the project with Maven and runs all tests on every push to main, master, and develop branches, as well as on pull requests to these branches.

#### Features:

1. **Maven Build**: Builds the project with Maven.
2. **Test Execution**: Runs all tests.
3. **Test Results Upload**: Uploads test results as artifacts.

### 4. Release Workflow

**File:** `release.yml`

This workflow builds and pushes a Docker image to DockerHub when all tests pass on the main or master branch.

#### Features:

1. **Automatic Triggering**: Runs automatically after successful completion of the Maven CI workflow.
2. **Docker Image Building**: Builds a Docker image from the Dockerfile.
3. **DockerHub Publishing**: Pushes the Docker image to DockerHub with appropriate tags.
4. **GitHub Release Creation**: Creates a GitHub release with the JAR file attached.
5. **Manual Triggering**: Can also be triggered manually from the GitHub Actions tab.

#### How to Use:

The workflow runs automatically after a successful Maven CI workflow run on the main or master branch. You need to set up the following secrets in your GitHub repository:

- `DOCKERHUB_USERNAME`: Your DockerHub username
- `DOCKERHUB_TOKEN`: Your DockerHub access token

## How to Add a New Component to Fast Tests:

1. Add a new entry to the matrix in the `fast-tests.yml` file:

```yaml
strategy:
  matrix:
    component: [storage-lsm, network, query, server, your-new-component]
```

2. Add a new condition to the "Run Fast Tests" step:

```yaml
if [ "${{ matrix.component }}" == "your-new-component" ]; then
  mvn test -Dtest=ms.ao.serengeti.your.package.YourComponentFastTest
fi
```

## Best Practices

1. **Keep Tests Fast**: Fast tests should run in under 2 seconds to provide quick feedback.
2. **Run Tests in Parallel**: Use matrix strategy to run tests in parallel.
3. **Monitor Coverage**: Keep code coverage above 80%.
4. **Review Test Results**: Regularly review test results to identify flaky tests.
5. **Use Consistent JDK**: Use JDK 11 for all workflows to ensure consistency.
6. **Optimize Build Time**: Use caching to speed up builds.