package dev.cerez.tahp.connector.model;

public record OrderResult(
        String orderId,
        Double executedQty,
        Double cumulativeQuoteQty,
        Double receivedQty
) {

}
