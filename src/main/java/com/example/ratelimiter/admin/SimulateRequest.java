package com.example.ratelimiter.admin;

import jakarta.validation.constraints.NotBlank;

public record SimulateRequest(String method, @NotBlank String path, String ip, String empId, String deptId,
                              String apiKey) {
}
