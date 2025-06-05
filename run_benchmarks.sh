#!/bin/bash

# Script to run benchmark tests for Serengeti
# This script provides options to run benchmarks for specific components
# and with different output formats.

# Default values
COMPONENT="all"
FORMAT="json"
ITERATIONS=3
WARMUP=2
THREADS=1
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
            FORMAT="$2"
            shift
            shift
            ;;
        -i|--iterations)
            ITERATIONS="$2"
            shift
            shift
            ;;
        -w|--warmup)
            WARMUP="$2"
            shift
            shift
            ;;
        -t|--threads)
            THREADS="$2"
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
    echo "Usage: ./run_benchmarks.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -c, --component COMPONENT  Component to benchmark (default: all)"
    echo "                             Available components: storage-scheduler, all"
    echo "  -f, --format FORMAT        Output format: json, csv, scsv, text, latex (default: json)"
    echo "  -i, --iterations N         Number of measurement iterations (default: 3)"
    echo "  -w, --warmup N             Number of warmup iterations (default: 2)"
    echo "  -t, --threads N            Number of threads to use (default: 1)"
    echo "  -o, --open                 Open the HTML report after generation"
    echo "  -h, --help                 Show this help message"
    echo ""
    echo "Examples:"
    echo "  ./run_benchmarks.sh"
    echo "  ./run_benchmarks.sh --component storage-scheduler"
    echo "  ./run_benchmarks.sh --format csv"
    echo "  ./run_benchmarks.sh --iterations 5 --warmup 3"
    echo "  ./run_benchmarks.sh --component storage-scheduler --format json --open"
    exit 0
fi

# Function to display benchmark header
display_header() {
    echo "========================================================"
    echo "  Running $1 Benchmarks"
    echo "========================================================"
    echo "Started at: $(date)"
    echo "--------------------------------------------------------"
}

# Function to display benchmark summary
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

# Create results directory if it doesn't exist
mkdir -p target/benchmarks

# Run benchmarks based on component
if [ "$COMPONENT" = "all" ] || [ "$COMPONENT" = "storage-scheduler" ]; then
    display_header "StorageScheduler"
    
    # Build JMH benchmark options
    JMH_OPTS="-wi $WARMUP -i $ITERATIONS -t $THREADS -f 1 -rf $FORMAT"
    
    # Run StorageScheduler benchmarks
    mvn clean test -Pbenchmark -Djmh.includes=StorageSchedulerBenchmark -Djmh.args="$JMH_OPTS -rff target/benchmarks/storage-scheduler-results.$FORMAT"
    
    # Store the exit code
    benchmark_status=$?
    
    # Display summary
    display_summary $benchmark_status
    
    # Set overall status
    overall_status=$benchmark_status
    
    # Generate HTML report if requested
    if [ "$OPEN_REPORT" = true ] && [ "$FORMAT" = "json" ]; then
        echo "Generating HTML report from JSON results..."
        
        # Check if jmh-visualizer is available, if not download it
        if [ ! -d "jmh-visualizer" ]; then
            echo "Downloading JMH Visualizer..."
            git clone https://github.com/jzillmann/jmh-visualizer.git
            cd jmh-visualizer
            npm install
            npm run build
            cd ..
        fi
        
        # Create a simple HTML file that loads the visualizer
        cat > target/benchmarks/report.html << EOF
<!DOCTYPE html>
<html>
<head>
    <title>StorageScheduler Benchmark Results</title>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/react/15.4.1/react.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/react/15.4.1/react-dom.min.js"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.7/css/bootstrap.min.css">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.7/js/bootstrap.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jquery/3.1.1/jquery.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/lodash.js/4.17.2/lodash.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/d3/4.4.0/d3.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/metrics-graphics/2.11.0/metricsgraphics.min.js"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/metrics-graphics/2.11.0/metricsgraphics.min.css">
</head>
<body>
    <div id="app"></div>
    <script>
        // Load the benchmark results
        fetch('storage-scheduler-results.json')
            .then(response => response.json())
            .then(data => {
                // Display the results
                const container = document.getElementById('app');
                const header = document.createElement('h1');
                header.textContent = 'StorageScheduler Benchmark Results';
                container.appendChild(header);
                
                // Create a table for the results
                const table = document.createElement('table');
                table.className = 'table table-striped';
                container.appendChild(table);
                
                // Create table header
                const thead = document.createElement('thead');
                table.appendChild(thead);
                const headerRow = document.createElement('tr');
                thead.appendChild(headerRow);
                
                const headers = ['Benchmark', 'Mode', 'Threads', 'Samples', 'Score', 'Error', 'Units'];
                headers.forEach(text => {
                    const th = document.createElement('th');
                    th.textContent = text;
                    headerRow.appendChild(th);
                });
                
                // Create table body
                const tbody = document.createElement('tbody');
                table.appendChild(tbody);
                
                // Add rows for each benchmark result
                data.forEach(result => {
                    const row = document.createElement('tr');
                    tbody.appendChild(row);
                    
                    const cells = [
                        result.benchmark.split('.').pop(),
                        result.mode,
                        result.threads,
                        result.measurementIterations,
                        result.primaryMetric.score.toFixed(3),
                        result.primaryMetric.scoreError.toFixed(3),
                        result.primaryMetric.scoreUnit
                    ];
                    
                    cells.forEach(text => {
                        const td = document.createElement('td');
                        td.textContent = text;
                        row.appendChild(td);
                    });
                });
            })
            .catch(error => {
                console.error('Error loading benchmark results:', error);
                document.getElementById('app').textContent = 'Error loading benchmark results: ' + error.message;
            });
    </script>
</body>
</html>
EOF
        
        # Open the HTML report
        if command -v xdg-open > /dev/null; then
            xdg-open target/benchmarks/report.html
        elif command -v open > /dev/null; then
            open target/benchmarks/report.html
        else
            echo "HTML report generated at: target/benchmarks/report.html"
            echo "Please open it in a browser to view the results."
        fi
    fi
fi

# Display results location
echo "Benchmark results saved to: target/benchmarks/storage-scheduler-results.$FORMAT"
echo ""

# Exit with the appropriate status code
exit $overall_status