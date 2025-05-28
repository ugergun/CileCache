import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * TimeoutCache is a Guava-based cache implementation with timeout functionality.
 * It provides a simple wrapper around Guava's cache with additional methods
 * for compatibility with CileCache.
 *
 * @param <K> The type of keys maintained by this cache
 * @param <V> The type of values maintained by this cache
 */
public abstract class GuavaTestCache<K, V> {

    private static final Logger logger = Logger.getLogger(GuavaTestCache.class.getName());
    
    private final Cache<K, V> cache;
    private final boolean expireProcess;
    
    /**
     * Constructs a TimeoutCache with the specified expiration processing option and timeout.
     *
     * @param expireProcess whether to enable expiration processing
     * @param timeoutInSec the timeout period in seconds
     */
    public GuavaTestCache(boolean expireProcess, int timeoutInSec) {
        this.expireProcess = expireProcess;
        
        // Build the Guava cache with expiration
        cache = CacheBuilder.newBuilder()
                .expireAfterWrite(timeoutInSec, TimeUnit.SECONDS)
                .removalListener(notification -> {
                    if (expireProcess && notification.wasEvicted()) {
                        try {
                            processAfterExpire((K) notification.getKey(), (V) notification.getValue());
                        } catch (Exception e) {
                            logger.error("Error processing expired entry", e);
                        }
                    }
                })
                .build();
    }
    
    /**
     * Abstract method that must be implemented by subclasses to define
     * what happens when an entry expires.
     *
     * @param key the key of the expired entry
     * @param obj the value of the expired entry
     */
    public abstract void processAfterExpire(K key, V obj);
    
    /**
     * Adds a key-value pair to the cache.
     * If the key already exists, it will be replaced.
     *
     * @param key the key to add
     * @param value the value to associate with the key
     */
    public void put(K key, V value) {
        if (key == null) {
            logger.warn("Attempted to add null key to cache");
            return;
        }
        
        try {
            cache.put(key, value);
        } catch (Exception e) {
            logger.error("Error adding entry to cache", e);
        }
    }
    
    /**
     * Retrieves a value from the cache by its key.
     *
     * @param key the key to look up
     * @return the value associated with the key, or null if the key wasn't in the cache
     */
    public V getByKey(K key) {
        if (key == null) return null;
        
        try {
            return cache.getIfPresent(key);
        } catch (Exception e) {
            logger.error("Error retrieving entry from cache", e);
            return null;
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
            V value = cache.getIfPresent(key);
            cache.invalidate(key);
            return value;
        } catch (Exception e) {
            logger.error("Error deleting entry from cache", e);
            return null;
        }
    }
    
    /**
     * Removes all entries from the cache.
     */
    public void deleteAll() {
        try {
            cache.invalidateAll();
        } catch (Exception e) {
            logger.error("Error clearing cache", e);
        }
    }
    
    /**
     * Removes multiple entries from the cache.
     *
     * @param keys a collection of keys to remove
     */
    public void deleteSome(Collection<K> keys) {
        if (keys == null || keys.isEmpty()) return;
        
        try {
            cache.invalidateAll(keys);
        } catch (Exception e) {
            logger.error("Error removing entries from cache", e);
        }
    }
    
    /**
     * Processes all entries in the cache.
     * This is useful for cleaning up before shutdown.
     */
    public void cleanup() {
        try {
            cache.cleanUp();
        } catch (Exception e) {
            logger.error("Error cleaning up cache", e);
        }
    }
    
    /**
     * Returns the approximate number of entries in the cache.
     *
     * @return the approximate number of entries in the cache
     */
    public long getSize() {
        try {
            return cache.size();
        } catch (Exception e) {
            logger.error("Error getting cache size", e);
            return 0;
        }
    }
}