package com.example.ratelimiter.model;

/** What a limit is keyed on. GLOBAL means "all callers combined". */
public enum Dimension { IP, EMP, DEPT, API_KEY, GLOBAL }
