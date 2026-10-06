package process.util;

import org.barco.platform.cache.VersionStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The shared Redis version, for tests that stand several instances beside each other. */
public final class InMemoryVersionStore implements VersionStore {

    private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();

    @Override
    public long current(String cache) {
        return this.versions.computeIfAbsent(cache, name -> new AtomicLong()).get();
    }

    @Override
    public long bump(String cache) {
        return this.versions.computeIfAbsent(cache, name -> new AtomicLong()).incrementAndGet();
    }
}
