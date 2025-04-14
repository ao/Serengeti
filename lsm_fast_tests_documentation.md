# Serengeti Fast Tests

## Overview

This document describes the fast test suite for Serengeti components. The fast test suite is designed to run quickly (under 2 minutes) and provide rapid feedback during development. It focuses on testing core functionality with minimal setup and dependencies.

## Components

The fast test suite includes tests for the following components:

### Storage Components

1. **LSMStorageEngine** - The main class that coordinates the LSM-Tree components
2. **MemTable** - In-memory data structure for storing key-value pairs
3. **SSTable** - On-disk sorted string table for persistent storage

### Network Components

1. **Network** - The component responsible for node discovery and communication

### Query Components

1. **QueryEngine** - The component responsible for parsing and executing queries

### Server Components

1. **Server** - The component responsible for handling HTTP requests and responses

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

### NetworkFastTestBase

Extends LightweightTestBase and provides:
- MockNetwork initialization
- Helper methods for adding mock nodes and setting mock responses
- Utility methods for generating random node IDs and IP addresses

### QueryFastTestBase

Extends LightweightTestBase and provides:
- MockQueryEngine initialization
- Helper methods for query testing
- Utility methods for generating test queries

### ServerFastTestBase

Extends LightweightTestBase and provides:
- MockServer initialization
- Helper methods for HTTP request testing
- Utility methods for simulating client requests

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

### NetworkFastTest

Tests the core functionality of the Network component:
- Network initialization
- Adding and retrieving nodes
- Getting IP from UUID
- Communicating query logs to single and all nodes
- Getting primary and secondary nodes
- Getting random available nodes

### QueryFastTest

Tests the core functionality of the Query Engine:
- Query parsing
- Query execution
- Error handling
- Query optimization

### ServerFastTest

Tests the core functionality of the Server component:
- Server initialization
- Request handling
- Response generation
- Error handling

## Running the Tests

### Unix/Linux/macOS

```bash
./run_fast_tests.sh
```

### Windows

```cmd
run_fast_tests.bat
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