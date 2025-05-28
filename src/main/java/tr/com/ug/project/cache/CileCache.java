package tr.com.ug.project.cache;/*
 * Copyright 2025 Umut Gergun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CileCache is a high-performance cache implementation with timeout functionality.
 * It provides better performance by distributing expiration processing across multiple active threads.
 * The cache partitions items based on their expiration time and uses a thread-based approach
 * to handle expiration concurrently with add/get operations.
 *
 * @param <K> The type of keys maintained by this cache
 * @param <V> The type of values maintained by this cache
 */
public abstract class CileCache<K, V> {

    private static final Logger logger = Logger.getLogger(CileCache.class.getName());
    private static final int DEFAULT_QUEUE_CAPACITY = 1000000;
    private static final int DEFAULT_PARTITION_COUNT = 300;
    private static final long SLEEP_BASE_MILLIS = 500L;

    /** Map to store cache entries partitioned by expiration time */
    private final Map<Integer, Map<K, V>> cacheMap = new ConcurrentHashMap<>();

    /** Map to track which partition each key belongs to */
    private final Map<K, Integer> localMap = new ConcurrentHashMap<>();

    /** Map of queues for each thread to process expired entries */
    private final Map<Integer, BlockingQueue<Map.Entry<K, V>>> queueMap = new ConcurrentHashMap<>();

