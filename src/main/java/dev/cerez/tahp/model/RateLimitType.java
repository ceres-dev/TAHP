package dev.cerez.tahp.model;

public enum RateLimitType {
    REQUEST_WEIGHT,
    ORDERS,
    // Solo Spot
    RAW_REQUESTS,
    // Solo webSocket
    CONNECTIONS
}
