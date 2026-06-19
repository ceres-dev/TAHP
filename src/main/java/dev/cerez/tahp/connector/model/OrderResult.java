package dev.cerez.tahp.connector.model;

public record OrderResult(
        Long orderId,
        Double executedQty,
        Double cumulativeQuoteQty,
        Double receivedQty
) {

}
