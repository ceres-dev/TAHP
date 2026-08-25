package dev.cerez.tahp.fuding;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record BalancePreview(
        BigDecimal totalBalance,
        BigDecimal booking
) {

    public static final BigDecimal HALF = new BigDecimal("0.50");
    public static final BigDecimal ONE = BigDecimal.ONE;

    public BigDecimal getBookingQuote() {
        return totalBalance
                .multiply(HALF)
                .multiply(booking);
    }

    public BigDecimal getBookingBase(double price) {
        return getBookingBase(Double.valueOf(price));
    }

    public BigDecimal getBookingBase(BigDecimal price) {
        return getBookingQuote()
                .divide(price, 12, RoundingMode.DOWN);
    }

    public BigDecimal getLongQuote() {
        return totalBalance
                .multiply(HALF)
                .multiply(ONE.subtract(booking));
    }

    public BigDecimal getLongBase(double price){
        return getLongBase(BigDecimal.valueOf(price));
    }

    public BigDecimal getLongBase(BigDecimal price) {
        return getLongQuote()
                .divide(price, 12, RoundingMode.DOWN);
    }

    public BigDecimal getBorrowQuote() {
        return totalBalance
                .multiply(HALF);
    }

    public BigDecimal getBorrowBase(double price) {
        return getBorrowBase(BigDecimal.valueOf(price));
    }

    public BigDecimal getBorrowBase(BigDecimal price) {
        return getBorrowQuote()
                .divide(price, 12, RoundingMode.DOWN);
    }

    public BigDecimal getSellFromBorrowQuote() {
        return getBorrowQuote()
                .multiply(ONE.subtract(booking));
    }

    public BigDecimal getSellFromBorrowBase(double price) {
        return getSellFromBorrowBase(BigDecimal.valueOf(price));
    }

    public BigDecimal getSellFromBorrowBase(BigDecimal price) {
        return getBorrowBase(price)
                .multiply(ONE.subtract(booking));
    }

    public BigDecimal getBorrowReserveBase(double price) {
        return getBorrowReserveBase(BigDecimal.valueOf(price));
    }

    public BigDecimal getBorrowReserveBase(BigDecimal price) {
        return getBorrowBase(price)
                .multiply(booking);
    }
}
