package com.example.ratelimiter.model;

public record ApiEndpoint(String apiId, String httpMethod, String pathPattern, String description) {
}
