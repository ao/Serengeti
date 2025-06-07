# Performance Testing Framework

This document provides an overview of the Performance Testing Framework implemented in the Serengeti project. The framework is designed to measure, analyze, and report on the performance characteristics of the system, with a particular focus on the Storage Engine.

## Overview

The Performance Testing Framework consists of three main components:

1. **Automated Benchmark Suite**: A comprehensive set of benchmarks for measuring the performance of various system components.
2. **Continuous Performance Testing**: Integration with CI/CD pipelines to detect performance regressions.
3. **Load Testing Infrastructure**: Tools for simulating realistic workloads and identifying system limits.

## Automated Benchmark Suite

The benchmark suite provides a standardized way to measure the performance of different components of the system. It includes:

### Core Components

- `BenchmarkFramework`: The central class that manages benchmark execution and reporting.
- `BenchmarkSuite`: Interface for defining benchmark suites.
- `BenchmarkResult`: Class for storing and analyzing benchmark results.
- `BenchmarkMetric`: Class representing individual performance metrics.

### Benchmark Suites

The framework includes the following benchmark suites:

- `StorageEngineBenchmarkSuite`: Benchmarks for the Storage Engine, including:
  - Insert throughput
  - Select throughput
  - Operation latency
  - Scalability
  - Concurrency
  - Cache effectiveness
  - Bloom filter performance
  - Compaction performance

### Parameterized Benchmarks

Benchmarks can be parameterized to test different configurations:

- Storage Engine tuning levels (Performance, Balanced, Resource-Efficient)
- Cache sizes
- Async I/O settings

### Usage

To run a benchmark:

```java
BenchmarkFramework framework = BenchmarkFramework.getInstance();
BenchmarkResult result = framework.runBenchmark("storage-engine");
```

To run all benchmarks:

```java
Map<String, BenchmarkResult> results = framework.runAllBenchmarks();
```

## Continuous Performance Testing

The continuous performance testing component integrates with CI/CD pipelines to automatically detect performance regressions.

### Core Components

- `ContinuousPerformanceTesting`: Class for running benchmarks as part of CI/CD pipelines.
- `RegressionDetector`: Class for detecting performance regressions by comparing benchmark results.

### Features

- Historical performance tracking
- Regression detection with configurable thresholds
- HTML and JSON reports
- Integration with CI/CD pipelines

### Usage

To run benchmarks in a CI/CD pipeline:

```java
ContinuousPerformanceTesting cpt = new ContinuousPerformanceTesting(
    Paths.get("benchmark-history"),
    Paths.get("benchmark-reports"),
    10.0 // 10% regression threshold
);

boolean passed = cpt.runAllBenchmarks();
System.exit(passed ? 0 : 1);
```

## Load Testing Infrastructure

The load testing infrastructure provides tools for simulating realistic workloads and identifying system limits.

### Core Components

- `LoadTestingFramework`: The central class for managing load tests.
- `WorkloadPattern`: Interface for defining workload patterns.
- `LoadGenerator`: Interface for generating load.
- `StressTestResult`: Class for storing stress test results.

### Workload Patterns

The framework includes the following workload patterns:

- `ConstantWorkloadPattern`: Maintains a constant request rate.
- `RampWorkloadPattern`: Gradually increases the request rate.
- `SineWorkloadPattern`: Oscillates the request rate in a sine wave pattern.
- `StepWorkloadPattern`: Changes the request rate in discrete steps.

### Features

- Realistic workload simulation based on production patterns
- Scalable load generation
- Stress testing to identify system limits
- Detailed reporting

### Usage

To run a load test:

```java
LoadTestingFramework framework = LoadTestingFramework.getInstance();
CompletableFuture<BenchmarkResult> future = framework.startLoadTest(
    "test-name",
    "storage-generator",
    "ramp-up",
    60, // 60 seconds
    params
);

BenchmarkResult result = future.get();
```

To run a stress test:

```java
StressTestResult result = framework.runStressTest(
    "storage-generator",
    100, // initial rate
    10000, // max rate
    100, // step size
    30, // step duration
    "latency", // target metric
    100.0 // threshold (100ms)
);

System.out.println("System limit: " + result.getSystemLimit() + " requests/sec");
```

## Integration with Storage Engine Tuning

The Performance Testing Framework is designed to work closely with the Storage Engine Tuning component. It can be used to:

1. Measure the impact of different tuning configurations
2. Identify optimal settings for specific workloads
3. Detect performance regressions after code changes
4. Validate performance improvements

### Example: Comparing Tuning Levels

```java
BenchmarkFramework framework = BenchmarkFramework.getInstance();
StorageEngineBenchmarkSuite suite = (StorageEngineBenchmarkSuite) framework.getBenchmarkSuite("storage-engine");

// Run with different tuning levels
Map<String, Map<String, Object>> paramSets = new HashMap<>();

Map<String, Object> performanceParams = new HashMap<>();
performanceParams.put("tuningLevel", StorageEngineTuner.TuningLevel.PERFORMANCE);
paramSets.put("performance", performanceParams);

Map<String, Object> balancedParams = new HashMap<>();
balancedParams.put("tuningLevel", StorageEngineTuner.TuningLevel.BALANCED);
paramSets.put("balanced", balancedParams);

suite.setParameterSets(paramSets);
framework.runBenchmark("storage-engine");
```

## Reporting

The framework provides comprehensive reporting capabilities:

- HTML reports with interactive charts
- JSON data for further analysis
- CSV data for importing into spreadsheets
- Integration with monitoring systems

Reports include:

- Throughput (operations per second)
- Latency (response time)
- Resource utilization (CPU, memory)
- Error rates
- Scalability characteristics

## Best Practices

When using the Performance Testing Framework:

1. **Isolation**: Ensure tests run in an isolated environment to prevent external factors from affecting results.
2. **Repeatability**: Run tests multiple times to ensure consistent results.
3. **Warm-up**: Include warm-up periods to allow the system to reach a steady state.
4. **Realistic Workloads**: Use workload patterns that reflect real-world usage.
5. **Comprehensive Metrics**: Collect metrics for all relevant aspects of the system.
6. **Historical Tracking**: Maintain a history of benchmark results to track performance over time.
7. **Regression Detection**: Set appropriate thresholds for detecting performance regressions.

## Future Enhancements

Planned enhancements to the Performance Testing Framework include:

1. More benchmark suites for other components
2. Additional workload patterns based on production data
3. Enhanced visualization and reporting
4. Integration with more CI/CD platforms
5. Distributed load generation for higher throughput testing
6. Machine learning-based anomaly detection