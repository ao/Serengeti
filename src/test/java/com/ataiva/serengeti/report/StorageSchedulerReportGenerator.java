package com.ataiva.serengeti.report;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.json.JSONArray;
import org.json.JSONObject;

import static com.ataiva.serengeti.report.ReportGeneratorHelpers.*;

/**
 * Comprehensive test report generator for the StorageScheduler component.
 * This class aggregates results from different test types and generates unified reports.
 */
public class StorageSchedulerReportGenerator {

    // Constants for report paths
    private static final String REPORT_DIR = "target/reports/storage-scheduler";
    private static final String COVERAGE_REPORT_PATH = "target/site/jacoco";
    private static final String MUTATION_REPORT_PATH = "target/pit-reports";
    private static final String BENCHMARK_REPORT_PATH = "target/benchmarks";
    
    // Test result data structures
    private Map<String, TestSuiteResult> unitTestResults = new HashMap<>();
    private Map<String, TestSuiteResult> fastTestResults = new HashMap<>();
    private Map<String, TestSuiteResult> integrationTestResults = new HashMap<>();
    private Map<String, TestSuiteResult> propertyTestResults = new HashMap<>();
    private Map<String, TestSuiteResult> chaosTestResults = new HashMap<>();
    
    // Coverage metrics
    private CoverageMetrics coverageMetrics = new CoverageMetrics();
    
    // Mutation metrics
    private MutationMetrics mutationMetrics = new MutationMetrics();
    
    // Benchmark metrics
    private List<BenchmarkResult> benchmarkResults = new ArrayList<>();
    
    // Report metadata
    private String reportTimestamp;
    private String reportVersion;
    private String gitCommit;
    private String buildNumber;
    
    /**
     * Creates a new report generator with the current timestamp.
     */
    public StorageSchedulerReportGenerator() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.reportTimestamp = dateFormat.format(new Date());
        
