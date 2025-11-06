package com.incoresoft.dilijanCustomization.config;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Holds the runtime-resolved Unknown list ID (thread-safe).
 */
@Component
public class UnknownListRegistry {
    private final AtomicLong unknownListId = new AtomicLong(-1L);

    public long get() {
        long v = unknownListId.get();
        if (v <= 0) throw new IllegalStateException("Unknown list id is not initialized yet");
        return v;
    }

    public void set(long id) {
        if (id <= 0) throw new IllegalArgumentException("Unknown list id must be positive");
        unknownListId.set(id);
    }

    public boolean isInitialized() {
        return unknownListId.get() > 0;
    }
}
