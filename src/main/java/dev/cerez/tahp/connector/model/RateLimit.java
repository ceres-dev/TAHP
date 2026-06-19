package dev.cerez.tahp.connector.model;

import dev.cerez.tahp.model.RateLimitType;

import java.util.concurrent.TimeUnit;

public record RateLimit(
        RateLimitType rateLimitType,
        TimeUnit interval,
        Integer intervalNum,
        Integer limit
) {
}
