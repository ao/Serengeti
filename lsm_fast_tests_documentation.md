# LSM Storage Engine Fast Tests

## Overview

This document describes the fast test suite for the LSM (Log-Structured Merge-tree) storage engine components. The fast test suite is designed to run quickly (under 2 minutes) and provide rapid feedback during development. It focuses on testing core functionality with minimal setup and dependencies.

## Components

The fast test suite includes tests for the following components:

1. **LSMStorageEngine** - The main class that coordinates the LSM-Tree components
2. **MemTable** - In-memory data structure for storing key-value pairs
3. **SSTable** - On-disk sorted string table for persistent storage

## Test Base Classes

### LightweightTestBase

The base class for all fast tests. It provides:
- Temporary directory creation for test data
- Minimal setup and teardown
- Utility methods for generating random names

### LSMFastTestBase

Extends LightweightTestBase and provides:
- LSMStorageEngine initialization with a temporary directory
- Helper methods for creating MemTables and test data

## Test Classes

### LSMStorageEngineFastTest

Tests the core functionality of the LSMStorageEngine:
- Basic put and get operations
- Delete operations
- Multiple puts and gets
- Handling of null keys and values
- Update of existing keys

### MemTableFastTest

Tests the core functionality of the MemTable:
- Put and get operations
- Size tracking
- Update of existing keys
- Delete operations
- Flush threshold
- Snapshot creation

### SSTableFastTest

Tests the core functionality of the SSTable:
- Creation from MemTable
- Reading data back
- Bloom filter functionality
- Tombstone handling
- Metadata correctness

## Running the Tests

### Unix/Linux/macOS

```bash
./run_lsm_fast_tests.sh
```

### Windows

```cmd
run_lsm_fast_tests.bat
```

## Benefits

1. **Speed**: The fast tests run in seconds rather than minutes or hours
2. **Isolation**: Each test is isolated and doesn't depend on external resources
3. **Focused**: Tests focus on core functionality rather than edge cases
4. **Lightweight**: Uses in-memory components where possible to avoid disk I/O
5. **Developer-friendly**: Provides quick feedback during development

## Implementation Details

1. **In-memory Storage**: Uses in-memory implementations where possible
2. **Temporary Directories**: Creates and cleans up temporary directories for each test
3. **Minimal Setup**: Initializes only the components needed for each test
4. **Parallel Execution**: Tests can run in parallel for faster execution

## Future Improvements

1. Add more focused tests for specific edge cases
2. Improve test coverage for compaction and recovery scenarios
3. Add performance benchmarks to the fast test suite
4. Add more parameterized tests to cover a wider range of inputs