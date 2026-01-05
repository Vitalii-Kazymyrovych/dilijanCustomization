package com.incoresoft.dilijanCustomization.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnknownListRegistryTest {

    @Test
    void throwsIfNotInitialized() {
        UnknownListRegistry registry = new UnknownListRegistry();
        assertFalse(registry.isInitialized());
        assertThrows(IllegalStateException.class, registry::get);
    }

    @Test
    void setAndGetPositiveId() {
        UnknownListRegistry registry = new UnknownListRegistry();
        registry.set(123L);

        assertTrue(registry.isInitialized());
        assertEquals(123L, registry.get());
    }

    @Test
    void rejectsNonPositiveId() {
        UnknownListRegistry registry = new UnknownListRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.set(0));
        assertThrows(IllegalArgumentException.class, () -> registry.set(-5));
        assertFalse(registry.isInitialized());
    }
}
