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

# Check if Maven is available
if command -v mvn &> /dev/null; then
    # Run the report generator using Maven
    mvn exec:java -Dexec.mainClass="com.ataiva.serengeti.report.StorageSchedulerReportGenerator" \
        -Dexec.classpathScope=test \
        -Dexec.args="--format $FORMAT --output $REPORT_FILE --detail $DETAIL_LEVEL"
    
    # Store the exit code
    status=$?
else
    echo "Maven (mvn) command not found. Creating a simple report instead."
    
    # Create output directory if it doesn't exist
    mkdir -p "$(dirname "$REPORT_FILE")"
    
    # Create a simple HTML report
    cat > "$REPORT_FILE" << EOF
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>StorageScheduler Test Report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; }
        h1 { color: #333; }
        .note { background-color: #f8f9fa; padding: 15px; border-left: 5px solid #007bff; margin-bottom: 20px; }
        .warning { background-color: #fff3cd; padding: 15px; border-left: 5px solid #ffc107; margin-bottom: 20px; }
    </style>
</head>
<body>
    <h1>StorageScheduler Test Report</h1>
    <p>Generated on: $(date)</p>
    
    <div class="warning">
        <h3>Limited Report</h3>
        <p>This is a placeholder report created because Maven was not available to run the full report generator.</p>
        <p>To generate a complete report, please ensure Maven is installed and available in your PATH.</p>
    </div>
    
    <div class="note">
        <h3>Project Information</h3>
        <p>Project: Serengeti</p>
        <p>Component: StorageScheduler</p>
        <p>Format: $FORMAT</p>
        <p>Detail Level: $DETAIL_LEVEL</p>
    </div>
    
    <h2>Test Results</h2>
    <p>No test results available. Please run the tests with Maven to generate detailed results.</p>
    
    <h2>Next Steps</h2>
    <ol>
        <li>Install Maven if not already installed</li>
        <li>Run tests with: <code>mvn test</code></li>
        <li>Generate a complete report with: <code>./generate_test_report.sh</code></li>
    </ol>
</body>
</html>
EOF
    
    # Set status to success since we created a simple report
    status=0
fi

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