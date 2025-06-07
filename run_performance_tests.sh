#!/bin/bash

# Script to run performance tests for Serengeti
# This script is designed to be used in CI/CD pipelines to detect performance regressions

# Default values
COMPONENT="all"
HISTORY_DIR="benchmark-history"
REPORTS_DIR="benchmark-reports"
THRESHOLD=10.0
FAIL_ON_REGRESSION=true
GENERATE_HTML=true
COMPARE_WITH_BASELINE=true
SHOW_HELP=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        -c|--component)
            COMPONENT="$2"
            shift
            shift
            ;;
        -h|--history-dir)
            HISTORY_DIR="$2"
            shift
            shift
            ;;
        -r|--reports-dir)
            REPORTS_DIR="$2"
            shift
            shift
            ;;
        -t|--threshold)
            THRESHOLD="$2"
            shift
            shift
            ;;
        -n|--no-fail)
            FAIL_ON_REGRESSION=false
            shift
            ;;
        --no-html)
            GENERATE_HTML=false
            shift
            ;;
        --no-compare)
            COMPARE_WITH_BASELINE=false
            shift
            ;;
        --help)
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
    echo "Usage: ./run_performance_tests.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -c, --component COMPONENT    Component to test (default: all)"
    echo "                               Available components: storage-engine, all"
    echo "  -h, --history-dir DIR        Directory for historical benchmark results (default: benchmark-history)"
    echo "  -r, --reports-dir DIR        Directory for benchmark reports (default: benchmark-reports)"
    echo "  -t, --threshold VALUE        Regression threshold percentage (default: 10.0)"
    echo "  -n, --no-fail                Don't fail the build on regression"
    echo "  --no-html                    Don't generate HTML reports"
    echo "  --no-compare                 Don't compare with baseline"
    echo "  --help                       Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_performance_tests.sh"
    echo "  ./run_performance_tests.sh --component storage-engine"
    echo "  ./run_performance_tests.sh --threshold 5.0"
    echo "  ./run_performance_tests.sh --no-fail"
    exit 0
fi

# Function to display header
display_header() {
    echo "========================================================"
    echo "  Running Performance Tests: $1"
    echo "========================================================"
    echo "Started at: $(date)"
    echo "--------------------------------------------------------"
    echo "Component: $COMPONENT"
    echo "History Directory: $HISTORY_DIR"
    echo "Reports Directory: $REPORTS_DIR"
    echo "Regression Threshold: $THRESHOLD%"
    echo "Fail on Regression: $FAIL_ON_REGRESSION"
    echo "Generate HTML Reports: $GENERATE_HTML"
    echo "Compare with Baseline: $COMPARE_WITH_BASELINE"
    echo "--------------------------------------------------------"
}

# Function to display summary
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

# Record start time
start_time=$(date +%s)

# Create directories if they don't exist
mkdir -p "$HISTORY_DIR"
mkdir -p "$REPORTS_DIR"

# Display header
display_header "Performance Tests"

# Run the performance tests using the Java application
if [ "$COMPONENT" = "all" ]; then
    # Run all components
    echo "Running performance tests for all components..."
    java -cp target/serengeti.jar com.ataiva.serengeti.performance.ci.ContinuousPerformanceTesting \
        "" "$HISTORY_DIR" "$REPORTS_DIR" "$THRESHOLD"
else
    # Run specific component
    echo "Running performance tests for component: $COMPONENT..."
    java -cp target/serengeti.jar com.ataiva.serengeti.performance.ci.ContinuousPerformanceTesting \
        "$COMPONENT" "$HISTORY_DIR" "$REPORTS_DIR" "$THRESHOLD"
fi

# Store the exit code
exit_code=$?

# Display summary
display_summary $exit_code

# If we're not failing on regression, always exit with success
if [ "$FAIL_ON_REGRESSION" = false ]; then
    exit 0
fi

# Otherwise, exit with the actual exit code
exit $exit_code