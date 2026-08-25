package dev.cerez.tahp.connector.model;

import org.jetbrains.annotations.NotNull;

public record BookTickDouble(
        @NotNull String symbol,
        @NotNull Double bidPrice,
        @NotNull Double bidQty,
        @NotNull Double askPrice,
        @NotNull Double askQty
) {
}
