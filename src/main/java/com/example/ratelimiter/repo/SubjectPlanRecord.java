package com.example.ratelimiter.repo;

import com.example.ratelimiter.model.Dimension;

public record SubjectPlanRecord(Dimension dimension, String subjectValue, long planId, String planName) {
}