        // Create report directory if it doesn't exist
        try {
            Files.createDirectories(Paths.get(REPORT_DIR));
        } catch (IOException e) {
            System.err.println("Failed to create report directory: " + e.getMessage());
        }
    }
    
    /**
     * Sets metadata for the report.
     * 
     * @param version The version of the software
     * @param commit The git commit hash
     * @param buildNum The build number
     */
    public void setMetadata(String version, String commit, String buildNum) {
        this.reportVersion = version;
        this.gitCommit = commit;
        this.buildNumber = buildNum;
    }
    
    /**
     * Collects results from JUnit XML reports for unit tests.
     * 
     * @param xmlPath Path to the JUnit XML report file
     * @return True if collection was successful, false otherwise
     */
    public boolean collectUnitTestResults(String xmlPath) {
        try {
            Map<String, TestSuiteResult> results = parseJUnitXmlReport(xmlPath);
            unitTestResults.putAll(results);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to collect unit test results: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Collects results from JUnit XML reports for fast tests.
     * 
     * @param xmlPath Path to the JUnit XML report file
     * @return True if collection was successful, false otherwise
     */
    public boolean collectFastTestResults(String xmlPath) {
        try {
            Map<String, TestSuiteResult> results = parseJUnitXmlReport(xmlPath);
            fastTestResults.putAll(results);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to collect fast test results: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Collects results from JUnit XML reports for integration tests.
     * 
     * @param xmlPath Path to the JUnit XML report file
     * @return True if collection was successful, false otherwise
     */
    public boolean collectIntegrationTestResults(String xmlPath) {
        try {
            Map<String, TestSuiteResult> results = parseJUnitXmlReport(xmlPath);
            integrationTestResults.putAll(results);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to collect integration test results: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Collects results from JUnit XML reports for property tests.
     * 
     * @param xmlPath Path to the JUnit XML report file
     * @return True if collection was successful, false otherwise
     */
    public boolean collectPropertyTestResults(String xmlPath) {
        try {
            Map<String, TestSuiteResult> results = parseJUnitXmlReport(xmlPath);
            propertyTestResults.putAll(results);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to collect property test results: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Collects results from JUnit XML reports for chaos tests.
     * 
     * @param xmlPath Path to the JUnit XML report file
     * @return True if collection was successful, false otherwise
     */
    public boolean collectChaosTestResults(String xmlPath) {
        try {
            Map<String, TestSuiteResult> results = parseJUnitXmlReport(xmlPath);
            chaosTestResults.putAll(results);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to collect chaos test results: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Collects code coverage metrics from JaCoCo reports.
     * 
     * @param reportDir Directory containing JaCoCo reports
     * @return True if collection was successful, false otherwise
     */
    public boolean collectCoverageMetrics(String reportDir) {
        try {
            // Try to parse from XML report first
            Path xmlPath = Paths.get(reportDir, "jacoco.xml");
            if (Files.exists(xmlPath)) {
                parseCoverageXmlReport(xmlPath.toString());
                return true;
            }
            
            // Fall back to HTML parsing if XML not available
            Path htmlPath = Paths.get(reportDir, "index.html");
            if (Files.exists(htmlPath)) {
                parseCoverageHtmlReport(htmlPath.toString());
                return true;
            }
            
            System.err.println("No coverage reports found in: " + reportDir);
            return false;
        } catch (Exception e) {
            System.err.println("Failed to collect coverage metrics: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Collects mutation testing metrics from PIT reports.
     * 
     * @param reportDir Directory containing PIT reports
     * @return True if collection was successful, false otherwise
     */
    public boolean collectMutationMetrics(String reportDir) {
        try {
            // Find the latest report directory
            Optional<Path> latestDir = Files.list(Paths.get(reportDir))
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().matches("\\d{14}"))
                .max(Comparator.comparing(p -> p.getFileName().toString()));
                
            if (latestDir.isPresent()) {
                Path mutationCsvPath = latestDir.get().resolve("mutations.csv");
                if (Files.exists(mutationCsvPath)) {
                    parseMutationCsvReport(mutationCsvPath.toString());
                    return true;
                }
            }
            
            System.err.println("No mutation reports found in: " + reportDir);
            return false;
        } catch (Exception e) {
            System.err.println("Failed to collect mutation metrics: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Collects benchmark results from JMH JSON reports.
     * 
     * @param jsonPath Path to the JMH JSON report file
     * @return True if collection was successful, false otherwise
     */
    public boolean collectBenchmarkResults(String jsonPath) {
        try {
            parseBenchmarkJsonReport(jsonPath);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to collect benchmark results: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Generates an HTML report with all collected test results.
     * 
     * @param outputPath Path where the HTML report should be saved
     * @return True if generation was successful, false otherwise
     */
    public boolean generateHtmlReport(String outputPath) {
        try {
            StringBuilder html = new StringBuilder();
            
            // Start HTML document
            html.append("<!DOCTYPE html>\n")
                .append("<html lang=\"en\">\n")
                .append("<head>\n")
                .append("  <meta charset=\"UTF-8\">\n")
                .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("  <title>StorageScheduler Test Report</title>\n")
                .append("  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.2.3/dist/css/bootstrap.min.css\">\n")
                .append("  <script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n")
                .append("  <style>\n")
                .append("    body { padding: 20px; }\n")
                .append("    .report-header { margin-bottom: 30px; }\n")
                .append("    .summary-card { margin-bottom: 20px; }\n")
                .append("    .test-details { margin-top: 30px; }\n")
                .append("    .chart-container { height: 300px; margin-bottom: 30px; }\n")
                .append("    .status-passed { color: #198754; }\n")
                .append("    .status-failed { color: #dc3545; }\n")
                .append("    .status-skipped { color: #ffc107; }\n")
                .append("    .coverage-good { color: #198754; }\n")
                .append("    .coverage-warning { color: #ffc107; }\n")
                .append("    .coverage-bad { color: #dc3545; }\n")
                .append("  </style>\n")
                .append("</head>\n")
                .append("<body>\n")
                .append("  <div class=\"container\">\n");
            
            // Report header
            html.append("    <div class=\"report-header\">\n")
                .append("      <h1>StorageScheduler Test Report</h1>\n")
                .append("      <p class=\"text-muted\">Generated on ").append(reportTimestamp).append("</p>\n");
            
            if (reportVersion != null) {
                html.append("      <p>Version: ").append(reportVersion).append("</p>\n");
            }
            if (gitCommit != null) {
                html.append("      <p>Git Commit: ").append(gitCommit).append("</p>\n");
            }
            if (buildNumber != null) {
                html.append("      <p>Build: ").append(buildNumber).append("</p>\n");
            }
            
            html.append("    </div>\n");
            
            // Generate report sections using helper methods
            generateSummarySection(html, unitTestResults, fastTestResults, integrationTestResults, propertyTestResults, chaosTestResults);
            generateCoverageSection(html, coverageMetrics);
            generateMutationSection(html, mutationMetrics);
            generateBenchmarkSection(html, benchmarkResults);
            
            // Test details sections
            generateTestDetailsSection(html, "Unit Tests", unitTestResults);
            generateTestDetailsSection(html, "Fast Tests", fastTestResults);
            generateTestDetailsSection(html, "Integration Tests", integrationTestResults);
            generateTestDetailsSection(html, "Property Tests", propertyTestResults);
            generateTestDetailsSection(html, "Chaos Tests", chaosTestResults);
            
            // End HTML document
            html.append("  </div>\n")
                .append("  <script src=\"https://cdn.jsdelivr.net/npm/bootstrap@5.2.3/dist/js/bootstrap.bundle.min.js\"></script>\n")
                .append("</body>\n")
                .append("</html>");
            
            // Write HTML to file
            Files.write(Paths.get(outputPath), html.toString().getBytes());
            return true;
        } catch (Exception e) {
            System.err.println("Failed to generate HTML report: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Generates an XML report with all collected test results.
     * 
     * @param outputPath Path where the XML report should be saved
     * @return True if generation was successful, false otherwise
     */
    public boolean generateXmlReport(String outputPath) {
        try {
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
            Document doc = docBuilder.newDocument();
            
            // Root element
            Element rootElement = doc.createElement("storage-scheduler-test-report");
            doc.appendChild(rootElement);
            
            // Metadata
            Element metadataElement = doc.createElement("metadata");
            rootElement.appendChild(metadataElement);
            
            Element timestampElement = doc.createElement("timestamp");
            timestampElement.setTextContent(reportTimestamp);
            metadataElement.appendChild(timestampElement);
            
            if (reportVersion != null) {
                Element versionElement = doc.createElement("version");
                versionElement.setTextContent(reportVersion);
                metadataElement.appendChild(versionElement);
            }
            
            if (gitCommit != null) {
                Element commitElement = doc.createElement("git-commit");
                commitElement.setTextContent(gitCommit);
                metadataElement.appendChild(commitElement);
            }
            
            if (buildNumber != null) {
                Element buildElement = doc.createElement("build-number");
                buildElement.setTextContent(buildNumber);
                metadataElement.appendChild(buildElement);
            }
            
            // Summary
            Element summaryElement = doc.createElement("summary");
            rootElement.appendChild(summaryElement);
            
            addTestSummaryXml(doc, summaryElement, "unit-tests", unitTestResults);
            addTestSummaryXml(doc, summaryElement, "fast-tests", fastTestResults);
            addTestSummaryXml(doc, summaryElement, "integration-tests", integrationTestResults);
            addTestSummaryXml(doc, summaryElement, "property-tests", propertyTestResults);
            addTestSummaryXml(doc, summaryElement, "chaos-tests", chaosTestResults);
            
            // Write XML to file
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(outputPath));
            transformer.transform(source, result);
            
            return true;
        } catch (Exception e) {
            System.err.println("Failed to generate XML report: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Generates a JSON report with all collected test results.
     * 
     * @param outputPath Path where the JSON report should be saved
     * @return True if generation was successful, false otherwise
     */
    public boolean generateJsonReport(String outputPath) {
        try {
            JSONObject report = new JSONObject();
            
            // Metadata
            JSONObject metadata = new JSONObject();
            metadata.put("timestamp", reportTimestamp);
            if (reportVersion != null) metadata.put("version", reportVersion);
            if (gitCommit != null) metadata.put("gitCommit", gitCommit);
            if (buildNumber != null) metadata.put("buildNumber", buildNumber);
            report.put("metadata", metadata);
            
            // Summary
            JSONObject summary = new JSONObject();
            summary.put("unitTests", createTestSummaryJson(unitTestResults));
            summary.put("fastTests", createTestSummaryJson(fastTestResults));
            summary.put("integrationTests", createTestSummaryJson(integrationTestResults));
            summary.put("propertyTests", createTestSummaryJson(propertyTestResults));
            summary.put("chaosTests", createTestSummaryJson(chaosTestResults));
            report.put("summary", summary);
            
            // Write JSON to file
            Files.write(Paths.get(outputPath), report.toString(2).getBytes());
            return true;
        } catch (Exception e) {
            System.err.println("Failed to generate JSON report: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Helper method to parse a JUnit XML report.
     * 
     * @param xmlPath Path to the JUnit XML report file
     * @return Map of test suite results
     * @throws Exception If parsing fails
     */
    private Map<String, TestSuiteResult> parseJUnitXmlReport(String xmlPath) throws Exception {
        Map<String, TestSuiteResult> results = new HashMap<>();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(xmlPath));
        
        NodeList testsuites = doc.getElementsByTagName("testsuite");
        for (int i = 0; i < testsuites.getLength(); i++) {
            Element testsuite = (Element) testsuites.item(i);
            
            String name = testsuite.getAttribute("name");
            int tests = Integer.parseInt(testsuite.getAttribute("tests"));
            int failures = Integer.parseInt(testsuite.getAttribute("failures"));
            int errors = Integer.parseInt(testsuite.getAttribute("errors"));
            int skipped = Integer.parseInt(testsuite.getAttribute("skipped"));
            double time = Double.parseDouble(testsuite.getAttribute("time"));
            
            TestSuiteResult result = new TestSuiteResult(name, tests, failures, errors, skipped, time);
            
            // Parse test cases
            NodeList testcases = testsuite.getElementsByTagName("testcase");
            for (int j = 0; j < testcases.getLength(); j++) {
                Element testcase = (Element) testcases.item(j);
                
                String testName = testcase.getAttribute("name");
                String className = testcase.getAttribute("classname");
                double testTime = Double.parseDouble(testcase.getAttribute("time"));
                
                TestCaseResult testResult;
                
                if (testcase.getElementsByTagName("failure").getLength() > 0) {
                    Element failure = (Element) testcase.getElementsByTagName("failure").item(0);
                    String message = failure.getAttribute("message");
                    String type = failure.getAttribute("type");
                    String stackTrace = failure.getTextContent();
                    testResult = new TestCaseResult(testName, className, testTime, TestStatus.FAILED, message, type, stackTrace);
                } else if (testcase.getElementsByTagName("error").getLength() > 0) {
                    Element error = (Element) testcase.getElementsByTagName("error").item(0);
                    String message = error.getAttribute("message");
                    String type = error.getAttribute("type");
                    String stackTrace = error.getTextContent();
                    testResult = new TestCaseResult(testName, className, testTime, TestStatus.ERROR, message, type, stackTrace);
                } else if (testcase.getElementsByTagName("skipped").getLength() > 0) {
                    Element skippedElement = (Element) testcase.getElementsByTagName("skipped").item(0);
                    String message = skippedElement.getAttribute("message");
                    testResult = new TestCaseResult(testName, className, testTime, TestStatus.SKIPPED, message, "", "");
                } else {
                    testResult = new TestCaseResult(testName, className, testTime, TestStatus.PASSED, "", "", "");
                }
                
                result.addTestCase(testResult);
            }
            
            results.put(name, result);
        }
        
        return results;
    }
    
    /**
     * Helper method to parse a JaCoCo XML coverage report.
     * 
     * @param xmlPath Path to the JaCoCo XML report file
     * @throws Exception If parsing fails
     */
    private void parseCoverageXmlReport(String xmlPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(xmlPath));
        
        Element report = doc.getDocumentElement();
        
        // Get counters from the report
        NodeList counters = report.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            String type = counter.getAttribute("type");
            int covered = Integer.parseInt(counter.getAttribute("covered"));
            int missed = Integer.parseInt(counter.getAttribute("missed"));
            int total = covered + missed;
            double percentage = total > 0 ? (double) covered / total * 100 : 0;
            
            switch (type) {
                case "LINE":
                    coverageMetrics.setLineCoverage(percentage);
                    break;
                case "BRANCH":
                    coverageMetrics.setBranchCoverage(percentage);
                    break;
                case "METHOD":
                    coverageMetrics.setMethodCoverage(percentage);
                    break;
                case "CLASS":
                    coverageMetrics.setClassCoverage(percentage);
                    break;
            }
        }
    }
    
    /**
     * Helper method to parse a JaCoCo HTML coverage report.
     * 
     * @param htmlPath Path to the JaCoCo HTML report file
     * @throws Exception If parsing fails
     */
    private void parseCoverageHtmlReport(String htmlPath) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(htmlPath)));
        
        // Extract line coverage
        Pattern linePattern = Pattern.compile("Lines:\\s*<span[^>]*>(\\d+\\.\\d+)%</span>");
        Matcher lineMatcher = linePattern.matcher(content);
        if (lineMatcher.find()) {
            coverageMetrics.setLineCoverage(Double.parseDouble(lineMatcher.group(1)));
        }
        
        // Extract branch coverage
        Pattern branchPattern = Pattern.compile("Branches:\\s*<span[^>]*>(\\d+\\.\\d+)%</span>");
        Matcher branchMatcher = branchPattern.matcher(content);
        if (branchMatcher.find()) {
            coverageMetrics.setBranchCoverage(Double.parseDouble(branchMatcher.group(1)));
        }
        
        // Extract method coverage
        Pattern methodPattern = Pattern.compile("Methods:\\s*<span[^>]*>(\\d+\\.\\d+)%</span>");
        Matcher methodMatcher = methodPattern.matcher(content);
        if (methodMatcher.find()) {
            coverageMetrics.setMethodCoverage(Double.parseDouble(methodMatcher.group(1)));
        }
        
        // Extract class coverage
        Pattern classPattern = Pattern.compile("Classes:\\s*<span[^>]*>(\\d+\\.\\d+)%</span>");
        Matcher classMatcher = classPattern.matcher(content);
        if (classMatcher.find()) {
            coverageMetrics.setClassCoverage(Double.parseDouble(classMatcher.group(1)));
        }
    }
    
    /**
     * Helper method to parse a PIT mutation CSV report.
     * 
     * @param csvPath Path to the PIT CSV report file
     * @throws Exception If parsing fails
     */
    private void parseMutationCsvReport(String csvPath) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(csvPath));
        
        int total = 0;
        int killed = 0;
        int survived = 0;
        
        // Skip header line
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] parts = line.split(",");
            
            if (parts.length >= 6) {
                total++;
                String status = parts[5];
                
                if (status.equals("KILLED") || status.equals("TIMED_OUT")) {
                    killed++;
                } else if (status.equals("SURVIVED") || status.equals("NO_COVERAGE")) {
                    survived++;
                }
            }
        }
        
        double score = total > 0 ? (double) killed / total * 100 : 0;
        
        mutationMetrics.setMutationScore(score);
        mutationMetrics.setTotalMutations(total);
        mutationMetrics.setKilledMutations(killed);
        mutationMetrics.setSurvivedMutations(survived);
    }
    
    /**
     * Helper method to parse a JMH JSON benchmark report.
     * 
     * @param jsonPath Path to the JMH JSON report file
     * @throws Exception If parsing fails
     */
    private void parseBenchmarkJsonReport(String jsonPath) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(jsonPath)));
        JSONArray benchmarks = new JSONArray(content);
        
        for (int i = 0; i < benchmarks.length(); i++) {
            JSONObject benchmark = benchmarks.getJSONObject(i);
            
            String name = benchmark.getString("benchmark").replaceAll(".*\\.", "");
            String mode = benchmark.getString("mode");
            JSONObject primaryMetric = benchmark.getJSONObject("primaryMetric");
            double score = primaryMetric.getDouble("score");
            double error = primaryMetric.getDouble("scoreError");
            String units = primaryMetric.getString("scoreUnit");
            
            benchmarkResults.add(new BenchmarkResult(name, mode, score, error, units));
        }
    }
}