#!/bin/bash

# Script to generate code coverage reports for the StorageScheduler tests
# This script provides options to generate reports in different formats (HTML, XML, CSV)
# and to focus on specific test types (all, fast, comprehensive)

# Default values
FORMAT="html"
TEST_TYPE="all"
OPEN_REPORT=false
SHOW_HELP=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        -f|--format)
            FORMAT="$2"
            shift
            shift
            ;;
        -t|--test-type)
            TEST_TYPE="$2"
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

# Show help if requested or no options provided
if [ "$SHOW_HELP" = true ]; then
    echo "Usage: ./generate_coverage_report.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -f, --format FORMAT    Report format: html, xml, csv (default: html)"
    echo "  -t, --test-type TYPE   Test type: all, fast, comprehensive (default: all)"
    echo "  -o, --open             Open the report after generation"
    echo "  -h, --help             Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./generate_coverage_report.sh"
    echo "  ./generate_coverage_report.sh --format xml"
    echo "  ./generate_coverage_report.sh --test-type fast --open"
    echo "  ./generate_coverage_report.sh --format csv --test-type comprehensive"
    exit 0
fi

# Function to display header
display_header() {
    echo "========================================================"
    echo "  $1"
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

# Display header
display_header "Generating StorageScheduler Coverage Report"

# Set Maven goals based on format
case $FORMAT in
    html)
        REPORT_GOAL="jacoco:report"
        REPORT_PATH="target/site/jacoco/index.html"
        ;;
    xml)
        REPORT_GOAL="jacoco:report"
        REPORT_PATH="target/site/jacoco/jacoco.xml"
        ;;
    csv)
        REPORT_GOAL="jacoco:report"
        REPORT_PATH="target/site/jacoco/jacoco.csv"
        ;;
    *)
        echo "Invalid format: $FORMAT. Using HTML format."
        REPORT_GOAL="jacoco:report"
        REPORT_PATH="target/site/jacoco/index.html"
        ;;
esac

# Set test class based on test type
case $TEST_TYPE in
    all)
        TEST_CLASSES="com.ataiva.serengeti.unit.storage.StorageSchedulerTest,com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest"
        ;;
    fast)
        TEST_CLASSES="com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest"
        ;;
    comprehensive)
        TEST_CLASSES="com.ataiva.serengeti.unit.storage.StorageSchedulerTest"
        ;;
    *)
        echo "Invalid test type: $TEST_TYPE. Using all tests."
        TEST_CLASSES="com.ataiva.serengeti.unit.storage.StorageSchedulerTest,com.ataiva.serengeti.storage.StorageSchedulerFastTest,com.ataiva.serengeti.storage.lsm.StorageSchedulerFastTest"
        ;;
esac

# Run tests with coverage
echo "Running StorageScheduler tests with coverage analysis..."
mvn clean test -Dtest=$TEST_CLASSES jacoco:prepare-agent $REPORT_GOAL

# Store the exit code
status=$?

# Display summary
display_summary $status

# Display report location
if [ $status -eq 0 ]; then
    echo "Coverage report generated at: $REPORT_PATH"
    
    # Display coverage summary
    echo ""
    echo "Coverage Summary:"
    echo "----------------"
    
    if [ "$FORMAT" = "html" ]; then
        # Extract coverage data from the HTML report
        LINE_COVERAGE=$(grep -A1 "Lines:" target/site/jacoco/index.html | tail -n1 | grep -o '[0-9]*%' | head -1)
        BRANCH_COVERAGE=$(grep -A1 "Branches:" target/site/jacoco/index.html | tail -n1 | grep -o '[0-9]*%' | head -1)
        
        echo "Line Coverage: $LINE_COVERAGE"
        echo "Branch Coverage: $BRANCH_COVERAGE"
        
        # Check if coverage meets targets
        LINE_VALUE=${LINE_COVERAGE/\%/}
        BRANCH_VALUE=${BRANCH_COVERAGE/\%/}
        
        if [ "$LINE_VALUE" -lt 90 ]; then
            echo "⚠️ Line coverage is below target (90%)"
        else
            echo "✅ Line coverage meets or exceeds target (90%)"
        fi
        
        if [ "$BRANCH_VALUE" -lt 85 ]; then
            echo "⚠️ Branch coverage is below target (85%)"
        else
            echo "✅ Branch coverage meets or exceeds target (85%)"
        fi
    fi
    
    # Open the report if requested
    if [ "$OPEN_REPORT" = true ] && [ "$FORMAT" = "html" ]; then
        echo ""
        echo "Opening coverage report..."
        
        # Detect OS and use appropriate open command
        case "$(uname -s)" in
            Darwin*)    # macOS
                open $REPORT_PATH
                ;;
            Linux*)     # Linux
                if command -v xdg-open > /dev/null; then
                    xdg-open $REPORT_PATH
                elif command -v gnome-open > /dev/null; then
                    gnome-open $REPORT_PATH
                else
                    echo "Could not detect a program to open the report."
                    echo "Please open it manually at: $REPORT_PATH"
                fi
                ;;
            *)          # Other OS
                echo "Could not detect OS. Please open the report manually at: $REPORT_PATH"
                ;;
        esac
    fi
    
    echo ""
    echo "To view the detailed coverage report, open the following file in a browser:"
    echo "$REPORT_PATH"
fi

# Exit with the appropriate status code
exit $status