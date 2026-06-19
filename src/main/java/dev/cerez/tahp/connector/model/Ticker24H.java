package dev.cerez.tahp.connector.model;

public record Ticker24H(
        String symbol,
        Double priceChange,
        Double priceChangePercent,
        Double quoteVolumen,
        Double baseVolumen
) {
}
