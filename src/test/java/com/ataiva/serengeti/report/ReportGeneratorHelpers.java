package com.ataiva.serengeti.report;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import org.json.JSONObject;
import org.json.JSONArray;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Helper methods for the StorageSchedulerReportGenerator class.
 */
public class ReportGeneratorHelpers {

    /**
     * Counts the number of passed tests in a map of test suite results.
     * 
     * @param results The test suite results
     * @return The number of passed tests
     */
    public static int countPassedTests(Map<String, TestSuiteResult> results) {
        int count = 0;
        for (TestSuiteResult suite : results.values()) {
            for (TestCaseResult testCase : suite.getTestCases()) {
                if (testCase.getStatus() == TestStatus.PASSED) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Counts the number of failed tests in a map of test suite results.
     * 
     * @param results The test suite results
     * @return The number of failed tests
     */
    public static int countFailedTests(Map<String, TestSuiteResult> results) {
        int count = 0;
        for (TestSuiteResult suite : results.values()) {
            for (TestCaseResult testCase : suite.getTestCases()) {
                if (testCase.getStatus() == TestStatus.FAILED || testCase.getStatus() == TestStatus.ERROR) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Counts the number of skipped tests in a map of test suite results.
     * 
     * @param results The test suite results
     * @return The number of skipped tests
     */
    public static int countSkippedTests(Map<String, TestSuiteResult> results) {
        int count = 0;
        for (TestSuiteResult suite : results.values()) {
            for (TestCaseResult testCase : suite.getTestCases()) {
                if (testCase.getStatus() == TestStatus.SKIPPED) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Generates the summary section of the HTML report.
     * 
     * @param html StringBuilder to append the HTML content to
     * @param unitTestResults Unit test results
     * @param fastTestResults Fast test results
     * @param integrationTestResults Integration test results
     * @param propertyTestResults Property test results
     * @param chaosTestResults Chaos test results
     */
    public static void generateSummarySection(StringBuilder html, 
                                             Map<String, TestSuiteResult> unitTestResults,
                                             Map<String, TestSuiteResult> fastTestResults,
                                             Map<String, TestSuiteResult> integrationTestResults,
                                             Map<String, TestSuiteResult> propertyTestResults,
                                             Map<String, TestSuiteResult> chaosTestResults) {
        html.append("    <div class=\"row\">\n")
            .append("      <div class=\"col-12\">\n")
            .append("        <h2>Test Summary</h2>\n")
            .append("      </div>\n")
            .append("    </div>\n")
            .append("    <div class=\"row\">\n")
            .append("      <div class=\"col-md-6\">\n")
            .append("        <div class=\"card summary-card\">\n")
            .append("          <div class=\"card-header\">Test Results</div>\n")
            .append("          <div class=\"card-body\">\n")
            .append("            <div class=\"chart-container\">\n")
            .append("              <canvas id=\"testResultsChart\"></canvas>\n")
            .append("            </div>\n")
            .append("            <table class=\"table table-sm\">\n")
            .append("              <thead>\n")
            .append("                <tr>\n")
            .append("                  <th>Test Type</th>\n")
            .append("                  <th>Total</th>\n")
            .append("                  <th>Passed</th>\n")
            .append("                  <th>Failed</th>\n")
            .append("                  <th>Skipped</th>\n")
            .append("                  <th>Success Rate</th>\n")
            .append("                </tr>\n")
            .append("              </thead>\n")
            .append("              <tbody>\n");
        
        generateTestSummaryRow(html, "Unit Tests", unitTestResults);
        generateTestSummaryRow(html, "Fast Tests", fastTestResults);
        generateTestSummaryRow(html, "Integration Tests", integrationTestResults);
        generateTestSummaryRow(html, "Property Tests", propertyTestResults);
        generateTestSummaryRow(html, "Chaos Tests", chaosTestResults);
        
        html.append("              </tbody>\n")
            .append("            </table>\n")
            .append("          </div>\n")
            .append("        </div>\n")
            .append("      </div>\n");
    }
    
    /**
     * Generates the coverage section of the HTML report.
     * 
     * @param html StringBuilder to append the HTML content to
     * @param coverageMetrics Coverage metrics
     */
    public static void generateCoverageSection(StringBuilder html, CoverageMetrics coverageMetrics) {
        html.append("      <div class=\"col-md-6\">\n")
            .append("        <div class=\"card summary-card\">\n")
            .append("          <div class=\"card-header\">Code Coverage</div>\n")
            .append("          <div class=\"card-body\">\n");
        
        if (coverageMetrics.isInitialized()) {
            html.append("            <div class=\"chart-container\">\n")
                .append("              <canvas id=\"coverageChart\"></canvas>\n")
                .append("            </div>\n")
                .append("            <table class=\"table table-sm\">\n")
                .append("              <thead>\n")
                .append("                <tr>\n")
                .append("                  <th>Metric</th>\n")
                .append("                  <th>Coverage</th>\n")
                .append("                  <th>Status</th>\n")
                .append("                </tr>\n")
                .append("              </thead>\n")
                .append("              <tbody>\n");
            
            generateCoverageRow(html, "Line Coverage", coverageMetrics.getLineCoverage(), 90);
            generateCoverageRow(html, "Branch Coverage", coverageMetrics.getBranchCoverage(), 85);
            generateCoverageRow(html, "Method Coverage", coverageMetrics.getMethodCoverage(), 95);
            generateCoverageRow(html, "Class Coverage", coverageMetrics.getClassCoverage(), 95);
            
            html.append("              </tbody>\n")
                .append("            </table>\n");
        } else {
            html.append("            <p>No coverage data available.</p>\n");
        }
        
        html.append("          </div>\n")
            .append("        </div>\n")
            .append("      </div>\n")
            .append("    </div>\n");
    }
    
    /**
     * Generates the mutation section of the HTML report.
     * 
     * @param html StringBuilder to append the HTML content to
     * @param mutationMetrics Mutation metrics
     */
    public static void generateMutationSection(StringBuilder html, MutationMetrics mutationMetrics) {
        html.append("    <div class=\"row\">\n")
            .append("      <div class=\"col-md-6\">\n")
            .append("        <div class=\"card summary-card\">\n")
            .append("          <div class=\"card-header\">Mutation Testing</div>\n")
            .append("          <div class=\"card-body\">\n");
        
        if (mutationMetrics.isInitialized()) {
            html.append("            <div class=\"row\">\n")
                .append("              <div class=\"col-md-6\">\n")
                .append("                <div class=\"card bg-light mb-3\">\n")
                .append("                  <div class=\"card-body text-center\">\n")
                .append("                    <h5 class=\"card-title\">Mutation Score</h5>\n")
                .append("                    <p class=\"display-4\">").append(String.format("%.1f%%", mutationMetrics.getMutationScore())).append("</p>\n")
                .append("                  </div>\n")
                .append("                </div>\n")
                .append("              </div>\n")
                .append("              <div class=\"col-md-6\">\n")
                .append("                <div class=\"card bg-light mb-3\">\n")
                .append("                  <div class=\"card-body text-center\">\n")
                .append("                    <h5 class=\"card-title\">Total Mutations</h5>\n")
                .append("                    <p class=\"display-4\">").append(mutationMetrics.getTotalMutations()).append("</p>\n")
                .append("                  </div>\n")
                .append("                </div>\n")
                .append("              </div>\n")
                .append("            </div>\n")
                .append("            <table class=\"table table-sm\">\n")
                .append("              <thead>\n")
                .append("                <tr>\n")
                .append("                  <th>Metric</th>\n")
                .append("                  <th>Count</th>\n")
                .append("                  <th>Percentage</th>\n")
                .append("                </tr>\n")
                .append("              </thead>\n")
                .append("              <tbody>\n")
                .append("                <tr>\n")
                .append("                  <td>Killed Mutations</td>\n")
                .append("                  <td>").append(mutationMetrics.getKilledMutations()).append("</td>\n")
                .append("                  <td>").append(String.format("%.1f%%", mutationMetrics.getTotalMutations() > 0 ? (double) mutationMetrics.getKilledMutations() / mutationMetrics.getTotalMutations() * 100 : 0)).append("</td>\n")
                .append("                </tr>\n")
                .append("                <tr>\n")
                .append("                  <td>Survived Mutations</td>\n")
                .append("                  <td>").append(mutationMetrics.getSurvivedMutations()).append("</td>\n")
                .append("                  <td>").append(String.format("%.1f%%", mutationMetrics.getTotalMutations() > 0 ? (double) mutationMetrics.getSurvivedMutations() / mutationMetrics.getTotalMutations() * 100 : 0)).append("</td>\n")
                .append("                </tr>\n")
                .append("              </tbody>\n")
                .append("            </table>\n");
        } else {
            html.append("            <p>No mutation data available.</p>\n");
        }
        
        html.append("          </div>\n")
            .append("        </div>\n")
            .append("      </div>\n");
    }
    
    /**
     * Generates the benchmark section of the HTML report.
     * 
     * @param html StringBuilder to append the HTML content to
     * @param benchmarkResults Benchmark results
     */
    public static void generateBenchmarkSection(StringBuilder html, List<BenchmarkResult> benchmarkResults) {
        html.append("      <div class=\"col-md-6\">\n")
            .append("        <div class=\"card summary-card\">\n")
            .append("          <div class=\"card-header\">Benchmark Results</div>\n")
            .append("          <div class=\"card-body\">\n");
        
        if (!benchmarkResults.isEmpty()) {
            html.append("            <div class=\"chart-container\">\n")
                .append("              <canvas id=\"benchmarkChart\"></canvas>\n")
                .append("            </div>\n")
                .append("            <table class=\"table table-sm\">\n")
                .append("              <thead>\n")
                .append("                <tr>\n")
                .append("                  <th>Benchmark</th>\n")
                .append("                  <th>Mode</th>\n")
                .append("                  <th>Score</th>\n")
                .append("                  <th>Error</th>\n")
                .append("                  <th>Units</th>\n")
                .append("                </tr>\n")
                .append("              </thead>\n")
                .append("              <tbody>\n");
            
            for (BenchmarkResult result : benchmarkResults) {
                html.append("                <tr>\n")
                    .append("                  <td>").append(result.getName()).append("</td>\n")
                    .append("                  <td>").append(result.getMode()).append("</td>\n")
                    .append("                  <td>").append(String.format("%.3f", result.getScore())).append("</td>\n")
                    .append("                  <td>").append(String.format("%.3f", result.getError())).append("</td>\n")
                    .append("                  <td>").append(result.getUnits()).append("</td>\n")
                    .append("                </tr>\n");
            }
            
            html.append("              </tbody>\n")
                .append("            </table>\n");
        } else {
            html.append("            <p>No benchmark data available.</p>\n");
        }
        
        html.append("          </div>\n")
            .append("        </div>\n")
            .append("      </div>\n")
            .append("    </div>\n");
    }
    
    /**
     * Generates a test details section of the HTML report.
     * 
     * @param html StringBuilder to append the HTML content to
     * @param title The title of the section
     * @param results The test results to display
     */
    public static void generateTestDetailsSection(StringBuilder html, String title, Map<String, TestSuiteResult> results) {
        if (results.isEmpty()) {
            return;
        }
        
        String id = title.toLowerCase().replace(" ", "-");
        
        html.append("    <div class=\"test-details\">\n")
            .append("      <h3>").append(title).append("</h3>\n")
            .append("      <div class=\"accordion\" id=\"accordion-").append(id).append("\">\n");
        
        int index = 0;
        for (Map.Entry<String, TestSuiteResult> entry : results.entrySet()) {
            String suiteId = id + "-" + index;
            TestSuiteResult suite = entry.getValue();
            
            html.append("        <div class=\"accordion-item\">\n")
                .append("          <h2 class=\"accordion-header\" id=\"heading-").append(suiteId).append("\">\n")
                .append("            <button class=\"accordion-button collapsed\" type=\"button\" data-bs-toggle=\"collapse\" data-bs-target=\"#collapse-").append(suiteId).append("\" aria-expanded=\"false\" aria-controls=\"collapse-").append(suiteId).append("\">\n")
                .append("              ").append(entry.getKey()).append(" (")
                .append(suite.getTests()).append(" tests, ")
                .append(suite.getFailures() + suite.getErrors()).append(" failures, ")
                .append(suite.getSkipped()).append(" skipped, ")
                .append(String.format("%.2fs", suite.getTime())).append(")\n")
                .append("            </button>\n")
                .append("          </h2>\n")
                .append("          <div id=\"collapse-").append(suiteId).append("\" class=\"accordion-collapse collapse\" aria-labelledby=\"heading-").append(suiteId).append("\" data-bs-parent=\"#accordion-").append(id).append("\">\n")
                .append("            <div class=\"accordion-body\">\n")
                .append("              <table class=\"table table-sm\">\n")
                .append("                <thead>\n")
                .append("                  <tr>\n")
                .append("                    <th>Test</th>\n")
                .append("                    <th>Status</th>\n")
                .append("                    <th>Time</th>\n")
                .append("                    <th>Message</th>\n")
                .append("                  </tr>\n")
                .append("                </thead>\n")
                .append("                <tbody>\n");
            
            for (TestCaseResult testCase : suite.getTestCases()) {
                String statusClass = "";
                switch (testCase.getStatus()) {
                    case PASSED:
                        statusClass = "status-passed";
                        break;
                    case FAILED:
                    case ERROR:
                        statusClass = "status-failed";
                        break;
                    case SKIPPED:
                        statusClass = "status-skipped";
                        break;
                }
                
                html.append("                  <tr>\n")
                    .append("                    <td>").append(testCase.getName()).append("</td>\n")
                    .append("                    <td class=\"").append(statusClass).append("\">").append(testCase.getStatus()).append("</td>\n")
                    .append("                    <td>").append(String.format("%.3fs", testCase.getTime())).append("</td>\n")
                    .append("                    <td>").append(testCase.getMessage()).append("</td>\n")
                    .append("                  </tr>\n");
            }
            
            html.append("                </tbody>\n")
                .append("              </table>\n")
                .append("            </div>\n")
                .append("          </div>\n")
                .append("        </div>\n");
            
            index++;
        }
        
        html.append("      </div>\n")
            .append("    </div>\n");
    }
    
    /**
     * Generates a test summary row in the HTML report.
     * 
     * @param html StringBuilder to append the HTML content to
     * @param title The title of the row
     * @param results The test results to summarize
     */
    public static void generateTestSummaryRow(StringBuilder html, String title, Map<String, TestSuiteResult> results) {
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        
        for (TestSuiteResult suite : results.values()) {
            total += suite.getTests();
            failed += suite.getFailures() + suite.getErrors();
            skipped += suite.getSkipped();
        }
        
        passed = total - failed - skipped;
        double successRate = total > 0 ? (double) passed / total * 100 : 0;
        
        html.append("                <tr>\n")
            .append("                  <td>").append(title).append("</td>\n")
            .append("                  <td>").append(total).append("</td>\n")
            .append("                  <td>").append(passed).append("</td>\n")
            .append("                  <td>").append(failed).append("</td>\n")
            .append("                  <td>").append(skipped).append("</td>\n")
            .append("                  <td>").append(String.format("%.1f%%", successRate)).append("</td>\n")
            .append("                </tr>\n");
    }
    
    /**
     * Generates a coverage row in the HTML report.
     * 
     * @param html StringBuilder to append the HTML content to
     * @param title The title of the row
     * @param coverage The coverage percentage
     * @param target The target coverage percentage
     */
    public static void generateCoverageRow(StringBuilder html, String title, double coverage, double target) {
        String statusClass = coverage >= target ? "coverage-good" : (coverage >= target * 0.9 ? "coverage-warning" : "coverage-bad");
        String statusText = coverage >= target ? "✓ Met target" : (coverage >= target * 0.9 ? "⚠ Near target" : "✗ Below target");
        
        html.append("                <tr>\n")
            .append("                  <td>").append(title).append("</td>\n")
            .append("                  <td>").append(String.format("%.1f%%", coverage)).append("</td>\n")
            .append("                  <td class=\"").append(statusClass).append("\">").append(statusText).append("</td>\n")
            .append("                </tr>\n");
    }
    
    /**
     * Helper method to add test summary to XML report.
     * 
     * @param doc The XML document
     * @param parent The parent element
     * @param name The name of the test type
     * @param results The test results to summarize
     */
    public static void addTestSummaryXml(Document doc, Element parent, String name, Map<String, TestSuiteResult> results) {
        Element element = doc.createElement(name);
        parent.appendChild(element);
        
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        
        for (TestSuiteResult suite : results.values()) {
            total += suite.getTests();
            failed += suite.getFailures() + suite.getErrors();
            skipped += suite.getSkipped();
        }
        
        passed = total - failed - skipped;
        
        element.setAttribute("total", String.valueOf(total));
        element.setAttribute("passed", String.valueOf(passed));
        element.setAttribute("failed", String.valueOf(failed));
        element.setAttribute("skipped", String.valueOf(skipped));
    }
    
    /**
     * Helper method to add test details to XML report.
     * 
     * @param doc The XML document
     * @param parent The parent element
     * @param name The name of the test type
     * @param results The test results to add
     */
    public static void addTestDetailsXml(Document doc, Element parent, String name, Map<String, TestSuiteResult> results) {
        if (results.isEmpty()) {
            return;
        }
        
        Element element = doc.createElement(name + "-details");
        parent.appendChild(element);
        
        for (Map.Entry<String, TestSuiteResult> entry : results.entrySet()) {
            Element suiteElement = doc.createElement("test-suite");
            element.appendChild(suiteElement);
            
            TestSuiteResult suite = entry.getValue();
            suiteElement.setAttribute("name", entry.getKey());
            suiteElement.setAttribute("tests", String.valueOf(suite.getTests()));
            suiteElement.setAttribute("failures", String.valueOf(suite.getFailures()));
            suiteElement.setAttribute("errors", String.valueOf(suite.getErrors()));
            suiteElement.setAttribute("skipped", String.valueOf(suite.getSkipped()));
            suiteElement.setAttribute("time", String.valueOf(suite.getTime()));
            
            for (TestCaseResult testCase : suite.getTestCases()) {
                Element testElement = doc.createElement("test-case");
                suiteElement.appendChild(testElement);
                
                testElement.setAttribute("name", testCase.getName());
                testElement.setAttribute("classname", testCase.getClassName());
                testElement.setAttribute("time", String.valueOf(testCase.getTime()));
                testElement.setAttribute("status", testCase.getStatus().toString());
                
                if (!testCase.getMessage().isEmpty()) {
                    testElement.setAttribute("message", testCase.getMessage());
                }
                
                if (!testCase.getType().isEmpty()) {
                    testElement.setAttribute("type", testCase.getType());
                }
                
                if (!testCase.getStackTrace().isEmpty()) {
                    Element stackTraceElement = doc.createElement("stack-trace");
                    stackTraceElement.setTextContent(testCase.getStackTrace());
                    testElement.appendChild(stackTraceElement);
                }
            }
        }
    }
    
    /**
     * Helper method to create a test summary JSON object.
     * 
     * @param results The test results to summarize
     * @return A JSON object with the summary
     */
    public static JSONObject createTestSummaryJson(Map<String, TestSuiteResult> results) {
        JSONObject summary = new JSONObject();
        
        int total = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        
        for (TestSuiteResult suite : results.values()) {
            total += suite.getTests();
            failed += suite.getFailures() + suite.getErrors();
            skipped += suite.getSkipped();
        }
        
        passed = total - failed - skipped;
        
        summary.put("total", total);
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        
        return summary;
    }
    
    /**
     * Helper method to create test details JSON array.
     * 
     * @param results The test results to include
     * @return A JSON array with the test details
     */
    public static JSONArray createTestDetailsJson(Map<String, TestSuiteResult> results) {
        JSONArray details = new JSONArray();
        
        for (Map.Entry<String, TestSuiteResult> entry : results.entrySet()) {
            JSONObject suite = new JSONObject();
            suite.put("name", entry.getKey());
            
            TestSuiteResult suiteResult = entry.getValue();
            suite.put("tests", suiteResult.getTests());
            suite.put("failures", suiteResult.getFailures());
            suite.put("errors", suiteResult.getErrors());
            suite.put("skipped", suiteResult.getSkipped());
            suite.put("time", suiteResult.getTime());
            
            JSONArray testCases = new JSONArray();
            for (TestCaseResult testCase : suiteResult.getTestCases()) {
                JSONObject test = new JSONObject();
                test.put("name", testCase.getName());
                test.put("className", testCase.getClassName());
                test.put("time", testCase.getTime());
                test.put("status", testCase.getStatus().toString());
                test.put("message", testCase.getMessage());
                test.put("type", testCase.getType());
                test.put("stackTrace", testCase.getStackTrace());
                testCases.put(test);
            }
            
            suite.put("testCases", testCases);
            details.put(suite);
        }
        
        return details;
    }
}