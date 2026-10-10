package com.example.ratelimiter.model;

/** What to do when the limiter store (Redis) is unreachable. */
public enum FailMode { FAIL_OPEN, FAIL_CLOSED }