    /** Queue for expired entries when no active threads are available */
    private final BlockingQueue<Map.Entry<K, V>> waitingQueue = new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY);

    /** Flag to prevent multiple threads from processing the waiting queue simultaneously */
    private volatile boolean waitingQueueAttached = false;

    /** Map to track which queue index is assigned to each thread ID */
    private final Map<Long, Integer> threadIdMap = new ConcurrentHashMap<>();

    /** List of active thread IDs that can process expired entries */
    private volatile List<Long> activeThreads = new CopyOnWriteArrayList<>();

    /** Counter for assigning unique queue indices */
    private final AtomicInteger queueCount = new AtomicInteger(0);

    /** Timeout value in milliseconds */
    private final int timeoutInMs;

    /** Factor used to calculate the partition index */
    private final int factor;

    /** Number of partitions to divide the cache into */
    private final int partition = DEFAULT_PARTITION_COUNT;

    /** Flag to enable/disable expiration processing */
    private final boolean expireProcess;

    /**
     * Constructs a CileCache with the specified timeout in seconds.
     * Expiration processing is enabled by default.
     *
     * @param timeoutInSec the timeout period in seconds
     */
    public CileCache(int timeoutInSec) {
        this(timeoutInSec, true);
    }

    /**
     * Constructs a CileCache with the specified timeout and expiration processing option.
     *
     * @param timeoutInSec the timeout period in seconds
     * @param expireProcess whether to enable expiration processing
     */
    public CileCache(int timeoutInSec, boolean expireProcess) {
        this.expireProcess = expireProcess;
        // Calculate factor based on timeout and partition size
        factor = Math.max(1, (timeoutInSec / partition) + 1);
        this.timeoutInMs = timeoutInSec * 1000;

        // Initialize cache partitions
        initializeCachePartitions();

        // Start the background thread for checking timeouts
        startTimeoutCheckThread();
    }

    /**
     * Initializes the cache partitions.
     */
    private void initializeCachePartitions() {
        for (int i = 0; i < partition; i++) {
            cacheMap.put(i, new ConcurrentHashMap<>());
        }
    }

    /**
     * Starts the background thread that checks for expired entries.
     */
    private void startTimeoutCheckThread() {
        Thread timeoutCheckThread = new Thread(() -> runTimeoutCheckLoop());
        timeoutCheckThread.setName("CILE_TIMEOUT_CHECK_THREAD");
        timeoutCheckThread.setDaemon(true); // Make it a daemon thread so it doesn't prevent JVM shutdown
        timeoutCheckThread.start();
    }

    /**
     * The main loop for the timeout check thread.
     */
    private void runTimeoutCheckLoop() {
        long period = 0;
        while (true) {
            try {
                // Sleep for a duration proportional to the factor
                Thread.sleep(SLEEP_BASE_MILLIS * factor);
                long currentTime = System.currentTimeMillis();

                // Calculate the current partition to process
                int currentPartition = calculatePartitionIndex(currentTime);
                Map<K, V> map = cacheMap.get(currentPartition);

                if (map != null && !map.isEmpty()) {
                    processPartition(map, currentPartition, currentTime, period++);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Timeout check thread interrupted", e);
                break;
            } catch (Exception e) {
                logger.error("Error in timeout check thread", e);
            }
        }
    }

    /**
     * Processes a partition of expired entries.
     * 
     * @param map the map containing expired entries
     * @param currentPartition the current partition being processed
     * @param startTime the time when processing started
     * @param period the current period counter
     */
    private void processPartition(Map<K, V> map, int currentPartition, long startTime, long period) {
        if (expireProcess) {
            if (!activeThreads.isEmpty()) {
                // Distribute expired entries among active threads
                distributeExpiredEntries(map);

                // Redistribute tasks from inactive threads to active threads
                redistributeTasksFromInactiveThreads();
            } else {
                // If no active threads, add all expired entries to the waiting queue
                if (!waitingQueue.addAll(map.entrySet())) {
                    logger.warn("Failed to add all expired entries to waiting queue - queue might be full");
                }

                if (logger.isInfoEnabled()) {
                    logger.info("No active thread found. Tasks will be sent to waiting queue");
                }
            }

            // Periodically reduce the number of active threads to balance load
            if (period % 3 == 0) {
                balanceActiveThreads();
            }
        } else {
            // If expiration processing is disabled, just remove entries from localMap
            for (K k : map.keySet()) {
                localMap.remove(k);
            }
        }

        // Log processing statistics
        logProcessingStatistics(currentPartition, startTime, map);

        // Clear the processed partition
        map.clear();
    }

    /**
     * Calculates the partition index for a given timestamp.
     * 
     * @param timestamp the timestamp to calculate the partition for
     * @return the partition index
     */
    private int calculatePartitionIndex(long timestamp) {
        return (int) (timestamp / factor / 1000L) % partition;
    }

    /**
     * Distributes expired entries among active threads.
     * 
     * @param map the map containing expired entries
     */
    private void distributeExpiredEntries(Map<K, V> map) {
        int activeThreadCount = activeThreads.size();
        if (activeThreadCount == 0 || map.isEmpty()) return;

        // Calculate how many entries each thread should process
        int entriesPerThread = Math.max(1, map.size() / activeThreadCount);
        int currentCount = 0;
        int threadIndex = 0;

        // Distribute entries to active threads
        for (Map.Entry<K, V> entry : map.entrySet()) {
            // Move to next thread when current thread's quota is reached
            if (currentCount >= entriesPerThread) {
                currentCount = 0;
                threadIndex = (threadIndex + 1) % activeThreadCount;
            }

            // Add entry to the thread's queue
            addEntryToThreadQueue(entry, threadIndex);
            currentCount++;
        }
    }

    /**
     * Adds an entry to a thread's queue.
     * 
     * @param entry the entry to add
     * @param threadIndex the index of the thread in the activeThreads list
     */
    private void addEntryToThreadQueue(Map.Entry<K, V> entry, int threadIndex) {
        if (threadIndex < 0 || threadIndex >= activeThreads.size()) return;

        K key = entry.getKey();
        long threadId = activeThreads.get(threadIndex);
        Integer queueIndex = threadIdMap.get(threadId);

        if (queueIndex != null) {
            // Remove from localMap and add to the thread's queue
            localMap.remove(key);
            BlockingQueue<Map.Entry<K, V>> queue = queueMap.get(queueIndex);
            if (queue != null && !queue.offer(entry)) {
                logger.warn("Failed to add entry to queue " + queueIndex + " - queue might be full");
            }
        }
    }

    /**
     * Redistributes tasks from inactive threads to active threads.
     */
    private void redistributeTasksFromInactiveThreads() {
        int activeThreadCount = activeThreads.size();
        if (activeThreadCount == 0) return;

        // Check each thread in the threadIdMap
        for (Long threadId : new HashSet<>(threadIdMap.keySet())) {
            if (!activeThreads.contains(threadId)) {
                redistributeTasksFromThread(threadId, activeThreadCount);
            }
        }
    }

    /**
     * Redistributes tasks from a specific inactive thread to active threads.
     * 
     * @param inactiveThreadId the ID of the inactive thread
     * @param activeThreadCount the number of active threads
     */
    private void redistributeTasksFromThread(long inactiveThreadId, int activeThreadCount) {
        Integer queueIndex = threadIdMap.get(inactiveThreadId);
        if (queueIndex == null) return;

        BlockingQueue<Map.Entry<K, V>> queue = queueMap.get(queueIndex);
        if (queue == null || queue.isEmpty()) return;

        if (logger.isInfoEnabled()) {
            logger.info("Thread " + inactiveThreadId + " is not active. Tasks will be sent to other threads");
        }

        // Copy entries to a list to avoid ConcurrentModificationException
        List<Map.Entry<K, V>> entries = new ArrayList<>(queue);
        int entriesPerThread = Math.max(1, entries.size() / activeThreadCount);
        int currentCount = 0;
        int threadIndex = 0;

        // Redistribute entries to active threads
        for (Map.Entry<K, V> entry : entries) {
            // Move to next thread when current thread's quota is reached
            if (currentCount >= entriesPerThread) {
                currentCount = 0;
                threadIndex = (threadIndex + 1) % activeThreadCount;
            }

            addEntryToThreadQueue(entry, threadIndex);
            currentCount++;
        }

        // Clear the queue of the inactive thread
        queue.clear();
    }

    /**
     * Balances the active threads list by keeping only a subset of threads.
     * This helps prevent too many threads from being active at once.
     */
    private void balanceActiveThreads() {
        if (activeThreads.isEmpty()) return;

        List<Long> newActiveThreadList = new CopyOnWriteArrayList<>();

        // Keep only odd-indexed threads (reduces active threads by ~50%)
        for (int i = 0; i < activeThreads.size(); i++) {
            if (i % 2 == 1) {
                newActiveThreadList.add(activeThreads.get(i));
            }
        }

        // If we ended up with no threads, keep at least one
        if (newActiveThreadList.isEmpty()) {
            newActiveThreadList.add(activeThreads.get(0));
        }

        activeThreads = newActiveThreadList;
    }

    /**
     * Logs statistics about the current processing cycle.
     * 
     * @param currentSec the current partition being processed
     * @param startTime the time when processing started
     * @param map the map being processed
     */
    private void logProcessingStatistics(int currentSec, long startTime, Map<K, V> map) {
        if (logger.isInfoEnabled()) {
            logger.info(currentSec + " sec is processing in " + (System.currentTimeMillis() - startTime) + " ms " +
                    "partition cache size: " + map.size() + " total cache size: " + localMap.size() +
                    " active threads: " + activeThreads.size() + " thread id map size: " + threadIdMap.size() +
                    " queue count: " + queueCount.get() + " queue map size: " + queueMap.size());
        }

        if (logger.isDebugEnabled()) {
            for (Integer queueIndex : queueMap.keySet()) {
                BlockingQueue<Map.Entry<K, V>> queue = queueMap.get(queueIndex);
                if (queue != null) {
                    logger.debug("Expire Queue " + queueIndex + " size: " + queue.size());
                }
            }
            logger.debug("Waiting queue size: " + waitingQueue.size());
        }
    }

    /**
     * Abstract method that must be implemented by subclasses to define
     * what happens when an entry expires.
     *
     * @param key the key of the expired entry
     * @param value the value of the expired entry
     */
    public abstract void expireProcess(K key, V value);

    /**
     * Calculates the partition index for an entry that will expire at the given future time.
     * 
     * @param expirationTime the time when the entry will expire
     * @return the partition index
     */
    private int calculateExpirationPartitionIndex(long expirationTime) {
        return (int) (expirationTime / factor / 1000L) % partition;
    }

    /**
     * Adds a key-value pair to the cache with the default timeout.
     * If the key already exists, it will be replaced.
     * This method also triggers expiration processing on active threads.
     *
     * @param key the key to add
     * @param value the value to associate with the key
     */
    public void add(K key, V value) {
        if (key == null) {
            logger.warn("Attempted to add null key to cache");
            return;
        }

        try {
            // Calculate which partition this entry should go into based on its expiration time
            long expirationTime = System.currentTimeMillis() + timeoutInMs;
            int partitionIndex = calculateExpirationPartitionIndex(expirationTime);
            Map<K, V> map = cacheMap.get(partitionIndex);

            // Remove any existing entry with this key
            delete(key);

            // Add the new entry
            localMap.put(key, partitionIndex);
            map.put(key, value);

            // Process any expired entries if expiration is enabled
            if (expireProcess) {
                expireAction(true);
            }
        } catch (Exception e) {
            logger.error("Error adding entry to cache", e);
        }
    }

    /**
     * Removes a key-value pair from the cache.
     *
     * @param key the key to remove
     * @return the value that was associated with the key, or null if the key wasn't in the cache
     */
    public V delete(K key) {
        if (key == null) return null;

        try {
            // Find which partition contains this key
            Integer partitionIndex = localMap.remove(key);
            if (partitionIndex != null) {
                Map<K, V> map = cacheMap.get(partitionIndex);
                if (map != null) {
                    return map.remove(key);
                }
            }
        } catch (Exception e) {
            logger.error("Error deleting entry from cache", e);
        }
        return null;
    }

    /**
     * Retrieves a value from the cache by its key.
     * This method also triggers expiration processing on active threads.
     *
     * @param key the key to look up
     * @return the value associated with the key, or null if the key wasn't in the cache
     */
    public V getByKey(K key) {
        if (key == null) return null;

        V value = null;
        try {
            // Find which partition contains this key
            Integer partitionIndex = localMap.get(key);
            if (partitionIndex != null) {
                Map<K, V> map = cacheMap.get(partitionIndex);
                if (map != null) {
                    value = map.get(key);
                }
            }

            // Process any expired entries if expiration is enabled
            if (expireProcess) {
                expireAction(false);
            }
        } catch (Exception e) {
            logger.error("Error retrieving entry from cache", e);
        }
        return value;
    }

    /**
     * Processes all entries in all expiration queues.
     * This is useful for cleaning up before shutdown.
     */
    public void cleanup() {
        // Make a copy to avoid concurrent modification
        Set<Integer> queueIndices = new HashSet<>(queueMap.keySet());

        for (Integer index : queueIndices) {
            BlockingQueue<Map.Entry<K, V>> queue = queueMap.get(index);
            if (queue == null) continue;

            // Process all entries in the queue
            List<Map.Entry<K, V>> entries = new ArrayList<>(queue);
            for (Map.Entry<K, V> kvEntry : entries) {
                if (kvEntry != null) {
                    K key = kvEntry.getKey();
                    V value = kvEntry.getValue();
                    localMap.remove(key);
                    try {
                        expireProcess(key, value);
                    } catch (Exception e) {
                        logger.error("Error processing expired entry during cleanup", e);
                    }
                }
            }
            queue.clear();
        }
    }

    /**
     * Returns the number of entries in the cache.
     *
     * @return the number of entries in the cache
     */
    public long getSize() {
        return localMap.size();
    }

    /**
     * Removes all entries from the cache.
     */
    public void deleteAll() {
        for (Map<K, V> map : cacheMap.values()) {
            if (map != null) {
                map.clear();
            }
        }
        localMap.clear();
    }

    /**
     * Removes multiple entries from the cache.
     *
     * @param keys a collection of keys to remove
     */
    public void deleteSome(Collection<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        for (K key : keys) {
            if (key != null) {
                delete(key);
            }
        }
    }

    /**
     * Resets the expiration time for an entry.
     * This is useful when you want to keep an entry in the cache longer.
     *
     * @param key the key whose expiration time should be reset
     */
    public void resetTime(K key) {
        if (key == null) return;

        try {
            // Calculate which partition this entry should go into based on its new expiration time
            long expirationTime = System.currentTimeMillis() + timeoutInMs;
            int partitionIndex = calculateExpirationPartitionIndex(expirationTime);
            Map<K, V> map = cacheMap.get(partitionIndex);

            // Remove the entry from its current partition
            V value = delete(key);

            // If the entry existed, add it to the new partition
            if (value != null && map != null) {
                localMap.put(key, partitionIndex);
                map.put(key, value);
            }
        } catch (Exception e) {
            logger.error("Error resetting expiration time", e);
        }
    }


    /**
     * Processes expired entries and manages active threads.
     * This method is called during add and get operations to ensure that
     * expiration processing happens concurrently with normal cache operations.
     *
     * @param activeCheck if true, registers the current thread as an active thread
     */
    private void expireAction(boolean activeCheck) {
        long threadId = Thread.currentThread().getId();
        Integer queueIndex = threadIdMap.get(threadId);

        // If activeCheck is true, register this thread as an active thread
        if (activeCheck) {
            queueIndex = registerThreadAsActive(threadId, queueIndex);
        }

        // Process entries from this thread's queue
        if (queueIndex != null) {
            BlockingQueue<Map.Entry<K, V>> queue = queueMap.get(queueIndex);
            if (queue != null) {
                // Process up to 2 entries from the queue
                // This limits the time spent in expiration processing during normal operations
                processEntriesFromQueue(queue, 2);
            }
        }

        // Process entries from the waiting queue if it's not empty and not being processed by another thread
        processWaitingQueueIfNeeded(threadId);
    }

    /**
     * Registers the current thread as an active thread.
     * 
     * @param threadId the ID of the thread to register
     * @param queueIndex the current queue index for the thread, or null if none
     * @return the queue index for the thread
     */
    private Integer registerThreadAsActive(long threadId, Integer queueIndex) {
        // If this thread doesn't have a queue index yet, assign one
        if (queueIndex == null) {
            queueIndex = queueCount.getAndIncrement();
            threadIdMap.put(threadId, queueIndex);
        }

        // If this thread doesn't have a queue yet, create one
        if (queueMap.get(queueIndex) == null) {
            queueMap.put(queueIndex, new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY));
        }

        // Add this thread to the active threads list if it's not already there
        if (!activeThreads.contains(threadId)) {
            activeThreads.add(threadId);
        }

        return queueIndex;
    }

    /**
     * Processes a specified number of entries from a queue.
     * 
     * @param queue the queue to process entries from
     * @param count the maximum number of entries to process
     */
    private void processEntriesFromQueue(BlockingQueue<Map.Entry<K, V>> queue, int count) {
        for (int i = 0; i < count; i++) {
            Map.Entry<K, V> entry = queue.poll();
            if (entry == null) break;

            processExpiredEntry(entry);
        }
    }

    /**
     * Processes a single expired entry.
     * 
     * @param entry the entry to process
     */
    private void processExpiredEntry(Map.Entry<K, V> entry) {
        if (entry == null) return;

        try {
            K key = entry.getKey();
            V value = entry.getValue();
            localMap.remove(key);
            expireProcess(key, value);
        } catch (Exception e) {
            logger.error("Error processing expired entry", e);
        }
    }

    /**
     * Processes entries from the waiting queue if it's not empty and not being processed by another thread.
     * 
     * @param threadId the ID of the current thread
     */
    private void processWaitingQueueIfNeeded(long threadId) {
        // Only process the waiting queue if it's not empty and not being processed by another thread
        if (waitingQueue.isEmpty() || waitingQueueAttached) return;

        // Set the flag to prevent other threads from processing the waiting queue simultaneously
        waitingQueueAttached = true;

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Waiting queue is not empty. Thread " + threadId + 
                        " will process waiting queue items (" + waitingQueue.size() + ")");
            }

            // Copy entries to a list to avoid ConcurrentModificationException
            List<Map.Entry<K, V>> entries = new ArrayList<>(waitingQueue);

            // Process all entries in the waiting queue
            for (Map.Entry<K, V> entry : entries) {
                processExpiredEntry(entry);
            }

            // Clear the waiting queue
            waitingQueue.clear();
        } finally {
            // Reset the flag to allow other threads to process the waiting queue in the future
            waitingQueueAttached = false;
        }
    }
}