package com.example.ratelimiter.model;

/** The caller, as seen by the limiter. Any field may be null (e.g. anonymous requests have no empId). */
public record Identity(String ip, String empId, String deptId, String apiKey) {

    /** Value this identity contributes for a dimension, or null when the caller has none. */
    public String valueFor(Dimension d) {
        String v;
        switch (d) {
            case IP: v = ip; break;
            case EMP: v = empId; break;
            case DEPT: v = deptId; break;
            case API_KEY: v = apiKey; break;
            case GLOBAL: return "all";
            default: v = null;
        }
        return (v == null || v.isBlank()) ? null : v;
    }
}
