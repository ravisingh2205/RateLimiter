package com.example.ratelimiter.admin;

import com.example.ratelimiter.model.Dimension;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubjectPlanRequest(@NotNull Dimension dimension, @NotBlank String subjectValue, @NotNull Long planId) {
}
