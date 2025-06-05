// StorageScheduler Test Report JavaScript

document.addEventListener('DOMContentLoaded', function() {
    // Initialize charts if Chart.js is available
    if (typeof Chart !== 'undefined') {
        initializeCharts();
    }
    
    // Add event listeners for expandable stack traces
    document.querySelectorAll('.stack-trace-toggle').forEach(function(button) {
        button.addEventListener('click', function() {
            const stackTraceId = this.getAttribute('data-target');
            const stackTrace = document.getElementById(stackTraceId);
            
            if (stackTrace.style.display === 'none') {
                stackTrace.style.display = 'block';
                this.textContent = 'Hide Stack Trace';
            } else {
                stackTrace.style.display = 'none';
                this.textContent = 'Show Stack Trace';
            }
        });
    });
    
    // Add search functionality for test cases
    const searchInput = document.getElementById('test-search');
    if (searchInput) {
        searchInput.addEventListener('input', function() {
            const searchTerm = this.value.toLowerCase();
            
            document.querySelectorAll('.test-case-row').forEach(function(row) {
                const testName = row.querySelector('.test-name').textContent.toLowerCase();
                const testClass = row.querySelector('.test-class').textContent.toLowerCase();
                
                if (testName.includes(searchTerm) || testClass.includes(searchTerm)) {
                    row.style.display = '';
                } else {
                    row.style.display = 'none';
                }
            });
        });
    }
    
    // Add filter functionality for test status
    document.querySelectorAll('.status-filter').forEach(function(filter) {
        filter.addEventListener('change', function() {
            filterTestsByStatus();
        });
    });
});

// Initialize all charts
function initializeCharts() {
    // Test results chart
    const testResultsCtx = document.getElementById('testResultsChart');
    if (testResultsCtx) {
        new Chart(testResultsCtx, {
            type: 'bar',
            data: {
                labels: ['Unit', 'Fast', 'Integration', 'Property', 'Chaos'],
                datasets: [
                    {
                        label: 'Passed',
                        data: testResultsData.passed,
                        backgroundColor: '#198754'
                    },
                    {
                        label: 'Failed',
                        data: testResultsData.failed,
                        backgroundColor: '#dc3545'
                    },
                    {
                        label: 'Skipped',
                        data: testResultsData.skipped,
                        backgroundColor: '#ffc107'
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: { stacked: true },
                    y: { stacked: true }
                }
            }
        });
    }
    
    // Coverage chart
    const coverageCtx = document.getElementById('coverageChart');
    if (coverageCtx && typeof coverageData !== 'undefined') {
        new Chart(coverageCtx, {
            type: 'bar',
            data: {
                labels: ['Line', 'Branch', 'Method', 'Class'],
                datasets: [{
                    label: 'Coverage %',
                    data: [
                        coverageData.lineCoverage,
                        coverageData.branchCoverage,
                        coverageData.methodCoverage,
                        coverageData.classCoverage
                    ],
                    backgroundColor: ['#198754', '#20c997', '#0dcaf0', '#0d6efd']
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true,
                        max: 100
                    }
                }
            }
        });
    }
    
    // Benchmark chart
    const benchmarkCtx = document.getElementById('benchmarkChart');
    if (benchmarkCtx && typeof benchmarkData !== 'undefined') {
        new Chart(benchmarkCtx, {
            type: 'bar',
            data: {
                labels: benchmarkData.labels,
                datasets: [{
                    label: 'Score',
                    data: benchmarkData.scores,
                    backgroundColor: '#0d6efd'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true
                    }
                }
            }
        });
    }
}

// Filter test cases by status
function filterTestsByStatus() {
    const showPassed = document.getElementById('show-passed').checked;
    const showFailed = document.getElementById('show-failed').checked;
    const showSkipped = document.getElementById('show-skipped').checked;
    
    document.querySelectorAll('.test-case-row').forEach(function(row) {
        const status = row.getAttribute('data-status');
        
        if ((status === 'PASSED' && showPassed) ||
            ((status === 'FAILED' || status === 'ERROR') && showFailed) ||
            (status === 'SKIPPED' && showSkipped)) {
            row.style.display = '';
        } else {
            row.style.display = 'none';
        }
    });
}

// Export report to PDF
function exportToPdf() {
    window.print();
}

// Toggle visibility of all test suites
function toggleAllTestSuites(expand) {
    document.querySelectorAll('.accordion-button').forEach(function(button) {
        const isCollapsed = button.classList.contains('collapsed');
        
        if (expand && isCollapsed) {
            button.click();
        } else if (!expand && !isCollapsed) {
            button.click();
        }
    });
}