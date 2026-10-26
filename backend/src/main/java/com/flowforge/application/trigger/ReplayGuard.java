package com.flowforge.application.trigger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers signed webhook deliveries until their timestamp leaves the accepted window, so a captured request cannot be
 * replayed. In-memory and per process (single-instance MVP): a restart forgets the entries, which is acceptable because
 * the timestamp window still bounds the exposure. When full, new deliveries are refused rather than evicting entries
 * that are still inside the window.
 */
public class ReplayGuard {

    private final int capacity;
    private final Map<String, Instant> seen = new HashMap<>();

    public ReplayGuard(int capacity) {
        this.capacity = capacity;
    }

    public enum Result { ACCEPTED, REPLAYED, FULL }

    /** Records {@code key} until {@code expiresAt}; {@code now} purges expired entries. */
    public synchronized Result record(String key, Instant expiresAt, Instant now) {
        Instant previous = seen.get(key);
        if (previous != null && previous.isAfter(now)) {
            return Result.REPLAYED;
        }
        if (seen.size() >= capacity) {
            seen.values().removeIf(expiry -> !expiry.isAfter(now));
            if (seen.size() >= capacity) {
                return Result.FULL;
            }
        }
        seen.put(key, expiresAt);
        return Result.ACCEPTED;
    }

    synchronized int size() {
        return seen.size();
    }
}
