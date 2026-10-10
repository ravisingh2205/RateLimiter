package com.example.ratelimiter.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PathMatcherTest {

    @Test
    void exactAndTrailingSlash() {
        assertTrue(PathMatcher.matches("/api/employees", "/api/employees"));
        assertTrue(PathMatcher.matches("/api/employees", "/api/employees/"));
        assertFalse(PathMatcher.matches("/api/employees", "/api/employees/1"));
    }

    @Test
    void singleSegmentWildcards() {
        assertTrue(PathMatcher.matches("/api/employees/{id}", "/api/employees/42"));
        assertTrue(PathMatcher.matches("/api/*/export", "/api/reports/export"));
        assertFalse(PathMatcher.matches("/api/employees/{id}", "/api/employees"));
        assertFalse(PathMatcher.matches("/api/employees/{id}", "/api/employees/1/x"));
    }

    @Test
    void doubleStar() {
        assertTrue(PathMatcher.matches("/api/**", "/api/a/b/c"));
        assertTrue(PathMatcher.matches("/api/**", "/api"));
        assertTrue(PathMatcher.matches("/api/**/export", "/api/x/y/export"));
        assertFalse(PathMatcher.matches("/api/**/export", "/api/x/y/import"));
    }
}
