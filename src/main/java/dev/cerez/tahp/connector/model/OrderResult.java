package dev.cerez.tahp.connector.model;

import java.math.BigDecimal;

public record OrderResult(
        String orderId,
        BigDecimal executedQty,
        BigDecimal cumulativeQuoteQty,
        BigDecimal receivedQty
) {

}
