#!/bin/bash

# Script to run the StorageScheduler tests for Serengeti
# This script provides options to run all StorageScheduler tests, only fast tests,
# or only comprehensive tests.

# Default values
RUN_ALL=false
RUN_FAST=false
RUN_COMPREHENSIVE=false
RUN_INTEGRATION=false
WITH_COVERAGE=false
WITH_MUTATION=false
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
        -i|--integration)
            RUN_INTEGRATION=true
            shift
            ;;
        --coverage)
            WITH_COVERAGE=true
            shift
            ;;
        --mutation)
            WITH_MUTATION=true
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
if [ "$SHOW_HELP" = true ] || [ "$RUN_ALL" = false ] && [ "$RUN_FAST" = false ] && [ "$RUN_COMPREHENSIVE" = false ] && [ "$RUN_INTEGRATION" = false ]; then
    echo "Usage: ./run_storage_scheduler_tests.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -a, --all            Run all StorageScheduler tests"
    echo "  -f, --fast           Run only fast StorageScheduler tests"
    echo "  -c, --comprehensive  Run only comprehensive StorageScheduler tests"
    echo "  -i, --integration    Run only integration StorageScheduler tests"
    echo "  --coverage           Run tests with code coverage analysis"
    echo "  --mutation           Run tests with mutation testing"
    echo "  -h, --help           Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_storage_scheduler_tests.sh --all"
    echo "  ./run_storage_scheduler_tests.sh --fast"
    echo "  ./run_storage_scheduler_tests.sh --comprehensive"
    echo "  ./run_storage_scheduler_tests.sh --integration"
    echo "  ./run_storage_scheduler_tests.sh --all --coverage"
    echo "  ./run_storage_scheduler_tests.sh --fast --coverage"
    echo "  ./run_storage_scheduler_tests.sh --all --mutation"
    echo "  ./run_storage_scheduler_tests.sh --fast --mutation"
    echo "  ./run_storage_scheduler_tests.sh --all --coverage --mutation"
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
    if [ "$WITH_COVERAGE" = true ] && [ "$WITH_MUTATION" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest -Pjacoco,storage-scheduler-mutation
    elif [ "$WITH_COVERAGE" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest -Pjacoco
    elif [ "$WITH_MUTATION" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest -Pstorage-scheduler-mutation
    else
        mvn test -Dtest=com.ataiva.serengeti.unit.storage.StorageSchedulerTest
    fi
    
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
    if [ "$WITH_COVERAGE" = true ] && [ "$WITH_MUTATION" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest -Pjacoco,storage-scheduler-mutation
    elif [ "$WITH_COVERAGE" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest -Pjacoco
    elif [ "$WITH_MUTATION" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest -Pstorage-scheduler-mutation
    else
        mvn test -Dtest=com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest
    fi
    
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

# Run integration tests if requested
if [ "$RUN_ALL" = true ] || [ "$RUN_INTEGRATION" = true ]; then
    display_header "StorageScheduler Integration Tests"
    
    # Run integration tests
    if [ "$WITH_COVERAGE" = true ] && [ "$WITH_MUTATION" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest -Pjacoco,storage-scheduler-mutation
    elif [ "$WITH_COVERAGE" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest -Pjacoco
    elif [ "$WITH_MUTATION" = true ]; then
        mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest -Pstorage-scheduler-mutation
    else
        mvn test -Dtest=com.ataiva.serengeti.integration.StorageSchedulerIntegrationTest
    fi
    
    # Store the exit code
    integration_status=$?
    
    # Display summary
    display_summary $integration_status
    
    # Update overall status
    if [ "$RUN_ALL" = true ]; then
        if [ $integration_status -ne 0 ]; then
            overall_status=$integration_status
        fi
    else
        overall_status=$integration_status
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
    
    # Display coverage report location if coverage was enabled
    if [ "$WITH_COVERAGE" = true ]; then
        echo ""
        echo "Coverage report generated at: target/site/jacoco/index.html"
        echo "You can open this file in a browser to view the detailed coverage report."
        echo ""
    fi
    
    # Display mutation report location if mutation testing was enabled
    if [ "$WITH_MUTATION" = true ]; then
        echo ""
        echo "Mutation test report generated at: target/pit-reports/YYYYMMDDHHMI/index.html"
        echo "You can open this file in a browser to view the detailed mutation test report."
        echo ""
    fi
fi

# Exit with the appropriate status code
exit $overall_status