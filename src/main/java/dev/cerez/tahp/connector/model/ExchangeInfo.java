package dev.cerez.tahp.connector.model;

import java.util.HashMap;
import java.util.List;

public record ExchangeInfo(
        List<RateLimit> rateLimits,
        HashMap<String, Symbol> symbols
) {
}
