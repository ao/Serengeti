# Storage Compression System

The Storage Compression System in Serengeti provides efficient data compression capabilities to reduce storage requirements and improve I/O performance. This document describes the compression algorithms available, their characteristics, and how to use them effectively.

## Overview

The `StorageCompressor` class provides a unified interface for compressing and decompressing data in the Serengeti distributed database system. It supports multiple compression algorithms with different performance characteristics, allowing users to choose the most appropriate algorithm for their specific use case.

## Supported Compression Algorithms

### GZIP

GZIP is a widely used compression algorithm based on the DEFLATE algorithm. It provides good compression ratios at the cost of relatively high CPU usage.

**Characteristics:**
- **Compression Ratio**: High
- **Compression Speed**: Moderate
- **Decompression Speed**: Moderate
- **CPU Usage**: High
- **Memory Usage**: Moderate

**Best for:**
- Archival storage
- Situations where storage space is at a premium
- Data that will be read infrequently

### LZ4

LZ4 is a fast compression algorithm focused on speed rather than compression ratio. It provides extremely fast compression and decompression speeds, making it ideal for high-throughput scenarios.

**Characteristics:**
- **Compression Ratio**: Moderate
- **Compression Speed**: Very High
- **Decompression Speed**: Extremely High
- **CPU Usage**: Low
- **Memory Usage**: Low

**Best for:**
- High-throughput data processing
- Real-time applications
- Situations where CPU usage is a concern
- Frequently accessed data

### SNAPPY

Snappy is Google's compression algorithm designed for high speed rather than maximum compression. It aims for very high speeds and reasonable compression.

**Characteristics:**
- **Compression Ratio**: Moderate
- **Compression Speed**: Very High
- **Decompression Speed**: Extremely High
- **CPU Usage**: Low
- **Memory Usage**: Low

## Performance Comparison

| Algorithm | Compression Ratio | Compression Speed | Decompression Speed | CPU Usage | Memory Usage |
|-----------|-------------------|-------------------|---------------------|-----------|--------------|
| GZIP      | High              | Moderate          | Moderate            | High      | Moderate     |
| LZ4       | Moderate          | Very High         | Extremely High      | Low       | Low          |
| SNAPPY    | Moderate          | Very High         | Extremely High      | Low       | Low          |

## Usage Guidelines

### When to Use GZIP

- For cold storage or archival data
- When storage space is limited and CPU resources are available
- For data that is written once and read infrequently

### When to Use LZ4

- For hot data that is frequently accessed
- In high-throughput scenarios
- When CPU resources are limited
- For real-time applications where latency is critical

### When to Use Snappy

- For very high-throughput scenarios where decompression speed is critical
- When working with Google technologies (as it's optimized for Google's infrastructure)
- For applications where consistent performance is more important than maximum compression
- When you need a good balance between compression ratio and speed

## Implementation Details

The `StorageCompressor` class provides a simple API for compressing and decompressing data:

```java
// Create a compressor with the desired algorithm
StorageCompressor compressor = new StorageCompressor(CompressionAlgorithm.LZ4);

// Compress data
byte[] compressed = compressor.compress(originalData);

// Decompress data
String decompressed = compressor.decompress(compressed);
```

### Compression Levels

The `StorageCompressor` supports different compression levels (1-9) for algorithms that support it:

```java
// Create a compressor with high compression level
StorageCompressor highCompressor = new StorageCompressor(CompressionAlgorithm.GZIP, 9);

// Create a compressor with low compression level (faster)
StorageCompressor lowCompressor = new StorageCompressor(CompressionAlgorithm.GZIP, 1);
```

Higher compression levels generally result in better compression ratios but slower compression speeds. Decompression speed is typically not affected by the compression level.

## Integration with Storage System

The compression system is designed to be integrated with the Serengeti storage system. The `StorageImpl` class uses the `StorageCompressor` to compress data before writing it to disk and decompress it when reading.

### Configuration

The compression algorithm and level can be configured in the Serengeti configuration file:

```json
{
  "storage": {
    "compression": {
      "algorithm": "LZ4",
      "level": 6
    }
  }
}
```

## Performance Considerations

### Memory Usage

Compression and decompression operations require additional memory. The amount of memory required depends on the algorithm and the size of the data being compressed or decompressed.

### CPU Usage

Compression operations can be CPU-intensive, especially with GZIP at high compression levels. Consider the available CPU resources when choosing a compression algorithm and level.

### I/O Performance

While compression reduces the amount of data that needs to be written to or read from disk, it also adds CPU overhead. The net effect on I/O performance depends on the specific hardware, the compression algorithm, and the characteristics of the data.

## Future Enhancements

1. **Adaptive Compression**: Automatically select the best compression algorithm based on data characteristics
2. **Compression Dictionary**: Support for shared dictionaries to improve compression ratios for similar data
3. **Parallel Compression**: Utilize multiple CPU cores for compression and decompression operations
4. **Hardware Acceleration**: Support for hardware-accelerated compression where available
5. **Additional Compression Algorithms**: Implementation of other compression algorithms like Zstandard (zstd) or Brotli

## Conclusion

The Storage Compression System provides a flexible and efficient way to reduce storage requirements and potentially improve I/O performance in the Serengeti distributed database system. By choosing the appropriate compression algorithm and level, users can optimize for their specific use case and hardware constraints.