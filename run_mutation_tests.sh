#!/bin/bash

# Script to run mutation tests for the StorageScheduler component
# This script provides options to run mutation tests with different configurations

# Default values
COMPONENT="storage-scheduler"
REPORT_FORMAT="HTML"
SHOW_HELP=false
OPEN_REPORT=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        -c|--component)
            COMPONENT="$2"
            shift
            shift
            ;;
        -f|--format)
            REPORT_FORMAT="$2"
            shift
            shift
            ;;
        -o|--open)
            OPEN_REPORT=true
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
    echo "Usage: ./run_mutation_tests.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -c, --component COMPONENT  Component to test (default: storage-scheduler)"
    echo "                             Available components: storage-scheduler, all"
    echo "  -f, --format FORMAT        Report format: HTML, XML (default: HTML)"
    echo "  -o, --open                 Open the HTML report after generation"
    echo "  -h, --help                 Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_mutation_tests.sh"
    echo "  ./run_mutation_tests.sh --component storage-scheduler"
    echo "  ./run_mutation_tests.sh --format XML"
    echo "  ./run_mutation_tests.sh --open"
    exit 0
fi

# Function to display header
display_header() {
    echo "========================================================"
    echo "  Running $1"
    echo "========================================================"
    echo "Started at: $(date)"
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

# Run mutation tests based on component
if [ "$COMPONENT" = "storage-scheduler" ]; then
    display_header "StorageScheduler Mutation Tests"
    
    # Run mutation tests for StorageScheduler
    mvn test -Pstorage-scheduler-mutation
    
    # Store the exit code
    mutation_status=$?
    
    # Display summary
    display_summary $mutation_status
    
    # Set overall status
    overall_status=$mutation_status
elif [ "$COMPONENT" = "all" ]; then
    display_header "All Components Mutation Tests"
    
    # Run mutation tests for all components
    mvn test -Pmutation
    
    # Store the exit code
    mutation_status=$?
    
    # Display summary
    display_summary $mutation_status
    
    # Set overall status
    overall_status=$mutation_status
else
    echo "Unknown component: $COMPONENT"
    echo "Available components: storage-scheduler, all"
    exit 1
fi

# Display report location
echo "Mutation test report generated at: target/pit-reports/YYYYMMDDHHMI/index.html"
echo "You can open this file in a browser to view the detailed mutation test report."
echo ""

# Open the report if requested
if [ "$OPEN_REPORT" = true ] && [ "$REPORT_FORMAT" = "HTML" ]; then
    # Find the latest report directory
    LATEST_REPORT=$(find target/pit-reports -type d -name "2*" | sort | tail -n 1)
    
    if [ -n "$LATEST_REPORT" ] && [ -f "$LATEST_REPORT/index.html" ]; then
        echo "Opening mutation test report..."
        
        # Try different commands to open the report based on the OS
        if command -v xdg-open &> /dev/null; then
            xdg-open "$LATEST_REPORT/index.html"
        elif command -v open &> /dev/null; then
            open "$LATEST_REPORT/index.html"
        else
            echo "Could not open the report automatically. Please open it manually."
        fi
    else
        echo "Could not find the mutation test report."
    fi
fi

# Exit with the appropriate status code
exit $overall_status