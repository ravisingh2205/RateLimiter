package com.example.ratelimiter.admin;

import jakarta.validation.constraints.NotBlank;

public record PlanRequest(@NotBlank String name, String description) {
}
