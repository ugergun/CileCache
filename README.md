# CileCache

A high-performance cache implementation with timeout functionality that distributes expiration processing across multiple active threads for optimal performance in high-traffic environments.

**[Visit the CileCache Website](https://ugergun.github.io/CileCache/)** for detailed documentation, performance benchmarks, and examples.

## Features

- **High Performance**: Efficiently processes cache expiration concurrently with add/get operations
- **Thread Distribution**: Distributes expiration processing across active threads
- **Flexible Timeout**: Configurable timeout periods for cache entries
- **Minimal Overhead**: Low impact on application performance during normal operations
- **Automatic Cleanup**: Background thread handles expired entries
- **Thread Safety**: Fully thread-safe implementation using concurrent collections

## Key Advantages

CileCache offers several advantages:

1. **Concurrent Expiration Processing**: Expiration happens in parallel with normal cache operations
2. **Distributed Processing**: Workload is distributed across multiple threads
3. **Adaptive Thread Management**: Automatically adjusts the number of active threads
4. **Minimal Blocking**: Non-blocking implementation for most operations
5. **Efficient Memory Usage**: Partitioned design for better memory management

## Usage

### Basic Usage

```java
import tr.com.ug.project.cache.CileCache;

// Create a cache with 60 seconds timeout
CileCache<String, User> userCache = new CileCache<String, User>(60) {
    @Override
    public void expireProcess(String key, User value) {
        // Handle expired entries here
        System.out.println("User " + value.getName() + " expired");
    }
};

// Add an entry
userCache.add("user123", new User("John Doe"));

// Get an entry
User user = userCache.getByKey("user123");

// Delete an entry
userCache.delete("user123");
```

## API Documentation

### Constructors

- `CileCache(int timeoutInSec)`: Creates a cache with the specified timeout in seconds
- `CileCache(int timeoutInSec, boolean expireProcess)`: Creates a cache with the specified timeout and expiration processing option

### Methods

- `void add(K key, V value)`: Adds a key-value pair to the cache
- `V delete(K key)`: Removes a key-value pair from the cache
- `V getByKey(K key)`: Retrieves a value from the cache by its key
- `void cleanup()`: Processes all entries in all expiration queues
- `long getSize()`: Returns the number of entries in the cache
- `void deleteAll()`: Removes all entries from the cache
- `void deleteSome(ArrayList<K> keys)`: Removes multiple entries from the cache
- `void resetTime(K key)`: Resets the expiration time for an entry

### Abstract Methods

- `void expireProcess(K key, V value)`: Must be implemented to define what happens when an entry expires

## Performance Considerations

- The cache is optimized for high-traffic environments with many concurrent operations
- Performance benefits are most noticeable in multi-threaded applications with frequent cache operations
- The cache automatically balances the number of active threads to prevent thread explosion
- The timeout check interval scales with the timeout duration (e.g., with a 5-hour timeout, the check thread will process expired entries approximately every 60 seconds due to the 300 partitions)
- For applications with low traffic, it's recommended to periodically call the `cleanup()` method to ensure expired entries are processed

## License

This project is licensed under the [Apache License 2.0](LICENSE) - see the LICENSE file for details.

The Apache License 2.0 is a permissive free software license that allows you to:

- Use the software for any purpose
- Distribute, modify, and redistribute the software
- Apply your own license to your modifications
- Use the software in commercial applications

The license requires you to:

- Include the original license and copyright notice
- State significant changes made to the software
- Include the NOTICE file if one exists (not applicable for this project)

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

When contributing to this project, please ensure that your code follows the existing style and includes appropriate license headers.
