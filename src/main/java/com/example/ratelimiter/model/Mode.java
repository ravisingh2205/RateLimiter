package com.example.ratelimiter.model;

/** SHADOW = evaluate and record would-be denials, but never block. Use it to roll out new rules safely. */
public enum Mode { ENFORCE, SHADOW }
