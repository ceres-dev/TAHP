package dev.cerez.tahp.model;

// TODO.
public enum MarketStatus {
    TRADING(true, true), // Común
    // Solo Spot
    END_OF_DAY(true, false),
    HALT(true, false),
    BREAK(true, false),
    PENDING_TRADING(true, false),
    // Solo futuros
    PRE_DELIVERING(false, true),
    DELIVERING(false, true),
    DELIVERED(false, true),
    PRE_SETTLE(false, true),
    SETTLING(false, true),
    CLOSE(false, true),;

    private final boolean isSpot;
    private final boolean isFutures;

    MarketStatus(boolean isSpot, boolean isFutures) {
        this.isSpot = isSpot;
        this.isFutures = isFutures;
    }
}
