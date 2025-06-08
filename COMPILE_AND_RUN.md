# Compiling and Running Serengeti

This document provides instructions on how to compile and run the Serengeti database system.

## Prerequisites

- Java JDK 11 or higher
- Maven 3.6 or higher
- Docker and Docker Compose (optional, for containerized deployment)

## Compiling the Project

### Using Maven

To compile the project using Maven, run the following command from the project root directory:

```bash
mvn clean package
```

This will:
1. Clean any previous build artifacts
2. Compile the source code
3. Run the tests
4. Package the application into a JAR file with dependencies

If you want to skip the tests during compilation, use:

```bash
mvn clean package -DskipTests
```

The compiled JAR file will be available at `target/Serengeti-<version>-jar-with-dependencies.jar`, where `<version>` corresponds to the current version of the project.

## Versioning

Serengeti uses semantic versioning (MAJOR.MINOR.PATCH). The current version can be found in the `version.txt` file in the project root directory. This is the source of truth for the current version of the application.

## Running the Application

### Using Java

To run the application directly using Java:

```bash
java -jar target/Serengeti-<version>-jar-with-dependencies.jar
```

Where `<version>` is the version number from the version.txt file.

### Using Docker

To build and run the application using Docker:

```bash
docker build -t serengeti .
docker run -p 1985:1985 serengeti
```

### Using Docker Compose

To run the application using Docker Compose:

```bash
docker-compose up -d
```

This will:
1. Build the Docker image if it doesn't exist
2. Start the Serengeti container in detached mode
3. Map port 1985 to your host machine
4. Create a persistent volume for data storage

To stop the application:

```bash
docker-compose down
```

## Running Tests

### Running All Tests

To run all tests:

```bash
mvn test
```

### Running Fast Tests Only

To run only the fast tests:

```bash
./run_fast_tests.sh
```

Or on Windows:

```bash
run_fast_tests.bat
```

## Troubleshooting

If you encounter any issues with the compilation or running of the application, please check the following:

1. Ensure you have the correct Java version installed
2. Verify that Maven is correctly installed and configured
3. Check that all dependencies are available in your Maven repository
4. Ensure that port 1985 is not already in use by another application