#!/bin/bash

# Script to run the StorageScheduler tests for Serengeti
# This script provides options to run all StorageScheduler tests, only fast tests,
# or only comprehensive tests.

# Default values
RUN_ALL=false
RUN_FAST=false
RUN_COMPREHENSIVE=false
SHOW_HELP=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        -a|--all)
            RUN_ALL=true
            shift
            ;;
        -f|--fast)
            RUN_FAST=true
            shift
            ;;
        -c|--comprehensive)
            RUN_COMPREHENSIVE=true
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

# Show help if requested or no options provided
if [ "$SHOW_HELP" = true ] || [ "$RUN_ALL" = false ] && [ "$RUN_FAST" = false ] && [ "$RUN_COMPREHENSIVE" = false ]; then
    echo "Usage: ./run_storage_scheduler_tests.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -a, --all            Run all StorageScheduler tests"
    echo "  -f, --fast           Run only fast StorageScheduler tests"
    echo "  -c, --comprehensive  Run only comprehensive StorageScheduler tests"
    echo "  -h, --help           Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_storage_scheduler_tests.sh --all"
    echo "  ./run_storage_scheduler_tests.sh --fast"
    echo "  ./run_storage_scheduler_tests.sh --comprehensive"
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

# Run tests based on options
if [ "$RUN_ALL" = true ] || [ "$RUN_COMPREHENSIVE" = true ]; then
    display_header "StorageScheduler Comprehensive Tests"
    
    # Run comprehensive tests
    mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest
    
    # Store the exit code
    comprehensive_status=$?
    
    # Display summary
    display_summary $comprehensive_status
    
    # Set overall status
    overall_status=$comprehensive_status
fi

if [ "$RUN_ALL" = true ] || [ "$RUN_FAST" = true ]; then
    display_header "StorageScheduler Fast Tests"
    
    # Run fast tests
    mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
    
    # Store the exit code
    fast_status=$?
    
    # Display summary
    display_summary $fast_status
    
    # Update overall status
    if [ "$RUN_ALL" = true ]; then
        if [ $fast_status -ne 0 ]; then
            overall_status=$fast_status
        fi
    else
        overall_status=$fast_status
    fi
fi

# Display overall summary if running all tests
if [ "$RUN_ALL" = true ]; then
    echo "========================================================"
    echo "  Overall Test Summary"
    echo "========================================================"
    
    local end_time=$(date +%s)
    local total_execution_time=$((end_time - start_time))
    
    echo "Total execution time: $total_execution_time seconds"
    
    if [ $overall_status -eq 0 ]; then
        echo "Overall Status: SUCCESS ✅"
    else
        echo "Overall Status: FAILED ❌"
    fi
    echo "========================================================"
fi

# Exit with the appropriate status code
exit $overall_status