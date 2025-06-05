#!/bin/bash

# Script to run chaos tests for the StorageScheduler component
# This script provides options to focus on specific chaos scenarios
# and to set different levels of chaos intensity.

# Default values
COMPONENT="storage-scheduler"
SCENARIO="all"
INTENSITY="medium"
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
        -s|--scenario)
            SCENARIO="$2"
            shift
            shift
            ;;
        -i|--intensity)
            INTENSITY="$2"
            shift
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
    echo "Usage: ./run_chaos_tests.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -c, --component COMPONENT  Component to test (default: storage-scheduler)"
    echo "                             Available components: storage-scheduler, all"
    echo "  -s, --scenario SCENARIO    Chaos scenario to test (default: all)"
    echo "                             Available scenarios: disk-failure, network-outage,"
    echo "                             resource-constraint, thread-interruption,"
    echo "                             unexpected-exception, combined, all"
    echo "  -i, --intensity INTENSITY  Chaos intensity level (default: medium)"
    echo "                             Available levels: low, medium, high"
    echo "  -h, --help                 Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_chaos_tests.sh"
    echo "  ./run_chaos_tests.sh --component storage-scheduler"
    echo "  ./run_chaos_tests.sh --scenario disk-failure"
    echo "  ./run_chaos_tests.sh --intensity high"
    echo "  ./run_chaos_tests.sh --component storage-scheduler --scenario network-outage --intensity low"
    exit 0
fi

# Function to display test header
display_header() {
    echo "========================================================"
    echo "  Running $1"
    echo "========================================================"
    echo "Started at: $(date)"
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

# Record start time
start_time=$(date +%s)

# Display header
display_header "Chaos Tests for $COMPONENT (Scenario: $SCENARIO, Intensity: $INTENSITY)"

# Set Maven system properties based on parameters
MAVEN_PROPS="-Dchaos.component=$COMPONENT -Dchaos.scenario=$SCENARIO -Dchaos.intensity=$INTENSITY"

# Run the appropriate tests based on component
if [ "$COMPONENT" = "storage-scheduler" ] || [ "$COMPONENT" = "all" ]; then
    # Run StorageScheduler chaos tests
    mvn test -Dtest=com.ataiva.serengeti.chaos.StorageSchedulerChaosTest -Pchaos-testing $MAVEN_PROPS
    status=$?
    
    # Display summary
    display_summary $status
    
    # Exit with the appropriate status code
    exit $status
else
    echo "Error: Unknown component '$COMPONENT'"
    echo "Available components: storage-scheduler, all"
    exit 1
fi