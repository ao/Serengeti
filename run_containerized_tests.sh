#!/bin/bash

# Script to run containerized tests for the StorageScheduler component
# This script provides options to run different types of tests in Docker containers

# Default values
TEST_TYPE="all"
PRESERVE_RESULTS=false
PRESERVE_LOGS=false
SHOW_HELP=false
PLATFORM="linux"
CLEAN_FIRST=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        -t|--test-type)
            TEST_TYPE="$2"
            shift
            shift
            ;;
        -p|--platform)
            PLATFORM="$2"
            shift
            shift
            ;;
        -r|--preserve-results)
            PRESERVE_RESULTS=true
            shift
            ;;
        -l|--preserve-logs)
            PRESERVE_LOGS=true
            shift
            ;;
        -c|--clean)
            CLEAN_FIRST=true
            shift
            ;;
        -h|--help)
            SHOW_HELP=true
            shift
            ;;
        *)
            echo "Unknown option: $key"
            SHOW_HELP=true
            shift
            ;;
    esac
done

# Show help if requested
if [ "$SHOW_HELP" = true ]; then
    echo "Usage: ./run_containerized_tests.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -t, --test-type TYPE    Type of tests to run (default: all)"
    echo "                          Available types: all, unit, fast, integration,"
    echo "                          property, benchmark, chaos, mutation"
    echo "  -p, --platform PLATFORM Platform to run tests on (default: linux)"
    echo "                          Available platforms: linux, windows"
    echo "  -r, --preserve-results  Preserve test results after completion"
    echo "  -l, --preserve-logs     Preserve container logs after completion"
    echo "  -c, --clean             Clean previous test results before running"
    echo "  -h, --help              Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_containerized_tests.sh"
    echo "  ./run_containerized_tests.sh --test-type fast"
    echo "  ./run_containerized_tests.sh --test-type unit --preserve-results"
    echo "  ./run_containerized_tests.sh --test-type all --platform windows"
    echo "  ./run_containerized_tests.sh --test-type benchmark --preserve-logs"
    exit 0
fi

# Function to display test header
display_header() {
    echo "========================================================"
    echo "  Running $1 Tests in Container"
    echo "========================================================"
    echo "Started at: $(date)"
    echo "Platform: $PLATFORM"
    echo "--------------------------------------------------------"
}

# Function to display test summary
display_summary() {
    local status=$1
    local end_time=$(date +%s)
    local execution_time=$((end_time - start_time))
    
    echo "--------------------------------------------------------"
    echo "Finished at: $(date)"
    echo "Execution time: $execution_time seconds"
    
    if [ $status -eq 0 ]; then
        echo "Status: SUCCESS ✅"
    else
        echo "Status: FAILED ❌"
    fi
    echo "========================================================"
    echo ""
}

# Clean previous test results if requested
if [ "$CLEAN_FIRST" = true ]; then
    echo "Cleaning previous test results..."
    rm -rf ./test-results/*
    mkdir -p ./test-results
fi

# Create test results directory if it doesn't exist
mkdir -p ./test-results

# Record start time
start_time=$(date +%s)

# Determine the service name based on test type
case $TEST_TYPE in
    all)
        SERVICE_NAME="all-tests"
        ;;
    unit)
        SERVICE_NAME="unit-tests"
        ;;
    fast)
        SERVICE_NAME="fast-tests"
        ;;
    integration)
        SERVICE_NAME="integration-tests"
        ;;
    property)
        SERVICE_NAME="property-tests"
        ;;
    benchmark)
        SERVICE_NAME="benchmark-tests"
        ;;
    chaos)
        SERVICE_NAME="chaos-tests"
        ;;
    mutation)
        SERVICE_NAME="mutation-tests"
        ;;
    *)
        echo "Unknown test type: $TEST_TYPE"
        echo "Available types: all, unit, fast, integration, property, benchmark, chaos, mutation"
        exit 1
        ;;
esac

# Add platform-specific profile if needed
PLATFORM_PROFILE=""
if [ "$PLATFORM" = "windows" ]; then
    PLATFORM_PROFILE="--profile windows"
    if [ "$SERVICE_NAME" != "windows-tests" ]; then
        SERVICE_NAME="windows-tests"
        echo "Using windows-tests service for Windows platform"
    fi
fi

# Display header
display_header "$TEST_TYPE"

# Build and run the container
echo "Building and running Docker container for $TEST_TYPE tests..."
docker-compose -f docker-compose.test.yml build $SERVICE_NAME
docker-compose -f docker-compose.test.yml up $PLATFORM_PROFILE $SERVICE_NAME

# Get the exit code of the container
CONTAINER_EXIT_CODE=$(docker-compose -f docker-compose.test.yml ps -q $SERVICE_NAME | xargs docker inspect -f '{{.State.ExitCode}}')

# Display summary
display_summary $CONTAINER_EXIT_CODE

# Save logs if requested
if [ "$PRESERVE_LOGS" = true ]; then
    echo "Saving container logs to ./test-results/$TEST_TYPE-logs.txt"
    docker-compose -f docker-compose.test.yml logs $SERVICE_NAME > ./test-results/$TEST_TYPE-logs.txt
fi

# Clean up containers if not preserving results
if [ "$PRESERVE_RESULTS" = false ]; then
    echo "Cleaning up containers..."
    docker-compose -f docker-compose.test.yml down
else
    echo "Containers preserved. Use 'docker-compose -f docker-compose.test.yml down' to clean up."
fi

# Display results location
echo "Test results are available in ./test-results/$TEST_TYPE/"

# Exit with the container's exit code
exit $CONTAINER_EXIT_CODE