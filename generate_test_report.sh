#!/bin/bash

# Script to generate comprehensive test reports for the StorageScheduler component
# This script aggregates results from different test types and generates a unified report

# Default values
FORMAT="html"
OUTPUT_DIR="target/reports/storage-scheduler"
PUBLISH_DIR=""
DETAIL_LEVEL="standard"
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
        -o|--output-dir)
            OUTPUT_DIR="$2"
            shift
            shift
            ;;
        -p|--publish)
            PUBLISH_DIR="$2"
            shift
            shift
            ;;
        -d|--detail)
            DETAIL_LEVEL="$2"
            shift
            shift
            ;;
        --open)
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
    echo "Usage: ./generate_test_report.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -f, --format FORMAT      Report format: html, xml, json (default: html)"
    echo "  -o, --output-dir DIR     Directory to store the report (default: target/reports/storage-scheduler)"
    echo "  -p, --publish DIR        Directory to publish the report to (optional)"
    echo "  -d, --detail LEVEL       Detail level: minimal, standard, full (default: standard)"
    echo "      --open               Open the report after generation (HTML format only)"
    echo "  -h, --help               Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./generate_test_report.sh"
    echo "  ./generate_test_report.sh --format xml"
    echo "  ./generate_test_report.sh --detail full --open"
    echo "  ./generate_test_report.sh --publish /var/www/html/reports"
    exit 0
fi

# Function to display header
display_header() {
    echo "========================================================"
    echo "  StorageScheduler Test Report Generator"
    echo "========================================================"
    echo "Started at: $(date)"
    echo "Format: $FORMAT"
    echo "Detail Level: $DETAIL_LEVEL"
    echo "Output Directory: $OUTPUT_DIR"
    if [ -n "$PUBLISH_DIR" ]; then
        echo "Publish Directory: $PUBLISH_DIR"
    fi
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
display_header

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

# Set report file path based on format
TIMESTAMP=$(date +"%Y%m%d%H%M%S")
REPORT_FILE="$OUTPUT_DIR/storage-scheduler-report-$TIMESTAMP.$FORMAT"

echo "Collecting test results..."

# Run the report generator
mvn exec:java -Dexec.mainClass="com.ataiva.serengeti.report.StorageSchedulerReportGenerator" \
    -Dexec.args="--format $FORMAT --output $REPORT_FILE --detail $DETAIL_LEVEL"

# Store the exit code
status=$?

# Display summary
display_summary $status

# Publish the report if requested
if [ $status -eq 0 ] && [ -n "$PUBLISH_DIR" ]; then
    echo "Publishing report to $PUBLISH_DIR..."
    mkdir -p "$PUBLISH_DIR"
    
    # Copy the report file
    cp "$REPORT_FILE" "$PUBLISH_DIR/"
    
    # Copy additional resources for HTML reports
    if [ "$FORMAT" = "html" ]; then
        cp -r "$OUTPUT_DIR/resources" "$PUBLISH_DIR/" 2>/dev/null || true
    fi
    
    echo "Report published successfully."
fi

# Open the report if requested
if [ $status -eq 0 ] && [ "$OPEN_REPORT" = true ] && [ "$FORMAT" = "html" ]; then
    echo "Opening report..."
    
    # Detect OS and use appropriate open command
    case "$(uname -s)" in
        Darwin*)    # macOS
            open "$REPORT_FILE"
            ;;
        Linux*)     # Linux
            if command -v xdg-open > /dev/null; then
                xdg-open "$REPORT_FILE"
            elif command -v gnome-open > /dev/null; then
                gnome-open "$REPORT_FILE"
            else
                echo "Could not detect a program to open the report."
                echo "Please open it manually at: $REPORT_FILE"
            fi
            ;;
        *)          # Other OS
            echo "Could not detect OS. Please open the report manually at: $REPORT_FILE"
            ;;
    esac
fi

echo "Report generated at: $REPORT_FILE"

# Exit with the appropriate status code
exit $status