package dev.cerez.tahp.fuding;

public record BalancePreview(double totalBalance) {

    public static final double BOOKING = 0.10;
    public static final double HALF = 0.50;

    public double getBookingQuote() {
        return totalBalance * HALF * BOOKING;
    }

    public double getBookingBase(double price) {
        return getBookingQuote() / price;
    }

    public double getLongQuote() {
        return totalBalance * HALF * (1.0 - BOOKING);
    }

    public double getLongBase(double price) {
        return getLongQuote() / price;
    }

    public double getBorrowQuote() {
        return totalBalance * HALF;
    }

    public double getBorrowBase(double price) {
        return getBorrowQuote() / price;
    }

    public double getSellFromBorrowBase(double price) {
        return getBorrowBase(price) * (1.0 - BOOKING);
    }

    public double getBorrowReserveBase(double price) {
        return getBorrowBase(price) * BOOKING;
    }
}
